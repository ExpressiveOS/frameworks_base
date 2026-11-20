/*
 * SPDX-FileCopyrightText: 2026 The ExpressiveOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.security.integrity;

import android.os.Parcel;
import android.os.Parcelable;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Metadata-only summary of the installed keybox. Never carries private key material.
 *
 * @hide
 */
public final class KeyboxSummary implements Parcelable {
    public final boolean present;
    public final String source;
    public final String revocationStatus;
    public final String revocationReason;
    public final long lastFetchedMs;
    public final long lastCheckedMs;
    public final long leafExpiryMs;

    public KeyboxSummary(
            boolean present,
            String source,
            String revocationStatus,
            String revocationReason,
            long lastFetchedMs,
            long lastCheckedMs,
            long leafExpiryMs) {
        this.present = present;
        this.source = source != null ? source : "";
        this.revocationStatus = revocationStatus != null ? revocationStatus : "";
        this.revocationReason = revocationReason != null ? revocationReason : "";
        this.lastFetchedMs = lastFetchedMs;
        this.lastCheckedMs = lastCheckedMs;
        this.leafExpiryMs = leafExpiryMs;
    }

    private KeyboxSummary(Parcel in) {
        this.present = in.readBoolean();
        this.source = nonNull(in.readString());
        this.revocationStatus = nonNull(in.readString());
        this.revocationReason = nonNull(in.readString());
        this.lastFetchedMs = in.readLong();
        this.lastCheckedMs = in.readLong();
        this.leafExpiryMs = in.readLong();
    }

    private static String nonNull(String s) {
        return s != null ? s : "";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(present);
        dest.writeString(source);
        dest.writeString(revocationStatus);
        dest.writeString(revocationReason);
        dest.writeLong(lastFetchedMs);
        dest.writeLong(lastCheckedMs);
        dest.writeLong(leafExpiryMs);
    }

    public String toJsonString() {
        try {
            final JSONObject json = new JSONObject();
            json.put("present", present);
            json.put("source", source);
            json.put("revocation_status", revocationStatus);
            json.put("revocation_reason", revocationReason);
            json.put("last_fetched_ms", lastFetchedMs);
            json.put("last_checked_ms", lastCheckedMs);
            json.put("leaf_expiry_ms", leafExpiryMs);
            return json.toString();
        } catch (JSONException e) {
            return "{\"present\":false}";
        }
    }

    public static final Creator<KeyboxSummary> CREATOR = new Creator<KeyboxSummary>() {
        @Override
        public KeyboxSummary createFromParcel(Parcel in) {
            return new KeyboxSummary(in);
        }

        @Override
        public KeyboxSummary[] newArray(int size) {
            return new KeyboxSummary[size];
        }
    };
}
