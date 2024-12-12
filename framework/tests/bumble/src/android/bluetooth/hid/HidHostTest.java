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

package android.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.bluetooth.flags.Flags;
import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.Empty;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import pandora.HIDGrpc;
import pandora.HidProto.ProtocolModeEvent;
import pandora.HidProto.ReportEvent;

import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Test cases for {@link Hid Host}. */
@RunWith(AndroidJUnit4.class)
@Ignore("b/355328584")
public class HidHostTest {
    private static final String TAG = "HidHostTest";
    private SettableFuture<Integer> mFutureConnectionIntent,
            mFutureAdapterStateIntent,
            mFutureBondIntent,
            mFutureHandShakeIntent,
            mFutureProtocolModeIntent,
            mFutureVirtualUnplugIntent,
            mFutureReportIntent;
    private SettableFuture<Boolean> mAclConnectionIntent;
    private BluetoothDevice mDevice;
    private BluetoothHidHost mHidService;
    private BluetoothHeadset mHfpService;
    private BluetoothA2dp mA2dpService;
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final BluetoothManager mManager = mContext.getSystemService(BluetoothManager.class);
    private final BluetoothAdapter mAdapter = mManager.getAdapter();
    private HIDGrpc.HIDBlockingStub mHidBlockingStub;
    private byte mReportId;
    private static final int KEYBD_RPT_ID = 1;
    private static final int KEYBD_RPT_SIZE = 9;
    private static final int MOUSE_RPT_ID = 2;
    private static final int MOUSE_RPT_SIZE = 4;
    private static final int INVALID_RPT_ID = 3;
    private static final int CONNECTION_TIMEOUT_MS = 2_000;

    private static final Duration PROTO_MODE_TIMEOUT = Duration.ofSeconds(10);

