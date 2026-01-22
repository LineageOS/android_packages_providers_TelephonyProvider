/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.RestrictionEntry;
import android.content.RestrictionsManager;
import android.os.UserHandle;
import android.os.Process;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import com.android.internal.telephony.flags.Flags;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ProviderUtilTest {
    private static final String TAG = "ProviderUtilTest";

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private Context mContext;
    @Mock
    private SubscriptionManager mSubscriptionManager;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private AppOpsManager mAppOpsManager;
    @Mock
    private RestrictionsManager mRestrictionsManager;

    private Map<Integer, List<EmergencyNumber>> mEmergencyNumberList;

    private static final String EXAMPLE_PACKAGE_NAME = "com.example.app";
    private static final String EXAMPLE_ARCHIVAL_PACKAGE_NAME = "com.example.archival.app";
    private static final int EXAMPLE_PACKAGE_UID = 21001;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = spy(ApplicationProvider.getApplicationContext());

        when(mContext.getSystemService(SubscriptionManager.class)).thenReturn(mSubscriptionManager);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        when(mContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mAppOpsManager);
        when(mContext.getSystemService(Context.RESTRICTIONS_SERVICE)).thenReturn(
                mRestrictionsManager);
        when(mRestrictionsManager.getManifestRestrictions(anyString())).thenReturn(null);
    }

    @After
    public void tearDown() throws Exception {
        mEmergencyNumberList = null;
    }

    @Test
    public void getSelectionBySubIds_noSubscription() {
        List<SubscriptionInfo> subscriptionInfoList = new ArrayList<>();
        doReturn(subscriptionInfoList).when(mSubscriptionManager)
                .getSubscriptionInfoListAssociatedWithUser(UserHandle.SYSTEM);

        assertThat(ProviderUtil.getSelectionBySubIds(mContext, UserHandle.SYSTEM, null))
                .isEqualTo("sub_id IN ('-1')");
    }

    @Test
    public void getSelectionBySubIds_withDefaultSubId() {
        // As sub_id is not set explicitly, its value will be -1
        SubscriptionInfo subscriptionInfo1 = new SubscriptionInfo.Builder()
                .setSimSlotIndex(0)
                .build();
        List<SubscriptionInfo> subscriptionInfoList = new ArrayList<>();
        subscriptionInfoList.add(subscriptionInfo1);

        doReturn(subscriptionInfoList).when(mSubscriptionManager)
                .getSubscriptionInfoListAssociatedWithUser(UserHandle.SYSTEM);

        assertThat(ProviderUtil.getSelectionBySubIds(mContext, UserHandle.SYSTEM, null))
                .isEqualTo("sub_id IN ('-1','-1')");
    }

    @Test
    public void getSelectionBySubIds_withActiveSubscriptions() {
        SubscriptionInfo subscriptionInfo1 = new SubscriptionInfo.Builder()
                .setId(1)
                .setSimSlotIndex(0)
                .build();
        List<SubscriptionInfo> subscriptionInfoList = new ArrayList<>();

        SubscriptionInfo subscriptionInfo2 = new SubscriptionInfo.Builder()
                .setId(2)
                .setSimSlotIndex(1)
                .build();

        subscriptionInfoList.add(subscriptionInfo1);
        subscriptionInfoList.add(subscriptionInfo2);
        doReturn(subscriptionInfoList).when(mSubscriptionManager)
                .getSubscriptionInfoListAssociatedWithUser(UserHandle.SYSTEM);

        assertThat(ProviderUtil.getSelectionBySubIds(mContext, UserHandle.SYSTEM, null))
                .isEqualTo("sub_id IN ('1','2','-1')");
    }

    @Test
    public void getSelectionBySubIds_withTableName_withActiveSubscriptions() {
        String tableName = "pdu";
        SubscriptionInfo subscriptionInfo1 = new SubscriptionInfo.Builder()
                .setId(1)
                .setSimSlotIndex(0)
                .build();
        List<SubscriptionInfo> subscriptionInfoList = new ArrayList<>();

        SubscriptionInfo subscriptionInfo2 = new SubscriptionInfo.Builder()
                .setId(2)
                .setSimSlotIndex(1)
                .build();

        subscriptionInfoList.add(subscriptionInfo1);
        subscriptionInfoList.add(subscriptionInfo2);
        doReturn(subscriptionInfoList).when(mSubscriptionManager)
                .getSubscriptionInfoListAssociatedWithUser(UserHandle.SYSTEM);

        assertThat(ProviderUtil.getSelectionBySubIds(mContext, UserHandle.SYSTEM, tableName))
                .isEqualTo(tableName + "." + "sub_id IN ('1','2','-1')");
    }

    @Test
    public void getSelectionByEmergencyNumbers_nullEmergencyNumberList() {
        doReturn(null).when(mTelephonyManager).getEmergencyNumberList();

        assertThat(ProviderUtil.getSelectionByEmergencyNumbers(mContext))
                .isEqualTo(null);
    }

    @Test
    public void getSelectionByEmergencyNumbers_emptyEmergencyNumberList() {
        mEmergencyNumberList = Map.of();
        doReturn(mEmergencyNumberList).when(mTelephonyManager).getEmergencyNumberList();

        assertThat(ProviderUtil.getSelectionByEmergencyNumbers(mContext))
                .isEqualTo(null);
    }

    @Test
    public void getSelectionBySubIds_withEmergencyNumberList() {
        // Create emergencyNumberList for testing.
        List<EmergencyNumber> emergencyNumberList1 = new ArrayList<EmergencyNumber>();
        emergencyNumberList1.add(new EmergencyNumber("911", "us", "000",
                EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE,null, 0, 0));
        emergencyNumberList1.add(new EmergencyNumber("112", "us", "000",
                EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE,null, 0, 0));
        mEmergencyNumberList = Map.of(-1, emergencyNumberList1);
        doReturn(mEmergencyNumberList).when(mTelephonyManager).getEmergencyNumberList();

        assertThat(ProviderUtil.getSelectionByEmergencyNumbers(mContext))
                .isEqualTo("address IN ('911','112')");
    }

    @Test
    @EnableFlags(Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES)
    public void canReadRestrictedMessages_systemUid_returnsTrue() {
        assertThat(ProviderUtil.canReadRestrictedMessages(mContext, mContext.getPackageName(),
                Process.SYSTEM_UID)).isTrue();
    }

    @Test
    @EnableFlags(Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES)
    public void canReadRestrictedMessages_packageNoAppOpGranted_returnsFalse() {
        when(mAppOpsManager.noteOpNoThrow(AppOpsManager.OP_READ_RESTRICTED_MESSAGES,
                EXAMPLE_PACKAGE_UID, EXAMPLE_PACKAGE_NAME, null, null)).thenReturn(
                    AppOpsManager.MODE_IGNORED);

        assertThat(ProviderUtil.canReadRestrictedMessages(mContext, EXAMPLE_PACKAGE_NAME,
                EXAMPLE_PACKAGE_UID)).isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES)
    public void canReadRestrictedMessages_appOpNotGranted_differentArchivalApp_returnsFalse() {
        when(mRestrictionsManager.getManifestRestrictions(EXAMPLE_PACKAGE_NAME))
                .thenReturn(
                List.of(
                        new RestrictionEntry("messages_archival", "com.example.different.app")
                ));
        when(mAppOpsManager.noteOpNoThrow(AppOpsManager.OP_READ_RESTRICTED_MESSAGES,
                EXAMPLE_PACKAGE_UID, EXAMPLE_PACKAGE_NAME, null, null)).thenReturn(
                    AppOpsManager.MODE_IGNORED);

        assertThat(ProviderUtil.canReadRestrictedMessages(mContext, EXAMPLE_PACKAGE_NAME,
                EXAMPLE_PACKAGE_UID)).isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES)
    public void canReadRestrictedMessages_packageWithAppOpGranted_returnsTrue() {
        when(mAppOpsManager.noteOpNoThrow(AppOpsManager.OP_READ_RESTRICTED_MESSAGES,
                EXAMPLE_PACKAGE_UID, EXAMPLE_PACKAGE_NAME, null, null)).thenReturn(
                    AppOpsManager.MODE_ALLOWED);

        assertThat(ProviderUtil.canReadRestrictedMessages(mContext, EXAMPLE_PACKAGE_NAME,
                EXAMPLE_PACKAGE_UID)).isTrue();
    }

    @Test
    @DisableFlags(Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES)
    public void canReadRestrictedMessages_packageWithAppOpGranted_flagDisabled_returnsTrue() {
        when(mAppOpsManager.noteOpNoThrow(AppOpsManager.OP_READ_RESTRICTED_MESSAGES,
                EXAMPLE_PACKAGE_UID, EXAMPLE_PACKAGE_NAME, null, null)).thenReturn(
                    AppOpsManager.MODE_ALLOWED);

        assertThat(ProviderUtil.canReadRestrictedMessages(mContext, EXAMPLE_PACKAGE_NAME,
                EXAMPLE_PACKAGE_UID)).isTrue();
    }

    @Test
    @EnableFlags(Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES)
    public void canWriteRestrictedMessages_systemUid_returnsTrue() {
        assertThat(ProviderUtil.canWriteRestrictedMessages(mContext, mContext.getPackageName(),
                Process.SYSTEM_UID)).isTrue();
    }

    @Test
    @EnableFlags(Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES)
    public void canWriteRestrictedMessages_packageNoAppOpGranted_returnsFalse() {
        when(mAppOpsManager.noteOpNoThrow(AppOpsManager.OP_WRITE_RESTRICTED_MESSAGES,
                EXAMPLE_PACKAGE_UID, EXAMPLE_PACKAGE_NAME, null, null)).thenReturn(
                    AppOpsManager.MODE_IGNORED);

        assertThat(ProviderUtil.canWriteRestrictedMessages(mContext, EXAMPLE_PACKAGE_NAME,
                EXAMPLE_PACKAGE_UID)).isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES)
    public void canWriteRestrictedMessages_packageWithAppOpGranted_returnsTrue() {
        when(mAppOpsManager.noteOpNoThrow(AppOpsManager.OP_WRITE_RESTRICTED_MESSAGES,
                EXAMPLE_PACKAGE_UID, EXAMPLE_PACKAGE_NAME, null, null)).thenReturn(
                    AppOpsManager.MODE_ALLOWED);

        assertThat(ProviderUtil.canWriteRestrictedMessages(mContext, EXAMPLE_PACKAGE_NAME,
                EXAMPLE_PACKAGE_UID)).isTrue();
    }

    @Test
    @DisableFlags(Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES)
    public void canWriteRestrictedMessages_flagDisabled_packageWithAppOpGranted_returnsTrue() {
        when(mAppOpsManager.noteOpNoThrow(AppOpsManager.OP_WRITE_RESTRICTED_MESSAGES,
                EXAMPLE_PACKAGE_UID, EXAMPLE_PACKAGE_NAME, null, null)).thenReturn(
                    AppOpsManager.MODE_ALLOWED);

        assertThat(ProviderUtil.canWriteRestrictedMessages(mContext, EXAMPLE_PACKAGE_NAME,
                EXAMPLE_PACKAGE_UID)).isTrue();
    }

    @Test
    public void allowInteractWithEntryOfSubId() {
        assertThat(ProviderUtil.allowInteractingWithEntryOfSubscription(mContext,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID, UserHandle.SYSTEM)).isTrue();
    }
}
