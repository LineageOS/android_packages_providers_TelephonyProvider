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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContentValues;
import android.os.Process;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Telephony;
import android.provider.Telephony.ReadRestriction;
import android.provider.Telephony.ReadRestriction.ReadRestrictionValues;
import androidx.test.core.app.ApplicationProvider;

import com.android.internal.telephony.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

public class ReadRestrictionTest {

    private static final String TEST_PACKAGE_NAME = "com.android.test.package";

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private Context mContext = ApplicationProvider.getApplicationContext();

    @Test
    @EnableFlags({
        Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES,
        Flags.FLAG_MESSAGE_PROMOTION
    })
    public void setReadRestrictionValueOnInsert_restrictedSet_verifyRestrictedValueIsReplaced() {
        ContentValues values = createExampleContentValues();
        values.put(ReadRestriction.RESTRICTED, true);

        ReadRestriction.setReadRestrictionValueOnInsert(mContext, values, TEST_PACKAGE_NAME,
            /* canWriteRestrictedMessages= */ true);

        assertThat(values.getAsInteger(ReadRestriction.READ_RESTRICTION_COLUMN_NAME))
            .isEqualTo(ReadRestrictionValues.READ_RESTRICTION_RESTRICTED);
        assertThat(values.containsKey(ReadRestriction.RESTRICTED)).isFalse();
    }

    @Test
    @DisableFlags({
        Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES,
        Flags.FLAG_MESSAGE_PROMOTION
    })
    public void setReadRestrictionValueOnInsert_restrictedSet_flagDisabled_noop() {
        ContentValues values = createExampleContentValues();
        values.put(ReadRestriction.RESTRICTED, true);

        ReadRestriction.setReadRestrictionValueOnInsert(mContext, values, TEST_PACKAGE_NAME,
            /* canWriteRestrictedMessages= */ true);

        assertThat(values.getAsBoolean(ReadRestriction.RESTRICTED)).isTrue();
        assertThat(values.containsKey(ReadRestriction.READ_RESTRICTION_COLUMN_NAME)).isFalse();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES,
        Flags.FLAG_MESSAGE_PROMOTION
    })
    public void setReadRestrictionValueOnInsert_restrictedNotSet_readRestrictionIsRestricted() {
        ContentValues values = createExampleContentValues();

        ReadRestriction.setReadRestrictionValueOnInsert(mContext, values, TEST_PACKAGE_NAME,
            /* canWriteRestrictedMessages= */ true);

        assertThat(values.getAsInteger(ReadRestriction.READ_RESTRICTION_COLUMN_NAME))
            .isEqualTo(ReadRestrictionValues.READ_RESTRICTION_RESTRICTED);
        assertThat(values.containsKey(ReadRestriction.RESTRICTED)).isFalse();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES,
        Flags.FLAG_MESSAGE_PROMOTION
    })
    public void setReadRestrictionValueOnInsert_cannotWriteRestrictedMessages_throwsException() {
        ContentValues values = createExampleContentValues();
        values.put(ReadRestriction.RESTRICTED, true);

        assertThrows(UnsupportedOperationException.class, () -> {
            ReadRestriction.setReadRestrictionValueOnInsert(mContext, values, TEST_PACKAGE_NAME,
                /* canWriteRestrictedMessages= */ false);
        });

        values.put(ReadRestriction.RESTRICTED, false);

        assertThrows(UnsupportedOperationException.class, () -> {
            ReadRestriction.setReadRestrictionValueOnInsert(mContext, values, TEST_PACKAGE_NAME,
                /* canWriteRestrictedMessages= */ false);
        });
    }

    @Test
    @EnableFlags({
        Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES,
        Flags.FLAG_MESSAGE_PROMOTION
    })
    public void setReadRestrictionValueOnUpdate_restrictedFalse_setsReadRestrictionToZero() {
        ContentValues values = createExampleContentValues();
        values.put(ReadRestriction.RESTRICTED, false);

        ReadRestriction.setReadRestrictionValueOnUpdate(values,
            /* canWriteRestrictedMessages= */ true);

        assertThat(values.getAsInteger(ReadRestriction.READ_RESTRICTION_COLUMN_NAME)).isEqualTo(0);
        assertThat(values.containsKey(ReadRestriction.RESTRICTED)).isFalse();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES,
        Flags.FLAG_MESSAGE_PROMOTION
    })
    public void setReadRestrictionValueOnUpdate_cannotWriteRestrictedMessages_throwsException() {
        ContentValues values = createExampleContentValues();
        values.put(ReadRestriction.RESTRICTED, false);

        assertThrows(UnsupportedOperationException.class, () ->
            ReadRestriction.setReadRestrictionValueOnUpdate(values,
                /* canWriteRestrictedMessages= */ false));
    }

    @Test
    @EnableFlags({
        Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES,
        Flags.FLAG_MESSAGE_PROMOTION
    })
    public void setReadRestrictionValueOnUpdate_restrictedTrue_throwsException() {
        ContentValues values = createExampleContentValues();
        values.put(ReadRestriction.RESTRICTED, true);

        assertThrows(UnsupportedOperationException.class, () ->
            ReadRestriction.setReadRestrictionValueOnUpdate(values,
                /* canWriteRestrictedMessages= */ true));
    }

    @Test
    @DisableFlags({
        Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES,
        Flags.FLAG_MESSAGE_PROMOTION
    })
    public void setReadRestrictionValueOnUpdate_flagDisabled_noop() {
        ContentValues values = createExampleContentValues();
        values.put(ReadRestriction.RESTRICTED, true);

        ReadRestriction.setReadRestrictionValueOnUpdate(values,
            /* canWriteRestrictedMessages= */ true);

        assertThat(values.getAsBoolean(ReadRestriction.RESTRICTED)).isTrue();
        assertThat(values.containsKey(ReadRestriction.READ_RESTRICTION_COLUMN_NAME)).isFalse();
    }

    private ContentValues createExampleContentValues() {
        ContentValues values = new ContentValues();
        values.put(Telephony.Sms.ADDRESS, "+1234567890");
        values.put(Telephony.Sms.BODY, "test message body");
        values.put(Telephony.Sms.DATE, System.currentTimeMillis());
        return values;
    }
}