    @Rule(order = 0)
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule(order = 1)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 2)
    public final PandoraDevice mBumble = new PandoraDevice();

    private BroadcastReceiver mHidStateReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    switch (intent.getAction()) {
                        case BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED:
                            int state =
                                    intent.getIntExtra(
                                            BluetoothProfile.EXTRA_STATE, BluetoothAdapter.ERROR);
                            Log.i(TAG, "Connection state change:" + state);
                            if (state == BluetoothProfile.STATE_CONNECTED
                                    || state == BluetoothProfile.STATE_DISCONNECTED) {
                                if (mFutureConnectionIntent != null) {
                                    mFutureConnectionIntent.set(state);
                                }
                            }
                            break;
                        case BluetoothDevice.ACTION_PAIRING_REQUEST:
                            mBumble.getRemoteDevice().setPairingConfirmation(true);
                            break;
                        case BluetoothAdapter.ACTION_STATE_CHANGED:
                            int adapterState =
                                    intent.getIntExtra(
                                            BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                            Log.i(TAG, "Adapter state change:" + adapterState);
                            if (adapterState == BluetoothAdapter.STATE_ON
                                    || adapterState == BluetoothAdapter.STATE_OFF) {
                                if (mFutureAdapterStateIntent != null) {
                                    mFutureAdapterStateIntent.set(adapterState);
                                }
                            }
                            break;
                        case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                            int bondState =
                                    intent.getIntExtra(
                                            BluetoothDevice.EXTRA_BOND_STATE,
                                            BluetoothDevice.ERROR);
                            Log.i(TAG, "Bond state change:" + bondState);
                            if (bondState == BluetoothDevice.BOND_BONDED
                                    || bondState == BluetoothDevice.BOND_NONE) {
                                if (mFutureBondIntent != null) {
                                    mFutureBondIntent.set(bondState);
                                }
                            }
                            break;
                        case BluetoothHidHost.ACTION_PROTOCOL_MODE_CHANGED:
                            int protocolMode =
                                    intent.getIntExtra(
                                            BluetoothHidHost.EXTRA_PROTOCOL_MODE,
                                            BluetoothHidHost.PROTOCOL_UNSUPPORTED_MODE);
                            Log.i(TAG, "Protocol mode:" + protocolMode);
                            if (mFutureProtocolModeIntent != null) {
                                mFutureProtocolModeIntent.set(protocolMode);
                            }
                            break;
                        case BluetoothHidHost.ACTION_HANDSHAKE:
                            int handShake =
                                    intent.getIntExtra(
                                            BluetoothHidHost.EXTRA_STATUS,
                                            BluetoothHidDevice.ERROR_RSP_UNKNOWN);
                            Log.i(TAG, "Handshake status:" + handShake);
                            if (mFutureHandShakeIntent != null) {
                                mFutureHandShakeIntent.set(handShake);
                            }
                            break;
                        case BluetoothHidHost.ACTION_VIRTUAL_UNPLUG_STATUS:
                            int virtualUnplug =
                                    intent.getIntExtra(
                                            BluetoothHidHost.EXTRA_VIRTUAL_UNPLUG_STATUS,
                                            BluetoothHidHost.VIRTUAL_UNPLUG_STATUS_FAIL);
                            Log.i(TAG, "Virtual Unplug status:" + virtualUnplug);
                            if (mFutureVirtualUnplugIntent != null) {
                                mFutureVirtualUnplugIntent.set(virtualUnplug);
                            }
                            break;
                        case BluetoothHidHost.ACTION_REPORT:
                            byte[] report = intent.getByteArrayExtra(BluetoothHidHost.EXTRA_REPORT);
                            int reportSize =
                                    intent.getIntExtra(
                                            BluetoothHidHost.EXTRA_REPORT_BUFFER_SIZE, 0);
                            mReportId = report[0];
                            if (mFutureReportIntent != null) {
                                mFutureReportIntent.set((reportSize - 1));
                            }
                            break;
                        case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                            if (mAclConnectionIntent != null) {
                                mAclConnectionIntent.set(true);
                            }
                            break;
                        default:
                            break;
                    }
                }
            };

    // These callbacks run on the main thread.
    private final BluetoothProfile.ServiceListener mBluetoothProfileServiceListener =
            new BluetoothProfile.ServiceListener() {

                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    switch (profile) {
                        case BluetoothProfile.HEADSET:
                            mHfpService = (BluetoothHeadset) proxy;
                            break;
                        case BluetoothProfile.A2DP:
                            mA2dpService = (BluetoothA2dp) proxy;
                            break;
                        case BluetoothProfile.HID_HOST:
                            mHidService = (BluetoothHidHost) proxy;
                            break;
                        default:
                            break;
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {}
            };

    @Before
    public void setUp() throws Exception {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothHidHost.ACTION_PROTOCOL_MODE_CHANGED);
        filter.addAction(BluetoothHidHost.ACTION_HANDSHAKE);
        filter.addAction(BluetoothHidHost.ACTION_VIRTUAL_UNPLUG_STATUS);
        filter.addAction(BluetoothHidHost.ACTION_REPORT);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);

        mContext.registerReceiver(mHidStateReceiver, filter);
        mAdapter.getProfileProxy(
                mContext, mBluetoothProfileServiceListener, BluetoothProfile.HID_HOST);
        mAdapter.getProfileProxy(mContext, mBluetoothProfileServiceListener, BluetoothProfile.A2DP);
        mAdapter.getProfileProxy(
                mContext, mBluetoothProfileServiceListener, BluetoothProfile.HEADSET);
        mHidBlockingStub = mBumble.hidBlocking();
        mFutureConnectionIntent = SettableFuture.create();

        mDevice = mBumble.getRemoteDevice();
        mFutureBondIntent = SettableFuture.create();
        assertThat(mDevice.createBond()).isTrue();
        assertThat(mFutureBondIntent.get()).isEqualTo(BluetoothDevice.BOND_BONDED);

        if (mA2dpService != null
                && mA2dpService.getConnectionPolicy(mDevice)
                        == BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            assertThat(
                            mA2dpService.setConnectionPolicy(
                                    mDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN))
                    .isTrue();
        }
        if (mHfpService != null
                && mHfpService.getConnectionPolicy(mDevice)
                        == BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            assertThat(
                            mHfpService.setConnectionPolicy(
                                    mDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN))
                    .isTrue();
        }
        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_CONNECTED);
    }

    @After
    public void tearDown() throws Exception {

        if (mDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
            mFutureBondIntent = SettableFuture.create();
            mDevice.removeBond();
            assertThat(mFutureBondIntent.get()).isEqualTo(BluetoothDevice.BOND_NONE);
        }

        if (mDevice.isConnected()) {
            mAclConnectionIntent = SettableFuture.create();
            mDevice.disconnect();
            assertThat(mAclConnectionIntent.get()).isTrue();
        }

        mContext.unregisterReceiver(mHidStateReceiver);
    }

    /**
     * Test HID Disconnection:
     *
     * <ol>
     *   <li>1. Android tries to create bond, emitting bonding intent 4. Android confirms the
     *       pairing via pairing request intent
     *   <li>2. Bumble confirms the pairing internally
     *   <li>3. Android tries to HID connect and verifies Connection state intent
     *   <li>4. Bumble Disconnect the HID and Android verifies Connection state intent
     * </ol>
     */
    @Test
    public void disconnectHidDeviceTest() throws Exception {

        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.disconnectHost(Empty.getDefaultInstance());

        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
    }

    /**
     * Test HID Device reconnection when connection policy change:
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android verifies the connection policy
     *   <li>3. Bumble disconnect HID and Android verifies Connection state intent
     *   <li>4. Bumble reconnects and Android verifies Connection state intent
     *   <li>5. Bumble disconnect HID and Android verifies Connection state intent
     *   <li>6. Android disable connection policy
     *   <li>7. Bumble connect the HID and Android verifies Connection state intent
     *   <li>8. Android enable connection policy
     *   <li>9. Bumble disconnect HID and Android verifies Connection state intent
     *   <li>10. Bumble connect the HID and Android verifies Connection state intent
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_ALLOW_SWITCHING_HID_AND_HOGP,
        Flags.FLAG_SAVE_INITIAL_HID_CONNECTION_POLICY
    })
    public void hidReconnectionWhenConnectionPolicyChangeTest() throws Exception {

        assertThat(mHidService.getConnectionPolicy(mDevice))
                .isEqualTo(BluetoothProfile.CONNECTION_POLICY_ALLOWED);

        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.disconnectHost(Empty.getDefaultInstance());
        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED);

        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.connectHost(Empty.getDefaultInstance());
        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_CONNECTED);

        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.disconnectHost(Empty.getDefaultInstance());
        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED);

        assertThat(
                        mHidService.setConnectionPolicy(
                                mDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN))
                .isTrue();

        reconnectionFromRemoteAndVerifyDisconnectedState();

        mFutureConnectionIntent = SettableFuture.create();
        assertThat(
                        mHidService.setConnectionPolicy(
                                mDevice, BluetoothProfile.CONNECTION_POLICY_ALLOWED))
                .isTrue();
        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_CONNECTED);

        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.disconnectHost(Empty.getDefaultInstance());
        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED);

        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.connectHost(Empty.getDefaultInstance());
        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_CONNECTED);
    }

    /**
     * Test HID Device reconnection after BT restart with connection policy allowed
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android verifies the connection policy
     *   <li>3. BT restart on Android
     *   <li>4. Bumble reconnects and Android verifies Connection state intent
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_ALLOW_SWITCHING_HID_AND_HOGP,
        Flags.FLAG_SAVE_INITIAL_HID_CONNECTION_POLICY
    })
    public void hidReconnectionAfterBTrestartWithConnectionPolicyAllowedTest() throws Exception {

        assertThat(mHidService.getConnectionPolicy(mDevice))
                .isEqualTo(BluetoothProfile.CONNECTION_POLICY_ALLOWED);

        bluetoothRestart();

        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.connectHost(Empty.getDefaultInstance());
        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_CONNECTED);
    }

    /**
     * Test HID Device reconnection after BT restart with connection policy disallowed
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android verifies the connection policy
     *   <li>3. Android disable the connection policy
     *   <li>4. BT restart on Android
     *   <li>5. Bumble reconnects and Android verifies Connection state intent
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_ALLOW_SWITCHING_HID_AND_HOGP,
        Flags.FLAG_SAVE_INITIAL_HID_CONNECTION_POLICY
    })
    public void hidReconnectionAfterBTrestartWithConnectionPolicyiDisallowedTest()
            throws Exception {

        assertThat(mHidService.getConnectionPolicy(mDevice))
                .isEqualTo(BluetoothProfile.CONNECTION_POLICY_ALLOWED);

        assertThat(
                        mHidService.setConnectionPolicy(
                                mDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN))
                .isTrue();

        bluetoothRestart();
        reconnectionFromRemoteAndVerifyDisconnectedState();
    }

    /**
     * Test HID Device reconnection when device is removed
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android verifies the connection policy
     *   <li>3. Android disconnect and remove the bond
     *   <li>4. Bumble reconnects and Android verifies Connection state intent
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_ALLOW_SWITCHING_HID_AND_HOGP,
        Flags.FLAG_SAVE_INITIAL_HID_CONNECTION_POLICY
    })
    public void hidReconnectionAfterDeviceRemovedTest() throws Exception {

        assertThat(mHidService.getConnectionPolicy(mDevice))
                .isEqualTo(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.disconnectHost(Empty.getDefaultInstance());

        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED);

        mFutureBondIntent = SettableFuture.create();
        mDevice.removeBond();
        assertThat(mFutureBondIntent.get()).isEqualTo(BluetoothDevice.BOND_NONE);

        reconnectionFromRemoteAndVerifyDisconnectedState();
    }

    /**
     * Test Virtual Unplug from Hid Host
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android Virtual Unplug and verifies Bonding
     * </ol>
     */
    @Test
    public void hidVirtualUnplugFromHidHostTest() throws Exception {
        mHidService.virtualUnplug(mDevice);
        mFutureBondIntent = SettableFuture.create();
        assertThat(mFutureBondIntent.get()).isEqualTo(BluetoothDevice.BOND_NONE);
    }

    /**
     * Test Virtual Unplug from Hid Device
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Bumble Virtual Unplug and Android verifies Bonding
     * </ol>
     */
    @Test
    public void hidVirtualUnplugFromHidDeviceTest() throws Exception {
        mHidBlockingStub.virtualCableUnplugHost(Empty.getDefaultInstance());
        mFutureVirtualUnplugIntent = SettableFuture.create();
        assertThat(mFutureVirtualUnplugIntent.get())
                .isEqualTo(BluetoothHidHost.VIRTUAL_UNPLUG_STATUS_SUCCESS);
    }

    /**
     * Test Get Protocol mode
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android Gets the Protocol mode and verifies the mode
     * </ol>
     */
    @Test
    public void hidGetProtocolModeTest() throws Exception {
        mHidService.getProtocolMode(mDevice);
        mFutureProtocolModeIntent = SettableFuture.create();
        assertThat(mFutureProtocolModeIntent.get())
                .isEqualTo(BluetoothHidHost.PROTOCOL_REPORT_MODE);
    }

    /**
     * Test Set Protocol mode
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android Sets the Protocol mode and verifies the mode
     * </ol>
     */
    @Test
    @Ignore("b/349351673: sets wrong protocol mode value")
    public void hidSetProtocolModeTest() throws Exception {
        Iterator<ProtocolModeEvent> mHidProtoModeEventObserver =
                mHidBlockingStub
                        .withDeadlineAfter(PROTO_MODE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .onSetProtocolMode(Empty.getDefaultInstance());
        mHidService.setProtocolMode(mDevice, BluetoothHidHost.PROTOCOL_BOOT_MODE);
        mFutureHandShakeIntent = SettableFuture.create();
        assertThat(mFutureHandShakeIntent.get())
                .isEqualTo(BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ);
        if (mHidProtoModeEventObserver.hasNext()) {
            ProtocolModeEvent hidProtoModeEvent = mHidProtoModeEventObserver.next();
            Log.i(TAG, "Protocol mode:" + hidProtoModeEvent.getProtocolMode());
            assertThat(hidProtoModeEvent.getProtocolModeValue())
                    .isEqualTo(BluetoothHidHost.PROTOCOL_BOOT_MODE);
        }
    }

    /**
     * Test Get Report
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android get report and verifies the report
     * </ol>
     */
    @Test
    public void hidGetReportTest() throws Exception {
        // Keyboard report
        byte id = KEYBD_RPT_ID;
        mHidService.getReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, id, (int) 0);
        mFutureReportIntent = SettableFuture.create();
        assertThat(mFutureReportIntent.get()).isEqualTo(KEYBD_RPT_SIZE);
        assertThat(mReportId).isEqualTo(KEYBD_RPT_ID);

        // Mouse report
        id = MOUSE_RPT_ID;
        mHidService.getReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, id, (int) 0);
        mFutureReportIntent = SettableFuture.create();
        assertThat(mFutureReportIntent.get()).isEqualTo(MOUSE_RPT_SIZE);
        assertThat(mReportId).isEqualTo(MOUSE_RPT_ID);

        // Invalid report
        id = INVALID_RPT_ID;
        mHidService.getReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, id, (int) 0);
        mFutureHandShakeIntent = SettableFuture.create();
        assertThat(mFutureHandShakeIntent.get())
                .isEqualTo(BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
    }

    /**
     * Test Set Report
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android Set report and verifies the report
     * </ol>
     */
    @Test
    public void hidSetReportTest() throws Exception {
        Iterator<ReportEvent> mHidReportEventObserver =
                mHidBlockingStub
                        .withDeadlineAfter(PROTO_MODE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .onSetReport(Empty.getDefaultInstance());
        // Keyboard report
        String kbReportData = "010203040506070809";
        mHidService.setReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, kbReportData);
        mFutureHandShakeIntent = SettableFuture.create();
        assertThat(mFutureHandShakeIntent.get()).isEqualTo(BluetoothHidDevice.ERROR_RSP_SUCCESS);
        if (mHidReportEventObserver.hasNext()) {
            ReportEvent hidReportEvent = mHidReportEventObserver.next();
            assertThat(hidReportEvent.getReportTypeValue())
                    .isEqualTo(BluetoothHidHost.REPORT_TYPE_INPUT);
            assertThat(hidReportEvent.getReportIdValue()).isEqualTo(KEYBD_RPT_ID);
            assertThat(hidReportEvent.getReportData()).isEqualTo(kbReportData.substring(2));
        }
        // Keyboard report - Invalid param
        mHidService.setReport(
                mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, kbReportData.substring(0, 10));
        mFutureHandShakeIntent = SettableFuture.create();
        assertThat(mFutureHandShakeIntent.get())
                .isEqualTo(BluetoothHidDevice.ERROR_RSP_INVALID_PARAM);
        if (mHidReportEventObserver.hasNext()) {
            ReportEvent hidReportEvent = mHidReportEventObserver.next();
            assertThat(hidReportEvent.getReportTypeValue())
                    .isEqualTo(BluetoothHidHost.REPORT_TYPE_INPUT);
            assertThat(hidReportEvent.getReportIdValue()).isEqualTo(KEYBD_RPT_ID);
            assertThat(hidReportEvent.getReportData()).isEqualTo(kbReportData.substring(2, 10));
        }
        // Mouse report
        String mouseReportData = "02030405";
        mHidService.setReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, mouseReportData);
        mFutureHandShakeIntent = SettableFuture.create();
        assertThat(mFutureHandShakeIntent.get()).isEqualTo(BluetoothHidDevice.ERROR_RSP_SUCCESS);
        if (mHidReportEventObserver.hasNext()) {
            ReportEvent hidReportEvent = mHidReportEventObserver.next();
            assertThat(hidReportEvent.getReportTypeValue())
                    .isEqualTo(BluetoothHidHost.REPORT_TYPE_INPUT);
            assertThat(hidReportEvent.getReportIdValue()).isEqualTo(MOUSE_RPT_ID);
            assertThat(hidReportEvent.getReportData()).isEqualTo(mouseReportData.substring(2));
        }
        // Invalid report id
        String inValidReportData = "0304";
        mHidService.setReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, inValidReportData);
        mFutureHandShakeIntent = SettableFuture.create();
        assertThat(mFutureHandShakeIntent.get())
                .isEqualTo(BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
        if (mHidReportEventObserver.hasNext()) {
            ReportEvent hidReportEvent = mHidReportEventObserver.next();
            assertThat(hidReportEvent.getReportTypeValue())
                    .isEqualTo(BluetoothHidHost.REPORT_TYPE_INPUT);
            assertThat(hidReportEvent.getReportIdValue()).isEqualTo(INVALID_RPT_ID);
            assertThat(hidReportEvent.getReportData()).isEqualTo(inValidReportData.substring(2));
        }
    }

    private void reconnectionFromRemoteAndVerifyDisconnectedState() throws Exception {
        mHidBlockingStub.connectHost(Empty.getDefaultInstance());
        final CompletableFuture<Integer> future = new CompletableFuture<>();
        future.completeOnTimeout(null, CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS).join();
        assertThat(mHidService.getConnectionState(mDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
    }

    private void bluetoothRestart() throws Exception {
        mAdapter.disable();
        mFutureAdapterStateIntent = SettableFuture.create();
        assertThat(mFutureAdapterStateIntent.get()).isEqualTo(BluetoothAdapter.STATE_OFF);

        mAdapter.enable();
        mFutureAdapterStateIntent = SettableFuture.create();
        assertThat(mFutureAdapterStateIntent.get()).isEqualTo(BluetoothAdapter.STATE_ON);
    }
}
