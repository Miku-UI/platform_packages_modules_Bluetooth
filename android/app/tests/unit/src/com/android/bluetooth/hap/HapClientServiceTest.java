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

package com.android.bluetooth.hap;

import static android.bluetooth.BluetoothHapClient.ACTION_HAP_CONNECTION_STATE_CHANGED;
import static android.bluetooth.BluetoothHapClient.ACTION_HAP_DEVICE_AVAILABLE;
import static android.bluetooth.BluetoothProfile.EXTRA_PREVIOUS_STATE;
import static android.bluetooth.BluetoothProfile.EXTRA_STATE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.core.AllOf.allOf;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHapClient;
import android.bluetooth.BluetoothHapPresetInfo;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothHapClientCallback;
import android.os.Binder;
import android.os.ParcelUuid;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.csip.CsipSetCoordinatorService;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class HapClientServiceTest {
    private static final int TIMEOUT_MS = 1000;
    private final BluetoothAdapter mAdapter = BluetoothAdapter.getDefaultAdapter();
    private final BluetoothDevice mDevice = TestUtils.getTestDevice(mAdapter, 0);
    private final BluetoothDevice mDevice2 = TestUtils.getTestDevice(mAdapter, 1);
    private final BluetoothDevice mDevice3 = TestUtils.getTestDevice(mAdapter, 2);

    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock private AdapterService mAdapterService;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private HapClientNativeInterface mNativeInterface;
    @Mock private ServiceFactory mServiceFactory;
    @Mock private CsipSetCoordinatorService mCsipService;
    @Mock private IBluetoothHapClientCallback mFrameworkCallback;
    @Mock private Binder mBinder;

    private HapClientService mService;
    private HapClientNativeCallback mNativeCallback;

    @Before
    public void setUp() throws Exception {
        HapClientStateMachine.sConnectTimeoutMs = TIMEOUT_MS;

        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        doReturn(mDevice).when(mAdapterService).getDeviceFromByte(eq(getByteAddress(mDevice)));
        doReturn(mDevice2).when(mAdapterService).getDeviceFromByte(eq(getByteAddress(mDevice2)));
        doReturn(mDevice3).when(mAdapterService).getDeviceFromByte(eq(getByteAddress(mDevice3)));

        doReturn(mCsipService).when(mServiceFactory).getCsipSetCoordinatorService();

        doReturn(mBinder).when(mFrameworkCallback).asBinder();

        /* Prepare CAS groups */
        doReturn(List.of(0x02, 0x03)).when(mCsipService).getAllGroupIds(BluetoothUuid.CAP);

        int groupId2 = 0x02;
        Map groups2 =
                Map.of(groupId2, ParcelUuid.fromString("00001853-0000-1000-8000-00805F9B34FB"));

        int groupId3 = 0x03;
        Map groups3 =
                Map.of(groupId3, ParcelUuid.fromString("00001853-0000-1000-8000-00805F9B34FB"));

        doReturn(List.of(mDevice, mDevice2)).when(mCsipService).getGroupDevicesOrdered(groupId2);
        doReturn(groups2).when(mCsipService).getGroupUuidMapByDevice(mDevice);
        doReturn(groups2).when(mCsipService).getGroupUuidMapByDevice(mDevice2);

        doReturn(List.of(mDevice3)).when(mCsipService).getGroupDevicesOrdered(groupId3);
        doReturn(groups3).when(mCsipService).getGroupUuidMapByDevice(mDevice3);

        doReturn(List.of(mDevice)).when(mCsipService).getGroupDevicesOrdered(0x01);

        doReturn(BluetoothDevice.BOND_BONDED)
                .when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();

        startService();
        mNativeCallback = new HapClientNativeCallback(mAdapterService, mService);
        mService.mFactory = mServiceFactory;
        synchronized (mService.mCallbacks) {
            mService.mCallbacks.register(mFrameworkCallback);
        }
    }

    @After
    public void tearDown() throws Exception {
        if (mService == null) {
            return;
        }

        synchronized (mService.mCallbacks) {
            mService.mCallbacks.unregister(mFrameworkCallback);
        }

        stopService();
    }

    private void startService() throws TimeoutException {
        mService = new HapClientService(mAdapterService, mNativeInterface);
        mService.start();
        mService.setAvailable(true);
    }

    private void stopService() throws TimeoutException {
        mService.stop();
        mService = HapClientService.getHapClientService();
        Assert.assertNull(mService);
    }

    @Test
    public void testGetHapService() {
        Assert.assertEquals(mService, HapClientService.getHapClientService());
    }

    @Test
    public void testGetSetPolicy() throws Exception {
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
        Assert.assertEquals(
                "Initial device policy",
                BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                mService.getConnectionPolicy(mDevice));

        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        Assert.assertEquals(
                "Setting device policy to POLICY_FORBIDDEN",
                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                mService.getConnectionPolicy(mDevice));

        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        int policy = mService.getConnectionPolicy(mDevice);
        Assert.assertEquals(
                "Setting device policy to POLICY_ALLOWED",
                BluetoothProfile.CONNECTION_POLICY_ALLOWED,
                policy);
    }

    @Test
    public void testGetPolicyAfterStopped() {
        mService.stop();
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
        Assert.assertEquals(
                "Initial device policy",
                BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                mService.getConnectionPolicy(mDevice));
    }

    @Test
    public void testOkToConnect() {
        int badPolicyValue = 1024;
        int badBondState = 42;
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_NONE,
                BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                false);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_NONE,
                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                false);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_NONE,
                BluetoothProfile.CONNECTION_POLICY_ALLOWED,
                false);
        testOkToConnectCase(mDevice, BluetoothDevice.BOND_NONE, badPolicyValue, false);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_BONDING,
                BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                false);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_BONDING,
                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                false);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_BONDING,
                BluetoothProfile.CONNECTION_POLICY_ALLOWED,
                false);
        testOkToConnectCase(mDevice, BluetoothDevice.BOND_BONDING, badPolicyValue, false);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_BONDED,
                BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                true);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_BONDED,
                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                false);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_BONDED,
                BluetoothProfile.CONNECTION_POLICY_ALLOWED,
                true);
        testOkToConnectCase(mDevice, BluetoothDevice.BOND_BONDED, badPolicyValue, false);
        testOkToConnectCase(
                mDevice, badBondState, BluetoothProfile.CONNECTION_POLICY_UNKNOWN, false);
        testOkToConnectCase(
                mDevice, badBondState, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(
                mDevice, badBondState, BluetoothProfile.CONNECTION_POLICY_ALLOWED, false);
        testOkToConnectCase(mDevice, badBondState, badPolicyValue, false);
    }

    @Test
    public void testOutgoingConnectMissingHasUuid() {
        // Update the device policy so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectHapClient(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectHapClient(any(BluetoothDevice.class));

        // Return No UUID
        doReturn(new ParcelUuid[] {})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        // Send a connect request
        Assert.assertFalse("Connect expected to fail", mService.connect(mDevice));
    }

    @Test
    public void testOutgoingConnectExistingHasUuid() {
        // Update the device policy so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectHapClient(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectHapClient(any(BluetoothDevice.class));

        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        // Send a connect request
        Assert.assertTrue("Connect expected to succeed", mService.connect(mDevice));

        verify(mAdapterService, timeout(TIMEOUT_MS)).sendBroadcastMultiplePermissions(any(), any());
    }

    @Test
    public void testOutgoingConnectPolicyForbidden() {
        doReturn(true).when(mNativeInterface).connectHapClient(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectHapClient(any(BluetoothDevice.class));

        // Set the device policy to POLICY_FORBIDDEN so connect() should fail
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);

        // Send a connect request
        Assert.assertFalse("Connect expected to fail", mService.connect(mDevice));
    }

    @Test
    public void testOutgoingConnectTimeout() throws Exception {
        InOrder order = inOrder(mAdapterService);

        // Update the device policy so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectHapClient(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectHapClient(any(BluetoothDevice.class));

        // Send a connect request
        Assert.assertTrue("Connect failed", mService.connect(mDevice));

        order.verify(mAdapterService, timeout(TIMEOUT_MS))
                .sendBroadcastMultiplePermissions(
                        argThat(
                                allOf(
                                        hasAction(ACTION_HAP_CONNECTION_STATE_CHANGED),
                                        hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                                        hasExtra(EXTRA_STATE, STATE_CONNECTING),
                                        hasExtra(EXTRA_PREVIOUS_STATE, STATE_DISCONNECTED))),
                        any());

        Assert.assertEquals(
                BluetoothProfile.STATE_CONNECTING, mService.getConnectionState(mDevice));

        // Verify the connection state broadcast, and that we are in Disconnected state via binder
        order.verify(mAdapterService, timeout(HapClientStateMachine.sConnectTimeoutMs * 2L))
                .sendBroadcastMultiplePermissions(
                        argThat(
                                allOf(
                                        hasAction(ACTION_HAP_CONNECTION_STATE_CHANGED),
                                        hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                                        hasExtra(EXTRA_STATE, STATE_DISCONNECTED),
                                        hasExtra(EXTRA_PREVIOUS_STATE, STATE_CONNECTING))),
                        any());

        int state = mService.getConnectionState(mDevice);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED, state);
    }

    @Test
    public void testConnectTwo() throws Exception {
        InOrder order = inOrder(mAdapterService);
        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        // Send a connect request for the 1st device
        testConnectingDevice(order, mDevice);

        // Send a connect request for the 2nd device
        BluetoothDevice Device2 = TestUtils.getTestDevice(mAdapter, 1);
        testConnectingDevice(order, Device2);

        List<BluetoothDevice> devices = mService.getConnectedDevices();
        Assert.assertTrue(devices.contains(mDevice));
        Assert.assertTrue(devices.contains(Device2));
        Assert.assertNotEquals(mDevice, Device2);
    }

    @Test
    public void testCallsForNotConnectedDevice() {
        Assert.assertEquals(
                BluetoothHapClient.PRESET_INDEX_UNAVAILABLE,
                mService.getActivePresetIndex(mDevice));
    }

    @Test
    public void testGetHapGroupCoordinatedOps() throws Exception {
        InOrder order = inOrder(mAdapterService);
        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(order, mDevice);
        testConnectingDevice(order, mDevice2);
        testConnectingDevice(order, mDevice3);

        mNativeCallback.onFeaturesUpdate(getByteAddress(mDevice), 0x04);
        mNativeCallback.onFeaturesUpdate(getByteAddress(mDevice3), 0x04);

        /* This one has no coordinated operation support but is part of a coordinated set with
         * mDevice, which supports it, thus mDevice will forward the operation to mDevice2.
         * This device should also be rocognised as grouped one.
         */
        mNativeCallback.onFeaturesUpdate(getByteAddress(mDevice2), 0);

        /* Two devices support coordinated operations thus shall report valid group ID */
        Assert.assertEquals(2, mService.getHapGroup(mDevice));
        Assert.assertEquals(3, mService.getHapGroup(mDevice3));

        /* Third one has no coordinated operations support but is part of the group */
        int hapGroup = mService.getHapGroup(mDevice2);
        Assert.assertEquals(2, hapGroup);
    }

    @Test
    public void testSelectPresetNative() throws Exception {
        InOrder order = inOrder(mAdapterService);
        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(order, mDevice);

        // Verify Native Interface call
        mService.selectPreset(mDevice, 0x00);
        verify(mNativeInterface, never()).selectActivePreset(eq(mDevice), eq(0x00));
        verify(mFrameworkCallback, after(TIMEOUT_MS))
                .onPresetSelectionFailed(
                        eq(mDevice), eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));

        mService.selectPreset(mDevice, 0x01);
        verify(mNativeInterface).selectActivePreset(eq(mDevice), eq(0x01));
    }

    @Test
    public void testGroupSelectActivePresetNative() throws Exception {
        InOrder order = inOrder(mAdapterService);
        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(order, mDevice3);

        int flags = 0x01;
        mNativeCallback.onFeaturesUpdate(getByteAddress(mDevice3), flags);

        // Verify Native Interface call
        mService.selectPresetForGroup(0x03, 0x00);
        verify(mFrameworkCallback, after(TIMEOUT_MS))
                .onPresetSelectionForGroupFailed(
                        eq(0x03), eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));

        mService.selectPresetForGroup(0x03, 0x01);
        verify(mNativeInterface).groupSelectActivePreset(eq(0x03), eq(0x01));
    }

    @Test
    public void testSwitchToNextPreset() {
        InOrder order = inOrder(mAdapterService);
        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(order, mDevice);

        // Verify Native Interface call
        mService.switchToNextPreset(mDevice);
        verify(mNativeInterface).nextActivePreset(eq(mDevice));
    }

    @Test
    public void testSwitchToNextPresetForGroup() {
        InOrder order = inOrder(mAdapterService);
        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(order, mDevice3);
        int flags = 0x01;
        mNativeCallback.onFeaturesUpdate(getByteAddress(mDevice3), flags);

        // Verify Native Interface call
        mService.switchToNextPresetForGroup(0x03);
        verify(mNativeInterface).groupNextActivePreset(eq(0x03));
    }

    @Test
    public void testSwitchToPreviousPreset() {
        InOrder order = inOrder(mAdapterService);
        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(order, mDevice);

        // Verify Native Interface call
        mService.switchToPreviousPreset(mDevice);
        verify(mNativeInterface).previousActivePreset(eq(mDevice));
    }

    @Test
    public void testSwitchToPreviousPresetForGroup() {
        InOrder order = inOrder(mAdapterService);
        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(order, mDevice);
        testConnectingDevice(order, mDevice2);

        int flags = 0x01;
        mNativeCallback.onFeaturesUpdate(getByteAddress(mDevice), flags);

        // Verify Native Interface call
        mService.switchToPreviousPresetForGroup(0x02);
        verify(mNativeInterface).groupPreviousActivePreset(eq(0x02));
    }

    @Test
    public void testGetActivePresetIndex() throws Exception {
        InOrder order = inOrder(mAdapterService);
        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(order, mDevice);
        testOnPresetSelected(mDevice, 0x01);

        // Verify cached value via binder
        int presetIndex = mService.getActivePresetIndex(mDevice);
        Assert.assertEquals(0x01, presetIndex);
    }

    @Test
    public void testGetPresetInfoAndActivePresetInfo() throws Exception {
        InOrder order = inOrder(mAdapterService);
        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(order, mDevice2);

        // Check when active preset is not known yet
        List<BluetoothHapPresetInfo> presetList = mService.getAllPresetInfo(mDevice2);

        BluetoothHapPresetInfo presetInfo = mService.getPresetInfo(mDevice2, 0x01);
        Assert.assertTrue(presetList.contains(presetInfo));
        Assert.assertEquals(0x01, presetInfo.getIndex());

        Assert.assertEquals(
                BluetoothHapClient.PRESET_INDEX_UNAVAILABLE,
                mService.getActivePresetIndex(mDevice2));
        Assert.assertEquals(null, mService.getActivePresetInfo(mDevice2));

        // Inject active preset change event
        testOnPresetSelected(mDevice2, 0x01);

        // Check when active preset is known
        Assert.assertEquals(0x01, mService.getActivePresetIndex(mDevice2));
        BluetoothHapPresetInfo info = mService.getActivePresetInfo(mDevice2);
        Assert.assertNotNull(info);
        Assert.assertEquals("One", info.getName());
    }

    @Test
    public void testSetPresetNameNative() throws Exception {
        InOrder order = inOrder(mAdapterService);
        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(order, mDevice);

        mService.setPresetName(mDevice, 0x00, "ExamplePresetName");
        verify(mNativeInterface, never())
                .setPresetName(eq(mDevice), eq(0x00), eq("ExamplePresetName"));
        verify(mFrameworkCallback)
                .onSetPresetNameFailed(
                        eq(mDevice), eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));

        // Verify Native Interface call
        mService.setPresetName(mDevice, 0x01, "ExamplePresetName");
        verify(mNativeInterface).setPresetName(eq(mDevice), eq(0x01), eq("ExamplePresetName"));
    }

    @Test
    public void testSetPresetNameForGroup() throws Exception {
        InOrder order = inOrder(mAdapterService);
        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        int test_group = 0x02;

        testConnectingDevice(order, mDevice);
        testConnectingDevice(order, mDevice2);

        int flags = 0x21;
        mNativeCallback.onFeaturesUpdate(getByteAddress(mDevice), flags);

        mService.setPresetNameForGroup(test_group, 0x00, "ExamplePresetName");
        verify(mFrameworkCallback, after(TIMEOUT_MS))
                .onSetPresetNameForGroupFailed(
                        eq(test_group), eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));

        mService.setPresetNameForGroup(-1, 0x01, "ExamplePresetName");
        verify(mFrameworkCallback, after(TIMEOUT_MS))
                .onSetPresetNameForGroupFailed(
                        eq(-1), eq(BluetoothStatusCodes.ERROR_CSIP_INVALID_GROUP_ID));

        // Verify Native Interface call
        mService.setPresetNameForGroup(test_group, 0x01, "ExamplePresetName");
        verify(mNativeInterface)
                .groupSetPresetName(eq(test_group), eq(0x01), eq("ExamplePresetName"));
    }

    @Test
    public void testStackEventDeviceAvailable() {
        int features = 0x03;

        mNativeCallback.onDeviceAvailable(getByteAddress(mDevice), features);

        verify(mAdapterService)
                .sendBroadcastMultiplePermissions(
                        argThat(
                                allOf(
                                        hasAction(ACTION_HAP_DEVICE_AVAILABLE),
                                        hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                                        hasExtra(BluetoothHapClient.EXTRA_HAP_FEATURES, features))),
                        any());
    }

    @Test
    public void testStackEventOnPresetSelected() throws Exception {
        int presetIndex = 0x01;

        mNativeCallback.onActivePresetSelected(getByteAddress(mDevice), presetIndex);

        verify(mFrameworkCallback)
                .onPresetSelected(
                        eq(mDevice),
                        eq(presetIndex),
                        eq(BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST));
        assertThat(mService.getActivePresetIndex(mDevice)).isEqualTo(presetIndex);
    }

    @Test
    public void testStackEventOnActivePresetSelectError() throws Exception {
        mNativeCallback.onActivePresetSelectError(getByteAddress(mDevice), 0x05);

        verify(mFrameworkCallback)
                .onPresetSelectionFailed(
                        eq(mDevice), eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));
    }

    @Test
    public void testStackEventOnPresetInfo() throws Exception {
        InOrder order = inOrder(mAdapterService);
        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        // Connect and inject initial presets
        testConnectingDevice(order, mDevice);

        int infoReason = HapClientStackEvent.PRESET_INFO_REASON_PRESET_INFO_UPDATE;
        BluetoothHapPresetInfo[] info = {
            new BluetoothHapPresetInfo.Builder(0x01, "OneChangedToUnavailable")
                    .setWritable(true)
                    .setAvailable(false)
                    .build()
        };

        mNativeCallback.onPresetInfo(getByteAddress(mDevice), infoReason, info);

        ArgumentCaptor<List<BluetoothHapPresetInfo>> presetsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mFrameworkCallback)
                .onPresetInfoChanged(
                        eq(mDevice),
                        presetsCaptor.capture(),
                        eq(BluetoothStatusCodes.REASON_REMOTE_REQUEST));

        List<BluetoothHapPresetInfo> presets = presetsCaptor.getValue();
        Assert.assertEquals(3, presets.size());

        Optional<BluetoothHapPresetInfo> preset =
                presetsCaptor.getValue().stream().filter(p -> 0x01 == p.getIndex()).findFirst();
        Assert.assertEquals("OneChangedToUnavailable", preset.get().getName());
        Assert.assertFalse(preset.get().isAvailable());
        Assert.assertTrue(preset.get().isWritable());
    }

    @Test
    public void testStackEventOnPresetNameSetError() throws Exception {
        /* Not a valid name length */
        mNativeCallback.onPresetNameSetError(
                getByteAddress(mDevice),
                0x01,
                HapClientStackEvent.STATUS_INVALID_PRESET_NAME_LENGTH);
        verify(mFrameworkCallback)
                .onSetPresetNameFailed(
                        eq(mDevice), eq(BluetoothStatusCodes.ERROR_HAP_PRESET_NAME_TOO_LONG));

        /* Invalid preset index provided */
        mNativeCallback.onPresetNameSetError(
                getByteAddress(mDevice), 0x01, HapClientStackEvent.STATUS_INVALID_PRESET_INDEX);
        verify(mFrameworkCallback)
                .onSetPresetNameFailed(
                        eq(mDevice), eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));

        /* Not allowed on this particular preset */
        mNativeCallback.onPresetNameSetError(
                getByteAddress(mDevice), 0x01, HapClientStackEvent.STATUS_SET_NAME_NOT_ALLOWED);
        verify(mFrameworkCallback)
                .onSetPresetNameFailed(
                        eq(mDevice), eq(BluetoothStatusCodes.ERROR_REMOTE_OPERATION_REJECTED));

        /* Not allowed on this particular preset at this time, might be possible later on */
        mNativeCallback.onPresetNameSetError(
                getByteAddress(mDevice), 0x01, HapClientStackEvent.STATUS_OPERATION_NOT_POSSIBLE);
        verify(mFrameworkCallback, times(2))
                .onSetPresetNameFailed(
                        eq(mDevice), eq(BluetoothStatusCodes.ERROR_REMOTE_OPERATION_REJECTED));

        /* Not allowed on all presets - for example missing characteristic */
        mNativeCallback.onPresetNameSetError(
                getByteAddress(mDevice), 0x01, HapClientStackEvent.STATUS_OPERATION_NOT_SUPPORTED);
        verify(mFrameworkCallback)
                .onSetPresetNameFailed(
                        eq(mDevice), eq(BluetoothStatusCodes.ERROR_REMOTE_OPERATION_NOT_SUPPORTED));
    }

    @Test
    public void testStackEventOnGroupPresetNameSetError() throws Exception {
        int groupId = 0x01;
        int presetIndex = 0x04;
        /* Not a valid name length */
        mNativeCallback.onGroupPresetNameSetError(
                groupId, presetIndex, HapClientStackEvent.STATUS_INVALID_PRESET_NAME_LENGTH);
        verify(mFrameworkCallback)
                .onSetPresetNameForGroupFailed(
                        eq(groupId), eq(BluetoothStatusCodes.ERROR_HAP_PRESET_NAME_TOO_LONG));

        /* Invalid preset index provided */
        mNativeCallback.onGroupPresetNameSetError(
                groupId, presetIndex, HapClientStackEvent.STATUS_INVALID_PRESET_INDEX);
        verify(mFrameworkCallback)
                .onSetPresetNameForGroupFailed(
                        eq(groupId), eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));

        /* Not allowed on this particular preset */
        mNativeCallback.onGroupPresetNameSetError(
                groupId, presetIndex, HapClientStackEvent.STATUS_SET_NAME_NOT_ALLOWED);
        verify(mFrameworkCallback)
                .onSetPresetNameForGroupFailed(
                        eq(groupId), eq(BluetoothStatusCodes.ERROR_REMOTE_OPERATION_REJECTED));

        /* Not allowed on this particular preset at this time, might be possible later on */
        mNativeCallback.onGroupPresetNameSetError(
                groupId, presetIndex, HapClientStackEvent.STATUS_OPERATION_NOT_POSSIBLE);
        verify(mFrameworkCallback, times(2))
                .onSetPresetNameForGroupFailed(
                        eq(groupId), eq(BluetoothStatusCodes.ERROR_REMOTE_OPERATION_REJECTED));

        /* Not allowed on all presets - for example if peer is missing optional CP characteristic */
        mNativeCallback.onGroupPresetNameSetError(
                groupId, presetIndex, HapClientStackEvent.STATUS_OPERATION_NOT_SUPPORTED);
        verify(mFrameworkCallback)
                .onSetPresetNameForGroupFailed(
                        eq(groupId), eq(BluetoothStatusCodes.ERROR_REMOTE_OPERATION_NOT_SUPPORTED));
    }

    @Test
    public void testServiceBinderGetDevicesMatchingConnectionStates() throws Exception {
        List<BluetoothDevice> devices = mService.getDevicesMatchingConnectionStates(null);
        Assert.assertEquals(0, devices.size());
    }

    @Test
    public void testServiceBinderSetConnectionPolicy() throws Exception {
        Assert.assertTrue(
                mService.setConnectionPolicy(mDevice, BluetoothProfile.CONNECTION_POLICY_UNKNOWN));
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mDevice,
                        BluetoothProfile.HAP_CLIENT,
                        BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
    }

    @Test
    public void testServiceBinderGetFeatures() throws Exception {
        int features = mService.getFeatures(mDevice);
        Assert.assertEquals(0x00, features);
    }

    @Test
    public void testServiceBinderRegisterUnregisterCallback() throws Exception {
        IBluetoothHapClientCallback callback = Mockito.mock(IBluetoothHapClientCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        when(callback.asBinder()).thenReturn(binder);

        synchronized (mService.mCallbacks) {
            int size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            Assert.assertEquals(size + 1, mService.mCallbacks.getRegisteredCallbackCount());

            mService.unregisterCallback(callback);
            Assert.assertEquals(size, mService.mCallbacks.getRegisteredCallbackCount());
        }
    }

    @Test
    public void testDumpDoesNotCrash() {
        // Update the device policy so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectHapClient(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectHapClient(any(BluetoothDevice.class));

        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        // Add state machine for testing dump()
        mService.connect(mDevice);

        verify(mAdapterService, timeout(TIMEOUT_MS)).sendBroadcastMultiplePermissions(any(), any());

        mService.dump(new StringBuilder());
    }

    /** Helper function to test device connecting */
    private void prepareConnectingDevice(BluetoothDevice device) {
        // Prepare intent queue and all the mocks
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectHapClient(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectHapClient(any(BluetoothDevice.class));
    }

    /** Helper function to test device connecting */
    private void testConnectingDevice(InOrder order, BluetoothDevice device) {
        prepareConnectingDevice(device);
        // Send a connect request
        Assert.assertTrue("Connect expected to succeed", mService.connect(device));
        verifyConnectingDevice(order, device);
    }

    /** Helper function to test device connecting */
    private void verifyConnectingDevice(InOrder order, BluetoothDevice device) {
        // Verify the connection state broadcast, and that we are in Connecting state
        order.verify(mAdapterService, timeout(TIMEOUT_MS))
                .sendBroadcastMultiplePermissions(
                        argThat(
                                allOf(
                                        hasAction(ACTION_HAP_CONNECTION_STATE_CHANGED),
                                        hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                                        hasExtra(EXTRA_STATE, STATE_CONNECTING),
                                        hasExtra(EXTRA_PREVIOUS_STATE, STATE_DISCONNECTED))),
                        any());
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING, mService.getConnectionState(device));

        // Send a message to trigger connection completed
        HapClientStackEvent evt =
                new HapClientStackEvent(HapClientStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        evt.device = device;
        evt.valueInt1 = HapClientStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(evt);

        // Verify the connection state broadcast, and that we are in Connected state
        order.verify(mAdapterService, timeout(TIMEOUT_MS))
                .sendBroadcastMultiplePermissions(
                        argThat(
                                allOf(
                                        hasAction(ACTION_HAP_CONNECTION_STATE_CHANGED),
                                        hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                                        hasExtra(EXTRA_STATE, STATE_CONNECTED),
                                        hasExtra(EXTRA_PREVIOUS_STATE, STATE_CONNECTING))),
                        any());
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(device));

        evt = new HapClientStackEvent(HapClientStackEvent.EVENT_TYPE_DEVICE_AVAILABLE);
        evt.device = device;
        evt.valueInt1 = 0x01; // features
        mService.messageFromNative(evt);

        order.verify(mAdapterService, timeout(TIMEOUT_MS))
                .sendBroadcastMultiplePermissions(
                        argThat(
                                allOf(
                                        hasAction(ACTION_HAP_DEVICE_AVAILABLE),
                                        hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                                        hasExtra(BluetoothHapClient.EXTRA_HAP_FEATURES, 0x01))),
                        any());

        evt = new HapClientStackEvent(HapClientStackEvent.EVENT_TYPE_DEVICE_FEATURES);
        evt.device = device;
        evt.valueInt1 = 0x01; // features
        mService.messageFromNative(evt);

        // Inject some initial presets
        List<BluetoothHapPresetInfo> presets =
                new ArrayList<>(
                        Arrays.asList(
                                new BluetoothHapPresetInfo.Builder(0x01, "One")
                                        .setAvailable(true)
                                        .setWritable(false)
                                        .build(),
                                new BluetoothHapPresetInfo.Builder(0x02, "Two")
                                        .setAvailable(true)
                                        .setWritable(true)
                                        .build(),
                                new BluetoothHapPresetInfo.Builder(0x03, "Three")
                                        .setAvailable(false)
                                        .setWritable(false)
                                        .build()));
        mService.updateDevicePresetsCache(
                device, HapClientStackEvent.PRESET_INFO_REASON_ALL_PRESET_INFO, presets);
    }

    private void testOnPresetSelected(BluetoothDevice device, int index) throws Exception {
        HapClientStackEvent evt =
                new HapClientStackEvent(HapClientStackEvent.EVENT_TYPE_ON_ACTIVE_PRESET_SELECTED);
        evt.device = device;
        evt.valueInt1 = index;
        mService.messageFromNative(evt);

        verify(mFrameworkCallback, after(TIMEOUT_MS))
                .onPresetSelected(
                        eq(device),
                        eq(evt.valueInt1),
                        eq(BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST));
    }

    /** Helper function to test okToConnect() method */
    private void testOkToConnectCase(
            BluetoothDevice device, int bondState, int policy, boolean expected) {
        doReturn(bondState).when(mAdapterService).getBondState(device);
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.HAP_CLIENT))
                .thenReturn(policy);
        Assert.assertEquals(expected, mService.okToConnect(device));
    }

    /** Helper function to get byte array for a device address */
    private byte[] getByteAddress(BluetoothDevice device) {
        if (device == null) {
            return Utils.getBytesFromAddress("00:00:00:00:00:00");
        }
        return Utils.getBytesFromAddress(device.getAddress());
    }
}
