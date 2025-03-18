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

package android.bluetooth.pairing;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.timeout;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.OobDataCallback;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.OobData;
import android.bluetooth.PandoraDevice;
import android.bluetooth.StreamObserverSpliterator;
import android.bluetooth.Utils;
import android.bluetooth.cts.EnableBluetoothRule;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;

import io.grpc.Deadline;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.hamcrest.MockitoHamcrest;
import org.mockito.stubbing.Answer;

import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.ConnectLERequest;
import pandora.HostProto.ConnectLEResponse;
import pandora.HostProto.OwnAddressType;
import pandora.HostProto.ScanRequest;
import pandora.HostProto.ScanningResponse;
import pandora.OobProto.OobDataRequest;
import pandora.OobProto.OobDataResponse;
import pandora.SecurityProto.LESecurityLevel;
import pandora.SecurityProto.SecureRequest;
import pandora.SecurityProto.SecureResponse;

import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class OobPairingTest {
    private static final String TAG = OobPairingTest.class.getSimpleName();
    private static final Duration INTENT_TIMEOUT = Duration.ofSeconds(10);
    private static final String CF_NAME = "Cuttlefish";

    private BluetoothDevice mDevice;
    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final BluetoothAdapter mAdapter =
            mContext.getSystemService(BluetoothManager.class).getAdapter();
    private OobDataResponse mRemoteOobData;
    private boolean mRemoteInitiator = false;
    private static final int TIMEOUT_ADVERTISING_MS = 1000;

    private static final int HASH_START_POSITION = 0;
    private static final int HASH_END_POSITION = 16;
    private static final int RANDOMIZER_START_POSITION = 16;
    private static final int RANDOMIZER_END_POSITION = 32;

    @Rule(order = 0)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 1)
    public final PandoraDevice mBumble = new PandoraDevice();

    @Rule(order = 3)
    public final EnableBluetoothRule enableBluetoothRule = new EnableBluetoothRule(false, true);

    @Mock private BroadcastReceiver mReceiver;
    private InOrder mInOrder = null;

    @SuppressLint("MissingPermission")
    private final Answer<Void> mIntentHandler =
            inv -> {
                Log.i(TAG, "onReceive(): intent=" + Arrays.toString(inv.getArguments()));
                Intent intent = inv.getArgument(1);
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                    BluetoothDevice device =
                            intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    int bondState =
                            intent.getIntExtra(
                                    BluetoothDevice.EXTRA_BOND_STATE, BluetoothAdapter.ERROR);
                    int prevBondState =
                            intent.getIntExtra(
                                    BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                                    BluetoothAdapter.ERROR);
                    Log.i(
                            TAG,
                            "onReceive(): device "
                                    + device
                                    + " bond state changed from "
                                    + prevBondState
                                    + " to "
                                    + bondState);
                } else {
                    Log.i(TAG, "onReceive(): unknown intent action " + action);
                }
                return null;
            };

    private OobData buildOobData() {

        byte[] confirmationHash =
                mRemoteOobData
                        .getOob()
                        .substring(HASH_START_POSITION, HASH_END_POSITION)
                        .toByteArray();
        byte[] randomizer =
                mRemoteOobData
                        .getOob()
                        .substring(RANDOMIZER_START_POSITION, RANDOMIZER_END_POSITION)
                        .toByteArray();
        byte[] address = Utils.addressBytesFromString(Utils.BUMBLE_RANDOM_ADDRESS);
        byte[] addressType = {BluetoothDevice.ADDRESS_TYPE_RANDOM};

        OobData p256 =
                new OobData.LeBuilder(
                                confirmationHash,
                                Bytes.concat(address, addressType),
                                OobData.LE_DEVICE_ROLE_BOTH_PREFER_CENTRAL)
                        .setRandomizerHash(randomizer)
                        .build();
        return p256;
    }

    private void startAdvertise() throws Exception {
        AdvertiseRequest request =
                AdvertiseRequest.newBuilder()
                        .setLegacy(true)
                        .setConnectable(true)
                        .setOwnAddressType(OwnAddressType.RANDOM)
                        .build();
        mBumble.hostBlocking().advertise(request);
    }

    private void initiatePairingFromRemote() {
        ByteString deviceAddr;
        StreamObserverSpliterator<ScanningResponse> scanningResponseObserver =
                new StreamObserverSpliterator<>();
        Deadline deadline = Deadline.after(TIMEOUT_ADVERTISING_MS, TimeUnit.MILLISECONDS);
        mBumble.host()
                .withDeadline(deadline)
                .scan(ScanRequest.newBuilder().build(), scanningResponseObserver);
        Iterator<ScanningResponse> scanningResponseIterator = scanningResponseObserver.iterator();

        while (true) {
            if (scanningResponseIterator.hasNext()) {
                ScanningResponse scanningResponse = scanningResponseIterator.next();
                // select first available device
                deviceAddr = scanningResponse.getRandom();
                break;
            }
        }

        ConnectLEResponse leConn =
                mBumble.hostBlocking()
                        .connectLE(
                                ConnectLERequest.newBuilder()
                                        .setOwnAddressType(OwnAddressType.RANDOM)
                                        .setRandom(deviceAddr)
                                        .build());
        // Start pairing from Bumble
        StreamObserverSpliterator<SecureResponse> responseObserver =
                new StreamObserverSpliterator<>();
        mBumble.security()
                .secure(
                        SecureRequest.newBuilder()
                                .setConnection(leConn.getConnection())
                                .setLe(LESecurityLevel.LE_LEVEL4)
                                .build(),
                        responseObserver);
    }

    private final OobDataCallback mGenerateOobDataCallback =
            new OobDataCallback() {
                @Override
                public void onError(int error) {
                    Log.i(TAG, "onError: " + error);
                }

                @Override
                public void onOobData(int transport, OobData data) {
                    Log.d(TAG, "OobData: " + data);
                    data.getConfirmationHash();
                    data.getRandomizerHash();
                    byte[] localData =
                            Bytes.concat(data.getConfirmationHash(), data.getRandomizerHash());
                    OobDataRequest localOobData =
                            OobDataRequest.newBuilder()
                                    .setOob(ByteString.copyFrom(localData))
                                    .build();
                    mRemoteOobData = mBumble.oobBlocking().shareOobData(localOobData);
                    OobData p256 = buildOobData();
                    if (mRemoteInitiator) {
                        initiatePairingFromRemote();
                    } else {
                        mDevice.createBondOutOfBand(BluetoothDevice.TRANSPORT_LE, null, p256);
                    }
                }
            };

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mInOrder = inOrder(mReceiver);
        doAnswer(mIntentHandler).when(mReceiver).onReceive(any(), any());
        final IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        mContext.registerReceiver(mReceiver, filter);
        mDevice =
                mAdapter.getRemoteLeDevice(
                        Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);
    }

    @After
    public void tearDown() throws Exception {
        if (mDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
            mDevice.removeBond();
            verifyIntentReceived(
                    hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                    hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                    hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));
        }
        mContext.unregisterReceiver(mReceiver);
    }

    /**
     * Test OOB pairing: Configuration: Initiator: Locali, Local OOB: No, Remote OOB: Yes ,Secure
     * Connections: Yes
     *
     * <ol>
     *   <li>1. Android gets OOB Data from Bumble.
     *   <li>2. Android creates bond with remote OOB data
     *   <li>3. Android verifies bonded intent
     * </ol>
     */
    @Test
    public void createBondWithRemoteOob() throws Exception {

        startAdvertise();
        OobDataRequest noLocalOobData =
                OobDataRequest.newBuilder().setOob(ByteString.EMPTY).build();
        mRemoteOobData = mBumble.oobBlocking().shareOobData(noLocalOobData);
        OobData p256 = buildOobData();
        mDevice.createBondOutOfBand(BluetoothDevice.TRANSPORT_LE, null, p256);
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));
    }

    /**
     * Test OOB pairing: Configuration: Initiator - Local, Local OOB - Yes, Remote OOB - Yes, Secure
     * Connections - Yes
     *
     * <ol>
     *   <li>1. Android gets OOB Data from Bumble.
     *   <li>2. Android creates bond with remote OOB data
     *   <li>3. Android verifies bonded intent
     * </ol>
     */
    @Test
    public void createBondWithRemoteAndLocalOob() throws Exception {

        startAdvertise();
        mAdapter.generateLocalOobData(
                BluetoothDevice.TRANSPORT_LE, mContext.getMainExecutor(), mGenerateOobDataCallback);
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));
    }

    /**
     * Test OOB pairing: Configuration: Initiator: Remote, Local OOB: yes , Remote OOB: No, Secure
     * Connections: Yes
     *
     * <ol>
     *   <li>1. Android generates OOB Data and share with Bumble.
     *   <li>2. Bumble creates bond
     *   <li>3. Android verifies bonded intent
     * </ol>
     */
    @Test
    public void createBondByRemoteDevicWithLocalOob() throws Exception {

        mRemoteInitiator = true;
        String deviceName = mAdapter.getName();
        // set adapter name for verification
        mAdapter.setName(CF_NAME);

        mAdapter.generateLocalOobData(
                BluetoothDevice.TRANSPORT_LE, mContext.getMainExecutor(), mGenerateOobDataCallback);
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));
        mRemoteInitiator = false;
        // revert adapter name
        mAdapter.setName(deviceName);
    }

    @SafeVarargs
    private void verifyIntentReceived(Matcher<Intent>... matchers) {
        mInOrder.verify(mReceiver, timeout(INTENT_TIMEOUT.toMillis()))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }
}
