/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.bluetooth.pairing;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.PandoraDevice;
import android.bluetooth.StreamObserverSpliterator;
import android.bluetooth.test_utils.BlockingBluetoothAdapter;
import android.bluetooth.test_utils.EnableBluetoothRule;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.flags.Flags;
import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import io.grpc.stub.StreamObserver;

import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.core.AllOf;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.hamcrest.MockitoHamcrest;

import pandora.GattProto;
import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.AdvertiseResponse;
import pandora.HostProto.OwnAddressType;
import pandora.SecurityProto.LESecurityLevel;
import pandora.SecurityProto.PairingEvent;
import pandora.SecurityProto.PairingEventAnswer;
import pandora.SecurityProto.SecureRequest;
import pandora.SecurityProto.SecureResponse;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class PairingTest {
    private static final String TAG = "PairingTest";
    private static final Duration BOND_INTENT_TIMEOUT = Duration.ofSeconds(10);

    private static final ParcelUuid BATTERY_UUID =
            ParcelUuid.fromString("0000180F-0000-1000-8000-00805F9B34FB");

    private static final ParcelUuid HOGP_UUID =
            ParcelUuid.fromString("00001812-0000-1000-8000-00805F9B34FB");

    private static final Context sTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final BluetoothAdapter sAdapter =
            sTargetContext.getSystemService(BluetoothManager.class).getAdapter();

    @Rule(order = 0)
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule(order = 1)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 2)
    public final PandoraDevice mBumble = new PandoraDevice();

    @Rule(order = 3)
    public final EnableBluetoothRule mEnableBluetoothRule =
            new EnableBluetoothRule(false /* enableTestMode */, true /* toggleBluetooth */);

    private final Map<String, Integer> mActionRegistrationCounts = new HashMap<>();
    private final StreamObserverSpliterator<PairingEvent> mPairingEventStreamObserver =
            new StreamObserverSpliterator<>();
    @Mock private BroadcastReceiver mReceiver;
    @Mock private BluetoothProfile.ServiceListener mProfileServiceListener;
    private InOrder mInOrder = null;
    private BluetoothDevice mBumbleDevice;
    private BluetoothHidHost mHidService;
    private BluetoothHeadset mHfpService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        doAnswer(
                        inv -> {
                            Log.d(
                                    TAG,
                                    "onReceive(): intent=" + Arrays.toString(inv.getArguments()));
                            Intent intent = inv.getArgument(1);
                            String action = intent.getAction();
                            if (BluetoothDevice.ACTION_UUID.equals(action)) {
                                ParcelUuid[] uuids =
                                        intent.getParcelableArrayExtra(
                                                BluetoothDevice.EXTRA_UUID, ParcelUuid.class);
                                Log.d(TAG, "onReceive(): UUID=" + Arrays.toString(uuids));
                            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                                int bondState =
                                        intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                                Log.d(TAG, "onReceive(): bondState=" + bondState);
                            }
                            return null;
                        })
                .when(mReceiver)
                .onReceive(any(), any());

        mInOrder = inOrder(mReceiver);

        // Get profile proxies
        mHidService = (BluetoothHidHost) getProfileProxy(BluetoothProfile.HID_HOST);
        mHfpService = (BluetoothHeadset) getProfileProxy(BluetoothProfile.HEADSET);

        mBumbleDevice = mBumble.getRemoteDevice();
        Set<BluetoothDevice> bondedDevices = sAdapter.getBondedDevices();
        if (bondedDevices.contains(mBumbleDevice)) {
            removeBond(mBumbleDevice);
        }
    }

    @After
    public void tearDown() throws Exception {
        Set<BluetoothDevice> bondedDevices = sAdapter.getBondedDevices();
        if (bondedDevices.contains(mBumbleDevice)) {
            removeBond(mBumbleDevice);
        }
        mBumbleDevice = null;
        if (getTotalActionRegistrationCounts() > 0) {
            sTargetContext.unregisterReceiver(mReceiver);
            mActionRegistrationCounts.clear();
        }
    }

    /**
     * Test a simple BR/EDR just works pairing flow in the follow steps:
     *
     * <ol>
     *   <li>1. Bumble resets, enables inquiry and page scan, and sets I/O cap to no display no
     *       input
     *   <li>2. Android tries to create bond via MAC address, emitting bonding intent
     *   <li>3. Android confirms the pairing via pairing request intent
     *   <li>4. Bumble confirms the pairing internally (optional, added only for test confirmation)
     *   <li>5. Android verifies bonded intent
     * </ol>
     */
    @Test
    public void testBrEdrPairing_phoneInitiatedBrEdrInquiryOnlyJustWorks() {
        registerIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED, BluetoothDevice.ACTION_PAIRING_REQUEST);

        StreamObserver<PairingEventAnswer> pairingEventAnswerObserver =
                mBumble.security()
                        .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .onPairing(mPairingEventStreamObserver);

        assertThat(mBumbleDevice.createBond()).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));
        mBumbleDevice.setPairingConfirmation(true);

        PairingEvent pairingEvent = mPairingEventStreamObserver.iterator().next();
        assertThat(pairingEvent.hasJustWorks()).isTrue();
        pairingEventAnswerObserver.onNext(
                PairingEventAnswer.newBuilder().setEvent(pairingEvent).setConfirm(true).build());

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        verifyNoMoreInteractions(mReceiver);

        unregisterIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED, BluetoothDevice.ACTION_PAIRING_REQUEST);
    }

    /**
     * Test a simple BR/EDR just works pairing flow in the follow steps:
     *
     * <ol>
     *   <li>1. Bumble resets, enables inquiry and page scan, and sets I/O cap to no display no
     *       input
     *   <li>2. Android tries to create bond via MAC address, emitting bonding intent
     *   <li>3. Android confirms the pairing via pairing request intent
     *   <li>4. Android cancel the pairing of unrelated device. verify current pairing is continued
     *       and success.
     *   <li>5. Bumble confirms the pairing internally (optional, added only for test confirmation)
     *   <li>6. Android verifies bonded intent
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_IGNORE_UNRELATED_CANCEL_BOND})
    public void testBrEdrPairing_cancelBond_forUnrelatedDevice() {
        registerIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED, BluetoothDevice.ACTION_PAIRING_REQUEST);

        StreamObserver<PairingEventAnswer> pairingEventAnswerObserver =
                mBumble.security()
                        .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .onPairing(mPairingEventStreamObserver);

        assertThat(mBumbleDevice.createBond()).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));
        // cancel bonding for unrelated device and verify current pairing continued and success.
        BluetoothDevice fakeUnintendedDevice = sAdapter.getRemoteDevice("51:F7:A8:75:17:01");
        assertThat(fakeUnintendedDevice.cancelBondProcess()).isTrue();
        mBumbleDevice.setPairingConfirmation(true);

        PairingEvent pairingEvent = mPairingEventStreamObserver.iterator().next();
        assertThat(pairingEvent.hasJustWorks()).isTrue();
        pairingEventAnswerObserver.onNext(
                PairingEventAnswer.newBuilder().setEvent(pairingEvent).setConfirm(true).build());

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        verifyNoMoreInteractions(mReceiver);

        unregisterIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED, BluetoothDevice.ACTION_PAIRING_REQUEST);
    }

    /**
     * Test a simple BR/EDR just works pairing flow in the follow steps:
     *
     * <ol>
     *   <li>1. Bumble resets, enables inquiry and page scan, and sets I/O cap to no display no
     *       input
     *   <li>2. Android connects to Bumble via its MAC address
     *   <li>3. Android tries to create bond, emitting bonding intent
     *   <li>4. Android confirms the pairing via pairing request intent
     *   <li>5. Bumble confirms the pairing internally (optional, added only for test confirmation)
     *   <li>6. Android verifies bonded intent
     * </ol>
     */
    @Test
    public void testBrEdrPairing_phoneInitiatedBrEdrInquiryOnlyJustWorksWhileSdpConnected() {
        registerIntentActions(
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_PAIRING_REQUEST);

        StreamObserver<PairingEventAnswer> pairingEventAnswerObserver =
                mBumble.security()
                        .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .onPairing(mPairingEventStreamObserver);

        // Start SDP.  This will create an ACL connection before the bonding starts.
        assertThat(mBumbleDevice.fetchUuidsWithSdp(BluetoothDevice.TRANSPORT_BREDR)).isTrue();

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        assertThat(mBumbleDevice.createBond()).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));
        mBumbleDevice.setPairingConfirmation(true);

        PairingEvent pairingEvent = mPairingEventStreamObserver.iterator().next();
        assertThat(pairingEvent.hasJustWorks()).isTrue();
        pairingEventAnswerObserver.onNext(
                PairingEventAnswer.newBuilder().setEvent(pairingEvent).setConfirm(true).build());

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        verifyNoMoreInteractions(mReceiver);

        unregisterIntentActions(
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_PAIRING_REQUEST);
    }

    /**
     * Test if parallel GATT service discovery interrupts cancelling LE pairing
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     *   <li>Bumble has GATT services in addition to GAP and GATT services
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is discoverable and connectable over LE
     *   <li>Android connects to Bumble over LE
     *   <li>Android starts GATT service discovery
     *   <li>Bumble initiates pairing
     *   <li>Android does not confirm the pairing immediately
     *   <li>Service discovery completes
     *   <li>Android cancels the pairing
     * </ol>
     *
     * <p>Expectation: Pairing gets cancelled instead of getting timed out
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_PREVENT_DUPLICATE_UUID_INTENT})
    public void testCancelBondLe_WithGattServiceDiscovery() {
        registerIntentActions(BluetoothDevice.ACTION_BOND_STATE_CHANGED);

        // Outgoing GATT service discovery and incoming LE pairing in parallel
        StreamObserverSpliterator<SecureResponse> responseObserver =
                helper_OutgoingGattServiceDiscoveryWithIncomingLePairing();

        // Cancel pairing from Android
        assertThat(mBumbleDevice.cancelBondProcess()).isTrue();

        SecureResponse secureResponse = responseObserver.iterator().next();
        assertThat(secureResponse.hasPairingFailure()).isTrue();

        // Pairing should be cancelled in a moment instead of timing out in 30
        // seconds
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));

        verifyNoMoreInteractions(mReceiver);

        unregisterIntentActions(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
    }

    /**
     * Test if parallel GATT service discovery interrupts the LE pairing
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     *   <li>Bumble has GATT services in addition to GAP and GATT services
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is discoverable and connectable over LE
     *   <li>Android connects to Bumble over LE
     *   <li>Android starts GATT service discovery
     *   <li>Bumble starts pairing
     *   <li>Service discovery completes
     *   <li>Android does confirms the pairing
     *   <li>Pairing is successful
     * </ol>
     *
     * <p>Expectation: Pairing succeeds
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_PREVENT_DUPLICATE_UUID_INTENT})
    public void testBondLe_WithGattServiceDiscovery() {
        registerIntentActions(BluetoothDevice.ACTION_BOND_STATE_CHANGED);

        // Outgoing GATT service discovery and incoming LE pairing in parallel
        StreamObserverSpliterator<SecureResponse> responseObserver =
                helper_OutgoingGattServiceDiscoveryWithIncomingLePairing();

        // Approve pairing from Android
        assertThat(mBumbleDevice.setPairingConfirmation(true)).isTrue();

        SecureResponse secureResponse = responseObserver.iterator().next();
        assertThat(secureResponse.hasSuccess()).isTrue();

        // Ensure that pairing succeeds
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        verifyNoMoreInteractions(mReceiver);

        unregisterIntentActions(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
    }

    /**
     * Test if bonded LE device can reconnect after BT restart
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is discoverable and connectable over LE
     *   <li>Android pairs with Bumble over LE
     *   <li>Android restarts
     *   <li>Bumble is connectable over LE
     *   <li>Android reconnects to Bumble successfully and re-encrypts the link
     * </ol>
     *
     * <p>Expectation: Pairing succeeds
     */
    @Test
    public void testBondLe_Reconnect() {
        registerIntentActions(BluetoothDevice.ACTION_ACL_CONNECTED);

        testStep_BondLe();
        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);

        testStep_restartBt();

        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);
        mBumble.gattBlocking()
                .registerService(
                        GattProto.RegisterServiceRequest.newBuilder()
                                .setService(
                                        GattProto.GattServiceParams.newBuilder()
                                                .setUuid(HOGP_UUID.toString())
                                                .build())
                                .build());

        mBumble.hostBlocking()
                .advertise(
                        AdvertiseRequest.newBuilder()
                                .setLegacy(true)
                                .setConnectable(true)
                                .setOwnAddressType(OwnAddressType.PUBLIC)
                                .build());

        assertThat(mBumbleDevice.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS);
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        verifyNoMoreInteractions(mReceiver);
        unregisterIntentActions(BluetoothDevice.ACTION_ACL_CONNECTED);
    }

    private void testStep_BondLe() {
        registerIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_PAIRING_REQUEST);

        mBumble.gattBlocking()
                .registerService(
                        GattProto.RegisterServiceRequest.newBuilder()
                                .setService(
                                        GattProto.GattServiceParams.newBuilder()
                                                .setUuid(BATTERY_UUID.toString())
                                                .build())
                                .build());
        mBumble.gattBlocking()
                .registerService(
                        GattProto.RegisterServiceRequest.newBuilder()
                                .setService(
                                        GattProto.GattServiceParams.newBuilder()
                                                .setUuid(HOGP_UUID.toString())
                                                .build())
                                .build());

        mBumble.hostBlocking()
                .advertise(
                        AdvertiseRequest.newBuilder()
                                .setLegacy(true)
                                .setConnectable(true)
                                .setOwnAddressType(OwnAddressType.PUBLIC)
                                .build());

        StreamObserver<PairingEventAnswer> pairingEventAnswerObserver =
                mBumble.security()
                        .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .onPairing(mPairingEventStreamObserver);

        assertThat(mBumbleDevice.createBond(BluetoothDevice.TRANSPORT_LE)).isTrue();

        verifyIntentReceivedUnordered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE));
        verifyIntentReceivedUnordered(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));

        // Approve pairing from Android
        assertThat(mBumbleDevice.setPairingConfirmation(true)).isTrue();

        PairingEvent pairingEvent = mPairingEventStreamObserver.iterator().next();
        assertThat(pairingEvent.hasJustWorks()).isTrue();
        pairingEventAnswerObserver.onNext(
                PairingEventAnswer.newBuilder().setEvent(pairingEvent).setConfirm(true).build());

        // Ensure that pairing succeeds
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        unregisterIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_PAIRING_REQUEST);
    }

    /**
     * Test if bonded BR/EDR device can reconnect after BT restart
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is discoverable and connectable over BR/EDR
     *   <li>Android pairs with Bumble over BR/EDR
     *   <li>Android restarts
     *   <li>Bumble is connectable over BR/EDR
     *   <li>Android reconnects to Bumble successfully and re-encrypts the link
     * </ol>
     *
     * <p>Expectation: Pairing succeeds
     */
    @Test
    public void testBondBredr_Reconnect() {
        registerIntentActions(BluetoothDevice.ACTION_ACL_CONNECTED);

        testStep_BondBredr();
        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);

        testStep_restartBt();

        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);

        assertThat(mBumbleDevice.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS);
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        verifyNoMoreInteractions(mReceiver);
        unregisterIntentActions(BluetoothDevice.ACTION_ACL_CONNECTED);
    }

    /**
     * Test removeDevice API when connected over LE
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is connectable over LE
     *   <li>Android pairs with Bumble over LE
     *   <li>Call BluetoothDevice.removeBond() API
     *   <li>Android disconnects the ACL and removes the bond
     * </ol>
     *
     * <p>Expectation: Bumble is not bonded
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_WAIT_FOR_DISCONNECT_BEFORE_UNBOND})
    public void testRemoveBondLe_WhenConnected() {
        registerIntentActions(
                BluetoothDevice.ACTION_ACL_DISCONNECTED, BluetoothDevice.ACTION_BOND_STATE_CHANGED);

        testStep_BondLe();
        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);

        assertThat(mBumbleDevice.removeBond()).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));

        assertThat(sAdapter.getBondedDevices()).doesNotContain(mBumbleDevice);

        verifyNoMoreInteractions(mReceiver);
        unregisterIntentActions(
                BluetoothDevice.ACTION_ACL_DISCONNECTED, BluetoothDevice.ACTION_BOND_STATE_CHANGED);
    }

    /**
     * Test removeDevice API when connected over BR/EDR
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is connectable over BR/EDR
     *   <li>Android pairs with Bumble over BR/EDR
     *   <li>Call BluetoothDevice.removeBond() API
     *   <li>Android disconnects the ACL and removes the bond
     * </ol>
     *
     * <p>Expectation: Bumble is not bonded
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_WAIT_FOR_DISCONNECT_BEFORE_UNBOND})
    public void testRemoveBondBredr_WhenConnected() {
        registerIntentActions(
                BluetoothDevice.ACTION_ACL_DISCONNECTED, BluetoothDevice.ACTION_BOND_STATE_CHANGED);

        testStep_BondBredr();
        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);

        assertThat(mBumbleDevice.removeBond()).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));

        assertThat(sAdapter.getBondedDevices()).doesNotContain(mBumbleDevice);

        verifyNoMoreInteractions(mReceiver);
        unregisterIntentActions(
                BluetoothDevice.ACTION_ACL_DISCONNECTED, BluetoothDevice.ACTION_BOND_STATE_CHANGED);
    }

    /**
     * Test removeDevice API when not connected
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     *   <li>Bumble supports HOGP
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is connectable over LE
     *   <li>Android pairs with Bumble over LE
     *   <li>Disconnect the Bumble
     *   <li>Call BluetoothDevice.removeBond() API
     *   <li>Removes the bond
     * </ol>
     *
     * <p>Expectation: Bumble is not bonded
     */
    @Test
    public void testRemoveBondLe_WhenDisconnected() {
        registerIntentActions(
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);

        testStep_BondLe();
        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);

        // Wait for profiles to get connected
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothHidHost.EXTRA_STATE, BluetoothHidHost.STATE_CONNECTING));
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothHidHost.EXTRA_STATE, BluetoothHidHost.STATE_CONNECTED));

        // Disconnect Bumble
        assertThat(mBumbleDevice.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS);
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothHidHost.EXTRA_STATE, BluetoothHidHost.STATE_DISCONNECTING));
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothHidHost.EXTRA_STATE, BluetoothHidHost.STATE_DISCONNECTED));

        // Wait for ACL to get disconnected
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        // Remove bond
        assertThat(mBumbleDevice.removeBond()).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));
        assertThat(sAdapter.getBondedDevices()).doesNotContain(mBumbleDevice);

        verifyNoMoreInteractions(mReceiver);
        unregisterIntentActions(
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);
    }

    /**
     * Test removeDevice API when not connected
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     *   <li>Bumble supports HID device role
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is connectable over BR/EDR
     *   <li>Android pairs with Bumble over BR/EDR
     *   <li>Disconnect the Bumble
     *   <li>Call BluetoothDevice.removeBond() API
     *   <li>Removes the bond
     * </ol>
     *
     * <p>Expectation: Bumble is not bonded
     */
    @Test
    public void testRemoveBondBredr_WhenDisconnected() {
        registerIntentActions(
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);

        // Disable all profiles other than A2DP as profile connections take too long
        assertThat(
                        mHfpService.setConnectionPolicy(
                                mBumbleDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN))
                .isTrue();
        assertThat(
                        mHidService.setConnectionPolicy(
                                mBumbleDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN))
                .isTrue();

        testStep_BondBredr();
        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);

        // Wait for profiles to get connected
        verifyIntentReceived(
                hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_CONNECTING),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        verifyIntentReceived(
                hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        // Disconnect all profiles
        assertThat(mBumbleDevice.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS);
        verifyIntentReceived(
                hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_DISCONNECTING),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        verifyIntentReceived(
                hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        // Wait for the ACL to get disconnected
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        // Remove bond
        assertThat(mBumbleDevice.removeBond()).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));
        assertThat(sAdapter.getBondedDevices()).doesNotContain(mBumbleDevice);

        verifyNoMoreInteractions(mReceiver);
        unregisterIntentActions(
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
    }

    private void testStep_BondBredr() {
        registerIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_PAIRING_REQUEST);

        StreamObserver<PairingEventAnswer> pairingEventAnswerObserver =
                mBumble.security()
                        .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .onPairing(mPairingEventStreamObserver);

        assertThat(mBumbleDevice.createBond(BluetoothDevice.TRANSPORT_BREDR)).isTrue();

        verifyIntentReceivedUnordered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR));
        verifyIntentReceivedUnordered(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));

        // Approve pairing from Android
        assertThat(mBumbleDevice.setPairingConfirmation(true)).isTrue();

        PairingEvent pairingEvent = mPairingEventStreamObserver.iterator().next();
        assertThat(pairingEvent.hasJustWorks()).isTrue();
        pairingEventAnswerObserver.onNext(
                PairingEventAnswer.newBuilder().setEvent(pairingEvent).setConfirm(true).build());

        // Ensure that pairing succeeds
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        unregisterIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_PAIRING_REQUEST);
    }

    private void testStep_restartBt() {
        assertThat(BlockingBluetoothAdapter.disable(true)).isTrue();
        assertThat(BlockingBluetoothAdapter.enable()).isTrue();
    }

    /* Starts outgoing GATT service discovery and incoming LE pairing in parallel */
    private StreamObserverSpliterator<SecureResponse>
            helper_OutgoingGattServiceDiscoveryWithIncomingLePairing() {
        // Setup intent filters
        registerIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_PAIRING_REQUEST,
                BluetoothDevice.ACTION_UUID,
                BluetoothDevice.ACTION_ACL_CONNECTED);

        // Register lots of interesting GATT services on Bumble
        for (int i = 0; i < 40; i++) {
            mBumble.gattBlocking()
                    .registerService(
                            GattProto.RegisterServiceRequest.newBuilder()
                                    .setService(
                                            GattProto.GattServiceParams.newBuilder()
                                                    .setUuid(BATTERY_UUID.toString())
                                                    .build())
                                    .build());
        }

        // Start GATT service discovery, this will establish LE ACL
        assertThat(mBumbleDevice.fetchUuidsWithSdp(BluetoothDevice.TRANSPORT_LE)).isTrue();

        // Make Bumble connectable
        AdvertiseResponse advertiseResponse =
                mBumble.hostBlocking()
                        .advertise(
                                AdvertiseRequest.newBuilder()
                                        .setLegacy(true)
                                        .setConnectable(true)
                                        .setOwnAddressType(OwnAddressType.PUBLIC)
                                        .build())
                        .next();

        // Todo: Unexpected empty ACTION_UUID intent is generated
        verifyIntentReceivedUnordered(hasAction(BluetoothDevice.ACTION_UUID));

        // Wait for connection on Android
        verifyIntentReceivedUnordered(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE));

        // Start pairing from Bumble
        StreamObserverSpliterator<SecureResponse> responseObserver =
                new StreamObserverSpliterator<>();
        mBumble.security()
                .secure(
                        SecureRequest.newBuilder()
                                .setConnection(advertiseResponse.getConnection())
                                .setLe(LESecurityLevel.LE_LEVEL3)
                                .build(),
                        responseObserver);

        // Wait for incoming pairing notification on Android
        // TODO: Order of these events is not deterministic
        verifyIntentReceivedUnordered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));
        verifyIntentReceivedUnordered(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));

        // Allow participating in the incoming pairing on Android
        assertThat(mBumbleDevice.setPairingConfirmation(true)).isTrue();

        // Wait for pairing approval notification on Android
        verifyIntentReceivedUnordered(
                2,
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));

        // Wait for GATT service discovery to complete on Android
        // so that ACTION_UUID is received here.
        verifyIntentReceivedUnordered(
                hasAction(BluetoothDevice.ACTION_UUID),
                hasExtra(BluetoothDevice.EXTRA_UUID, Matchers.hasItemInArray(BATTERY_UUID)));

        unregisterIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_PAIRING_REQUEST,
                BluetoothDevice.ACTION_UUID,
                BluetoothDevice.ACTION_ACL_CONNECTED);

        return responseObserver;
    }

    private void removeBond(BluetoothDevice device) {
        registerIntentActions(BluetoothDevice.ACTION_BOND_STATE_CHANGED);

        assertThat(device.removeBond()).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));

        unregisterIntentActions(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
    }

    @SafeVarargs
    private void verifyIntentReceived(Matcher<Intent>... matchers) {
        mInOrder.verify(mReceiver, timeout(BOND_INTENT_TIMEOUT.toMillis()))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }

    @SafeVarargs
    private void verifyIntentReceivedUnordered(int num, Matcher<Intent>... matchers) {
        verify(mReceiver, timeout(BOND_INTENT_TIMEOUT.toMillis()).times(num))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }

    @SafeVarargs
    private void verifyIntentReceivedUnordered(Matcher<Intent>... matchers) {
        verifyIntentReceivedUnordered(1, matchers);
    }

    /**
     * Helper function to add reference count to registered intent actions
     *
     * @param actions new intent actions to add. If the array is empty, it is a no-op.
     */
    private void registerIntentActions(String... actions) {
        if (actions.length == 0) {
            return;
        }
        if (getTotalActionRegistrationCounts() > 0) {
            Log.d(TAG, "registerIntentActions(): unregister ALL intents");
            sTargetContext.unregisterReceiver(mReceiver);
        }
        for (String action : actions) {
            mActionRegistrationCounts.merge(action, 1, Integer::sum);
        }
        IntentFilter filter = new IntentFilter();
        mActionRegistrationCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .forEach(
                        entry -> {
                            Log.d(
                                    TAG,
                                    "registerIntentActions(): Registering action = "
                                            + entry.getKey());
                            filter.addAction(entry.getKey());
                        });
        sTargetContext.registerReceiver(mReceiver, filter);
    }

    /**
     * Helper function to reduce reference count to registered intent actions If total reference
     * count is zero after removal, no broadcast receiver will be registered.
     *
     * @param actions intent actions to be removed. If some action is not registered, it is no-op
     *     for that action. If the actions array is empty, it is also a no-op.
     */
    private void unregisterIntentActions(String... actions) {
        if (actions.length == 0) {
            return;
        }
        if (getTotalActionRegistrationCounts() <= 0) {
            return;
        }
        Log.d(TAG, "unregisterIntentActions(): unregister ALL intents");
        sTargetContext.unregisterReceiver(mReceiver);
        for (String action : actions) {
            if (!mActionRegistrationCounts.containsKey(action)) {
                continue;
            }
            mActionRegistrationCounts.put(action, mActionRegistrationCounts.get(action) - 1);
            if (mActionRegistrationCounts.get(action) <= 0) {
                mActionRegistrationCounts.remove(action);
            }
        }
        if (getTotalActionRegistrationCounts() > 0) {
            IntentFilter filter = new IntentFilter();
            mActionRegistrationCounts.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .forEach(
                            entry -> {
                                Log.d(
                                        TAG,
                                        "unregisterIntentActions(): Registering action = "
                                                + entry.getKey());
                                filter.addAction(entry.getKey());
                            });
            sTargetContext.registerReceiver(mReceiver, filter);
        }
    }

    /**
     * Get sum of reference count from all registered actions
     *
     * @return sum of reference count from all registered actions
     */
    private int getTotalActionRegistrationCounts() {
        return mActionRegistrationCounts.values().stream().reduce(0, Integer::sum);
    }

    private BluetoothProfile getProfileProxy(int profile) {
        sAdapter.getProfileProxy(sTargetContext, mProfileServiceListener, profile);
        ArgumentCaptor<BluetoothProfile> proxyCaptor =
                ArgumentCaptor.forClass(BluetoothProfile.class);
        verify(mProfileServiceListener, timeout(BOND_INTENT_TIMEOUT.toMillis()))
                .onServiceConnected(eq(profile), proxyCaptor.capture());
        return proxyCaptor.getValue();
    }
}
