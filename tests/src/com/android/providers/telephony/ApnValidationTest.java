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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.Telephony.Carriers;
import android.util.Log;
import android.util.Xml;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.util.XmlUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class ApnValidationTest {
    private static final String TAG = "ApnValidationTest";
    private static final String APN_CONF_FILE = "apns-full-conf.xml";

    @Test
    @SmallTest
    public void testLoadApns() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        Context spyContext = spy(new ContextWrapper(context));
        Resources resources = spy(context.getResources());
        doReturn(resources).when(spyContext).getResources();
        // Prevent NPE by returning a safe array for persist_apns_for_plmn
        doReturn(new String[] {})
                .when(resources)
                .getStringArray(com.android.providers.telephony.R.array.persist_apns_for_plmn);

        SQLiteOpenHelper dbHelper = new LocalInMemoryDbHelper(spyContext);
        try (SQLiteDatabase db = dbHelper.getWritableDatabase()) {
            TelephonyProviderTestable provider = new TelephonyProviderTestable();
            TelephonyProvider.DatabaseHelper realDbHelper = provider.new DatabaseHelper(spyContext);

            // 1. Get internal fields/methods from provider using reflection
            Field uniqueFieldsField =
                    TelephonyProvider.class.getDeclaredField("CARRIERS_UNIQUE_FIELDS");
            uniqueFieldsField.setAccessible(true);
            List<String> uniqueFields = (List<String>) uniqueFieldsField.get(null);

            Method getRowMethod =
                    TelephonyProvider.DatabaseHelper.class.getDeclaredMethod(
                            "getRow", XmlPullParser.class, boolean.class);
            getRowMethod.setAccessible(true);

            Method setDefaultValueMethod =
                    TelephonyProvider.class.getDeclaredMethod(
                            "setDefaultValue", ContentValues.class);
            setDefaultValueMethod.setAccessible(true);

            // 2. Scan XML to calculate exact unique count and identify boundary entries
            int expectedUniqueCount;
            String firstCarrier = null, firstApn = null;
            String lastCarrier = null, lastApn = null;

            try (InputStream inputStream =
                    this.getClass().getClassLoader().getResourceAsStream(APN_CONF_FILE)) {
                assertNotNull("Resource " + APN_CONF_FILE + " not found", inputStream);
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(new InputStreamReader(inputStream));
                XmlUtils.beginDocument(parser, "apns");

                Set<Map<String, String>> uniqueKeys = new HashSet<>();
                int totalXmlEntries = 0;

                XmlUtils.nextElement(parser);
                while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                    if ("apn".equals(parser.getName())) {
                        totalXmlEntries++;
                        String carrier = parser.getAttributeValue(null, "carrier");
                        String apn = parser.getAttributeValue(null, "apn");
                        if (firstCarrier == null) {
                            firstCarrier = carrier;
                            firstApn = apn;
                        }
                        lastCarrier = carrier;
                        lastApn = apn;

                        ContentValues row =
                                (ContentValues) getRowMethod.invoke(realDbHelper, parser, false);
                        row = (ContentValues) setDefaultValueMethod.invoke(provider, row);

                        Map<String, String> key = new HashMap<>();
                        for (String field : uniqueFields) {
                            key.put(field, row.getAsString(field));
                        }
                        uniqueKeys.add(key);
                    }
                    XmlUtils.nextElement(parser);
                }
                expectedUniqueCount = uniqueKeys.size();
                Log.d(
                        TAG,
                        "XML entries: "
                                + totalXmlEntries
                                + ", Unique keys (expected rows): "
                                + expectedUniqueCount);
            }

            // 3. Load the APN file using TelephonyProvider logic
            try (InputStream inputStream =
                    this.getClass().getClassLoader().getResourceAsStream(APN_CONF_FILE)) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(new InputStreamReader(inputStream));
                com.android.internal.util.XmlUtils.beginDocument(parser, "apns");

                Method loadApnsMethod =
                        TelephonyProvider.DatabaseHelper.class.getDeclaredMethod(
                                "loadApns",
                                SQLiteDatabase.class,
                                XmlPullParser.class,
                                boolean.class);
                loadApnsMethod.setAccessible(true);
                loadApnsMethod.invoke(realDbHelper, db, parser, false);
            }

            // 4. Verify results
            try (Cursor cursor = db.query("carriers", null, null, null, null, null, null)) {
                assertEquals(
                        "Database row count does not match expected unique keys",
                        expectedUniqueCount,
                        cursor.getCount());
            }

            // 5. Completion check: ensure first and last APNs from XML exist in DB
            verifyEntryExists(db, firstApn, firstCarrier, "First");
            verifyEntryExists(db, lastApn, lastCarrier, "Last");
        }
    }

    private void verifyEntryExists(SQLiteDatabase db, String apn, String name, String label) {
        StringBuilder selection = new StringBuilder();
        List<String> selectionArgs = new ArrayList<>();

        selection.append(Carriers.APN);
        if (apn == null) {
            selection.append(" IS NULL");
        } else {
            selection.append("=?");
            selectionArgs.add(apn);
        }

        selection.append(" AND ").append(Carriers.NAME);
        if (name == null) {
            selection.append(" IS NULL");
        } else {
            selection.append("=?");
            selectionArgs.add(name);
        }

        try (Cursor c =
                db.query(
                        "carriers",
                        null,
                        selection.toString(),
                        selectionArgs.toArray(new String[0]),
                        null,
                        null,
                        null)) {
            assertTrue(
                    label + " APN from XML (" + apn + ") missing from DB",
                    c != null && c.getCount() > 0);
        }
    }

    private static class LocalInMemoryDbHelper extends SQLiteOpenHelper {
        LocalInMemoryDbHelper(Context context) {
            super(context, null, null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(TelephonyProvider.getStringForCarrierTableCreation("carriers"));
            db.execSQL(TelephonyProvider.getStringForSimInfoTableCreation("siminfo"));
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
    }
}
