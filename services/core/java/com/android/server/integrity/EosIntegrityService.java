/*
 * SPDX-FileCopyrightText: 2026 The ExpressiveOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.integrity;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.integrity.IEosIntegrityManager;
import android.security.integrity.KeyboxSummary;
import android.security.trickystore.TrickyStoreService;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Base64;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.expressiveos.PixelDeviceRepository;
import com.android.server.AxExtServiceFactory;
import com.android.server.SystemService;
import com.android.server.spoof.AxSpoofManager;
import com.android.server.spoof.IAxSpoofManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Privileged ExpressiveOS 2.0 Integrity Service (`eos_integrity`).
 *
 * Confines raw attestation keybox private keys inside `/data/system/eos_integrity/` (`0700`
 * `system:system`, atomic `0600` files) instead of `Settings.Secure`, fetches official keyboxes
 * and canary PIF profiles using the ExpressiveOS 1.x direct HTTPS + PixelDeviceRepository
 * workflow, gates all keybox imports/fetches through XmlPullParser + X.509 chain + leaf-key-match
 * + Google Hardware Attestation Root CA + fail-closed CRL revocation checks, and performs a
 * deterministic 3-way `target.txt` merge (`user > remote > auto`).
 *
 * @hide
 */
public final class EosIntegrityService extends SystemService {
    private static final String TAG = "EosIntegrityService";
    public static final String SERVICE_NAME = "eos_integrity";
    public static final String PERMISSION_MANAGE_EOS_INTEGRITY = "android.permission.MANAGE_EOS_INTEGRITY";

    private static final File BASE_DIR = new File("/data/system/eos_integrity");
    private static final File KEYBOX_FILE = new File(BASE_DIR, "keybox.xml");
    private static final File PIF_FILE = new File(BASE_DIR, "pif.json");
    private static final File TARGETS_USER_FILE = new File(BASE_DIR, "targets_user.txt");
    private static final File TARGETS_REMOTE_FILE = new File(BASE_DIR, "targets_remote.txt");
    private static final File TARGETS_AUTO_FILE = new File(BASE_DIR, "targets_auto.txt");
    private static final File TARGET_MERGED_FILE = new File(BASE_DIR, "target.txt");
    private static final File PATCH_FILE = new File(BASE_DIR, "patch.txt");
    private static final File STATE_FILE = new File(BASE_DIR, "state.json");

    private static final String OFFICIAL_KEYBOX_URL =
            "https://keybox.kowx712.cc/";
    private static final String ROOTS_URL = "https://android.googleapis.com/attestation/root";
    private static final String CRL_URL =
            "https://android.googleapis.com/attestation/status?encrypted=0";
    private static final String LAST_AUTO_FETCH_KEY = "spoof_pif_last_auto_fetch";

    // Pinned Google Hardware Attestation Root CA DER SHA-256 digests (offline fallback).
    private static final Set<String> PINNED_GOOGLE_ROOT_DER_SHA256 = Set.of(
            "1EF1A04B8BA58AB94589AC498C8982A783F24EA7307E0159A0C3A73B377D87CC",
            "CEDB1CB6DC896AE5EC797348BCE9286753C2B38EE71CE0FBE34A9A1248800DFC",
            "6D9DB4CE6C5C0B293166D08986E05774A8776CEB525D9E4329520DE12BA4BCC0"
    );

    private static final long ROOTS_CACHE_TTL_MS = TimeUnit.DAYS.toMillis(30);
    private static final long REVOKED_CACHE_TTL_MS = TimeUnit.DAYS.toMillis(7);
    private static final long EXPIRY_WARN_MS = TimeUnit.DAYS.toMillis(14);
    private static final int HTTP_TIMEOUT_MS = 10_000;
    private static final long OPERATION_DEADLINE_MS = TimeUnit.SECONDS.toMillis(25);
    private static final int MAX_HTTP_BYTES = 4 * 1024 * 1024;

    private static final String SOURCE_USER = "user";
    private static final String SOURCE_OFFICIAL = "official";

