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

package com.android.bluetooth.vc;

import android.bluetooth.BluetoothVolumeControl;
import android.util.Log;

import com.android.bluetooth.btservice.ProfileService;

import java.util.HashMap;
import java.util.Map;

class VolumeControlInputDescriptor {
    private static final String TAG = "VolumeControlInputDescriptor";
    final Map<Integer, Descriptor> mVolumeInputs = new HashMap<>();

    public static final int AUDIO_INPUT_TYPE_UNSPECIFIED = 0x00;
    public static final int AUDIO_INPUT_TYPE_BLUETOOTH = 0x01;
    public static final int AUDIO_INPUT_TYPE_MICROPHONE = 0x02;
    public static final int AUDIO_INPUT_TYPE_ANALOG = 0x03;
    public static final int AUDIO_INPUT_TYPE_DIGITAL = 0x04;
    public static final int AUDIO_INPUT_TYPE_RADIO = 0x05;
    public static final int AUDIO_INPUT_TYPE_STREAMING = 0x06;
    public static final int AUDIO_INPUT_TYPE_AMBIENT = 0x07;

    private static class Descriptor {
        /* True when input is active, false otherwise */
        boolean mIsActive = false;

        /* Defined as in Assigned Numbers in the BluetoothVolumeControl.AUDIO_INPUT_TYPE_ */
        int mType = AUDIO_INPUT_TYPE_UNSPECIFIED;

        int mGainValue = 0;

        /* As per AICS 1.0
         * 3.1.3. Gain_Mode field
         * The Gain_Mode field shall be set to a value that reflects whether gain modes are
         *  manual or automatic.
         *
         * If the Gain_Mode field value is Manual Only, the server allows only manual gain.
         * If the Gain_Mode field is Automatic Only, the server allows only automatic gain.
         *
         * For all other Gain_Mode field values, the server allows switchable
         * automatic/manual gain.
         */
        int mGainMode = 0;

        boolean mIsMute = false;

        /* As per AICS 1.0
         * The Gain_Setting (mGainValue) field is a signed value for which a single increment
         * or decrement should result in a corresponding increase or decrease of the input
         * amplitude by the value of the Gain_Setting_Units (mGainSettingsUnits)
         * field of the Gain Setting Properties characteristic value.
         */
        int mGainSettingsUnits = 0;

        int mGainSettingsMaxSetting = 0;
        int mGainSettingsMinSetting = 0;

        String mDescription = "";
    }

    int size() {
        return mVolumeInputs.size();
    }

    void add(int id) {
        if (!mVolumeInputs.containsKey(id)) {
            mVolumeInputs.put(id, new Descriptor());
        }
    }

    boolean setActive(int id, boolean active) {
        Descriptor desc = mVolumeInputs.get(id);
        if (desc == null) {
            Log.e(TAG, "setActive, Id " + id + " is unknown");
            return false;
        }
        desc.mIsActive = active;
        return true;
    }

    boolean isActive(int id) {
        Descriptor desc = mVolumeInputs.get(id);
        if (desc == null) {
            Log.e(TAG, "isActive, Id " + id + " is unknown");
            return false;
        }
        return desc.mIsActive;
    }

    boolean setDescription(int id, String description) {
        Descriptor desc = mVolumeInputs.get(id);
        if (desc == null) {
            Log.e(TAG, "setDescription, Id " + id + " is unknown");
            return false;
        }
        desc.mDescription = description;
        return true;
    }

    String getDescription(int id) {
        Descriptor desc = mVolumeInputs.get(id);
        if (desc == null) {
            Log.e(TAG, "getDescription, Id " + id + " is unknown");
            return null;
        }
        return desc.mDescription;
    }

    boolean setType(int id, int type) {
        Descriptor desc = mVolumeInputs.get(id);
        if (desc == null) {
            Log.e(TAG, "setType, Id " + id + " is unknown");
            return false;
        }

        if (type > AUDIO_INPUT_TYPE_AMBIENT) {
            Log.e(TAG, "setType, Type " + type + "for id " + id + " is invalid");
            return false;
        }

        desc.mType = type;
        return true;
    }

    int getType(int id) {
        Descriptor desc = mVolumeInputs.get(id);
        if (desc == null) {
            Log.e(TAG, "getType, Id " + id + " is unknown");
            return AUDIO_INPUT_TYPE_UNSPECIFIED;
        }
        return desc.mType;
    }

    int getGain(int id) {
        Descriptor desc = mVolumeInputs.get(id);
        if (desc == null) {
            Log.e(TAG, "getGain, Id " + id + " is unknown");
            return 0;
        }
        return desc.mGainValue;
    }

    boolean isMuted(int id) {
        Descriptor desc = mVolumeInputs.get(id);
        if (desc == null) {
            Log.e(TAG, "isMuted, Id " + id + " is unknown");
            return false;
        }
        return desc.mIsMute;
    }

    boolean setPropSettings(int id, int gainUnit, int gainMin, int gainMax) {
        Descriptor desc = mVolumeInputs.get(id);
        if (desc == null) {
            Log.e(TAG, "setPropSettings, Id " + id + " is unknown");
            return false;
        }

        desc.mGainSettingsUnits = gainUnit;
        desc.mGainSettingsMinSetting = gainMin;
        desc.mGainSettingsMaxSetting = gainMax;

        return true;
    }

    boolean setState(int id, int gainValue, int gainMode, boolean mute) {
        Descriptor desc = mVolumeInputs.get(id);
        if (desc == null) {
            Log.e(TAG, "Id " + id + " is unknown");
            return false;
        }

        if (gainValue > desc.mGainSettingsMaxSetting || gainValue < desc.mGainSettingsMinSetting) {
            Log.e(TAG, "Invalid gainValue " + gainValue);
            return false;
        }

        desc.mGainValue = gainValue;
        desc.mGainMode = gainMode;
        desc.mIsMute = mute;

        return true;
    }

    void remove(int id) {
        Log.d(TAG, "remove, id: " + id);
        mVolumeInputs.remove(id);
    }

    void clear() {
        Log.d(TAG, "clear all inputs");
        mVolumeInputs.clear();
    }

    void dump(StringBuilder sb) {
        for (Map.Entry<Integer, Descriptor> entry : mVolumeInputs.entrySet()) {
            Descriptor desc = entry.getValue();
            Integer id = entry.getKey();
            ProfileService.println(sb, "        id: " + id);
            ProfileService.println(sb, "        description: " + desc.mDescription);
            ProfileService.println(sb, "        type: " + desc.mType);
            ProfileService.println(sb, "        isActive: " + desc.mIsActive);
            ProfileService.println(sb, "        gainValue: " + desc.mGainValue);
            ProfileService.println(sb, "        gainMode: " + desc.mGainMode);
            ProfileService.println(sb, "        mute: " + desc.mIsMute);
            ProfileService.println(sb, "        units:" + desc.mGainSettingsUnits);
            ProfileService.println(sb, "        minGain:" + desc.mGainSettingsMinSetting);
            ProfileService.println(sb, "        maxGain:" + desc.mGainSettingsMaxSetting);
        }
    }
}
