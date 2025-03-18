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

public class PbapApplicationParameters {
    private static final String TAG = PbapApplicationParameters.class.getSimpleName();

    // Max size for a phonebook, which determines the max size of a batch and an offset. This comes
    // from the fact that each field is 2 bytes -> 16 bits -> [0, 65535] (i.e, inclusive)
    public static final int MAX_PHONEBOOK_SIZE = 65535;

    // Application Parameter Header keys (PBAP 1.2.3, Section 6.2.1)
    public static final byte OAP_ORDER = 0x01;
    public static final byte OAP_SEARCH_VALUE = 0x02;
    public static final byte OAP_SEARCH_PROPERTY = 0x03;
    public static final byte OAP_MAX_LIST_COUNT = 0x04;
    public static final byte OAP_LIST_START_OFFSET = 0x05;
    public static final byte OAP_PROPERTY_SELECTOR = 0x06;
    public static final byte OAP_FORMAT = 0x07;
    public static final byte OAP_PHONEBOOK_SIZE = 0x08;
    public static final byte OAP_NEW_MISSED_CALLS = 0x09;
    public static final byte OAP_PRIMARY_FOLDER_VERSION = 0x0A;
    public static final byte OAP_SECONDARY_FOLDER_VERSION = 0x0B;
    public static final byte OAP_VCARD_SELECTOR = 0x0C;
    public static final byte OAP_DATABASE_IDENTIFIER = 0x0D;
    public static final byte OAP_VCARD_SELECTOR_OPERATOR = 0x0E;
    public static final byte OAP_RESET_NEW_MISSED_CALLS = 0x0F;
    public static final byte OAP_PBAP_SUPPORTED_FEATURES = 0x10;

    // Property Selector "filter" constants, PBAP 1.2.3, section 5.1.4.1
    public static final long PROPERTIES_ALL = 0;
    public static final long PROPERTY_VERSION = 1 << 0;
    public static final long PROPERTY_FN = 1 << 1;
    public static final long PROPERTY_N = 1 << 2;
    public static final long PROPERTY_PHOTO = 1 << 3;
    public static final long PROPERTY_ADR = 1 << 5;
    public static final long PROPERTY_TEL = 1 << 7;
    public static final long PROPERTY_EMAIL = 1 << 8;
    public static final long PROPERTY_NICKNAME = 1 << 23;

    // MaxListCount field, PBAP 1.2.3, Section 5.3.4.4: "0" signifies to the PSE that the PCE is
    // requesting the number of indexes in the phonebook of interest that are actually used
    // (i.e. indexes that correspond to non-NULL entries). Using this causes other parameters to be
    // ignored. Only metadata will be returned, like the size.
    public static final int RETURN_SIZE_ONLY = 0;

    private final long mProperties; // 64-bit property selector bit field, 8 bytes
    private final byte mFormat; // Vcard format, 0 or 1, 1 byte, From PbapVcardList object
    private final int mMaxListCount; // The total number of items to fetch, for batching, 2 bytes
    private final int mListStartOffset; // The item index to start at, for batching, 2 bytes

    PbapApplicationParameters(long properties, byte format, int maxListCount, int listStartOffset) {
        if (maxListCount < 0 || maxListCount > MAX_PHONEBOOK_SIZE) {
            throw new IllegalArgumentException("maxListCount should be [0, 65535]");
        }
        if (listStartOffset < 0 || listStartOffset > MAX_PHONEBOOK_SIZE) {
            throw new IllegalArgumentException("listStartOffset should be [0, 65535]");
        }
        if (format != PbapPhonebook.FORMAT_VCARD_21 && format != PbapPhonebook.FORMAT_VCARD_30) {
            throw new IllegalArgumentException("VCard format must be 2.1 or 3.0");
        }

        mProperties = properties;
        mFormat = format;
        mMaxListCount = maxListCount;
        mListStartOffset = listStartOffset;
    }

    public long getPropertySelectorMask() {
        return mProperties;
    }

    public byte getVcardFormat() {
        return mFormat;
    }

    public int getMaxListCount() {
        return mMaxListCount;
    }

    public int getListStartOffset() {
        return mListStartOffset;
    }

    public static String propertiesToString(long properties) {
        if (properties == PROPERTIES_ALL) {
            return "PROPERTIES_ALL";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[");
        if ((properties & PROPERTY_VERSION) != 0) {
            sb.append("PROPERTY_VERSION ");
        }
        if ((properties & PROPERTY_FN) != 0) {
            sb.append("PROPERTY_FN ");
        }
        if ((properties & PROPERTY_N) != 0) {
            sb.append("PROPERTY_N ");
        }
        if ((properties & PROPERTY_PHOTO) != 0) {
            sb.append("PROPERTY_PHOTO ");
        }
        if ((properties & PROPERTY_ADR) != 0) {
            sb.append("PROPERTY_ADR ");
        }
        if ((properties & PROPERTY_TEL) != 0) {
            sb.append("PROPERTY_TEL ");
        }
        if ((properties & PROPERTY_EMAIL) != 0) {
            sb.append("PROPERTY_EMAIL ");
        }
        if ((properties & PROPERTY_NICKNAME) != 0) {
            sb.append("PROPERTY_NICKNAME ");
        }
        sb.deleteCharAt(sb.length() - 1);
        sb.append("]");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "<"
                + TAG
                + (" properties=" + propertiesToString(getPropertySelectorMask()))
                + (" format=" + getVcardFormat())
                + (" maxListCount=" + getMaxListCount())
                + (" listStartOffset=" + getListStartOffset())
                + ">";
    }
}
