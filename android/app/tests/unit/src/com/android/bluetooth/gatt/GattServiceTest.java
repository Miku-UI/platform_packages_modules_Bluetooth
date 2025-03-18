/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.bluetooth.gatt;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.IBluetoothGattCallback;
import android.bluetooth.IBluetoothGattServerCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.DistanceMeasurementMethod;
import android.bluetooth.le.DistanceMeasurementParams;
import android.bluetooth.le.IDistanceMeasurementCallback;
import android.bluetooth.le.PeriodicAdvertisingParameters;
import android.content.AttributionSource;
import android.content.Context;
import android.content.res.Resources;
import android.location.LocationManager;
import android.os.Bundle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.CompanionManager;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.le_scan.ScanManager;
import com.android.bluetooth.le_scan.ScanObjectsFactory;
import com.android.bluetooth.le_scan.ScannerMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Test cases for {@link GattService}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class GattServiceTest {
    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private ContextMap<IBluetoothGattCallback> mClientMap;
    @Mock private ScannerMap mScannerMap;
    @Mock private ScanManager mScanManager;
    @Mock private Set<String> mReliableQueue;
    @Mock private ContextMap<IBluetoothGattServerCallback> mServerMap;
    @Mock private DistanceMeasurementManager mDistanceMeasurementManager;
    @Mock private AdvertiseManagerNativeInterface mAdvertiseManagerNativeInterface;
    @Mock private Resources mResources;
    @Mock private AdapterService mAdapterService;
    @Mock private GattObjectsFactory mGattObjectsFactory;
    @Mock private ScanObjectsFactory mScanObjectsFactory;
    @Mock private GattNativeInterface mNativeInterface;

    private static final String REMOTE_DEVICE_ADDRESS = "00:00:00:00:00:00";
    private static final int TIMES_UP_AND_DOWN = 3;

    private final BluetoothAdapter mAdapter = BluetoothAdapter.getDefaultAdapter();
    private final AttributionSource mAttributionSource = mAdapter.getAttributionSource();
    private final Context mContext = InstrumentationRegistry.getTargetContext();

    private MockContentResolver mMockContentResolver;

    private GattService mService;
    private CompanionManager mBtCompanionManager;

    @Before
    public void setUp() throws Exception {
        mMockContentResolver = new MockContentResolver(mContext);
        mMockContentResolver.addProvider(
                Settings.AUTHORITY,
                new MockContentProvider() {
                    @Override
                    public Bundle call(String method, String request, Bundle args) {
                        return Bundle.EMPTY;
                    }
                });

        GattObjectsFactory.setInstanceForTesting(mGattObjectsFactory);
        ScanObjectsFactory.setInstanceForTesting(mScanObjectsFactory);

        doReturn(mNativeInterface).when(mGattObjectsFactory).getNativeInterface();
        doReturn(mDistanceMeasurementManager)
                .when(mGattObjectsFactory)
                .createDistanceMeasurementManager(any());
        doReturn(mScanManager)
                .when(mScanObjectsFactory)
                .createScanManager(any(), any(), any(), any());
        doReturn(mContext.getPackageManager()).when(mAdapterService).getPackageManager();
        doReturn(mContext.getSharedPreferences("GattServiceTestPrefs", Context.MODE_PRIVATE))
                .when(mAdapterService)
                .getSharedPreferences(anyString(), anyInt());
        doReturn(mResources).when(mAdapterService).getResources();
        doReturn(mMockContentResolver).when(mAdapterService).getContentResolver();

        TestUtils.mockGetSystemService(
                mAdapterService, Context.LOCATION_SERVICE, LocationManager.class);
        TestUtils.mockGetSystemService(
                mAdapterService, Context.ACTIVITY_SERVICE, ActivityManager.class);

        mBtCompanionManager = new CompanionManager(mAdapterService, null);
        doReturn(mBtCompanionManager).when(mAdapterService).getCompanionManager();

        AdvertiseManagerNativeInterface.setInstance(mAdvertiseManagerNativeInterface);
        mService = new GattService(mAdapterService);

        mService.mClientMap = mClientMap;
        mService.mTransitionalScanHelper.setScannerMap(mScannerMap);
        mService.mReliableQueue = mReliableQueue;
        mService.mServerMap = mServerMap;
    }

    @After
    public void tearDown() throws Exception {
        mService.stop();
        AdvertiseManagerNativeInterface.setInstance(null);

        GattObjectsFactory.setInstanceForTesting(null);
        ScanObjectsFactory.setInstanceForTesting(null);
    }

    @Test
    public void testServiceUpAndDown() throws Exception {
        for (int i = 0; i < TIMES_UP_AND_DOWN; i++) {
            mService.stop();
            mService = new GattService(mAdapterService);
        }
    }

    @Test
    public void emptyClearServices() {
        int serverIf = 1;

        mService.clearServices(serverIf, mAttributionSource);
        verify(mNativeInterface, times(0)).gattServerDeleteService(eq(serverIf), anyInt());
    }

    @Test
    public void clientReadPhy() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.clientReadPhy(clientIf, address, mAttributionSource);
        verify(mNativeInterface).gattClientReadPhy(clientIf, address);
    }

    @Test
    public void clientSetPreferredPhy() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int txPhy = 2;
        int rxPhy = 1;
        int phyOptions = 3;

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.clientSetPreferredPhy(
                clientIf, address, txPhy, rxPhy, phyOptions, mAttributionSource);
        verify(mNativeInterface)
                .gattClientSetPreferredPhy(clientIf, address, txPhy, rxPhy, phyOptions);
    }

    @Test
    public void connectionParameterUpdate() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;

        int connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_HIGH;
        mService.connectionParameterUpdate(
                clientIf, address, connectionPriority, mAttributionSource);

        connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER;
        mService.connectionParameterUpdate(
                clientIf, address, connectionPriority, mAttributionSource);

        connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_BALANCED;
        mService.connectionParameterUpdate(
                clientIf, address, connectionPriority, mAttributionSource);

        verify(mNativeInterface, times(3))
                .gattConnectionParameterUpdate(
                        eq(clientIf),
                        eq(address),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        eq(0),
                        eq(0));
    }

    @Test
    public void testDumpDoesNotCrash() {
        mService.dump(new StringBuilder());
    }

    @Test
    public void clientConnect() throws Exception {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = false;
        int transport = 2;
        boolean opportunistic = true;
        int phy = 3;

        mService.clientConnect(
                clientIf,
                address,
                addressType,
                isDirect,
                transport,
                opportunistic,
                phy,
                mAttributionSource);

        verify(mNativeInterface)
                .gattClientConnect(
                        clientIf, address, addressType, isDirect, transport, opportunistic, phy, 0);
    }

    @Test
    public void disconnectAll() {
        Map<Integer, String> connMap = new HashMap<>();
        int clientIf = 1;
        String address = "02:00:00:00:00:00";
        connMap.put(clientIf, address);
        doReturn(connMap).when(mClientMap).getConnectedMap();
        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.disconnectAll(mAttributionSource);
        verify(mNativeInterface).gattClientDisconnect(clientIf, address, connId);
    }

    @Test
    public void setAdvertisingData() {
        int advertiserId = 1;
        AdvertiseData data = new AdvertiseData.Builder().build();

        mService.setAdvertisingData(advertiserId, data, mAttributionSource);
    }

    @Test
    public void setAdvertisingParameters() {
        int advertiserId = 1;
        AdvertisingSetParameters parameters = new AdvertisingSetParameters.Builder().build();

        mService.setAdvertisingParameters(advertiserId, parameters, mAttributionSource);
    }

    @Test
    public void setPeriodicAdvertisingData() {
        int advertiserId = 1;
        AdvertiseData data = new AdvertiseData.Builder().build();

        mService.setPeriodicAdvertisingData(advertiserId, data, mAttributionSource);
    }

    @Test
    public void setPeriodicAdvertisingEnable() {
        int advertiserId = 1;
        boolean enable = true;

        mService.setPeriodicAdvertisingEnable(advertiserId, enable, mAttributionSource);
    }

    @Test
    public void setPeriodicAdvertisingParameters() {
        int advertiserId = 1;
        PeriodicAdvertisingParameters parameters =
                new PeriodicAdvertisingParameters.Builder().build();

        mService.setPeriodicAdvertisingParameters(advertiserId, parameters, mAttributionSource);
    }

    @Test
    public void setScanResponseData() {
        int advertiserId = 1;
        AdvertiseData data = new AdvertiseData.Builder().build();

        mService.setScanResponseData(advertiserId, data, mAttributionSource);
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {BluetoothProfile.STATE_CONNECTED};

        BluetoothDevice testDevice = mAdapter.getRemoteDevice("00:01:02:03:04:05");
        BluetoothDevice[] bluetoothDevices = new BluetoothDevice[] {testDevice};
        doReturn(bluetoothDevices).when(mAdapterService).getBondedDevices();

        Set<String> connectedDevices = new HashSet<>();
        String address = "02:00:00:00:00:00";
        connectedDevices.add(address);
        doReturn(connectedDevices).when(mClientMap).getConnectedDevices();

        List<BluetoothDevice> deviceList =
                mService.getDevicesMatchingConnectionStates(states, mAttributionSource);

        int expectedSize = 1;
        assertThat(deviceList.size()).isEqualTo(expectedSize);

        BluetoothDevice bluetoothDevice = deviceList.get(0);
        assertThat(bluetoothDevice.getAddress()).isEqualTo(address);
    }

    @Test
    public void registerClient() {
        UUID uuid = UUID.randomUUID();
        IBluetoothGattCallback callback = mock(IBluetoothGattCallback.class);
        boolean eattSupport = true;

        mService.registerClient(uuid, callback, eattSupport, mAttributionSource);
        verify(mNativeInterface)
                .gattClientRegisterApp(
                        uuid.getLeastSignificantBits(), uuid.getMostSignificantBits(), eattSupport);
    }

    @Test
    public void registerClient_checkLimitPerApp() {
        mSetFlagsRule.enableFlags(Flags.FLAG_GATT_CLIENT_DYNAMIC_ALLOCATION);
        doReturn(GattService.GATT_CLIENT_LIMIT_PER_APP).when(mClientMap).countByAppUid(anyInt());
        UUID uuid = UUID.randomUUID();
        IBluetoothGattCallback callback = mock(IBluetoothGattCallback.class);

        mService.registerClient(uuid, callback, /* eattSupport= */ true, mAttributionSource);
        verify(mClientMap, never()).add(any(), any(), any(), any());
        verify(mNativeInterface, never()).gattClientRegisterApp(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    public void unregisterClient() {
        int clientIf = 3;

        mService.unregisterClient(clientIf, mAttributionSource);
        verify(mClientMap).remove(clientIf);
        verify(mNativeInterface).gattClientUnregisterApp(clientIf);
    }

    @Test
    public void readCharacteristic() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int handle = 2;
        int authReq = 3;

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.readCharacteristic(clientIf, address, handle, authReq, mAttributionSource);
        verify(mNativeInterface).gattClientReadCharacteristic(connId, handle, authReq);
    }

    @Test
    public void readUsingCharacteristicUuid() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        UUID uuid = UUID.randomUUID();
        int startHandle = 2;
        int endHandle = 3;
        int authReq = 4;

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.readUsingCharacteristicUuid(
                clientIf, address, uuid, startHandle, endHandle, authReq, mAttributionSource);
        verify(mNativeInterface)
                .gattClientReadUsingCharacteristicUuid(
                        connId,
                        uuid.getLeastSignificantBits(),
                        uuid.getMostSignificantBits(),
                        startHandle,
                        endHandle,
                        authReq);
    }

    @Test
    public void writeCharacteristic() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int handle = 2;
        int writeType = 3;
        int authReq = 4;
        byte[] value = new byte[] {5, 6};

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        int writeCharacteristicResult =
                mService.writeCharacteristic(
                        clientIf, address, handle, writeType, authReq, value, mAttributionSource);
        assertThat(writeCharacteristicResult)
                .isEqualTo(BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED);
    }

    @Test
    public void readDescriptor() throws Exception {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int handle = 2;
        int authReq = 3;

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.readDescriptor(clientIf, address, handle, authReq, mAttributionSource);
        verify(mNativeInterface).gattClientReadDescriptor(connId, handle, authReq);
    }

    @Test
    public void beginReliableWrite() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;

        mService.beginReliableWrite(clientIf, address, mAttributionSource);
        verify(mReliableQueue).add(address);
    }

    @Test
    public void endReliableWrite() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        boolean execute = true;

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.endReliableWrite(clientIf, address, execute, mAttributionSource);
        verify(mReliableQueue).remove(address);
        verify(mNativeInterface).gattClientExecuteWrite(connId, execute);
    }

    @Test
    public void registerForNotification() throws Exception {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int handle = 2;
        boolean enable = true;

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.registerForNotification(clientIf, address, handle, enable, mAttributionSource);

        verify(mNativeInterface)
                .gattClientRegisterForNotifications(clientIf, address, handle, enable);
    }

    @Test
    public void readRemoteRssi() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;

        mService.readRemoteRssi(clientIf, address, mAttributionSource);
        verify(mNativeInterface).gattClientReadRemoteRssi(clientIf, address);
    }

    @Test
    public void configureMTU() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int mtu = 2;

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.configureMTU(clientIf, address, mtu, mAttributionSource);
        verify(mNativeInterface).gattClientConfigureMTU(connId, mtu);
    }

    @Test
    public void leConnectionUpdate() throws Exception {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int minInterval = 3;
        int maxInterval = 4;
        int peripheralLatency = 5;
        int supervisionTimeout = 6;
        int minConnectionEventLen = 7;
        int maxConnectionEventLen = 8;

        mService.leConnectionUpdate(
                clientIf,
                address,
                minInterval,
                maxInterval,
                peripheralLatency,
                supervisionTimeout,
                minConnectionEventLen,
                maxConnectionEventLen,
                mAttributionSource);

        verify(mNativeInterface)
                .gattConnectionParameterUpdate(
                        clientIf,
                        address,
                        minInterval,
                        maxInterval,
                        peripheralLatency,
                        supervisionTimeout,
                        minConnectionEventLen,
                        maxConnectionEventLen);
    }

    @Test
    public void serverConnect() {
        int serverIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = true;
        int transport = 2;

        mService.serverConnect(
                serverIf, address, addressType, isDirect, transport, mAttributionSource);
        verify(mNativeInterface)
                .gattServerConnect(serverIf, address, addressType, isDirect, transport);
    }

    @Test
    public void serverDisconnect() {
        int serverIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;

        Integer connId = 1;
        doReturn(connId).when(mServerMap).connIdByAddress(serverIf, address);

        mService.serverDisconnect(serverIf, address, mAttributionSource);
        verify(mNativeInterface).gattServerDisconnect(serverIf, address, connId);
    }

    @Test
    public void serverSetPreferredPhy() throws Exception {
        int serverIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int txPhy = 2;
        int rxPhy = 1;
        int phyOptions = 3;

        mService.serverSetPreferredPhy(
                serverIf, address, txPhy, rxPhy, phyOptions, mAttributionSource);
        verify(mNativeInterface)
                .gattServerSetPreferredPhy(serverIf, address, txPhy, rxPhy, phyOptions);
    }

    @Test
    public void serverReadPhy() {
        int serverIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;

        mService.serverReadPhy(serverIf, address, mAttributionSource);
        verify(mNativeInterface).gattServerReadPhy(serverIf, address);
    }

    @Test
    public void sendNotification() throws Exception {
        int serverIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int handle = 2;
        boolean confirm = true;
        byte[] value = new byte[] {5, 6};

        Integer connId = 1;
        doReturn(connId).when(mServerMap).connIdByAddress(serverIf, address);

        mService.sendNotification(serverIf, address, handle, confirm, value, mAttributionSource);
        verify(mNativeInterface).gattServerSendIndication(serverIf, handle, connId, value);

        confirm = false;

        mService.sendNotification(serverIf, address, handle, confirm, value, mAttributionSource);
        verify(mNativeInterface).gattServerSendNotification(serverIf, handle, connId, value);
    }

    @Test
    public void getOwnAddress() throws Exception {
        int advertiserId = 1;

        mService.getOwnAddress(advertiserId, mAttributionSource);
    }

    @Test
    public void enableAdvertisingSet() throws Exception {
        int advertiserId = 1;
        boolean enable = true;
        int duration = 3;
        int maxExtAdvEvents = 4;

        mService.enableAdvertisingSet(
                advertiserId, enable, duration, maxExtAdvEvents, mAttributionSource);
    }

    @Test
    public void unregAll() throws Exception {
        int appId = 1;
        List<Integer> appIds = new ArrayList<>();
        appIds.add(appId);
        doReturn(appIds).when(mClientMap).getAllAppsIds();

        mService.unregAll(mAttributionSource);
        verify(mClientMap).remove(appId);
        verify(mNativeInterface).gattClientUnregisterApp(appId);
    }

    @Test
    @DisableFlags(Flags.FLAG_SCAN_MANAGER_REFACTOR)
    public void numHwTrackFiltersAvailable() {
        mService.getTransitionalScanHelper().numHwTrackFiltersAvailable(mAttributionSource);
        verify(mScanManager).getCurrentUsedTrackingAdvertisement();
    }

    @Test
    public void getSupportedDistanceMeasurementMethods() {
        mService.getSupportedDistanceMeasurementMethods();
        verify(mDistanceMeasurementManager).getSupportedDistanceMeasurementMethods();
    }

    @Test
    public void startDistanceMeasurement() {
        UUID uuid = UUID.randomUUID();
        BluetoothDevice device = mAdapter.getRemoteDevice("00:01:02:03:04:05");
        DistanceMeasurementParams params =
                new DistanceMeasurementParams.Builder(device)
                        .setDurationSeconds(123)
                        .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                        .build();
        IDistanceMeasurementCallback callback = mock(IDistanceMeasurementCallback.class);
        mService.startDistanceMeasurement(uuid, params, callback);
        verify(mDistanceMeasurementManager).startDistanceMeasurement(uuid, params, callback);
    }

    @Test
    public void stopDistanceMeasurement() {
        UUID uuid = UUID.randomUUID();
        BluetoothDevice device = mAdapter.getRemoteDevice("00:01:02:03:04:05");
        int method = DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI;
        mService.stopDistanceMeasurement(uuid, device, method);
        verify(mDistanceMeasurementManager).stopDistanceMeasurement(uuid, device, method, false);
    }

    @Test
    public void cleanUp_doesNotCrash() {
        mService.cleanup();
    }

    @Test
    @DisableFlags(Flags.FLAG_SCAN_MANAGER_REFACTOR)
    public void profileConnectionStateChanged_notifyScanManager() {
        mService.notifyProfileConnectionStateChange(
                BluetoothProfile.A2DP,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_CONNECTED);
        verify(mScanManager)
                .handleBluetoothProfileConnectionStateChanged(
                        BluetoothProfile.A2DP,
                        BluetoothProfile.STATE_CONNECTING,
                        BluetoothProfile.STATE_CONNECTED);
    }

    @Test
    public void restrictedHandles() throws Exception {
        int clientIf = 1;
        int connId = 1;
        ArrayList<GattDbElement> db = new ArrayList<>();

        ContextMap<IBluetoothGattCallback>.App app = mock(ContextMap.App.class);
        IBluetoothGattCallback callback = mock(IBluetoothGattCallback.class);

        doReturn(app).when(mClientMap).getByConnId(connId);
        app.callback = callback;

        GattDbElement hidService =
                GattDbElement.createPrimaryService(
                        UUID.fromString("00001812-0000-1000-8000-00805F9B34FB"));
        hidService.id = 1;

        GattDbElement hidInfoChar =
                GattDbElement.createCharacteristic(
                        UUID.fromString("00002A4A-0000-1000-8000-00805F9B34FB"), 0, 0);
        hidInfoChar.id = 2;

        GattDbElement randomChar =
                GattDbElement.createCharacteristic(
                        UUID.fromString("0000FFFF-0000-1000-8000-00805F9B34FB"), 0, 0);
        randomChar.id = 3;

        db.add(hidService);
        db.add(hidInfoChar);
        db.add(randomChar);

        mService.onGetGattDb(connId, db);
        // HID characteristics should be restricted
        assertThat(mService.mRestrictedHandles.get(connId)).contains(hidInfoChar.id);
        assertThat(mService.mRestrictedHandles.get(connId)).doesNotContain(randomChar.id);

        mService.onDisconnected(
                clientIf, connId, BluetoothGatt.GATT_SUCCESS, REMOTE_DEVICE_ADDRESS);
        assertThat(mService.mRestrictedHandles).doesNotContainKey(connId);
    }
}
