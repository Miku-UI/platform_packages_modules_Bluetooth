/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.bluetooth.hfp;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.Manifest.permission.MODIFY_PHONE_STATE;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;

import java.util.List;

/**
 * A proxy class that facilitates testing of the BluetoothInCallService class.
 *
 * <p>This is necessary due to the "final" attribute of the BluetoothHeadset class. In order to test
 * the correct functioning of the BluetoothInCallService class, the final class must be put into a
 * container that can be mocked correctly.
 */
public class BluetoothHeadsetProxy {

    private BluetoothHeadset mBluetoothHeadset;

    public BluetoothHeadsetProxy(BluetoothHeadset headset) {
        mBluetoothHeadset = headset;
    }

    public void closeBluetoothHeadsetProxy(BluetoothAdapter adapter) {
        adapter.closeProfileProxy(BluetoothProfile.HEADSET, mBluetoothHeadset);
    }

    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, MODIFY_PHONE_STATE})
    public void clccResponse(
            int index, int direction, int status, int mode, boolean mpty, String number, int type) {
        mBluetoothHeadset.clccResponse(index, direction, status, mode, mpty, number, type);
    }

    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, MODIFY_PHONE_STATE})
    public void phoneStateChanged(
            int numActive, int numHeld, int callState, String number, int type, String name) {
        mBluetoothHeadset.phoneStateChanged(numActive, numHeld, callState, number, type, name);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    public List<BluetoothDevice> getConnectedDevices() {
        return mBluetoothHeadset.getConnectedDevices();
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    public int getConnectionState(BluetoothDevice device) {
        return mBluetoothHeadset.getConnectionState(device);
    }

    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public int getAudioState(BluetoothDevice device) {
        return mBluetoothHeadset.getAudioState(device);
    }

    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public int connectAudio() {
        return mBluetoothHeadset.connectAudio();
    }

    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, MODIFY_PHONE_STATE})
    public boolean setActiveDevice(BluetoothDevice device) {
        return mBluetoothHeadset.setActiveDevice(device);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    public BluetoothDevice getActiveDevice() {
        return mBluetoothHeadset.getActiveDevice();
    }

    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public int disconnectAudio() {
        return mBluetoothHeadset.disconnectAudio();
    }

    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public boolean isInbandRingingEnabled() {
        return mBluetoothHeadset.isInbandRingingEnabled();
    }
}
