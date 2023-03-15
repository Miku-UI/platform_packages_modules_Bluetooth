/*
 * Copyright 2022 The Android Open Source Project
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
package com.android.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.location.LocationManager;
import android.os.ParcelUuid;
import android.os.UserHandle;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.btservice.ProfileService;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * Test for Utils.java
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class UtilsTest {
    @Test
    public void byteArrayToShort() {
        byte[] valueBuf = new byte[] {0x01, 0x02};
        short s = Utils.byteArrayToShort(valueBuf);
        assertThat(s).isEqualTo(0x0201);
    }

    @Test
    public void byteArrayToString() {
        byte[] valueBuf = new byte[] {0x01, 0x02};
        String str = Utils.byteArrayToString(valueBuf);
        assertThat(str).isEqualTo("01 02");
    }

    @Test
    public void uuidsToByteArray() {
        ParcelUuid[] uuids = new ParcelUuid[] {
                new ParcelUuid(new UUID(10, 20)),
                new ParcelUuid(new UUID(30, 40))
        };
        ByteBuffer converter = ByteBuffer.allocate(uuids.length * 16);
        converter.order(ByteOrder.BIG_ENDIAN);
        converter.putLong(0, 10);
        converter.putLong(8, 20);
        converter.putLong(16, 30);
        converter.putLong(24, 40);
        assertThat(Utils.uuidsToByteArray(uuids)).isEqualTo(converter.array());
    }

    @Test
    public void checkServiceAvailable() {
        final String tag = "UTILS_TEST";
        assertThat(Utils.checkServiceAvailable(null, tag)).isFalse();

        ProfileService mockProfile = Mockito.mock(ProfileService.class);
        when(mockProfile.isAvailable()).thenReturn(false);
        assertThat(Utils.checkServiceAvailable(mockProfile, tag)).isFalse();

        when(mockProfile.isAvailable()).thenReturn(true);
        assertThat(Utils.checkServiceAvailable(mockProfile, tag)).isTrue();
    }

    @Test
    public void blockedByLocationOff() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        UserHandle userHandle = new UserHandle(UserHandle.USER_SYSTEM);
        LocationManager locationManager = context.getSystemService(LocationManager.class);
        boolean enableStatus = locationManager.isLocationEnabledForUser(userHandle);
        assertThat(Utils.blockedByLocationOff(context, userHandle)).isEqualTo(!enableStatus);

        locationManager.setLocationEnabledForUser(!enableStatus, userHandle);
        assertThat(Utils.blockedByLocationOff(context, userHandle)).isEqualTo(enableStatus);

        locationManager.setLocationEnabledForUser(enableStatus, userHandle);
    }

    @Test
    public void checkCallerHasCoarseLocation_doesNotCrash() {
        Context context = InstrumentationRegistry.getTargetContext();
        UserHandle userHandle = new UserHandle(UserHandle.USER_SYSTEM);
        LocationManager locationManager = context.getSystemService(LocationManager.class);
        boolean enabledStatus = locationManager.isLocationEnabledForUser(userHandle);

        locationManager.setLocationEnabledForUser(false, userHandle);
        assertThat(Utils.checkCallerHasCoarseLocation(context, null, userHandle)).isFalse();

        locationManager.setLocationEnabledForUser(true, userHandle);
        Utils.checkCallerHasCoarseLocation(context, null, userHandle);
        if (!enabledStatus) {
            locationManager.setLocationEnabledForUser(false, userHandle);
        }
    }

    @Test
    public void checkCallerHasCoarseOrFineLocation_doesNotCrash() {
        Context context = InstrumentationRegistry.getTargetContext();
        UserHandle userHandle = new UserHandle(UserHandle.USER_SYSTEM);
        LocationManager locationManager = context.getSystemService(LocationManager.class);
        boolean enabledStatus = locationManager.isLocationEnabledForUser(userHandle);

        locationManager.setLocationEnabledForUser(false, userHandle);
        assertThat(Utils.checkCallerHasCoarseOrFineLocation(context, null, userHandle)).isFalse();

        locationManager.setLocationEnabledForUser(true, userHandle);
        Utils.checkCallerHasCoarseOrFineLocation(context, null, userHandle);
        if (!enabledStatus) {
            locationManager.setLocationEnabledForUser(false, userHandle);
        }
    }

    @Test
    public void checkPermissionMethod_doesNotCrash() {
        Context context = InstrumentationRegistry.getTargetContext();
        try {
            Utils.checkAdvertisePermissionForDataDelivery(context, null, "message");
            Utils.checkAdvertisePermissionForPreflight(context);
            Utils.checkCallerHasWriteSmsPermission(context);
            Utils.checkScanPermissionForPreflight(context);
            Utils.checkConnectPermissionForPreflight(context);
        } catch (SecurityException e) {
            // SecurityException could happen.
        }
    }

    @Test
    public void enforceDumpPermission_doesNotCrash() {
        Context context = InstrumentationRegistry.getTargetContext();
        try {
            Utils.enforceDumpPermission(context);
        } catch (SecurityException e) {
            // SecurityException could happen.
        }
    }

    @Test
    public void getLoggableAddress() {
        assertThat(Utils.getLoggableAddress(null)).isEqualTo("00:00:00:00:00:00");

        BluetoothDevice device = TestUtils.getTestDevice(BluetoothAdapter.getDefaultAdapter(), 1);
        String loggableAddress = "xx:xx:xx:xx:" + device.getAddress().substring(12);
        assertThat(Utils.getLoggableAddress(device)).isEqualTo(loggableAddress);
    }
}
