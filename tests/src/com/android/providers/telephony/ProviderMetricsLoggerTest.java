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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

@RunWith(AndroidJUnit4.class)
public class ProviderMetricsLoggerTest {

    private Context mContext;

    @Before
    public void setUp() throws Exception {
        mContext = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        when(mContext.getPackageManager()).thenReturn(pm);
        // The logger asks for ActivityManager, which we mock out to return null
        // so it short circuits
        when(mContext.getSystemService(ActivityManager.class)).thenReturn(null);

        // Reset the in-memory maps to clean state before each test
        clearMap("sOperationStats");
        clearMap("sLockContentionStats");
        clearMap("sActiveWriters");
    }

    private void clearMap(String fieldName) throws Exception {
        Field field = ProviderMetricsLogger.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        ConcurrentHashMap<?, ?> map = (ConcurrentHashMap<?, ?>) field.get(null);
        if (map != null) {
            map.clear();
        }
    }

    private void setLastReportTime(long time) throws Exception {
        Field field = ProviderMetricsLogger.class.getDeclaredField("sLastStatsReportTimeMillis");
        field.setAccessible(true);
        field.set(null, time);
    }

    @Test
    public void testLogOperationLatency_multipleCalls_noCrash() {
        // Log multiple times to test aggregation math code
        long startTime = SystemClock.elapsedRealtime();
        ProviderMetricsLogger.logOperationLatency(
                mContext,
                ProviderMetricsLogger.OPERATION_QUERY,
                ProviderMetricsLogger.TARGET_URI_SMS,
                startTime - 10,
                1);
        ProviderMetricsLogger.logOperationLatency(
                mContext,
                ProviderMetricsLogger.OPERATION_QUERY,
                ProviderMetricsLogger.TARGET_URI_SMS,
                startTime - 50,
                2);
    }

    @Test
    public void testLogOperationLatency_triggersFlush() throws Exception {
        // Set the last report time to 25 hours ago so it forces a flush
        long pastTime = SystemClock.elapsedRealtime() - (25 * 60 * 60 * 1000L);
        setLastReportTime(pastTime);
        long startTime = SystemClock.elapsedRealtime();
        ProviderMetricsLogger.logOperationLatency(
                mContext,
                ProviderMetricsLogger.OPERATION_QUERY,
                ProviderMetricsLogger.TARGET_URI_SMS,
                startTime - 10,
                1);

        // At this point, the flush should have occurred
    }

    @Test
    public void testLogDbLockContention_withActiveWriters_noCrash() {
        ProviderMetricsLogger.trackWriteOperationStart(mContext);

        ProviderMetricsLogger.logDbLockContention(
                mContext,
                ProviderMetricsLogger.OPERATION_INSERT,
                ProviderMetricsLogger.TARGET_URI_SMS);

        ProviderMetricsLogger.trackWriteOperationEnd();
    }

    @Test
    public void testLogDbLockContention_triggersFlush() throws Exception {
        // Log a lock contention so it sits in the map
        ProviderMetricsLogger.logDbLockContention(
                mContext,
                ProviderMetricsLogger.OPERATION_UPDATE,
                ProviderMetricsLogger.TARGET_URI_MMS);

        // Force a flush by logging an operation latency with a stale timestamp
        long pastTime = SystemClock.elapsedRealtime() - (25 * 60 * 60 * 1000L);
        setLastReportTime(pastTime);
        long startTime = SystemClock.elapsedRealtime();
        ProviderMetricsLogger.logOperationLatency(
                mContext,
                ProviderMetricsLogger.OPERATION_QUERY,
                ProviderMetricsLogger.TARGET_URI_SMS,
                startTime - 10,
                1);

        // Verify no crash on flush of both stats and lock contention
    }

    @Test
    public void testLogDbLockContention_withoutActiveWriters_noCrash() {
        ProviderMetricsLogger.logDbLockContention(
                mContext,
                ProviderMetricsLogger.OPERATION_QUERY,
                ProviderMetricsLogger.TARGET_URI_MMS);
    }
}
