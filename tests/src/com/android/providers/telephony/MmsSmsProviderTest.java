/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentResolver;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

@RunWith(JUnit4.class)
public class MmsSmsProviderTest {
    private static final String TAG = "MmsSmsProviderTest";

    private Context mContext;
    private MockContentResolver mContentResolver;
    private MmsSmsProvider mMmsSmsProvider;
    private SmsProviderTestable mSmsProviderTestable;
    @Rule
    public final MockitoRule mocks = MockitoJUnit.rule();
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private Resources mMockResources;
    @Mock
    private SubscriptionManager mSubscriptionManager;

    @Before
    public void setUp() throws Exception {
        logd("Setup!");
        mContext = spy(ApplicationProvider.getApplicationContext());
        PackageManager pm = mContext.getPackageManager();

        // Check for telephony messaging feature
        boolean hasTelephonyMessaging = pm.hasSystemFeature(
                PackageManager.FEATURE_TELEPHONY_MESSAGING);
        assumeTrue("Device does not support FEATURE_TELEPHONY_MESSAGING, skipping test",
                hasTelephonyMessaging);

        mMmsSmsProvider = new MmsSmsProvider();
        mSmsProviderTestable = new SmsProviderTestable();

        // Common mock setup
        when(mContext.getSystemService(eq(Context.APP_OPS_SERVICE)))
                .thenReturn(mock(AppOpsManager.class));
        when(mContext.getSystemService(eq(Context.TELEPHONY_SERVICE)))
                .thenReturn(mock(TelephonyManager.class));
        when(mContext.checkCallingOrSelfPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mContext.getResources()).thenReturn(mMockResources);
        when(mContext.getUserId()).thenReturn(0);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getSystemService(SubscriptionManager.class)).thenReturn(mSubscriptionManager);

        // --- Start: Added SubscriptionManager mocks ---
        int subid = SmsManager.getDefaultSmsSubscriptionId();
        List<SubscriptionInfo> subscriptionInfoList = new ArrayList<>();
        SubscriptionInfo subscriptionInfo1 = mock(SubscriptionInfo.class);
        when(subscriptionInfo1.getSubscriptionId()).thenReturn(subid);
        when(subscriptionInfo1.getSimSlotIndex()).thenReturn(0);
        subscriptionInfoList.add(subscriptionInfo1);

        doReturn(subscriptionInfoList).when(mSubscriptionManager)
                .getSubscriptionInfoListAssociatedWithUser(any(UserHandle.class));
        doReturn(true).when(mSubscriptionManager).isSubscriptionAssociatedWithUser(anyInt(),
                any(UserHandle.class));
        // --- End: Added SubscriptionManager mocks ---

        mContentResolver = new MockContentResolver();
        when(mContext.getContentResolver()).thenReturn(mContentResolver);

        // Register MmsSmsProvider
        ProviderInfo mmsSmsProviderInfo = new ProviderInfo();
        mmsSmsProviderInfo.authority = "mms-sms";
        mMmsSmsProvider.attachInfo(mContext, mmsSmsProviderInfo);
        mContentResolver.addProvider("mms-sms", mMmsSmsProvider);

        // Register SmsProviderTestable for "sms" authority
        ProviderInfo smsProviderInfo = new ProviderInfo();
        smsProviderInfo.authority = "sms";
        mSmsProviderTestable.attachInfoForTesting(mContext, smsProviderInfo);
        mContentResolver.addProvider("sms", mSmsProviderTestable);

        // Insert preset data to make sure DB is not empty.
        insertPresetData();
    }

    @After
    public void tearDown() throws Exception {
        logd("TearDown!");
        if (mSmsProviderTestable != null) {
            mSmsProviderTestable.closeDatabase();
        }
    }

    @Test
    public void testQuery_withUnbalancedParentheses_returnsNull() {
        Uri testUri = Uri.parse("content://mms-sms/conversations");
        String[] projection = new String[]{"_id"};
        // Verify to check for unbalanced parentheses
        String maliciousSelection = "1=1) OR (1=1";

        // This should return null because the IllegalArgumentException from checkSelection
        // is caught and handled in MmsSmsProvider.
        Cursor cursor = mMmsSmsProvider.query(testUri, projection, maliciousSelection, null, null);
        assertNull("Cursor should be null due to caught exception for unbalanced parentheses",
                cursor);
    }

    @Test
    public void testQuery_withMaliciousClosingParenthesis_returnsNull() {
        Uri testUri = Uri.parse("content://mms-sms/conversations");
        String[] projection = new String[]{"_id"};
        // Verify to check for a closing parenthesis at the beginning
        String maliciousSelection = ") OR (1=1";

        // This should return null because the IllegalArgumentException from checkSelection
        // is caught and handled in MmsSmsProvider.
        Cursor cursor = mMmsSmsProvider.query(testUri, projection, maliciousSelection, null, null);
        assertNull(
                "Cursor should be null due to caught exception for malicious closing parenthesis",
                cursor);
    }

    @Test
    public void testQuery_withProperlyBalancedParentheses_doesNotReturnNullFromSelectionCheck() {
        Uri queryUri = Uri.parse("content://mms-sms/conversations");
        String[] projection = new String[]{"_id"};
        String[] normalSelections = {
                "(" + Telephony.Sms.READ + "=1)",  // Original test
                "",                                // New: Empty selection
                Telephony.Sms.READ + "=1"          // New: Selection without parentheses
        };

        for (String selection : normalSelections) {
            Cursor cursor = null;
            try {
                cursor = mMmsSmsProvider.query(queryUri, projection, selection, null, null);
                assertNotNull("Cursor should not be null for selection: \"" + selection + "\"",
                        cursor);

            } catch (IllegalArgumentException e) {
                if (e.getMessage() != null && e.getMessage().contains("Unbalanced brackets")) {
                    fail("Should NOT have thrown IllegalArgumentException from checkSelection for"
                            + " selection '"
                            + selection + "': " + e.getMessage());
                }
                Log.w(TAG,
                        "MmsSmsProvider.query threw an expected IllegalArgumentException for "
                                + "selection '"
                                + selection + "': " + e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "MmsSmsProvider.query threw an exception for selection '" + selection
                        + "': " + e.getMessage());
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
    }

    private void insertPresetData() {
        // Insert common data for all tests
        try {
            ContentValues values = new ContentValues();
            values.put(Telephony.Sms.ADDRESS, "12345");
            values.put(Telephony.Sms.BODY, "common test body");
            values.put(Telephony.Sms.READ, 1);
            Uri insertedUri = mContentResolver.insert(Telephony.Sms.CONTENT_URI, values);
            if (insertedUri != null) {
                Log.i(TAG, "Common data inserted successfully: " + insertedUri);
            } else {
                Log.w(TAG, "Common data insertion returned null URI.");
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Known issue: Common data insertion failed in setUp: " + e.getMessage());
        }
    }

    static void logd(String msg) {
        Log.d(TAG, msg);
    }
}