    private static final Pattern TARGET_LINE_PATTERN =
            Pattern.compile("^[a-zA-Z0-9._]+[!\\?\\-]?$");
    private static final Pattern PLAIN_DATE_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern CERT_PEM_PATTERN =
            Pattern.compile("-----BEGIN CERTIFICATE-----([\\s\\S]+?)-----END CERTIFICATE-----");
    private static final Pattern PRIV_PEM_PATTERN =
            Pattern.compile("-----BEGIN ([A-Z0-9 ]*PRIVATE KEY)-----([\\s\\S]+?)-----END \\1-----");
    private static final Pattern ZERO_WIDTH_PATTERN =
            Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]");

    private static volatile EosIntegrityService sInstance;

    private final Context mContext;
    private final Object mLock = new Object();
    private final SecureRandom mRandom = new SecureRandom();
    private final ExecutorService mNetworkExecutor = createNetworkExecutor();

    private static ExecutorService createNetworkExecutor() {
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 2, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(4),
                r -> {
                    final Thread t = new Thread(r, "EosIntegrity-Net");
                    t.setDaemon(true);
                    return t;
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    public EosIntegrityService(Context context) {
        super(context);
        mContext = context;
    }

    public static EosIntegrityService getInstance() {
        return sInstance;
    }

    @Override
    public void onStart() {
        sInstance = this;
        publishBinderService(SERVICE_NAME, mBinder);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            synchronized (mLock) {
                ensureStorageDirectoryLocked();
                migrateLegacySettingsKeyboxLocked();
                ensureTargetsInitializedLocked();
            }
        }
    }

    // ---- In-process system_server helpers (called by AxSpoofManager) --------

    public static boolean hasActiveKeybox() {
        final EosIntegrityService svc = sInstance;
        if (svc == null) return false;
        synchronized (svc.mLock) {
            return KEYBOX_FILE.isFile() && KEYBOX_FILE.length() > 0;
        }
    }

    /**
     * Returns Base64-encoded active keybox XML strictly for in-process `system_server`
     * attestation consumers (`AxSpoofManager.getTrickyStoreKeyBox()`). Never exposed over
     * `IEosIntegrityManager`.
     */
    public static String getActiveKeyboxBase64() {
        final EosIntegrityService svc = sInstance;
        if (svc == null) return null;
        synchronized (svc.mLock) {
            final byte[] raw = readBytesQuietly(KEYBOX_FILE);
            if (raw == null || raw.length == 0) return null;
            return Base64.encodeToString(raw, Base64.NO_WRAP);
        }
    }

    public static String getMergedTargetsOrFallback(String fallback) {
        final EosIntegrityService svc = sInstance;
        if (svc == null) return fallback;
        synchronized (svc.mLock) {
            final String merged = readUtf8Quietly(TARGET_MERGED_FILE);
            return (merged != null && !merged.trim().isEmpty()) ? merged : fallback;
        }
    }

    public static void recordAutoTarget(String pkg) {
        final EosIntegrityService svc = sInstance;
        if (svc == null || pkg == null || pkg.isEmpty()) return;
        if (TrickyStoreService.XPOSED_PACKAGES.contains(pkg)) return;
        if (!TARGET_LINE_PATTERN.matcher(pkg).matches()) return;
        synchronized (svc.mLock) {
            svc.ensureStorageDirectoryLocked();
            final String existing = nonNull(readUtf8Quietly(TARGETS_AUTO_FILE));
            for (String line : existing.split("\n")) {
                if (stripTargetSuffix(line.trim()).equals(pkg)) {
                    return;
                }
            }
            final String updated = existing.isEmpty() ? pkg + "\n"
                    : (existing.endsWith("\n") ? existing + pkg + "\n" : existing + "\n" + pkg + "\n");
            writeAtomic0600Locked(TARGETS_AUTO_FILE, updated.getBytes(StandardCharsets.UTF_8));
            svc.rebuildMergedTargetsLocked(false);
        }
    }

    public static boolean refreshOfficialKeyboxInternal(boolean force) {
        final EosIntegrityService svc = sInstance;
        if (svc == null) return false;
        final long deadlineMs = SystemClock.elapsedRealtime() + OPERATION_DEADLINE_MS;
        final ActionOutcome res = svc.fetchOfficialKeyboxImpl(force, deadlineMs);
        return res.ok;
    }

    public static boolean refreshCanaryPifInternal(boolean force) {
        final EosIntegrityService svc = sInstance;
        if (svc == null) return false;
        final ActionOutcome res = svc.refreshCanaryPifImpl(force);
        return res.ok;
    }

    private <T> T runNetworkBounded(Callable<T> task, T timeoutFallback, T busyFallback) {
        final Future<T> future;
        try {
            future = mNetworkExecutor.submit(task);
        } catch (RejectedExecutionException e) {
            Slog.w(TAG, "Integrity network executor saturated, rejecting request");
            return busyFallback;
        }
        try {
            return future.get(OPERATION_DEADLINE_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            Slog.w(TAG, "Integrity network operation exceeded " + OPERATION_DEADLINE_MS + "ms deadline");
            return timeoutFallback;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return timeoutFallback;
        } catch (Exception e) {
            Slog.w(TAG, "Integrity network operation failed", e);
            return timeoutFallback;
        }
    }

    // ---- Binder implementation ----------------------------------------------

    private void enforceCallerPermission() {
        mContext.enforceCallingOrSelfPermission(
                PERMISSION_MANAGE_EOS_INTEGRITY,
                "Caller lacks MANAGE_EOS_INTEGRITY permission");
    }

    private final IBinder mBinder = new IEosIntegrityManager.Stub() {
        @Override
        public String getKeyboxSummary() {
            enforceCallerPermission();
            synchronized (mLock) {
                return buildKeyboxSummaryLocked().toJsonString();
            }
        }

        @Override
        public String importUserKeybox(String rawXml) {
            enforceCallerPermission();
            final long deadlineMs = SystemClock.elapsedRealtime() + OPERATION_DEADLINE_MS;
            return runNetworkBounded(
                    () -> importUserKeyboxImpl(rawXml, deadlineMs),
                    new ActionOutcome(false, "TIMEOUT", "Keybox validation timed out"),
                    new ActionOutcome(false, "BUSY", "Integrity operation already in progress")
            ).toJsonString();
        }

        @Override
        public String fetchOfficialKeybox(boolean force) {
            enforceCallerPermission();
            final long deadlineMs = SystemClock.elapsedRealtime() + OPERATION_DEADLINE_MS;
            return runNetworkBounded(
                    () -> fetchOfficialKeyboxImpl(force, deadlineMs),
                    new ActionOutcome(false, "TIMEOUT", "Official keybox fetch timed out"),
                    new ActionOutcome(false, "BUSY", "Integrity operation already in progress")
            ).toJsonString();
        }

        @Override
        public String checkKeyboxRevocation() {
            enforceCallerPermission();
            final long deadlineMs = SystemClock.elapsedRealtime() + OPERATION_DEADLINE_MS;
            final KeyboxSummary fallback;
            synchronized (mLock) {
                fallback = buildKeyboxSummaryLocked();
            }
            return runNetworkBounded(
                    () -> checkKeyboxRevocationImpl(deadlineMs),
                    fallback,
                    fallback
            ).toJsonString();
        }

        @Override
        public String deleteKeybox() {
            enforceCallerPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                return deleteKeyboxImpl().toJsonString();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public String importUserTargets(String rawText) {
            enforceCallerPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                return importUserTargetsImpl(rawText).toJsonString();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public String refreshCanaryPif(boolean force) {
            enforceCallerPermission();
            return runNetworkBounded(
                    () -> refreshCanaryPifImpl(force),
                    new ActionOutcome(false, "TIMEOUT", "Canary PIF refresh timed out"),
                    new ActionOutcome(false, "BUSY", "Integrity operation already in progress")
            ).toJsonString();
        }

        @Override
        public String resetDefaults() {
            enforceCallerPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                return resetDefaultsImpl().toJsonString();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public KeyboxSummary getKeyboxSummaryParcelable() {
            enforceCallerPermission();
            synchronized (mLock) {
                return buildKeyboxSummaryLocked();
            }
        }
    };

    // ---- Core operations ----------------------------------------------------

    private static final class ActionOutcome {
        final boolean ok;
        final String code;
        final String message;

        ActionOutcome(boolean ok, String code, String message) {
            this.ok = ok;
            this.code = code != null ? code : "";
            this.message = message != null ? message : "";
        }

        String toJsonString() {
            try {
                final JSONObject j = new JSONObject();
                j.put("ok", ok);
                j.put("code", code);
                j.put("message", message);
                return j.toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"code\":\"JSON_ERROR\",\"message\":\"\"}";
            }
        }
    }

    private static final class ValidationResult {
        final boolean structurallyValid;
        final String status; // VALID, EXPIRING_SOON, REVOKED, SUSPENDED, CHAIN_INVALID, UNTRUSTED_ROOT, UNKNOWN
        final String reason;
        final long leafExpiryMs;

        ValidationResult(boolean structurallyValid, String status, String reason, long leafExpiryMs) {
            this.structurallyValid = structurallyValid;
            this.status = status;
            this.reason = reason != null ? reason : "";
            this.leafExpiryMs = leafExpiryMs;
        }
    }

    private ActionOutcome importUserKeyboxImpl(String rawXml, long deadlineMs) {
        if (rawXml == null || rawXml.trim().isEmpty()) {
            return new ActionOutcome(false, "EMPTY", "Keybox XML is empty");
        }
        final ValidationResult vr = validateKeyboxCandidate(
                rawXml, /* allowOfflineCrl= */ true, deadlineMs);
        if (!vr.structurallyValid) {
            return new ActionOutcome(false, vr.status, vr.reason);
        }
        boolean changed;
        synchronized (mLock) {
            ensureStorageDirectoryLocked();
            final String prevXml = readUtf8Quietly(KEYBOX_FILE);
            changed = prevXml == null || !sha256Hex(prevXml.getBytes(StandardCharsets.UTF_8))
                    .equals(sha256Hex(rawXml.getBytes(StandardCharsets.UTF_8)));
            writeAtomic0600Locked(KEYBOX_FILE, rawXml.getBytes(StandardCharsets.UTF_8));
            final JSONObject st = readStateLocked();
            final long now = System.currentTimeMillis();
            putQuietly(st, "source", SOURCE_USER);
            putQuietly(st, "revocation_status", vr.status);
            putQuietly(st, "revocation_reason", vr.reason);
            putQuietly(st, "last_fetched_ms", now);
            if (!"UNKNOWN".equals(vr.status)) {
                putQuietly(st, "last_checked_ms", now);
            }
            putQuietly(st, "leaf_expiry_ms", vr.leafExpiryMs);
            writeStateLocked(st);
            scrubLegacyKeyboxSettingLocked();
        }
        if (changed) {
            notifySpoofSubsystem(/* pifChanged= */ false, /* restartGms= */ true);
        }
        return new ActionOutcome(true, changed ? "IMPORTED" : "UNCHANGED", "");
    }

    private ActionOutcome fetchOfficialKeyboxImpl(boolean force, long deadlineMs) {
        synchronized (mLock) {
            final JSONObject st = readStateLocked();
            final String source = st.optString("source", "");
            if (!force && SOURCE_USER.equals(source) && KEYBOX_FILE.isFile()) {
                return new ActionOutcome(true, "USER_OWNED", "Preserving user-imported keybox");
            }
        }

        // EOS 1.x method: fetch official keybox directly over HTTPS from OFFICIAL_KEYBOX_URL,
        // then verify structure, private-key-to-leaf match, Google root CA chain, expiry, and CRL.
        final byte[] officialBytes = httpGetBytes(OFFICIAL_KEYBOX_URL, deadlineMs);
        if (officialBytes == null || officialBytes.length == 0) {
            return new ActionOutcome(false, "FETCH_FAILED", "Unable to reach official keybox feed");
        }
        final String candidate = new String(officialBytes, StandardCharsets.UTF_8);

        final ValidationResult vr = validateKeyboxCandidate(
                candidate, /* allowOfflineCrl= */ false, deadlineMs);
        if (!vr.structurallyValid
                || "REVOKED".equals(vr.status) || "SUSPENDED".equals(vr.status)
                || "CHAIN_INVALID".equals(vr.status) || "UNTRUSTED_ROOT".equals(vr.status)
                || "UNKNOWN".equals(vr.status)) {
            final String failure = vr.status + (vr.reason.isEmpty() ? "" : ": " + vr.reason);
            return new ActionOutcome(false, "NO_VALID_KEYBOX", failure);
        }

        boolean changed;
        synchronized (mLock) {
            ensureStorageDirectoryLocked();
            // Re-check user ownership under lock
            final JSONObject st = readStateLocked();
            if (!force && SOURCE_USER.equals(st.optString("source", "")) && KEYBOX_FILE.isFile()) {
                return new ActionOutcome(true, "USER_OWNED", "");
            }
            final String existing = readUtf8Quietly(KEYBOX_FILE);
            changed = existing == null || !sha256Hex(existing.getBytes(StandardCharsets.UTF_8))
                    .equals(sha256Hex(candidate.getBytes(StandardCharsets.UTF_8)));
            if (changed) {
                writeAtomic0600Locked(KEYBOX_FILE, candidate.getBytes(StandardCharsets.UTF_8));
            }
            final long now = System.currentTimeMillis();
            putQuietly(st, "source", SOURCE_OFFICIAL);
            putQuietly(st, "revocation_status", vr.status);
            putQuietly(st, "revocation_reason", vr.reason);
            putQuietly(st, "last_fetched_ms", now);
            if (!"UNKNOWN".equals(vr.status)) {
                putQuietly(st, "last_checked_ms", now);
            }
            putQuietly(st, "leaf_expiry_ms", vr.leafExpiryMs);
            writeStateLocked(st);
            scrubLegacyKeyboxSettingLocked();
        }
        if (changed) {
            notifySpoofSubsystem(/* pifChanged= */ false, /* restartGms= */ true);
            return new ActionOutcome(true, "UPDATED", "");
        }
        return new ActionOutcome(true, "UNCHANGED", "");
    }

    private KeyboxSummary checkKeyboxRevocationImpl(long deadlineMs) {
        final String xml;
        synchronized (mLock) {
            xml = readUtf8Quietly(KEYBOX_FILE);
            if (xml == null || xml.trim().isEmpty()) {
                return buildKeyboxSummaryLocked();
            }
        }
        final ValidationResult vr = validateKeyboxCandidate(
                xml, /* allowOfflineCrl= */ true, deadlineMs);
        boolean shouldAutoReplace = false;
        synchronized (mLock) {
            final JSONObject st = readStateLocked();
            putQuietly(st, "revocation_status", vr.status);
            putQuietly(st, "revocation_reason", vr.reason);
            if (!"UNKNOWN".equals(vr.status)) {
                putQuietly(st, "last_checked_ms", System.currentTimeMillis());
            }
            putQuietly(st, "leaf_expiry_ms", vr.leafExpiryMs);
            writeStateLocked(st);
            if (("REVOKED".equals(vr.status) || "SUSPENDED".equals(vr.status))
                    && !SOURCE_USER.equals(st.optString("source", ""))) {
                shouldAutoReplace = true;
            }
        }
        if (shouldAutoReplace) {
            fetchOfficialKeyboxImpl(/* force= */ false, deadlineMs);
        }
        synchronized (mLock) {
            return buildKeyboxSummaryLocked();
        }
    }

    private ActionOutcome deleteKeyboxImpl() {
        boolean existed;
        synchronized (mLock) {
            existed = KEYBOX_FILE.exists();
            if (existed) {
                KEYBOX_FILE.delete();
            }
            final JSONObject st = readStateLocked();
            putQuietly(st, "source", "");
            putQuietly(st, "revocation_status", "");
            putQuietly(st, "revocation_reason", "");
            putQuietly(st, "last_fetched_ms", 0L);
            putQuietly(st, "last_checked_ms", 0L);
            putQuietly(st, "leaf_expiry_ms", 0L);
            writeStateLocked(st);
            scrubLegacyKeyboxSettingLocked();
        }
        if (existed) {
            notifySpoofSubsystem(/* pifChanged= */ false, /* restartGms= */ true);
        }
        return new ActionOutcome(true, "DELETED", "");
    }

    private ActionOutcome importUserTargetsImpl(String rawText) {
        final String normalized = normalizeAndValidateTargets(rawText);
        if (normalized == null) {
            return new ActionOutcome(false, "INVALID_TARGETS", "Target list failed validation");
        }
        boolean changed;
        synchronized (mLock) {
            ensureStorageDirectoryLocked();
            writeAtomic0600Locked(TARGETS_USER_FILE, normalized.getBytes(StandardCharsets.UTF_8));
            changed = rebuildMergedTargetsLocked(/* syncSettingsMirror= */ true);
        }
        if (changed) {
            notifySpoofSubsystem(/* pifChanged= */ false, /* restartGms= */ false);
        }
        return new ActionOutcome(true, "IMPORTED", "");
    }

    private ActionOutcome refreshCanaryPifImpl(boolean force) {
        final ContentResolver resolver = mContext.getContentResolver();
        final String existingRaw = Settings.Secure.getStringForUser(
                resolver, Settings.Secure.SPOOF_PIF_CONFIG, UserHandle.USER_SYSTEM);
        if (!force && existingRaw != null && !existingRaw.trim().isEmpty()) {
            try {
                if (new JSONObject(existingRaw).optBoolean("manually_imported", false)) {
                    return new ActionOutcome(true, "MANUAL_PRESERVED", "");
                }
            } catch (Exception ignored) {
            }
        }

        // EOS 1.x method: resolve canary Pixel profile via PixelDeviceRepository with built-in fallback.
        List<PixelDeviceRepository.PixelProfile> candidates;
        try {
            candidates = PixelDeviceRepository.getProfiles(mContext, true);
        } catch (Exception e) {
            candidates = PixelDeviceRepository.FALLBACK_PROFILES;
        }
        if (candidates == null || candidates.isEmpty()) {
            candidates = PixelDeviceRepository.FALLBACK_PROFILES;
        }
        PixelDeviceRepository.PixelProfile selected = null;
        final String preferred = PixelDeviceRepository.getDefaultPifCodename(candidates);
        for (PixelDeviceRepository.PixelProfile p : candidates) {
            if (p.codename.equals(preferred)
                    && PixelDeviceRepository.isValidFingerprint(p.fingerprint)) {
                selected = p;
                break;
            }
        }
        if (selected == null) {
            for (PixelDeviceRepository.PixelProfile p : candidates) {
                if (PixelDeviceRepository.isValidFingerprint(p.fingerprint)) {
                    selected = p;
                    break;
                }
            }
        }
        if (selected == null) {
            return new ActionOutcome(false, "NO_VALID_PIF", "No valid Pixel profile found");
        }
        final JSONObject chosenJson = buildPifFromProfile(existingRaw, selected);
        final String chosenPatch = selected.securityPatch;

        String formatted;
        try {
            formatted = chosenJson.toString(2);
        } catch (Exception e) {
            formatted = chosenJson.toString();
        }
        final String newFp = chosenJson.optString("FINGERPRINT", "");
        String oldFp = "";
        if (existingRaw != null && !existingRaw.trim().isEmpty()) {
            try {
                oldFp = new JSONObject(existingRaw).optString("FINGERPRINT", "");
            } catch (Exception ignored) {
            }
        }
        final boolean changed = !newFp.equals(oldFp) || existingRaw == null || existingRaw.trim().isEmpty();

        synchronized (mLock) {
            ensureStorageDirectoryLocked();
            writeAtomic0600Locked(PIF_FILE, formatted.getBytes(StandardCharsets.UTF_8));
            if (chosenPatch != null && PLAIN_DATE_PATTERN.matcher(chosenPatch).matches()) {
                final String curPatch = Settings.Secure.getStringForUser(
                        resolver, Settings.Secure.SPOOF_TRICKYSTORE_PATCH, UserHandle.USER_SYSTEM);
                if (curPatch == null || curPatch.trim().isEmpty()
                        || PLAIN_DATE_PATTERN.matcher(curPatch.trim()).matches()) {
                    writeAtomic0600Locked(PATCH_FILE, chosenPatch.getBytes(StandardCharsets.UTF_8));
                    Settings.Secure.putStringForUser(
                            resolver, Settings.Secure.SPOOF_TRICKYSTORE_PATCH,
                            chosenPatch, UserHandle.USER_SYSTEM);
                }
            }
        }
        if (changed) {
            Settings.Secure.putStringForUser(
                    resolver, Settings.Secure.SPOOF_PIF_CONFIG, formatted, UserHandle.USER_SYSTEM);
            notifySpoofSubsystem(/* pifChanged= */ true, /* restartGms= */ true);
        }
        return new ActionOutcome(true, changed ? "UPDATED" : "UNCHANGED", "");
    }

    private ActionOutcome resetDefaultsImpl() {
        synchronized (mLock) {
            ensureStorageDirectoryLocked();
            PIF_FILE.delete();
            PATCH_FILE.delete();
            TARGETS_USER_FILE.delete();
            // Keep user-imported keybox per spec; rebuild merged targets from remote > auto.
            rebuildMergedTargetsLocked(/* syncSettingsMirror= */ true);
        }
        final ContentResolver resolver = mContext.getContentResolver();
        Settings.Secure.putLongForUser(
                resolver, LAST_AUTO_FETCH_KEY, 0L, UserHandle.USER_SYSTEM);
        Settings.Secure.putStringForUser(
                resolver, Settings.Secure.SPOOF_TRICKYSTORE_PATCH, "", UserHandle.USER_SYSTEM);
        Settings.Secure.putStringForUser(
                resolver, Settings.Secure.SPOOF_GAMEPROPS_CONFIG, "", UserHandle.USER_SYSTEM);
        Settings.Secure.putStringForUser(
                resolver, Settings.Secure.SPOOF_PIF_CONFIG, "", UserHandle.USER_SYSTEM);
        notifySpoofSubsystem(/* pifChanged= */ true, /* restartGms= */ true);
        return new ActionOutcome(true, "RESET", "");
    }

    // ---- Pre-commit cryptographic & CRL validation --------------------------

    private boolean isCachedRevokedSerial(List<String> allSerials, long now) {
        synchronized (mLock) {
            final JSONObject st = readStateLocked();
            final long cachedAt = st.optLong("cached_revoked_at_ms", 0L);
            final String cachedSerial = st.optString("cached_revoked_serial", "");
            return cachedAt > 0L
                    && now - cachedAt <= REVOKED_CACHE_TTL_MS
                    && !cachedSerial.isEmpty()
                    && allSerials.contains(cachedSerial);
        }
    }

    private ValidationResult validateKeyboxCandidate(
            String rawXml, boolean allowOfflineCrl, long deadlineMs) {
        final String cleanXml = sanitizeKeyboxXml(rawXml);
        if (!cleanXml.startsWith("<")) {
            return new ValidationResult(false, "CHAIN_INVALID", "Not XML", 0L);
        }
        final String structureProblem = checkKeyboxStructure(cleanXml);
        if (structureProblem != null) {
            return new ValidationResult(false, "CHAIN_INVALID", structureProblem, 0L);
        }

        final List<KeyChainBundle> bundles = parseKeyBundles(cleanXml);
        if (bundles.isEmpty()) {
            return new ValidationResult(false, "CHAIN_INVALID", "No parseable Key blocks", 0L);
        }

        final Set<PublicKey> liveRootKeys = getTrustedRootPublicKeys(deadlineMs);
        long earliestLeafExpiry = Long.MAX_VALUE;
        final List<String> allSerials = new ArrayList<>();
        final long now = System.currentTimeMillis();

        for (KeyChainBundle b : bundles) {
            if (b.certs.size() < 2) {
                return new ValidationResult(false, "CHAIN_INVALID", "CHAIN_TOO_SHORT", 0L);
            }
            if (!verifyPrivateKeyMatchesLeaf(b.algorithm, b.privateKeyPem, b.certs.get(0))) {
                return new ValidationResult(false, "CHAIN_INVALID", "PRIVATE_KEY_LEAF_MISMATCH", 0L);
            }
            for (int i = 0; i < b.certs.size() - 1; i++) {
                try {
                    b.certs.get(i).verify(b.certs.get(i + 1).getPublicKey());
                } catch (Exception e) {
                    return new ValidationResult(false, "CHAIN_INVALID", "BAD_SIGNATURE_AT_" + i, 0L);
                }
            }
            final X509Certificate root = b.certs.get(b.certs.size() - 1);
            try {
                root.verify(root.getPublicKey());
            } catch (Exception e) {
                return new ValidationResult(false, "CHAIN_INVALID", "ROOT_NOT_SELF_SIGNED", 0L);
            }
            boolean trustedRoot = false;
            if (!liveRootKeys.isEmpty()) {
                final String rootPubSha = sha256Hex(root.getPublicKey().getEncoded());
                for (PublicKey anchor : liveRootKeys) {
                    if (sha256Hex(anchor.getEncoded()).equals(rootPubSha)) {
                        trustedRoot = true;
                        break;
                    }
                }
            }
            if (!trustedRoot) {
                final String rootDerSha = sha256Hex(getEncodedQuietly(root));
                trustedRoot = PINNED_GOOGLE_ROOT_DER_SHA256.contains(rootDerSha);
            }
            if (!trustedRoot) {
                return new ValidationResult(true, "UNTRUSTED_ROOT", "UNTRUSTED_ROOT",
                        b.certs.get(0).getNotAfter().getTime());
            }

            final long leafExp = b.certs.get(0).getNotAfter().getTime();
            if (leafExp < now) {
                return new ValidationResult(false, "CHAIN_INVALID", "CERT_EXPIRED", leafExp);
            }
            earliestLeafExpiry = Math.min(earliestLeafExpiry, leafExp);
            for (X509Certificate c : b.certs) {
                allSerials.add(c.getSerialNumber().toString(16).toLowerCase(Locale.US));
                allSerials.add(c.getSerialNumber().toString());
            }
        }

        // Check Google CRL (always consult offline revocation cache first when unreachable;
        // fail closed when allowOfflineCrl == false).
        final byte[] crlBytes = httpGetBytes(CRL_URL + "&_=" + System.nanoTime(), deadlineMs);
        if (crlBytes == null) {
            if (isCachedRevokedSerial(allSerials, now)) {
                return new ValidationResult(allowOfflineCrl, "SUSPENDED", "CACHED_OFFLINE",
                        earliestLeafExpiry);
            }
            if (!allowOfflineCrl) {
                return new ValidationResult(false, "UNKNOWN", "CRL_UNREACHABLE",
                        earliestLeafExpiry);
            }
            return new ValidationResult(true, "UNKNOWN", "CRL_UNREACHABLE", earliestLeafExpiry);
        }

        try {
            final JSONObject crl = new JSONObject(new String(crlBytes, StandardCharsets.UTF_8));
            final JSONObject entries = crl.optJSONObject("entries");
            if (entries != null) {
                for (String serial : allSerials) {
                    final JSONObject entry = entries.optJSONObject(serial);
                    if (entry == null) continue;
                    final String status = entry.optString("status", "").toUpperCase(Locale.US);
                    final String reason = entry.optString("reason", "").toUpperCase(Locale.US);
                    if ("REVOKED".equals(status) || "SUSPENDED".equals(status)) {
                        synchronized (mLock) {
                            final JSONObject st = readStateLocked();
                            putQuietly(st, "cached_revoked_serial", serial);
                            putQuietly(st, "cached_revoked_at_ms", now);
                            writeStateLocked(st);
                        }
                        return new ValidationResult(true, status, reason, earliestLeafExpiry);
                    }
                }
            }
        } catch (Exception e) {
            if (isCachedRevokedSerial(allSerials, now)) {
                return new ValidationResult(allowOfflineCrl, "SUSPENDED", "CACHED_OFFLINE",
                        earliestLeafExpiry);
            }
            return new ValidationResult(allowOfflineCrl, "UNKNOWN", "CRL_PARSE_ERROR",
                    earliestLeafExpiry);
        }

        if (earliestLeafExpiry - now < EXPIRY_WARN_MS) {
            final String expDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    .format(new Date(earliestLeafExpiry));
            return new ValidationResult(true, "EXPIRING_SOON", expDate, earliestLeafExpiry);
        }
        return new ValidationResult(true, "VALID", "", earliestLeafExpiry);
    }

    private static final class KeyChainBundle {
        final String algorithm;
        final String privateKeyPem;
        final List<X509Certificate> certs;

        KeyChainBundle(String algorithm, String privateKeyPem, List<X509Certificate> certs) {
            this.algorithm = algorithm;
            this.privateKeyPem = privateKeyPem;
            this.certs = certs;
        }
    }

    private static List<KeyChainBundle> parseKeyBundles(String xml) {
        final List<KeyChainBundle> out = new ArrayList<>();
        try {
            final CertificateFactory cf = CertificateFactory.getInstance("X.509");
            final XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(xml));

            boolean inKey = false;
            boolean inPrivateKey = false;
            boolean inCertificateChain = false;
            boolean inCertificate = false;
            String currentAlg = null;
            StringBuilder privBuilder = null;
            StringBuilder certBuilder = null;
            String privPem = null;
            List<X509Certificate> certs = new ArrayList<>();

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                final String tag = parser.getName();
                if (eventType == XmlPullParser.START_TAG) {
                    if ("Key".equals(tag)) {
                        inKey = true;
                        final String rawAlg = parser.getAttributeValue(null, "algorithm");
                        currentAlg = rawAlg != null ? rawAlg.trim().toLowerCase(Locale.US) : null;
                        privPem = null;
                        certs = new ArrayList<>();
                        privBuilder = null;
                        certBuilder = null;
                    } else if ("PrivateKey".equals(tag) && inKey) {
                        inPrivateKey = true;
                        privBuilder = new StringBuilder();
                    } else if ("CertificateChain".equals(tag) && inKey) {
                        inCertificateChain = true;
                    } else if ("Certificate".equals(tag) && inCertificateChain) {
                        inCertificate = true;
                        certBuilder = new StringBuilder();
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    final String text = parser.getText();
                    if (text != null) {
                        if (inPrivateKey && privBuilder != null) {
                            privBuilder.append(text);
                        } else if (inCertificate && certBuilder != null) {
                            certBuilder.append(text);
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if ("PrivateKey".equals(tag)) {
                        if (privBuilder != null) {
                            final String raw = privBuilder.toString().trim();
                            final Matcher pm = PRIV_PEM_PATTERN.matcher(raw);
                            if (pm.find()) {
                                privPem = pm.group(0);
                            }
                            privBuilder = null;
                        }
                        inPrivateKey = false;
                    } else if ("Certificate".equals(tag)) {
                        if (certBuilder != null) {
                            final String raw = certBuilder.toString().trim();
                            final Matcher cm = CERT_PEM_PATTERN.matcher(raw);
                            while (cm.find()) {
                                final byte[] der = Base64.decode(
                                        cm.group(1).replaceAll("\\s+", ""), Base64.DEFAULT);
                                certs.add((X509Certificate) cf.generateCertificate(
                                        new ByteArrayInputStream(der)));
                            }
                            certBuilder = null;
                        }
                        inCertificate = false;
                    } else if ("CertificateChain".equals(tag)) {
                        inCertificateChain = false;
                    } else if ("Key".equals(tag)) {
                        inKey = false;
                        if (currentAlg != null && privPem != null && !certs.isEmpty()) {
                            out.add(new KeyChainBundle(currentAlg, privPem, certs));
                        }
                    }
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed parsing keybox bundles", e);
        }
        return out;
    }

    private boolean verifyPrivateKeyMatchesLeaf(
            String algorithm, String privPem, X509Certificate leaf) {
        try {
            final Matcher m = PRIV_PEM_PATTERN.matcher(privPem);
            if (!m.find()) return false;
            final String label = m.group(1);
            final byte[] der = Base64.decode(m.group(2).replaceAll("\\s+", ""), Base64.DEFAULT);
            final boolean isEc = algorithm.contains("ec") || "EC".equalsIgnoreCase(leaf.getPublicKey().getAlgorithm());
            final PrivateKey privKey = parsePrivateKey(label, der, isEc);
            if (privKey == null) return false;
            final String sigAlg = isEc ? "SHA256withECDSA" : "SHA256withRSA";
            final byte[] challenge = new byte[32];
            mRandom.nextBytes(challenge);
            final Signature signer = Signature.getInstance(sigAlg);
            signer.initSign(privKey);
            signer.update(challenge);
            final byte[] sig = signer.sign();

            final Signature verifier = Signature.getInstance(sigAlg);
            verifier.initVerify(leaf.getPublicKey());
            verifier.update(challenge);
            return verifier.verify(sig);
        } catch (Exception e) {
            Slog.w(TAG, "Private key / leaf public key verification failed", e);
            return false;
        }
    }

    private static PrivateKey parsePrivateKey(String label, byte[] der, boolean isEc) {
        final String kfAlg = isEc ? "EC" : "RSA";
        try {
            if ("PRIVATE KEY".equals(label)) {
                return KeyFactory.getInstance(kfAlg).generatePrivate(new PKCS8EncodedKeySpec(der));
            }
            // Wrap SEC1 EC PRIVATE KEY or PKCS#1 RSA PRIVATE KEY into PKCS#8 DER
            final byte[] pkcs8 = isEc ? wrapSec1EcInPkcs8(der) : wrapPkcs1RsaInPkcs8(der);
            return KeyFactory.getInstance(kfAlg).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] wrapPkcs1RsaInPkcs8(byte[] pkcs1) throws IOException {
        // AlgorithmIdentifier: rsaEncryption (1.2.840.113549.1.1.1) + NULL
        final byte[] algId = new byte[] {
                0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
        };
        return buildPkcs8Sequence(algId, pkcs1);
    }

    private static byte[] wrapSec1EcInPkcs8(byte[] sec1) throws IOException {
        // Parse curve OID if embedded in SEC1 [1] EXPLICIT, default prime256v1; try prime256v1 then secp384r1
        final byte[][] curveOids = new byte[][] {
                // prime256v1 (1.2.840.10045.3.1.7)
                new byte[] {0x06, 0x08, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x03, 0x01, 0x07},
                // secp384r1 (1.3.132.0.34)
                new byte[] {0x06, 0x05, 0x2b, (byte) 0x81, 0x04, 0x00, 0x22},
        };
        final byte[] ecPubKeyOid = new byte[] {
                0x06, 0x07, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x02, 0x01
        };
        for (byte[] curveOid : curveOids) {
            final byte[] algBody = concat(ecPubKeyOid, curveOid);
            final byte[] algId = derWrap((byte) 0x30, algBody);
            final byte[] pkcs8 = buildPkcs8Sequence(algId, sec1);
            try {
                KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                return pkcs8;
            } catch (Exception ignored) {
            }
        }
        return sec1;
    }

    private static byte[] buildPkcs8Sequence(byte[] algId, byte[] privateKeyBytes) {
        final byte[] version = new byte[] {0x02, 0x01, 0x00};
        final byte[] privOctet = derWrap((byte) 0x04, privateKeyBytes);
        return derWrap((byte) 0x30, concat(version, concat(algId, privOctet)));
    }

    private static byte[] derWrap(byte tag, byte[] body) {
        final byte[] lenBytes;
        final int len = body.length;
        if (len < 0x80) {
            lenBytes = new byte[] {(byte) len};
        } else if (len <= 0xff) {
            lenBytes = new byte[] {(byte) 0x81, (byte) len};
        } else {
            lenBytes = new byte[] {(byte) 0x82, (byte) ((len >> 8) & 0xff), (byte) (len & 0xff)};
        }
        final byte[] out = new byte[1 + lenBytes.length + len];
        out[0] = tag;
        System.arraycopy(lenBytes, 0, out, 1, lenBytes.length);
        System.arraycopy(body, 0, out, 1 + lenBytes.length, len);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        final byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ---- 3-way target.txt merge (user > remote > auto) ----------------------

    private String normalizeAndValidateTargets(String rawText) {
        if (rawText == null) return null;
        final String trimmed = rawText.replace("\uFEFF", "").trim();
        if (trimmed.isEmpty()) return null;
        final List<String> lines = new ArrayList<>();
        if (trimmed.startsWith("[")) {
            try {
                final JSONArray arr = new JSONArray(trimmed);
                for (int i = 0; i < arr.length(); i++) {
                    final JSONObject obj = arr.getJSONObject(i);
                    final String pkg = obj.getString("package").trim();
                    final String mode = obj.optString("mode", "AUTO").trim().toUpperCase(Locale.US);
                    final String suffix;
                    switch (mode) {
                        case "LEAF_HACK": suffix = "?"; break;
                        case "GENERATE": suffix = "!"; break;
                        case "SKIP": suffix = "-"; break;
                        case "AUTO":
                        case "": suffix = ""; break;
                        default: return null;
                    }
                    final String candidate = pkg + suffix;
                    if (!TARGET_LINE_PATTERN.matcher(candidate).matches()) return null;
                    if (!TrickyStoreService.XPOSED_PACKAGES.contains(pkg)) {
                        lines.add(candidate);
                    }
                }
            } catch (Exception e) {
                return null;
            }
        } else {
            for (String rawLine : trimmed.split("\n")) {
                final String l = rawLine.trim();
                if (l.isEmpty() || l.startsWith("#")) continue;
                if (!TARGET_LINE_PATTERN.matcher(l).matches()) {
                    return null;
                }
                final String bare = stripTargetSuffix(l);
                if (!TrickyStoreService.XPOSED_PACKAGES.contains(bare)) {
                    lines.add(l);
                }
            }
        }
        return String.join("\n", lines) + "\n";
    }

    private void ensureTargetsInitializedLocked() {
        if (!TARGETS_AUTO_FILE.isFile()) {
            writeAtomic0600Locked(TARGETS_AUTO_FILE,
                    (TrickyStoreService.DEFAULT_TARGET_LIST + "\n").getBytes(StandardCharsets.UTF_8));
        }
        rebuildMergedTargetsLocked(/* syncSettingsMirror= */ false);
    }

    /**
     * Deterministic 3-way merge: `targets_user.txt` > `targets_remote.txt` > `targets_auto.txt`.
     * Strips `XPOSED_PACKAGES` unconditionally.
     */
    private boolean rebuildMergedTargetsLocked(boolean syncSettingsMirror) {
        final Map<String, String> merged = new LinkedHashMap<>();
        mergeLayerInto(merged, readUtf8Quietly(TARGETS_AUTO_FILE));
        mergeLayerInto(merged, readUtf8Quietly(TARGETS_REMOTE_FILE));
        mergeLayerInto(merged, readUtf8Quietly(TARGETS_USER_FILE));

        final StringBuilder sb = new StringBuilder();
        for (String entry : merged.values()) {
            sb.append(entry).append('\n');
        }
        final String next = sb.toString();
        final String prev = nonNull(readUtf8Quietly(TARGET_MERGED_FILE));
        final boolean changed = !next.equals(prev);
        if (changed) {
            writeAtomic0600Locked(TARGET_MERGED_FILE, next.getBytes(StandardCharsets.UTF_8));
        }
        if (syncSettingsMirror) {
            Settings.Secure.putStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
                    next.trim(),
                    UserHandle.USER_SYSTEM);
        }
        return changed;
    }

    private static void mergeLayerInto(Map<String, String> map, String content) {
        if (content == null || content.isEmpty()) return;
        for (String raw : content.split("\n")) {
            final String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (!TARGET_LINE_PATTERN.matcher(line).matches()) continue;
            final String pkg = stripTargetSuffix(line);
            if (TrickyStoreService.XPOSED_PACKAGES.contains(pkg)) continue;
            map.put(pkg, line);
        }
    }

    private static String stripTargetSuffix(String line) {
        if (line.endsWith("!") || line.endsWith("?") || line.endsWith("-")) {
            return line.substring(0, line.length() - 1);
        }
        return line;
    }

    // ---- Google roots fetch & cache -----------------------------------------

    private Set<PublicKey> getTrustedRootPublicKeys(long deadlineMs) {
        final long now = System.currentTimeMillis();
        String cachedJson;
        long cachedAt;
        synchronized (mLock) {
            final JSONObject st = readStateLocked();
            cachedJson = st.optString("cached_roots_json", "");
            cachedAt = st.optLong("cached_roots_at_ms", 0L);
        }
        if (!cachedJson.isEmpty() && cachedAt > 0L && now - cachedAt <= ROOTS_CACHE_TTL_MS) {
            final Set<PublicKey> parsed = parseRootsPublicKeys(cachedJson);
            if (!parsed.isEmpty()) return parsed;
        }
        final byte[] live = httpGetBytes(ROOTS_URL + "?_=" + System.nanoTime(), deadlineMs);
        if (live != null) {
            final String raw = new String(live, StandardCharsets.UTF_8);
            final Set<PublicKey> parsed = parseRootsPublicKeys(raw);
            if (!parsed.isEmpty()) {
                synchronized (mLock) {
                    final JSONObject st = readStateLocked();
                    putQuietly(st, "cached_roots_json", raw);
                    putQuietly(st, "cached_roots_at_ms", now);
                    writeStateLocked(st);
                }
                return parsed;
            }
        }
        return cachedJson.isEmpty() ? Set.of() : parseRootsPublicKeys(cachedJson);
    }

    private static Set<PublicKey> parseRootsPublicKeys(String rawJson) {
        final Set<PublicKey> out = new HashSet<>();
        try {
            final JSONArray arr = new JSONArray(rawJson);
            final CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (int i = 0; i < arr.length(); i++) {
                final String pem = arr.optString(i, "");
                if (pem.isEmpty()) continue;
                final X509Certificate cert = (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
                out.add(cert.getPublicKey());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---- XML & JSON helpers -------------------------------------------------

    private static String sanitizeKeyboxXml(String content) {
        String s = content.trim();
        for (String bom : new String[] {"\uFEFF", "\uFFFE", "\u0000\uFEFF"}) {
            if (s.startsWith(bom)) {
                s = s.substring(bom.length());
            }
        }
        s = ZERO_WIDTH_PATTERN.matcher(s).replaceAll("");
        return s.replaceAll("<!--[\\s\\S]*?-->", "").trim();
    }

    private static String checkKeyboxStructure(String xml) {
        boolean hasValidKey = false;
        boolean hasIdentifier = false;
        int depth = 0;
        boolean rootClosed = false;
        try {
            final XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new StringReader(xml));

            boolean inKey = false;
            boolean supportedAlg = false;
            boolean hasPrivateKey = false;
            int certCount = 0;
            StringBuilder textBuf = null;

            int event = p.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                final String tag = p.getName();
                if (event == XmlPullParser.START_TAG) {
                    depth++;
                    if ("Keybox".equals(tag)) {
                        final String devId = p.getAttributeValue(null, "DeviceID");
                        if (devId != null && !devId.trim().isEmpty()) {
                            hasIdentifier = true;
                        }
                    } else if ("serial".equals(tag) || "DeviceID".equals(tag)) {
                        hasIdentifier = true;
                    } else if ("Key".equals(tag)) {
                        inKey = true;
                        final String alg = p.getAttributeValue(null, "algorithm");
                        final String norm = alg != null ? alg.trim().toLowerCase(Locale.US) : "";
                        supportedAlg = "ec".equals(norm) || "ecdsa".equals(norm) || "rsa".equals(norm);
                        hasPrivateKey = false;
                        certCount = 0;
                    } else if (inKey && ("PrivateKey".equals(tag) || "Certificate".equals(tag))) {
                        textBuf = new StringBuilder();
                    }
                } else if (event == XmlPullParser.TEXT) {
                    if (textBuf != null && p.getText() != null) {
                        textBuf.append(p.getText());
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if (--depth == 0) {
                        rootClosed = true;
                    }
                    if ("PrivateKey".equals(tag) && inKey) {
                        if (textBuf != null && !textBuf.toString().trim().isEmpty()) {
                            hasPrivateKey = true;
                        }
                        textBuf = null;
                    } else if ("Certificate".equals(tag) && inKey) {
                        if (textBuf != null && !textBuf.toString().trim().isEmpty()) {
                            certCount++;
                        }
                        textBuf = null;
                    } else if ("Key".equals(tag)) {
                        if (supportedAlg && hasPrivateKey && certCount > 0) {
                            hasValidKey = true;
                        }
                        inKey = false;
                    }
                }
                event = p.next();
            }
        } catch (Exception e) {
            return "Malformed keybox XML";
        }
        if (!rootClosed) {
            return "Truncated keybox XML";
        }
        if (!hasValidKey) {
            return "No ECDSA or RSA Key block";
        }
        if (!hasIdentifier) {
            return "No serial or DeviceID";
        }
        return null;
    }

    private static JSONObject buildPifFromProfile(
            String existingRaw, PixelDeviceRepository.PixelProfile profile) {
        final JSONObject out = copyPreservedPifFlags(existingRaw);
        putQuietly(out, "MANUFACTURER", capitalize(profile.brand));
        putQuietly(out, "BRAND", profile.brand);
        putQuietly(out, "MODEL", profile.model);
        putQuietly(out, "PRODUCT", profile.product);
        putQuietly(out, "DEVICE", profile.device);
        putQuietly(out, "FINGERPRINT", profile.fingerprint);
        putQuietly(out, "SECURITY_PATCH", profile.securityPatch);
        putQuietly(out, "DEVICE_INITIAL_SDK_INT",
                PixelDeviceRepository.getInitialSdkInt(profile.device));
        if (profile.isCanary && profile.securityPatch != null && profile.securityPatch.length() >= 7) {
            putQuietly(out, "_canary_month", profile.securityPatch.substring(0, 7));
            if (profile.releaseDate != null) {
                putQuietly(out, "_canary_release_date", profile.releaseDate);
            }
        }
        putQuietly(out, "manually_imported", false);
        return out;
    }

    private static JSONObject copyPreservedPifFlags(String existingRaw) {
        final JSONObject out = new JSONObject();
        if (existingRaw == null || existingRaw.trim().isEmpty()) return out;
        try {
            final JSONObject old = new JSONObject(existingRaw);
            final java.util.Iterator<String> it = old.keys();
            while (it.hasNext()) {
                final String k = it.next();
                if (k.startsWith("spoof") || "verboseLogs".equals(k) || "DEBUG".equals(k)) {
                    out.put(k, old.get(k));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---- Atomic storage & legacy migration ----------------------------------

    private void ensureStorageDirectoryLocked() {
        if (!BASE_DIR.exists()) {
            BASE_DIR.mkdirs();
        }
        try {
            Os.chmod(BASE_DIR.getAbsolutePath(), 0700);
        } catch (Exception ignored) {
        }
    }

    private void migrateLegacySettingsKeyboxLocked() {
        final ContentResolver resolver = mContext.getContentResolver();
        final String legacy = Settings.Secure.getStringForUser(
                resolver, Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX, UserHandle.USER_SYSTEM);
        if (legacy == null || legacy.trim().isEmpty()) {
            return;
        }
        if (!KEYBOX_FILE.isFile()) {
            String xml = legacy.trim();
            if (!xml.startsWith("<")) {
                try {
                    xml = new String(Base64.decode(xml, Base64.DEFAULT), StandardCharsets.UTF_8).trim();
                } catch (Exception e) {
                    xml = "";
                }
            }
            if (xml.startsWith("<")) {
                writeAtomic0600Locked(KEYBOX_FILE, xml.getBytes(StandardCharsets.UTF_8));
                final JSONObject st = readStateLocked();
                final String src = Settings.Secure.getStringForUser(
                        resolver, "spoof_trickystore_keybox_source", UserHandle.USER_SYSTEM);
                putQuietly(st, "source", (src != null && !src.isEmpty()) ? src : SOURCE_OFFICIAL);
                putQuietly(st, "last_fetched_ms", System.currentTimeMillis());
                writeStateLocked(st);
                Slog.i(TAG, "Migrated legacy Settings.Secure keybox into 0600 storage");
            }
        }
        scrubLegacyKeyboxSettingLocked();
    }

    private void scrubLegacyKeyboxSettingLocked() {
        try {
            Settings.Secure.putStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX,
                    "",
                    UserHandle.USER_SYSTEM);
        } catch (Exception ignored) {
        }
    }

    private KeyboxSummary buildKeyboxSummaryLocked() {
        final boolean present = KEYBOX_FILE.isFile() && KEYBOX_FILE.length() > 0;
        final JSONObject st = readStateLocked();
        return new KeyboxSummary(
                present,
                present ? st.optString("source", SOURCE_OFFICIAL) : "",
                present ? st.optString("revocation_status", "") : "",
                present ? st.optString("revocation_reason", "") : "",
                present ? st.optLong("last_fetched_ms", 0L) : 0L,
                present ? st.optLong("last_checked_ms", 0L) : 0L,
                present ? st.optLong("leaf_expiry_ms", 0L) : 0L
        );
    }

    private JSONObject readStateLocked() {
        final String raw = readUtf8Quietly(STATE_FILE);
        if (raw == null || raw.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStateLocked(JSONObject state) {
        ensureStorageDirectoryLocked();
        writeAtomic0600Locked(STATE_FILE, state.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeAtomic0600Locked(File target, byte[] bytes) {
        final File dir = target.getParentFile();
        if (dir == null) return;
        final File tmp = new File(dir, target.getName() + ".tmp");
        FileDescriptor fd = null;
        try {
            fd = Os.open(tmp.getAbsolutePath(),
                    OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_TRUNC,
                    0600);
            Os.fchmod(fd, 0600);
            int offset = 0;
            while (offset < bytes.length) {
                final int written = Os.write(fd, bytes, offset, bytes.length - offset);
                if (written <= 0) {
                    throw new IOException("Short write to " + tmp);
                }
                offset += written;
            }
            Os.fsync(fd);
        } catch (Exception e) {
            Slog.e(TAG, "Failed writing " + tmp, e);
            tmp.delete();
            return;
        } finally {
            if (fd != null) {
                try {
                    Os.close(fd);
                } catch (ErrnoException ignored) {
                }
            }
        }
        try {
            Os.rename(tmp.getAbsolutePath(), target.getAbsolutePath());
        } catch (ErrnoException e) {
            Slog.e(TAG, "Failed atomic rename " + tmp + " -> " + target, e);
            tmp.delete();
            return;
        }
        if (dir.isDirectory()) {
            FileDescriptor dirFd = null;
            try {
                dirFd = Os.open(dir.getAbsolutePath(), OsConstants.O_RDONLY, 0);
                Os.fsync(dirFd);
            } catch (ErrnoException e) {
                Slog.w(TAG, "Failed fsync on directory " + dir, e);
            } finally {
                if (dirFd != null) {
                    try {
                        Os.close(dirFd);
                    } catch (ErrnoException ignored) {
                    }
                }
            }
        }
    }

    private static void notifySpoofSubsystem(boolean pifChanged, boolean restartGms) {
        try {
            final IAxSpoofManager mgr = AxExtServiceFactory.getSpoofManager();
            if (mgr instanceof AxSpoofManager) {
                ((AxSpoofManager) mgr).onIntegrityServiceUpdated(pifChanged, restartGms);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Unable to notify AxSpoofManager", e);
        }
    }

    private static byte[] httpGetBytes(String urlStr, long deadlineElapsedMs) {
        final long remainingMs = deadlineElapsedMs - SystemClock.elapsedRealtime();
        if (remainingMs <= 0L) {
            return null;
        }
        final int timeoutMs = (int) Math.min(HTTP_TIMEOUT_MS, remainingMs);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setUseCaches(false);
            conn.setRequestProperty("Cache-Control", "no-store");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            try (InputStream in = conn.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                final byte[] buf = new byte[8192];
                int total = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (Thread.currentThread().isInterrupted()
                            || SystemClock.elapsedRealtime() >= deadlineElapsedMs) {
                        return null;
                    }
                    total += n;
                    if (total > MAX_HTTP_BYTES) {
                        Slog.w(TAG, "HTTP response exceeded max size (" + MAX_HTTP_BYTES + " bytes)");
                        return null;
                    }
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readBytesQuietly(File f) {
        if (!f.isFile()) return null;
        try (FileInputStream fis = new FileInputStream(f)) {
            return fis.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static String readUtf8Quietly(File f) {
        final byte[] b = readBytesQuietly(f);
        return b != null ? new String(b, StandardCharsets.UTF_8) : null;
    }

    private static byte[] getEncodedQuietly(X509Certificate cert) {
        try {
            return cert.getEncoded();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            final StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String capitalize(String s) {
        return (s == null || s.isEmpty())
                ? ""
                : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String nonNull(String s) {
        return s != null ? s : "";
    }

    private static void putQuietly(JSONObject obj, String key, Object val) {
        try {
            obj.put(key, val);
        } catch (Exception ignored) {
        }
    }
}
