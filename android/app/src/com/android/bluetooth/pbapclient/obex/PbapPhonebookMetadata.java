/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.bluetooth.pbapclient;

public class PbapPhonebookMetadata {
    private static final String TAG = PbapPhonebookMetadata.class.getSimpleName();

    public static final int INVALID_SIZE = -1;
    public static final String INVALID_DATABASE_IDENTIFIER = null;
    public static final String DEFAULT_DATABASE_IDENTIFIER = "0";
    public static final String INVALID_VERSION_COUNTER = null;

    private final String mPhonebook;
    private int mSize = INVALID_SIZE; // 2 byte number
    private String mDatabaseIdentifier = INVALID_DATABASE_IDENTIFIER; // 16 byte number as string
    private String mPrimaryVersionCounter = INVALID_VERSION_COUNTER; // 16 byte number as string
    private String mSecondaryVersionCounter = INVALID_VERSION_COUNTER; // 16 byte number as string

    PbapPhonebookMetadata(
            String phonebook,
            int size,
            String databaseIdentifier,
            String primaryVersionCounter,
            String secondaryVersionCounter) {
        mPhonebook = phonebook;
        mSize = size;
        mDatabaseIdentifier = databaseIdentifier;
        mPrimaryVersionCounter = primaryVersionCounter;
        mSecondaryVersionCounter = secondaryVersionCounter;
    }

    public String getPhonebook() {
        return mPhonebook;
    }

    public int getSize() {
        return mSize;
    }

    public String getDatabaseIdentifier() {
        return mDatabaseIdentifier;
    }

    public String getPrimaryVersionCounter() {
        return mPrimaryVersionCounter;
    }

    public String getSecondaryVersionCounter() {
        return mSecondaryVersionCounter;
    }

    @Override
    public String toString() {
        return "<"
                + TAG
                + (" phonebook=" + mPhonebook)
                + (" size=" + mSize)
                + (" databaseIdentifier=" + mDatabaseIdentifier)
                + (" primaryVersionCounter=" + mPrimaryVersionCounter)
                + (" secondaryVersionCounter=" + mSecondaryVersionCounter)
                + ">";
    }
}
