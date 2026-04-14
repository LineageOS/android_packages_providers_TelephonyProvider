/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.compat.CompatChanges;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.verify.domain.DomainVerificationInfo;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Telephony;
import android.provider.Telephony.ReadRestriction;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.PackageBasedTokenUtil;
import com.android.internal.telephony.SmsApplication;
import com.android.internal.telephony.TelephonyPermissions;
import com.android.internal.telephony.flags.Flags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Helpers
 */
public class ProviderUtil {
    private final static String TAG = "SmsProvider";
    private static final String TELEPHONY_PROVIDER_PACKAGE = "com.android.providers.telephony";

    /** A possible OTP message should only remain in its "pending otp classification" state for
     * up to 5 seconds
     */
    private static final long OTP_CLASSIFICATION_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);

    /** OTP messages should be redacted for 3 hours */
    public static final long OTP_HIDING_TIME_MS = TimeUnit.HOURS.toMillis(3);

    private static final int MAX_ALLOWED_VERIFIED_DOMAINS = 25;

    /**
     * Check if a caller of the provider has restricted access,
     * i.e. being non-system, non-phone, non-default SMS app
     *
     * @param context the context to use
     * @param packageName the caller package name
     * @param uid the caller uid
     * @return true if the caller is not system, or phone or default sms app, false otherwise
     */
    public static boolean isAccessRestricted(Context context, String packageName, int uid) {
        return (!TelephonyPermissions.isSystemOrPhone(uid)
                && !SmsApplication.isDefaultSmsApplication(context, packageName));
    }

    /**
     * Check if a caller of the provider can read restricted messages.
     *
     * @param context the context to use
     * @param packageName the caller package name
     * @param uid the caller uid
     * @return true if the caller is system or phone, or has the app op, false otherwise
     */
    public static boolean canReadRestrictedMessages(Context context, String packageName, int uid) {
        if(!Flags.secureAccessToRestrictedRcsMessages()
                || TelephonyPermissions.isSystemOrPhone(uid)) {
            return true;
        }
        int op = ((AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE)).noteOpNoThrow(
                AppOpsManager.OP_READ_RESTRICTED_MESSAGES, uid, packageName, null, null);
        return op == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Check if a caller of the provider can read OTP messages.
     *
     * @param context the context to use
     * @param uid the caller uid
     * @param packageName the caller package name
     * @return true if the caller is trusted for SMS OTP, false otherwise
     */
    @SuppressLint("MissingPermission")
    public static boolean canReadOtpSms(Context context, int uid, String packageName) {
        return SmsManager.isAppTrustedForSmsOtp(context, packageName, uid);
    }

    /**
     * Check if a caller of the provider can write restricted messages.
     *
     * @param context the context to use
     * @param packageName the caller package name
     * @param uid the caller uid
     * @return true if the caller is system or phone, or has the app op, false otherwise
     */
    public static boolean canWriteRestrictedMessages(Context context, String packageName, int uid) {
        // Assumes that the caller has the permission to write restricted messages, as long as they
        // have WRITE_SMS permission.
        return true;
    }

    /**
     * Check if a message is restricted by inspecting the read restriction column.
     *
     * @param values The content of the message
     * @return true if the message is restricted, false otherwise
     */
    public static boolean isMessageReadRestricted(ContentValues values) {
        if (!values.containsKey(ReadRestriction.READ_RESTRICTION_COLUMN_NAME)) {
            return false;
        }
        int readRestriction = values.getAsInteger(ReadRestriction.READ_RESTRICTION_COLUMN_NAME);
        return (readRestriction & ReadRestriction.ReadRestrictionValues.READ_RESTRICTION_RESTRICTED)
                > 0;
    }

    /**
     * Whether should set CREATOR for an insertion
     *
     * @param values The content of the message
     * @param uid The caller UID of the insertion
     * @return true if we should set CREATOR, false otherwise
     */
    public static boolean shouldSetCreator(ContentValues values, int uid) {
        return (!TelephonyPermissions.isSystemOrPhone(uid))
                || (!values.containsKey(Telephony.Sms.CREATOR)
                        && !values.containsKey(Telephony.Mms.CREATOR));
    }

    /**
     * Whether should remove CREATOR for an update
     *
     * @param values The content of the message
     * @param uid The caller UID of the update
     * @return true if we should remove CREATOR, false otherwise
     */
    public static boolean shouldRemoveCreator(ContentValues values, int uid) {
        return (!TelephonyPermissions.isSystemOrPhone(uid))
                && (values.containsKey(Telephony.Sms.CREATOR)
                        || values.containsKey(Telephony.Mms.CREATOR));
    }

    /**
     * Notify the default SMS app of an SMS/MMS provider change if the change is being made
     * by a package other than the default SMS app itself.
     *
     * @param uri The uri the provider change applies to
     * @param callingPackage The package name of the provider caller
     * @param Context
     */
    public static void notifyIfNotDefaultSmsApp(final Uri uri, final String callingPackage,
            final Context context) {
        if (TextUtils.equals(callingPackage, Telephony.Sms.getDefaultSmsPackage(context))) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(TAG, "notifyIfNotDefaultSmsApp - called from default sms app");
            }
            return;
        }
        // Direct the intent to only the default SMS app, and only if the SMS app has a receiver
        // for the intent.
        ComponentName componentName =
                SmsApplication.getDefaultExternalTelephonyProviderChangedApplication(context, true);
        if (componentName == null) {
            return;     // the default sms app doesn't have a receiver for this intent
        }

        final Intent intent =
                new Intent(Telephony.Sms.Intents.ACTION_EXTERNAL_PROVIDER_CHANGE);
        intent.setFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.setComponent(componentName);
        if (uri != null) {
            intent.setData(uri);
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, "notifyIfNotDefaultSmsApp - called from " + callingPackage + ", notifying");
        }
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.sendBroadcast(intent);
    }

    public static Context getCredentialEncryptedContext(Context context) {
        if (context.isCredentialProtectedStorage()) {
            return context;
        }
        return context.createCredentialProtectedStorageContext();
    }

    public static Context getDeviceEncryptedContext(Context context) {
        if (context.isDeviceProtectedStorage()) {
            return context;
        }
        return context.createDeviceProtectedStorageContext();
    }

    /**
     * Get subscriptions associated with the user in the format of a selection string.
     * @param context context
     * @param userHandle caller user handle.
     * @param tableName table name to be used in the selection string. If null, no prefix will be
     * added to the selection string.
     * @return subscriptions associated with the user in the format of a selection string
     * or {@code null} if user is not associated with any subscription.
     */
    @Nullable
    public static String getSelectionBySubIds(Context context,
            @NonNull final UserHandle userHandle, @Nullable String tableName) {
        List<SubscriptionInfo> associatedSubscriptionsList = new ArrayList<>();
        SubscriptionManager subManager = context.getSystemService(SubscriptionManager.class);
        UserManager userManager = context.getSystemService(UserManager.class);

        if (Flags.workProfileApiSplit()) {
            if (subManager != null) {
                // Get list of subscriptions accessible to this user.
                associatedSubscriptionsList = subManager
                        .getSubscriptionInfoListAssociatedWithUser(userHandle);

                if ((userManager != null)
                        && userManager.isManagedProfile(userHandle.getIdentifier())) {
                    // Work profile caller can only see subscriptions explicitly associated with it.
                    associatedSubscriptionsList = associatedSubscriptionsList.stream()
                            .filter(info -> userHandle.equals(subManager
                                            .getSubscriptionUserHandle(info.getSubscriptionId())))
                            .collect(Collectors.toList());
                } else {
                    // SMS/MMS restored from another device have sub_id=-1.
                    // To query/update/delete those messages, sub_id=-1 should be in the selection
                    // string.
                    SubscriptionInfo invalidSubInfo = new SubscriptionInfo.Builder()
                            .setId(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                            .build();
                    associatedSubscriptionsList.add(invalidSubInfo);
                }
            }
        } else {
            if (subManager != null) {
                // Get list of subscriptions associated with this user.
                associatedSubscriptionsList = subManager
                        .getSubscriptionInfoListAssociatedWithUser(userHandle);
            }

            if ((userManager != null)
                    && (!userManager.isManagedProfile(userHandle.getIdentifier()))) {
                // SMS/MMS restored from another device have sub_id=-1.
                // To query/update/delete those messages, sub_id=-1 should be in the selection
                // string.
                SubscriptionInfo invalidSubInfo = new SubscriptionInfo.Builder()
                        .setId(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                        .build();
                associatedSubscriptionsList.add(invalidSubInfo);
            }
        }

        if (associatedSubscriptionsList.isEmpty()) {
            return null;
        }

        final String tableNamePrefix = tableName == null ? "" : tableName + ".";
        // Converts [1,2,3,4,-1] to "'1','2','3','4','-1'" so that it can be appended to
        // selection string
        String subIdListStr = associatedSubscriptionsList.stream()
                .map(subInfo -> ("'" + subInfo.getSubscriptionId() + "'"))
                .collect(Collectors.joining(","));
        String selectionBySubId = (tableNamePrefix + Telephony.Sms.SUBSCRIPTION_ID +
                " IN (" + subIdListStr + ")");
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, "getSelectionBySubIds: " + selectionBySubId);
        }
        return selectionBySubId;
    }

    /**
     * Get emergency number list in the format of a selection string.
     * @param context context
     * @return emergency number list in the format of a selection string
     * or {@code null} if emergency number list is empty.
     */
    @Nullable
    public static String getSelectionByEmergencyNumbers(@NonNull Context context) {
        // Get emergency number list to add it to selection string.
        TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        Map<Integer, List<EmergencyNumber>> emergencyNumberList = null;
        try {
            if (tm != null) {
                emergencyNumberList = tm.getEmergencyNumberList();
            }
        } catch (Exception e) {
            Log.e(TAG, "Cannot get emergency number list", e);
        }

        String selectionByEmergencyNumber = null;
        if (emergencyNumberList != null && !emergencyNumberList.isEmpty()) {
            String emergencyNumberListStr = "";
            for (Map.Entry<Integer, List<EmergencyNumber>> entry : emergencyNumberList.entrySet()) {
                if (!emergencyNumberListStr.isEmpty() && !entry.getValue().isEmpty()) {
                    emergencyNumberListStr += ',';
                }

                emergencyNumberListStr += entry.getValue().stream()
                        .map(emergencyNumber -> ("'" + emergencyNumber.getNumber() + "'"))
                        .collect(Collectors.joining(","));
            }
            selectionByEmergencyNumber = Telephony.Sms.ADDRESS +
                    " IN (" + emergencyNumberListStr + ")";
        }
        return selectionByEmergencyNumber;
    }

    /**
     * Check sub is either default value(for backup restore) or is accessible by the caller profile.
     * @param ctx Context
     * @param subId The sub Id associated with the entry
     * @param callerUserHandle The user handle of the caller profile
     * @return {@code true} if allow the caller to insert an entry that's associated with this sub.
     */
    public static boolean allowInteractingWithEntryOfSubscription(Context ctx,
            int subId, UserHandle callerUserHandle) {
        return TelephonyPermissions
                .checkSubscriptionAssociatedWithUser(ctx, subId, callerUserHandle)
                // INVALID_SUBSCRIPTION_ID represents backup restore.
                || subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    /**
     * Log all running processes of the telephony provider package.
     */
    public static int logRunningTelephonyProviderProcesses(@NonNull Context context) {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) {
            Log.d(TAG, "logRunningTelephonyProviderProcesses: ActivityManager service is not"
                    + " available");
            return 0;
        }

        List<ActivityManager.RunningAppProcessInfo> processInfos = am.getRunningAppProcesses();
        if (processInfos == null) {
            Log.d(TAG, "logRunningTelephonyProviderProcesses: processInfos is null");
            return 0;
        }

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (ActivityManager.RunningAppProcessInfo processInfo : processInfos) {
            if (Arrays.asList(processInfo.pkgList).contains(TELEPHONY_PROVIDER_PACKAGE)
                    || UserHandle.isSameApp(processInfo.uid, Process.PHONE_UID)) {
                sb.append("{ProcessName=");
                count++;
                sb.append(processInfo.processName);
                sb.append(";PID=");
                sb.append(processInfo.pid);
                sb.append(";UID=");
                sb.append(processInfo.uid);
                sb.append(";pkgList=");
                for (String pkg : processInfo.pkgList) {
                    sb.append(pkg + ";");
                }
                sb.append("}");
            }
        }
        Log.d(TAG, "RunningTelephonyProviderProcesses:" + sb.toString());
        return count;
    }

    /**
     * Returns a SQL WHERE clause to filter out OTP messages for unauthorized callers.
     *
     * @param context the context to use
     * @param callingPackage the caller package name
     * @param userHandle the caller user handle
     * @return the SQL WHERE clause
     */
    @SuppressLint("MissingPermission")
    public static String getOtpWhereFilter(Context context, String callingPackage,
            UserHandle userHandle) {
        // If this app can't read OTP messages, only return messages without OTPs, or
        // messages more than the threshold old, or messages still pending classification,
        // past the classification cutoff time.
        long startOfCurrentMinuteInMs = (System.currentTimeMillis() / TimeUnit.MINUTES.toMillis(1))
                * TimeUnit.MINUTES.toMillis(1);
        long otpCutoff = startOfCurrentMinuteInMs - OTP_HIDING_TIME_MS;
        long startOfCurrentSecondInMs = (System.currentTimeMillis() / TimeUnit.SECONDS.toMillis(1))
                * TimeUnit.SECONDS.toMillis(1);
        long pendingOtpCutoff = startOfCurrentSecondInMs - OTP_CLASSIFICATION_TIMEOUT_MS;
        final StringBuilder where = new StringBuilder("(");
        where.append(String.format(Locale.US,
                " %s OR %s < %d OR (%s AND %s < %d)",
                getContainsOtpSqlFilter(Telephony.Sms.OTP_TYPE_NONE), Telephony.Sms.DATE, otpCutoff,
                getOtpPendingSqlFilter(), Telephony.Sms.DATE, pendingOtpCutoff));
        final String hash = PackageBasedTokenUtil.generatePackageBasedToken(
                context.getPackageManager(), callingPackage, userHandle);
        if (hash != null) {
            where.append(String.format(Locale.US, " OR (%s LIKE '%%%s%%')",
                    Telephony.Sms.BODY, hash));
        }
        // Note: For backwards compatibility, we allow packages with
        // targetSdk < CINNAMON_BUN to read generic OTP messages.
        if (android.view.flags.Flags.redactOtpAppCompatApi()
                && !CompatChanges.isChangeEnabled(SmsManager.FILTER_GENERIC_OTP,
                callingPackage, userHandle)) {
            where.append(String.format(Locale.US, " OR %s", getContainsGenericOtpSqlFilter()));
        }
        // Note: For backwards compatibility, we allow read access to verified owners of
        // the domain found in Web OTPs.
        if (android.view.flags.Flags.redactWebOtpSmsApi()) {
            where.append(getVerifiedDomainSql(context, callingPackage));
        }
        where.append(")");
        return where.toString();
    }

    private static String getContainsOtpSqlFilter(int containsOtpType) {
        if (android.view.flags.Flags.redactOtpAppCompatApi()) {
            return String.format(Locale.US, "((%s & %d) = %d)",
                    Telephony.Sms.CONTAINS_OTP, Telephony.Sms.OTP_TYPE_MASK, containsOtpType);
        }
        return String.format(Locale.US, "(%s = %d)", Telephony.Sms.CONTAINS_OTP, containsOtpType);
    }

    private static String getOtpPendingSqlFilter() {
        return getContainsOtpSqlFilter(Telephony.Sms.OTP_TYPE_PENDING);
    }

    private static String getContainsGenericOtpSqlFilter() {
        // Generic OTP is an OTP that does not follow standards defined by either
        // SMS Hash Retriever standards or Web OTP standards.
        return String.format(Locale.US, "((%s & %s) = %s)",
                Telephony.Sms.CONTAINS_OTP,
                Telephony.Sms.OTP_SUBTYPE_MASK | Telephony.Sms.OTP_TYPE_MASK,
                Telephony.Sms.OTP_SUBTYPE_NONE | Telephony.Sms.OTP_TYPE_CONTAINS_OTP);
    }

    // Returns SQL string to be appended to the where clause of the main query which will match
    // Web OTP rows containing a verified domain owned by `callingPackageName`.
    // Returns an empty string if the package has no verified domains, or if an exception is
    // encountered.
    @SuppressLint("MissingPermission")
    private static String getVerifiedDomainSql(Context context, String callingPackageName) {
        try {
            DomainVerificationManager domainVerificationManager =
                    context.getSystemService(DomainVerificationManager.class);
            DomainVerificationInfo domainVerificationInfo =
                    domainVerificationManager.getDomainVerificationInfo(callingPackageName);
            if (domainVerificationInfo != null
                    && !domainVerificationInfo.getHostToStateMap().isEmpty()) {
                StringBuilder verifiedDomainSql = new StringBuilder();
                for (Map.Entry<String, Integer> hostToVerificationState :
                        domainVerificationInfo.getHostToStateMap().entrySet()) {
                    String domain = hostToVerificationState.getKey();
                    Integer verificationState = hostToVerificationState.getValue();
                    boolean isDomainVerified =
                            verificationState == DomainVerificationInfo.STATE_MODIFIABLE_VERIFIED
                                    || verificationState == DomainVerificationInfo.STATE_SUCCESS;
                    // To avoid performance issue and potential abuse, we currently set a hard-limit
                    // to the number of verified domains allowed.
                    if (isDomainVerified && verifiedDomainSql.length()
                            < MAX_ALLOWED_VERIFIED_DOMAINS) {
                        // Match a "@<domain> #" substring.
                        String containsDomainSql = String.format(Locale.US,
                                "(%s LIKE '%%@%s #%%')", Telephony.Sms.BODY, domain);
                        if (verifiedDomainSql.length() != 0) {
                            verifiedDomainSql.append(" OR ");
                        }
                        verifiedDomainSql.append(containsDomainSql);
                    }
                }
                if (verifiedDomainSql.length() != 0) {
                    // Roughly translates to the following query:
                    // "OR ((contains_otp & 0xFFFF) = <bitmask for WEB OTP>"
                    // "AND (body LIKE '%@<domain1> #%' OR body LIKE '%@<domain2> #%' OR ...)"
                    return String.format(Locale.US, " OR ((%s & %s) = %s AND (%s))",
                            Telephony.Sms.CONTAINS_OTP,
                            Telephony.Sms.OTP_SUBTYPE_MASK | Telephony.Sms.OTP_TYPE_MASK,
                            android.view.flags.Flags.redactOtpAppCompatApi()
                                    ? Telephony.Sms.OTP_SUBTYPE_WEB_OTP
                                    | Telephony.Sms.OTP_TYPE_CONTAINS_OTP
                                    : Telephony.Sms.OTP_TYPE_CONTAINS_OTP,
                            verifiedDomainSql.toString());
                }
                return "";
            }
            return "";
        } catch (Exception e) {
            // In case of exceptions, fail gracefully.
            return "";
        }
    }
}
