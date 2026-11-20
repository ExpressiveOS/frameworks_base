/*
 * SPDX-FileCopyrightText: 2026 The ExpressiveOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.integrity;

import android.content.ContentResolver;
import android.content.Context;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.integrity.IEosIntegrityManager;
import android.security.integrity.KeyboxSummary;
import android.security.trickystore.TrickyStoreService;
import android.system.Os;
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
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Privileged ExpressiveOS 2.0 Integrity Service (`eos_integrity`).
 *
 * Confines raw attestation keybox private keys inside `/data/system/eos_integrity/` (`0700`
 * `system:system`, `0600` files) instead of `Settings.Secure`, enforces Ed25519 signature
 * verification on signed R2 integrity feeds (`manifest.json` + `manifest.json.sig`), gates all
 * keybox imports/fetches through X.509 + leaf-key-match + Google Hardware Attestation Root CA +
 * CRL revocation checks, filters revoked canary PIF profiles, and performs a deterministic 3-way
 * `target.txt` merge (`user > remote > auto`).
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

    private static final String PROP_FEED_URL = "ro.expressiveos.integrity.feed_url";
    private static final String PROP_FEED_PUBKEY = "ro.expressiveos.integrity.pubkey_spki";
    private static final String DEFAULT_FEED_URL =
            "https://ota-mirror.daryldenson-apps.workers.dev/expressiveos/integrity/manifest.json";
    private static final String FALLBACK_KEYBOX_URL =
            "https://ota-mirror.daryldenson-apps.workers.dev/expressiveos/keybox.xml";
    private static final String ROOTS_URL = "https://android.googleapis.com/attestation/root";
    private static final String CRL_URL =
            "https://android.googleapis.com/attestation/status?encrypted=0";

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

    private static final String SOURCE_USER = "user";
    private static final String SOURCE_OFFICIAL = "official";

    private static final Pattern TARGET_LINE_PATTERN =
            Pattern.compile("^[a-zA-Z0-9._]+[!\\?\\-]?$");
    private static final Pattern PLAIN_DATE_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern KEY_BLOCK_PATTERN =
            Pattern.compile("<Key\\b[^>]*algorithm=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</Key>");
    private static final Pattern CERT_PEM_PATTERN =
            Pattern.compile("-----BEGIN CERTIFICATE-----([\\s\\S]+?)-----END CERTIFICATE-----");
    private static final Pattern PRIV_PEM_PATTERN =
            Pattern.compile("-----BEGIN ([A-Z0-9 ]*PRIVATE KEY)-----([\\s\\S]+?)-----END \\1-----");

    private static volatile EosIntegrityService sInstance;

    private final Context mContext;
    private final Object mLock = new Object();
    private final SecureRandom mRandom = new SecureRandom();

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
        final ActionOutcome res = svc.fetchOfficialKeyboxImpl(force);
        return res.ok;
    }

    public static boolean refreshCanaryPifInternal(boolean force) {
        final EosIntegrityService svc = sInstance;
        if (svc == null) return false;
        final ActionOutcome res = svc.refreshCanaryPifImpl(force);
        return res.ok;
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
            return importUserKeyboxImpl(rawXml).toJsonString();
        }

        @Override
        public String fetchOfficialKeybox(boolean force) {
            enforceCallerPermission();
            return fetchOfficialKeyboxImpl(force).toJsonString();
        }

        @Override
        public String checkKeyboxRevocation() {
            enforceCallerPermission();
            return checkKeyboxRevocationImpl().toJsonString();
        }

        @Override
        public String deleteKeybox() {
            enforceCallerPermission();
            return deleteKeyboxImpl().toJsonString();
        }

        @Override
        public String importUserTargets(String rawText) {
            enforceCallerPermission();
            return importUserTargetsImpl(rawText).toJsonString();
        }

        @Override
        public String refreshCanaryPif(boolean force) {
            enforceCallerPermission();
            return refreshCanaryPifImpl(force).toJsonString();
        }

        @Override
        public String resetDefaults() {
            enforceCallerPermission();
            return resetDefaultsImpl().toJsonString();
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

    private ActionOutcome importUserKeyboxImpl(String rawXml) {
        if (rawXml == null || rawXml.trim().isEmpty()) {
            return new ActionOutcome(false, "EMPTY", "Keybox XML is empty");
        }
        final ValidationResult vr = validateKeyboxCandidate(rawXml, /* allowOfflineCrl= */ true);
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

    private ActionOutcome fetchOfficialKeyboxImpl(boolean force) {
        synchronized (mLock) {
            final JSONObject st = readStateLocked();
            final String source = st.optString("source", "");
            if (!force && SOURCE_USER.equals(source) && KEYBOX_FILE.isFile()) {
                return new ActionOutcome(true, "USER_OWNED", "Preserving user-imported keybox");
            }
        }

        // 1. Try signed R2 feed manifest first; fall back to FALLBACK_KEYBOX_URL with full gate.
        final List<String> candidateXmls = new ArrayList<>();
        final JSONObject feed = fetchVerifiedFeedManifest();
        if (feed != null) {
            final JSONArray boxes = feed.optJSONArray("keyboxes");
            if (boxes != null) {
                for (int i = 0; i < boxes.length(); i++) {
                    final JSONObject item = boxes.optJSONObject(i);
                    if (item == null) continue;
                    final String url = item.optString("url", "");
                    final String expectedSha = item.optString("sha256", "").toUpperCase(Locale.US);
                    if (url.isEmpty()) continue;
                    final byte[] raw = httpGetBytes(url);
                    if (raw == null || raw.length == 0) continue;
                    if (!expectedSha.isEmpty() && !sha256Hex(raw).equals(expectedSha)) {
                        Slog.w(TAG, "Rejected feed keybox due to SHA-256 mismatch");
                        continue;
                    }
                    candidateXmls.add(new String(raw, StandardCharsets.UTF_8));
                }
            }
            // Also refresh remote targets if included in verified feed
            final String targetsUrl = feed.optString("targets_url", "");
            final String targetsSha = feed.optString("targets_sha256", "").toUpperCase(Locale.US);
            if (!targetsUrl.isEmpty()) {
                final byte[] tRaw = httpGetBytes(targetsUrl);
                if (tRaw != null && (targetsSha.isEmpty() || sha256Hex(tRaw).equals(targetsSha))) {
                    updateRemoteTargets(new String(tRaw, StandardCharsets.UTF_8));
                }
            }
        }
        if (candidateXmls.isEmpty()) {
            final byte[] fallbackBytes = httpGetBytes(FALLBACK_KEYBOX_URL);
            if (fallbackBytes != null && fallbackBytes.length > 0) {
                candidateXmls.add(new String(fallbackBytes, StandardCharsets.UTF_8));
            }
        }
        if (candidateXmls.isEmpty()) {
            return new ActionOutcome(false, "FETCH_FAILED", "Unable to reach official keybox feed");
        }

        String lastFailure = "No unrevoked keybox candidate";
        for (String candidate : candidateXmls) {
            final ValidationResult vr = validateKeyboxCandidate(candidate, /* allowOfflineCrl= */ false);
            if (!vr.structurallyValid) {
                lastFailure = vr.reason;
                continue;
            }
            if ("REVOKED".equals(vr.status) || "SUSPENDED".equals(vr.status)
                    || "CHAIN_INVALID".equals(vr.status) || "UNTRUSTED_ROOT".equals(vr.status)) {
                lastFailure = vr.status + (vr.reason.isEmpty() ? "" : ": " + vr.reason);
                continue;
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
        return new ActionOutcome(false, "NO_VALID_KEYBOX", lastFailure);
    }

    private KeyboxSummary checkKeyboxRevocationImpl() {
        final String xml;
        synchronized (mLock) {
            xml = readUtf8Quietly(KEYBOX_FILE);
            if (xml == null || xml.trim().isEmpty()) {
                return buildKeyboxSummaryLocked();
            }
        }
        final ValidationResult vr = validateKeyboxCandidate(xml, /* allowOfflineCrl= */ true);
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
            fetchOfficialKeyboxImpl(/* force= */ false);
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

    private void updateRemoteTargets(String rawText) {
        final String normalized = normalizeAndValidateTargets(rawText);
        if (normalized == null) return;
        synchronized (mLock) {
            ensureStorageDirectoryLocked();
            writeAtomic0600Locked(TARGETS_REMOTE_FILE, normalized.getBytes(StandardCharsets.UTF_8));
            rebuildMergedTargetsLocked(/* syncSettingsMirror= */ true);
        }
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

        final Set<String> revokedFingerprints = new HashSet<>();
        JSONObject chosenJson = null;
        String chosenPatch = null;

        final JSONObject feed = fetchVerifiedFeedManifest();
        if (feed != null) {
            final JSONArray rev = feed.optJSONArray("revoked_pif_fingerprints");
            if (rev != null) {
                for (int i = 0; i < rev.length(); i++) {
                    final String fp = rev.optString(i, "").trim();
                    if (!fp.isEmpty()) revokedFingerprints.add(fp);
                }
            }
            final JSONArray profiles = feed.optJSONArray("pif_profiles");
            if (profiles != null) {
                for (int i = 0; i < profiles.length(); i++) {
                    final JSONObject p = profiles.optJSONObject(i);
                    if (p == null) continue;
                    final String status = p.optString("status", "ACTIVE").toUpperCase(Locale.US);
                    if ("REVOKED".equals(status) || "EXPIRED".equals(status)) continue;
                    final String fp = p.optString("FINGERPRINT", p.optString("fingerprint", "")).trim();
                    if (fp.isEmpty() || revokedFingerprints.contains(fp)
                            || !PixelDeviceRepository.isValidFingerprint(fp)) {
                        continue;
                    }
                    final String expUtc = p.optString("expires_utc", "");
                    if (!expUtc.isEmpty() && isIsoUtcExpired(expUtc)) {
                        continue;
                    }
                    chosenJson = buildPifFromFeedObject(existingRaw, p, fp);
                    chosenPatch = chosenJson.optString("SECURITY_PATCH", "");
                    break;
                }
            }
        }

        // Fallback: PixelDeviceRepository live scrape -> built-in FALLBACK_PROFILES (filtered by revokedFingerprints)
        if (chosenJson == null) {
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
                        && PixelDeviceRepository.isValidFingerprint(p.fingerprint)
                        && !revokedFingerprints.contains(p.fingerprint)) {
                    selected = p;
                    break;
                }
            }
            if (selected == null) {
                for (PixelDeviceRepository.PixelProfile p : candidates) {
                    if (PixelDeviceRepository.isValidFingerprint(p.fingerprint)
                            && !revokedFingerprints.contains(p.fingerprint)) {
                        selected = p;
                        break;
                    }
                }
            }
            if (selected == null) {
                return new ActionOutcome(false, "NO_VALID_PIF", "No unrevoked Pixel profile found");
            }
            chosenJson = buildPifFromProfile(existingRaw, selected);
            chosenPatch = selected.securityPatch;
        }

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
        return new ActionOutcome(true, "RESET", "");
    }

    // ---- Pre-commit cryptographic & CRL validation --------------------------

    private ValidationResult validateKeyboxCandidate(String rawXml, boolean allowOfflineCrl) {
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

        final Set<PublicKey> liveRootKeys = getTrustedRootPublicKeys();
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

        // Check Google CRL
        final byte[] crlBytes = httpGetBytes(CRL_URL + "&_=" + System.nanoTime());
        if (crlBytes == null) {
            if (!allowOfflineCrl) {
                return new ValidationResult(true, "UNKNOWN", "CRL_UNREACHABLE", earliestLeafExpiry);
            }
            synchronized (mLock) {
                final JSONObject st = readStateLocked();
                final long cachedAt = st.optLong("cached_revoked_at_ms", 0L);
                final String cachedSerial = st.optString("cached_revoked_serial", "");
                if (cachedAt > 0L && now - cachedAt <= REVOKED_CACHE_TTL_MS
                        && !cachedSerial.isEmpty() && allSerials.contains(cachedSerial)) {
                    return new ValidationResult(true, "SUSPENDED", "CACHED_OFFLINE", earliestLeafExpiry);
                }
            }
            return new ValidationResult(true, "UNKNOWN", "", earliestLeafExpiry);
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
            return new ValidationResult(true, "UNKNOWN", "CRL_PARSE_ERROR", earliestLeafExpiry);
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
            final Matcher km = KEY_BLOCK_PATTERN.matcher(xml);
            while (km.find()) {
                final String alg = km.group(1).trim().toLowerCase(Locale.US);
                final String body = km.group(2);
                final Matcher pm = PRIV_PEM_PATTERN.matcher(body);
                if (!pm.find()) continue;
                final String privPem = pm.group(0);
                final List<X509Certificate> certs = new ArrayList<>();
                final Matcher cm = CERT_PEM_PATTERN.matcher(body);
                while (cm.find()) {
                    final byte[] der = Base64.decode(
                            cm.group(1).replaceAll("\\s+", ""), Base64.DEFAULT);
                    certs.add((X509Certificate) cf.generateCertificate(
                            new ByteArrayInputStream(der)));
                }
                if (!certs.isEmpty()) {
                    out.add(new KeyChainBundle(alg, privPem, certs));
                }
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

    // ---- Ed25519 Signed Feed ------------------------------------------------

    private JSONObject fetchVerifiedFeedManifest() {
        final String feedUrl = SystemProperties.get(PROP_FEED_URL, DEFAULT_FEED_URL).trim();
        final String pubkeySpkiB64 = SystemProperties.get(PROP_FEED_PUBKEY, "").trim();
        if (feedUrl.isEmpty() || pubkeySpkiB64.isEmpty()) {
            return null;
        }
        final byte[] manifestBytes = httpGetBytes(feedUrl);
        final byte[] sigRaw = httpGetBytes(feedUrl + ".sig");
        if (manifestBytes == null || sigRaw == null) {
            return null;
        }
        try {
            final byte[] sigBytes = decodeEd25519Sig(sigRaw);
            if (sigBytes == null || sigBytes.length != 64) {
                Slog.w(TAG, "Invalid Ed25519 signature length on manifest.json.sig");
                return null;
            }
            final byte[] spkiDer = Base64.decode(pubkeySpkiB64, Base64.DEFAULT);
            final PublicKey pubKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(spkiDer));
            final Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(pubKey);
            verifier.update(manifestBytes);
            if (!verifier.verify(sigBytes)) {
                Slog.w(TAG, "Ed25519 verification FAILED for integrity manifest.json");
                return null;
            }
            final JSONObject json = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
            if (json.optInt("version", 0) != 1) {
                Slog.w(TAG, "Unsupported integrity manifest schema version");
                return null;
            }
            return json;
        } catch (Exception e) {
            Slog.w(TAG, "Failed verifying signed integrity manifest", e);
            return null;
        }
    }

    private static byte[] decodeEd25519Sig(byte[] raw) {
        if (raw.length == 64) return raw;
        final String text = new String(raw, StandardCharsets.UTF_8).trim();
        try {
            final byte[] b64 = Base64.decode(text, Base64.DEFAULT);
            if (b64.length == 64) return b64;
        } catch (Exception ignored) {
        }
        if (text.length() == 128) {
            final byte[] out = new byte[64];
            for (int i = 0; i < 64; i++) {
                out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        }
        return null;
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

    private Set<PublicKey> getTrustedRootPublicKeys() {
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
        final byte[] live = httpGetBytes(ROOTS_URL + "?_=" + System.nanoTime());
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
        if (s.startsWith("\uFEFF")) s = s.substring(1);
        return s.replaceAll("<!--[\\s\\S]*?-->", "").trim();
    }

    private static String checkKeyboxStructure(String xml) {
        if (!xml.contains("<Key algorithm=\"ecdsa\">") && !xml.contains("<Key algorithm=\"rsa\">")) {
            return "No ECDSA or RSA Key block";
        }
        if (!xml.contains("<serial>") && !xml.contains("DeviceID")) {
            return "No serial or DeviceID";
        }
        try {
            final XmlPullParser p = Xml.newPullParser();
            p.setInput(new StringReader(xml));
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                // Verify well-formed XML
            }
            return null;
        } catch (Exception e) {
            return "Malformed keybox XML";
        }
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

    private static JSONObject buildPifFromFeedObject(
            String existingRaw, JSONObject feedObj, String fp) {
        final JSONObject out = copyPreservedPifFlags(existingRaw);
        final String[] keys = {
                "MANUFACTURER", "BRAND", "MODEL", "PRODUCT", "DEVICE",
                "RELEASE", "ID", "INCREMENTAL", "TYPE", "TAGS",
                "SECURITY_PATCH", "DEVICE_INITIAL_SDK_INT",
                "_canary_month", "_canary_release_date"
        };
        for (String k : keys) {
            final String v = feedObj.optString(k, feedObj.optString(k.toLowerCase(Locale.US), ""));
            if (!v.isEmpty()) putQuietly(out, k, v);
        }
        putQuietly(out, "FINGERPRINT", fp);
        if (out.optString("DEVICE_INITIAL_SDK_INT", "").isEmpty()) {
            putQuietly(out, "DEVICE_INITIAL_SDK_INT",
                    PixelDeviceRepository.getInitialSdkInt(out.optString("DEVICE", "")));
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

    private static boolean isIsoUtcExpired(String isoUtc) {
        try {
            final String norm = isoUtc.endsWith("Z")
                    ? isoUtc.substring(0, isoUtc.length() - 1) : isoUtc;
            final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            final Date d = sdf.parse(norm);
            return d != null && d.getTime() < System.currentTimeMillis();
        } catch (Exception e) {
            return false;
        }
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
        final File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(bytes);
            fos.getFD().sync();
        } catch (IOException e) {
            Slog.e(TAG, "Failed writing " + tmp, e);
            tmp.delete();
            return;
        }
        try {
            Os.chmod(tmp.getAbsolutePath(), 0600);
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            FileUtils.sync(new FileOutputStream(new File(target.getParentFile(), ".dirsync_noop")));
        } catch (Exception e) {
            // Clean up any helper file and ensure target permissions
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Os.chmod(target.getAbsolutePath(), 0600);
            } catch (Exception ignored) {
            }
        } finally {
            new File(target.getParentFile(), ".dirsync_noop").delete();
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

    private static byte[] httpGetBytes(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setUseCaches(false);
            conn.setRequestProperty("Cache-Control", "no-store");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            return conn.getInputStream().readAllBytes();
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
