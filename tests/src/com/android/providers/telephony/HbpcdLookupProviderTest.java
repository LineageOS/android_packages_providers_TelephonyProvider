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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.test.mock.MockContentResolver;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.telephony.HbpcdLookup;
import com.android.internal.telephony.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.MockitoAnnotations;

/**
 * Tests for testing CRUD operations of HbpcdLookupProvider.
 */
public class HbpcdLookupProviderTest {
    private static final String TAG = "HbpcdLookupProviderTest";

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private Context mContext;
    private MockContentResolver mContentResolver;
    private HbpcdLookupProviderTestHelper mHbpcdLookupProviderTestHelper;

    @Before
    public void setUp() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity("android.permission.READ_PHONE_STATE");
        MockitoAnnotations.initMocks(this);
        mHbpcdLookupProviderTestHelper = new HbpcdLookupProviderTestHelper();
        mContext = spy(ApplicationProvider.getApplicationContext());

        when(mContext.checkCallingOrSelfPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        mContentResolver = new MockContentResolver();

        // Add authority="hbpcd_lookup" to given hbpcdLookupProvider
        ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = HbpcdLookup.AUTHORITY;

        // Add context to given hbpcdLookupProvider
        mHbpcdLookupProviderTestHelper.attachInfoForTesting(mContext, providerInfo);

        // Add given HbpcdLookupProvider to mResolver with authority="hbpcd_lookup"
        mContentResolver.addProvider(HbpcdLookup.AUTHORITY, mHbpcdLookupProviderTestHelper);
    }

    @After
    public void tearDown() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
        mHbpcdLookupProviderTestHelper.closeDatabase();
    }

    @Test
    @SmallTest
    @EnableFlags(Flags.FLAG_FIX_SQL_INJECTION_HBPCD)
    public void testQuery_withSubquery_returnsNull() {
        Uri testUri = HbpcdLookup.MccIdd.CONTENT_URI;
        String[] projection = new String[]{HbpcdLookup.MccIdd.IDD};
        // Verify to check for subqueries
        String maliciousSelection = "MCC=1) OR (SELECT 1 FROM mcc_idd";

        Cursor cursor = mHbpcdLookupProviderTestHelper.query(testUri, projection,
                maliciousSelection, null, null);
        assertNull("Cursor should be null due to SELECT token being forbidden for"
                        + " restricted callers", cursor);
    }

    @Test
    @SmallTest
    @EnableFlags(Flags.FLAG_FIX_SQL_INJECTION_HBPCD)
    public void testQuery_withoutSubquery_returnsNotNull() {
        Uri testUri = HbpcdLookup.MccIdd.CONTENT_URI;
        String[] projection = new String[]{HbpcdLookup.MccIdd.IDD};
        String normalSelection = "MCC=1";

        Cursor cursor = mHbpcdLookupProviderTestHelper.query(testUri, projection, normalSelection,
                null, null);
        assertNotNull("Cursor should not be null for normal selection", cursor);
        cursor.close();
    }
}
