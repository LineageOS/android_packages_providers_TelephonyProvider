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

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.telephony.TelephonyStatsLog;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Utility class for logging telephony provider metrics. */
public class ProviderMetricsLogger {
    private static final String TAG = "ProviderMetricsLogger";

    public static final int OPERATION_QUERY =
            TelephonyStatsLog.MESSAGING_DB_OPERATION_STATS__OPERATION__QUERY;
    public static final int OPERATION_INSERT =
            TelephonyStatsLog.MESSAGING_DB_OPERATION_STATS__OPERATION__INSERT;
    public static final int OPERATION_UPDATE =
            TelephonyStatsLog.MESSAGING_DB_OPERATION_STATS__OPERATION__UPDATE;
    public static final int OPERATION_DELETE =
            TelephonyStatsLog.MESSAGING_DB_OPERATION_STATS__OPERATION__DELETE;
    public static final int OPERATION_OPEN_FILE =
            TelephonyStatsLog.MESSAGING_DB_OPERATION_STATS__OPERATION__OPEN_FILE;

    // Executor to offload stats reporting to a background thread
    private static final Executor sMetricsExecutor = Executors.newSingleThreadExecutor();

    // Maps Thread ID to the Package Name currently performing a write operation
    private static final ConcurrentHashMap<Long, String> sActiveWriters = new ConcurrentHashMap<>();

