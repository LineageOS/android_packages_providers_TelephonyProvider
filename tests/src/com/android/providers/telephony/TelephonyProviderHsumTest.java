/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.telephony;

import static org.junit.Assert.assertFalse;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.UserManager;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Checks that the TelephonyProvider behavior on an HSUM (Headless System User Mode) device is as
 * expected.
 *
 * <p>Run this test with:
 * atest TelephonyProviderTests:com.android.providers.telephony.TelephonyProviderHsumTest
 */
@RunWith(JUnit4.class)
public class TelephonyProviderHsumTest {

    private static final String TAG = "TelephonyProviderHsumTest";

    /**
     * Tests that TelephonyProvider.apk is not installed on HSUM devices, since HSUM devices should
     * instead use the TelephonyProviderHsum.apk variant (if supported at all).
     */
    @Test
    @SmallTest
    public void testTelephonyProviderApkIsNotInstalledOnHsum() {
        assumeTrue("Test is only for HSUM devices", UserManager.isHeadlessSystemUserMode());

        Context context = ApplicationProvider.getApplicationContext();
        PackageManager pm = context.getPackageManager();
        ApplicationInfo info = null;
        try {
            info = pm.getApplicationInfo("com.android.providers.telephony",
                    PackageManager.MATCH_SYSTEM_ONLY);
        } catch (PackageManager.NameNotFoundException e) {
            // It is acceptable if the package isn't found at all (e.g., if the device doesn't
            // support telephony or messaging).
            assumeTrue("TelephonyProvider package not found", false);
        }

        Log.d(TAG, "TelephonyProvider APK found at: " + info.sourceDir);
        assertFalse("Headless system user mode devices should not have TelephonyProvider.apk "
                + "installed. Found at: " + info.sourceDir,
                info.sourceDir != null && info.sourceDir.endsWith("/TelephonyProvider.apk"));
    }

    /**
     * Tests that SmsProvider, MmsProvider, and MmsSmsProvider are not singleUser on HSUM devices.
     */
    @Test
    @SmallTest
    public void testProvidersAreNotSingleUserOnHsum() {
        assumeTrue("Test is only for HSUM devices", UserManager.isHeadlessSystemUserMode());

        Context context = ApplicationProvider.getApplicationContext();
        PackageManager pm = context.getPackageManager();
        PackageInfo packageInfo = null;
        try {
            packageInfo = pm.getPackageInfo("com.android.providers.telephony",
                    PackageManager.GET_PROVIDERS | PackageManager.MATCH_SYSTEM_ONLY);
        } catch (PackageManager.NameNotFoundException e) {
            // It is acceptable if the package isn't found at all (e.g., if the device doesn't
            // support telephony or messaging).
            assumeTrue("TelephonyProvider package not found", false);
        }

        ProviderInfo[] providers = packageInfo.providers;
        assumeTrue("TelephonyProvider had no providers", providers != null);

        for (ProviderInfo provider : providers) {
            if (provider.name.endsWith("SmsProvider")
                    || provider.name.endsWith("MmsProvider")
                    || provider.name.endsWith("MmsSmsProvider")) {
                boolean isSingleUser = (provider.flags & ProviderInfo.FLAG_SINGLE_USER) != 0;
                assertFalse(provider.name + " should not be singleUser on HSUM devices",
                        isSingleUser);
            }
        }
    }
}
