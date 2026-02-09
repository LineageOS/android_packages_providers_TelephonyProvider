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
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

/**
 * A subclass of HbpcdLookupProvider used for testing on an in-memory database
 */
public class HbpcdLookupProviderTestHelper extends HbpcdLookupProvider {
    private static final String TAG = "HbpcdLookupProviderTestHelper";

    @Override
    public boolean onCreate() {
        Log.d(TAG, "onCreate called: mDbHelper = new InMemoryHbpcdLookupProviderDbHelper()");
        injectDatabaseHelper(new InMemoryHbpcdLookupProviderDbHelper(getContext()));
        return super.onCreate();
    }

    // close mDbHelper database object
    protected void closeDatabase() {
        if (mDbHelper != null) {
            mDbHelper.close();
        }
    }

    /**
     * An in memory DB for HbpcdLookupProviderTestHelper to use
     */
    public static class InMemoryHbpcdLookupProviderDbHelper extends HbpcdLookupDatabaseHelper {

        public InMemoryHbpcdLookupProviderDbHelper(Context context) {
            super(context, null);
            Log.d(TAG, "InMemoryHbpcdLookupProviderDbHelper creating in-memory database");
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            super.onCreate(db);
        }

        @Override
        protected void initDatabase(SQLiteDatabase db) {
            // Do nothing for in-memory DB testing to avoid using context/resources
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Do nothing for in-memory DB testing to avoid using context/resources
        }
    }
}
