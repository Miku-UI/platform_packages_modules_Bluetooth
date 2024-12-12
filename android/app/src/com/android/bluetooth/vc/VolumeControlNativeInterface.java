/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

public class VolumeControlNativeInterface {
    private static final String TAG = "VolumeControlNativeInterface";
    private BluetoothAdapter mAdapter;

    @GuardedBy("INSTANCE_LOCK")
    private static VolumeControlNativeInterface sInstance;

    private static final Object INSTANCE_LOCK = new Object();

    private VolumeControlNativeInterface() {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mAdapter == null) {
            Log.wtf(TAG, "No Bluetooth Adapter Available");
        }
    }

    /** Get singleton instance. */
    public static VolumeControlNativeInterface getInstance() {
        synchronized (INSTANCE_LOCK) {
            if (sInstance == null) {
                sInstance = new VolumeControlNativeInterface();
            }
            return sInstance;
        }
    }

    /** Set singleton instance. */
    @VisibleForTesting
    public static void setInstance(VolumeControlNativeInterface instance) {
        synchronized (INSTANCE_LOCK) {
            sInstance = instance;
        }
    }

    /**
     * Initializes the native interface.
     *
     * <p>priorities to configure.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void init() {
        initNative();
    }

    /** Cleanup the native interface. */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void cleanup() {
        cleanupNative();
    }

    /**
     * Initiates VolumeControl connection to a remote device.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean connectVolumeControl(BluetoothDevice device) {
        return connectVolumeControlNative(getByteAddress(device));
    }

    /**
     * Disconnects VolumeControl from a remote device.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean disconnectVolumeControl(BluetoothDevice device) {
        return disconnectVolumeControlNative(getByteAddress(device));
    }

    /** Sets the VolumeControl volume */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void setVolume(BluetoothDevice device, int volume) {
        setVolumeNative(getByteAddress(device), volume);
    }

    /** Sets the VolumeControl volume for the group */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void setGroupVolume(int groupId, int volume) {
        setGroupVolumeNative(groupId, volume);
    }

    /** Mute the VolumeControl volume */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void mute(BluetoothDevice device) {
        muteNative(getByteAddress(device));
    }

    /** Mute the VolumeControl volume in the group */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void muteGroup(int groupId) {
        muteGroupNative(groupId);
    }

    /** Unmute the VolumeControl volume */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void unmute(BluetoothDevice device) {
        unmuteNative(getByteAddress(device));
    }

    /** Unmute the VolumeControl volume group */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void unmuteGroup(int groupId) {
        unmuteGroupNative(groupId);
    }

    /**
     * Gets external audio output volume offset from a remote device.
     *
     * @param device the remote device
     * @param externalOutputId external audio output id
     * @return true on success, otherwise false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean getExtAudioOutVolumeOffset(BluetoothDevice device, int externalOutputId) {
        return getExtAudioOutVolumeOffsetNative(getByteAddress(device), externalOutputId);
    }

    /**
     * Sets external audio output volume offset to a remote device.
     *
     * @param device the remote device
     * @param externalOutputId external audio output id
     * @param offset requested offset
     * @return true on success, otherwise false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean setExtAudioOutVolumeOffset(
            BluetoothDevice device, int externalOutputId, int offset) {
        if (Utils.isPtsTestMode()) {
            setVolumeNative(getByteAddress(device), offset);
            return true;
        }
        return setExtAudioOutVolumeOffsetNative(getByteAddress(device), externalOutputId, offset);
    }

    /**
     * Gets external audio output location from a remote device.
     *
     * @param device the remote device
     * @param externalOutputId external audio output id
     * @return true on success, otherwise false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean getExtAudioOutLocation(BluetoothDevice device, int externalOutputId) {
        return getExtAudioOutLocationNative(getByteAddress(device), externalOutputId);
    }

    /**
     * Sets external audio volume offset to a remote device.
     *
     * @param device the remote device
     * @param externalOutputId external audio output id
     * @param location requested location
     * @return true on success, otherwise false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean setExtAudioOutLocation(
            BluetoothDevice device, int externalOutputId, int location) {
        return setExtAudioOutLocationNative(getByteAddress(device), externalOutputId, location);
    }

    /**
     * Gets external audio output description from a remote device.
     *
     * @param device the remote device
     * @param externalOutputId external audio output id
     * @return true on success, otherwise false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean getExtAudioOutDescription(BluetoothDevice device, int externalOutputId) {
        return getExtAudioOutDescriptionNative(getByteAddress(device), externalOutputId);
    }

    /**
     * Sets external audio volume description to a remote device.
     *
     * @param device the remote device
     * @param externalOutputId external audio output id
     * @param descr requested description
     * @return true on success, otherwise false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean setExtAudioOutDescription(
            BluetoothDevice device, int externalOutputId, String descr) {
        return setExtAudioOutDescriptionNative(getByteAddress(device), externalOutputId, descr);
    }

    /**
     * Gets external audio input state from a remote device.
     *
     * <p>note: state describes configuration
     *
     * @param device the remote device
     * @param externalInputId external audio input id
     * @return true on success, otherwise false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean getExtAudioInState(BluetoothDevice device, int externalInputId) {
        return getExtAudioInStateNative(getByteAddress(device), externalInputId);
    }

    /**
     * Gets external audio input status from a remote device.
     *
     * <p>note: status says if input is active or not.
     *
     * @param device the remote device
     * @param externalInputId external audio input id
     * @return true on success, otherwise false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean getExtAudioInStatus(BluetoothDevice device, int externalInputId) {
        return getExtAudioInStatusNative(getByteAddress(device), externalInputId);
    }

    /**
     * Gets external audio input type from a remote device.
     *
     * @param device the remote device
     * @param externalInputId external audio input id
     * @return true on success, otherwise false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean getExtAudioInType(BluetoothDevice device, int externalInputId) {
        return getExtAudioInTypeNative(getByteAddress(device), externalInputId);
    }

    /**
     * Gets external audio input gain properties from a remote device.
     *
     * @param device the remote device
     * @param externalInputId external audio input id
     * @return true on success, otherwise false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean getExtAudioInGainProps(BluetoothDevice device, int externalInputId) {
        return getExtAudioInGainPropsNative(getByteAddress(device), externalInputId);
    }

    /**
     * Gets external audio input description from a remote device.
     *
     * @param device the remote device
     * @param externalInputId external audio input id
     * @return true on success, otherwise false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean getExtAudioInDescription(BluetoothDevice device, int externalInputId) {
        return getExtAudioInDescriptionNative(getByteAddress(device), externalInputId);
    }

    /**
     * Sets external audio input description from a remote device.
     *
     * @param device the remote device
     * @param externalInputId external audio input id
     * @param descr requested description
     * @return true on success, otherwise false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean setExtAudioInDescription(
            BluetoothDevice device, int externalInputId, String descr) {
        return setExtAudioInDescriptionNative(getByteAddress(device), externalInputId, descr);
    }

    /**
     * Sets external audio input gain value to a remote device.
     *
     * @param device the remote device
     * @param externalInputId external audio input id
     * @param value requested gain value
     * @return true on success, otherwise false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean setExtAudioInGainValue(BluetoothDevice device, int externalInputId, int value) {
        return setExtAudioInGainValueNative(getByteAddress(device), externalInputId, value);
    }

    /**
     * Sets external audio input gain mode to a remote device.
     *
     * @param device the remote device
     * @param externalInputId external audio input id
     * @param autoMode true when auto mode requested
     * @return true on success, otherwise false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean setExtAudioInGainMode(
            BluetoothDevice device, int externalInputId, boolean autoMode) {
        return setExtAudioInGainModeNative(getByteAddress(device), externalInputId, autoMode);
    }

    /**
     * Sets external audio input gain mode to a remote device.
     *
     * @param device the remote device
     * @param externalInputId external audio input id
     * @param mute true when mute requested
     * @return true on success, otherwise false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean setExtAudioInGainMute(
            BluetoothDevice device, int externalInputId, boolean mute) {
        return setExtAudioInGainMuteNative(getByteAddress(device), externalInputId, mute);
    }

    private BluetoothDevice getDevice(byte[] address) {
        return mAdapter.getRemoteDevice(address);
    }

    private byte[] getByteAddress(BluetoothDevice device) {
        if (device == null) {
            return Utils.getBytesFromAddress("00:00:00:00:00:00");
        }
        return Utils.getBytesFromAddress(device.getAddress());
    }

    private void sendMessageToService(VolumeControlStackEvent event) {
        VolumeControlService service = VolumeControlService.getVolumeControlService();
        if (service != null) {
            service.messageFromNative(event);
        } else {
            Log.e(TAG, "Event ignored, service not available: " + event);
        }
    }

    // Callbacks from the native stack back into the Java framework.
    // All callbacks are routed via the Service which will disambiguate which
    // state machine the message should be routed to.
    @VisibleForTesting
    void onConnectionStateChanged(int state, byte[] address) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.device = getDevice(address);
        event.valueInt1 = state;

        Log.d(TAG, "onConnectionStateChanged: " + event);
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onVolumeStateChanged(
            int volume, boolean mute, int flags, byte[] address, boolean isAutonomous) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_VOLUME_STATE_CHANGED);
        event.device = getDevice(address);
        event.valueInt1 = -1;
        event.valueInt2 = volume;
        event.valueInt3 = flags;
        event.valueBool1 = mute;
        event.valueBool2 = isAutonomous;

        Log.d(TAG, "onVolumeStateChanged: " + event);
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onGroupVolumeStateChanged(int volume, boolean mute, int groupId, boolean isAutonomous) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_VOLUME_STATE_CHANGED);
        event.device = null;
        event.valueInt1 = groupId;
        event.valueInt2 = volume;
        event.valueBool1 = mute;
        event.valueBool2 = isAutonomous;

        Log.d(TAG, "onGroupVolumeStateChanged: " + event);
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onDeviceAvailable(int numOfExternalOutputs, int numOfExternalInputs, byte[] address) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(VolumeControlStackEvent.EVENT_TYPE_DEVICE_AVAILABLE);
        event.device = getDevice(address);
        event.valueInt1 = numOfExternalOutputs;
        event.valueInt2 = numOfExternalInputs;

        Log.d(TAG, "onDeviceAvailable: " + event);
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onExtAudioOutVolumeOffsetChanged(int externalOutputId, int offset, byte[] address) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_VOL_OFFSET_CHANGED);
        event.device = getDevice(address);
        event.valueInt1 = externalOutputId;
        event.valueInt2 = offset;

        Log.d(TAG, "onExtAudioOutVolumeOffsetChanged: " + event);
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onExtAudioOutLocationChanged(int externalOutputId, int location, byte[] address) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_LOCATION_CHANGED);
        event.device = getDevice(address);
        event.valueInt1 = externalOutputId;
        event.valueInt2 = location;

        Log.d(TAG, "onExtAudioOutLocationChanged: " + event);
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onExtAudioOutDescriptionChanged(int externalOutputId, String descr, byte[] address) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_DESCRIPTION_CHANGED);
        event.device = getDevice(address);
        event.valueInt1 = externalOutputId;
        event.valueString1 = descr;

        Log.d(TAG, "onExtAudioOutLocationChanged: " + event);
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onExtAudioInStateChanged(
            int externalInputId, int gainValue, int gainMode, boolean mute, byte[] address) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_IN_STATE_CHANGED);
        event.device = getDevice(address);
        event.valueInt1 = externalInputId;
        event.valueInt2 = gainValue;
        event.valueInt3 = gainMode;
        event.valueBool1 = mute;

        Log.d(TAG, "onExtAudioInStateChanged: " + event);
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onExtAudioInStatusChanged(int externalInputId, int status, byte[] address) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_IN_STATUS_CHANGED);
        event.device = getDevice(address);
        event.valueInt1 = externalInputId;
        event.valueInt2 = status;

        Log.d(TAG, "onExtAudioInStatusChanged: " + event);
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onExtAudioInTypeChanged(int externalInputId, int type, byte[] address) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_IN_TYPE_CHANGED);
        event.device = getDevice(address);
        event.valueInt1 = externalInputId;
        event.valueInt2 = type;

        Log.d(TAG, "onExtAudioInTypeChanged: " + event);
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onExtAudioInDescriptionChanged(int externalInputId, String descr, byte[] address) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_IN_DESCR_CHANGED);
        event.device = getDevice(address);
        event.valueInt1 = externalInputId;
        event.valueString1 = descr;

        Log.d(TAG, "onExtAudioInDescriptionChanged: " + event);
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onExtAudioInGainPropsChanged(
            int externalInputId, int unit, int min, int max, byte[] address) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_IN_GAIN_PROPS_CHANGED);
        event.device = getDevice(address);
        event.valueInt1 = externalInputId;
        event.valueInt2 = unit;
        event.valueInt3 = min;
        event.valueInt4 = max;

        Log.d(TAG, "onExtAudioInGainPropsChanged: " + event);
        sendMessageToService(event);
    }

    // Native methods that call into the JNI interface
    private native void initNative();

    private native void cleanupNative();

    private native boolean connectVolumeControlNative(byte[] address);

    private native boolean disconnectVolumeControlNative(byte[] address);

    private native void setVolumeNative(byte[] address, int volume);

    private native void setGroupVolumeNative(int groupId, int volume);

    private native void muteNative(byte[] address);

    private native void muteGroupNative(int groupId);

    private native void unmuteNative(byte[] address);

    private native void unmuteGroupNative(int groupId);

    private native boolean getExtAudioOutVolumeOffsetNative(byte[] address, int externalOutputId);

    private native boolean setExtAudioOutVolumeOffsetNative(
            byte[] address, int externalOutputId, int offset);

    private native boolean getExtAudioOutLocationNative(byte[] address, int externalOutputId);

    private native boolean setExtAudioOutLocationNative(
            byte[] address, int externalOutputId, int location);

    private native boolean getExtAudioOutDescriptionNative(byte[] address, int externalOutputId);

    private native boolean setExtAudioOutDescriptionNative(
            byte[] address, int externalOutputId, String descr);

    /* Native methods for external audio inputs */
    private native boolean getExtAudioInStateNative(byte[] address, int externalInputId);

    private native boolean getExtAudioInStatusNative(byte[] address, int externalInputId);

    private native boolean getExtAudioInTypeNative(byte[] address, int externalInputId);

    private native boolean getExtAudioInGainPropsNative(byte[] address, int externalInputId);

    private native boolean getExtAudioInDescriptionNative(byte[] address, int externalInputId);

    private native boolean setExtAudioInDescriptionNative(
            byte[] address, int externalInputId, String descr);

    private native boolean setExtAudioInGainValueNative(
            byte[] address, int externalInputId, int gainValue);

    private native boolean setExtAudioInGainModeNative(
            byte[] address, int externalInputId, boolean modeAuto);

    private native boolean setExtAudioInGainMuteNative(
            byte[] address, int externalInputId, boolean mute);
}
