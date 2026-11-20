/*
 * SPDX-FileCopyrightText: 2026 The ExpressiveOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.security.integrity;

import android.security.integrity.KeyboxSummary;

/**
 * Privileged binder interface for ExpressiveOS 2.0 integrity state management.
 * Guarded by {@code android.permission.MANAGE_EOS_INTEGRITY} (signature|privileged).
 *
 * @hide
 */
interface IEosIntegrityManager {
    String getKeyboxSummary();
    String importUserKeybox(String rawXml);
    String fetchOfficialKeybox(boolean force);
    String checkKeyboxRevocation();
    String deleteKeybox();
    String importUserTargets(String rawText);
    String refreshCanaryPif(boolean force);
    String resetDefaults();
    KeyboxSummary getKeyboxSummaryParcelable();
}