    /** Tracks start of write operations. */
    public static void trackWriteOperationStart(Context context) {
        try {
            int callingUid = Binder.getCallingUid();
            String packageName = getCallingPackageName(context, callingUid);
            if (packageName == null) packageName = "";
            sActiveWriters.put(Thread.currentThread().getId(), packageName);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "trackWriteOperationStart: " + packageName);
            }
        } catch (Throwable t) {
            Log.wtf(TAG, "Failed to track write operation start", t);
        }
    }

    /** Tracks end of write operations. */
    public static void trackWriteOperationEnd() {
        try {
            sActiveWriters.remove(Thread.currentThread().getId());
            if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "trackWriteOperationEnd");
        } catch (Throwable t) {
            Log.wtf(TAG, "Failed to track write operation end", t);
        }
    }

    private static class LockContentionStats {
        int mCount = 0;
        int mRunningProcessCount = 0;

        synchronized void update(int runningProcessCount) {
            mCount++;
            mRunningProcessCount = Math.max(mRunningProcessCount, runningProcessCount);
        }
    }

    private static final ConcurrentHashMap<String, LockContentionStats> sLockContentionStats =
            new ConcurrentHashMap<>();

    /** Logs database lock contentions. */
    public static void logDbLockContention(Context context, int operation, int targetUri) {
        try {
            int runningProcessCount = ProviderUtil.logRunningTelephonyProviderProcesses(context);
            int callingUid = Binder.getCallingUid();
            String victimPackage = getCallingPackageName(context, callingUid);
            if (victimPackage == null) victimPackage = "";

            // Grab all packages currently performing a write operation
            String blockingPackages = String.join(",", sActiveWriters.values());
            if (blockingPackages.isEmpty()) {
                blockingPackages = "UNKNOWN_OR_BACKGROUND_THREAD";
            }

            // Using "::" as delimiter to avoid collisions with package names containing underscores
            String key = operation + "::" + targetUri + "::" + victimPackage + "::"
                    + blockingPackages;
            LockContentionStats stats = sLockContentionStats.computeIfAbsent(
                    key, k -> new LockContentionStats());
            stats.update(runningProcessCount);

            Log.w(TAG, "logDbLockContention: operation=" + operation + ", targetUri=" + targetUri
                    + ", victim=" + victimPackage + ", blockers=" + blockingPackages);
        } catch (Throwable t) {
            Log.wtf(TAG, "Failed to log DB lock contention", t);
        }
    }

    public static final int TARGET_URI_CONVERSATIONS =
            TelephonyStatsLog.MESSAGING_DB_OPERATION_STATS__TARGET_URI__CONVERSATIONS;
    public static final int TARGET_URI_SMS =
            TelephonyStatsLog.MESSAGING_DB_OPERATION_STATS__TARGET_URI__SMS;
    public static final int TARGET_URI_MMS =
            TelephonyStatsLog.MESSAGING_DB_OPERATION_STATS__TARGET_URI__MMS;
    public static final int TARGET_URI_THREADS =
            TelephonyStatsLog.MESSAGING_DB_OPERATION_STATS__TARGET_URI__THREADS;
    public static final int TARGET_URI_THREAD_ID_RESOLUTION = TelephonyStatsLog
            .MESSAGING_DB_OPERATION_STATS__TARGET_URI__THREAD_ID_RESOLUTION;
    public static final int TARGET_URI_PART =
            TelephonyStatsLog.MESSAGING_DB_OPERATION_STATS__TARGET_URI__PART;

    private static class OperationStats {
        int mMinLatency = Integer.MAX_VALUE;
        int mMaxLatency = 0;
        long mSumLatency = 0;
        int mCount = 0;

        synchronized void update(int latency) {
            if (latency < mMinLatency) mMinLatency = latency;
            if (latency > mMaxLatency) mMaxLatency = latency;
            mSumLatency += latency;
            mCount++;
        }
    }

    /**
     * Stores aggregated daily statistics for each messaging database operation.
     * The key is a composite string formatted as "{operation}::{targetUri}::{callingPackageName}".
     * The value is an {@link OperationStats} object tracking min, max, avg, and count.
     */
    private static final ConcurrentHashMap<String, OperationStats> sOperationStats =
            new ConcurrentHashMap<>();
    private static long sLastStatsReportTimeMillis = SystemClock.elapsedRealtime();
    // 24 hours in milliseconds
    private static final long STATS_REPORT_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L;

    private static void updateAndReportStats(int operation, int targetUri, int latencyMicros,
            String callingPackageName) {
        if (callingPackageName == null) callingPackageName = "";

        String key = operation + "::" + targetUri + "::" + callingPackageName;
        OperationStats stats = sOperationStats.computeIfAbsent(
                key, k -> new OperationStats());
        stats.update(latencyMicros);

        long now = SystemClock.elapsedRealtime();
        if (now - sLastStatsReportTimeMillis >= STATS_REPORT_INTERVAL_MILLIS) {
            // Update timestamp immediately so multiple threads don't queue the reporting task
            sLastStatsReportTimeMillis = now;
            sMetricsExecutor.execute(ProviderMetricsLogger::reportAndClearStats);
        }
    }

    private static synchronized void reportAndClearStats() {
        try {
            Log.d(TAG, "Flushing aggregated messaging DB metrics to StatsLog...");
            for (String key : sOperationStats.keySet()) {
                OperationStats stats = sOperationStats.remove(key);
                if (stats != null && stats.mCount > 0) {
                    String[] parts = key.split("::");
                    int operation = Integer.parseInt(parts[0]);
                    int targetUri = Integer.parseInt(parts[1]);
                    int avgLatency = (int) (stats.mSumLatency / stats.mCount);
                    String callingPackageName = parts.length > 2 ? parts[2] : "";
                    TelephonyStatsLog.write(
                            TelephonyStatsLog.MESSAGING_DB_OPERATION_STATS,
                            operation,
                            targetUri,
                            stats.mMinLatency == Integer.MAX_VALUE ? 0 : stats.mMinLatency,
                            stats.mMaxLatency,
                            avgLatency,
                            stats.mCount,
                            callingPackageName);
                }
            }
            for (String key : sLockContentionStats.keySet()) {
                LockContentionStats stats = sLockContentionStats.remove(key);
                if (stats != null && stats.mCount > 0) {
                    String[] parts = key.split("::");
                    int operation = Integer.parseInt(parts[0]);
                    int targetUri = Integer.parseInt(parts[1]);
                    String victimPackage = parts.length > 2 ? parts[2] : "";
                    String blockingPackages = parts.length > 3 ? parts[3] : "";
                    TelephonyStatsLog.write(
                            TelephonyStatsLog.MESSAGING_DB_LOCK_CONTENTION_STATS,
                            operation,
                            targetUri,
                            victimPackage,
                            blockingPackages,
                            stats.mRunningProcessCount,
                            stats.mCount);
                }
            }
        } catch (Throwable t) {
            Log.wtf(TAG, "Error while reporting metrics to StatsLog", t);
        }
    }

    /** Logs the latency of a database operation. */
    public static void logOperationLatency(Context context, int operation, int targetUri,
            long startTimeMillis, int recordCount) {
        try {
            long latencyMicrosLong = (SystemClock.elapsedRealtime() - startTimeMillis) * 1000L;
            // Guard against overflow if latency is somehow extraordinarily large
            int latencyMicros = (int) Math.min(latencyMicrosLong, Integer.MAX_VALUE);

            int callingUid = Binder.getCallingUid();
            String callingPackageName = getCallingPackageName(context, callingUid);

            updateAndReportStats(operation, targetUri, latencyMicros, callingPackageName);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "logOperationLatency: operation="
                        + operation + ", target=" + targetUri + ", latency=" + latencyMicros);
            }
        } catch (Throwable t) {
            Log.wtf(TAG, "Failed to log operation latency", t);
        }
    }

    private static String getCallingPackageName(Context context, int uid) {
        try {
            if (context == null) return "";
            PackageManager pm = context.getPackageManager();
            if (pm == null) return "";

            String[] packages = pm.getPackagesForUid(uid);
            if (packages != null && packages.length > 0) {
                return packages[0];
            }
        } catch (Throwable t) {
            Log.wtf(TAG, "Failed to get calling package name", t);
        }
        return "";
    }
}
