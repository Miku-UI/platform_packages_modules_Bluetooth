/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.bluetooth.bass_client;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.notNull;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothLeAudioCodecConfigMetadata;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcastAssistant;
import android.bluetooth.BluetoothLeBroadcastChannel;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastReceiveState;
import android.bluetooth.BluetoothLeBroadcastSubgroup;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothLeBroadcastAssistantCallback;
import android.bluetooth.le.PeriodicAdvertisingReport;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.le_audio.LeAudioService;

import com.google.common.truth.Expect;

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
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

/** Tests for {@link BassClientService} */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class BassClientServiceTest {
    private final String mFlagDexmarker = System.getProperty("dexmaker.share_classloader", "false");

    private static final int TIMEOUT_MS = 1000;

    private static final ParcelUuid[] FAKE_SERVICE_UUIDS = {BluetoothUuid.BASS};

    private static final String TEST_MAC_ADDRESS = "00:11:22:33:44:55";
    private static final String TEST_MAC_ADDRESS_2 = "00:11:22:33:44:66";
    private static final int TEST_BROADCAST_ID = 42;
    private static final int TEST_ADVERTISER_SID = 1234;
    private static final int TEST_PA_SYNC_INTERVAL = 100;
    private static final int TEST_PRESENTATION_DELAY_MS = 345;
    private static final int TEST_RSSI = -40;

    private static final int TEST_SYNC_HANDLE = 0;

    private static final int TEST_CODEC_ID = 42;
    private static final int TEST_CHANNEL_INDEX = 56;

    // For BluetoothLeAudioCodecConfigMetadata
    private static final long TEST_AUDIO_LOCATION_FRONT_LEFT = 0x01;
    private static final long TEST_AUDIO_LOCATION_FRONT_RIGHT = 0x02;

    // For BluetoothLeAudioContentMetadata
    private static final String TEST_PROGRAM_INFO = "Test";
    // German language code in ISO 639-3
    private static final String TEST_LANGUAGE = "deu";
    private static final int TEST_SOURCE_ID = 10;
    private static final int TEST_NUM_SOURCES = 2;

    private final HashMap<BluetoothDevice, BassClientStateMachine> mStateMachines = new HashMap<>();
    private HashMap<BluetoothDevice, LinkedBlockingQueue<Intent>> mIntentQueue;

    private Context mTargetContext;
    private BassClientService mBassClientService;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothDevice mCurrentDevice;
    private BluetoothDevice mCurrentDevice1;
    private BassIntentReceiver mBassIntentReceiver;

    private BluetoothDevice mSourceDevice;
    private BluetoothDevice mSourceDevice2;
    private ArgumentCaptor<ScanCallback> mCallbackCaptor;

    private InOrder mInOrderMethodProxy;

    @Spy private BassObjectsFactory mObjectsFactory = BassObjectsFactory.getInstance();
    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();
    @Rule public Expect expect = Expect.create();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private AdapterService mAdapterService;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private BluetoothLeScannerWrapper mBluetoothLeScannerWrapper;
    @Mock private ServiceFactory mServiceFactory;
    @Mock private CsipSetCoordinatorService mCsipService;
    @Mock private LeAudioService mLeAudioService;
    @Mock private IBluetoothLeBroadcastAssistantCallback mCallback;
    @Mock private Binder mBinder;
    @Mock private BluetoothMethodProxy mMethodProxy;

    BluetoothLeBroadcastSubgroup createBroadcastSubgroup() {
        BluetoothLeAudioCodecConfigMetadata codecMetadata =
                new BluetoothLeAudioCodecConfigMetadata.Builder()
                        .setAudioLocation(TEST_AUDIO_LOCATION_FRONT_LEFT)
                        .build();
        BluetoothLeAudioContentMetadata contentMetadata =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setProgramInfo(TEST_PROGRAM_INFO)
                        .setLanguage(TEST_LANGUAGE)
                        .build();
        BluetoothLeBroadcastSubgroup.Builder builder =
                new BluetoothLeBroadcastSubgroup.Builder()
                        .setCodecId(TEST_CODEC_ID)
                        .setCodecSpecificConfig(codecMetadata)
                        .setContentMetadata(contentMetadata);

        BluetoothLeAudioCodecConfigMetadata channelCodecMetadata =
                new BluetoothLeAudioCodecConfigMetadata.Builder()
                        .setAudioLocation(TEST_AUDIO_LOCATION_FRONT_RIGHT)
                        .build();

        // builder expect at least one channel
        BluetoothLeBroadcastChannel channel =
                new BluetoothLeBroadcastChannel.Builder()
                        .setSelected(true)
                        .setChannelIndex(TEST_CHANNEL_INDEX)
                        .setCodecMetadata(channelCodecMetadata)
                        .build();
        builder.addChannel(channel);
        return builder.build();
    }

    BluetoothLeBroadcastMetadata createBroadcastMetadata(int broadcastId) {
        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setEncrypted(false)
                        .setSourceDevice(mSourceDevice, BluetoothDevice.ADDRESS_TYPE_RANDOM)
                        .setSourceAdvertisingSid(TEST_ADVERTISER_SID)
                        .setBroadcastId(broadcastId)
                        .setBroadcastCode(null)
                        .setPaSyncInterval(TEST_PA_SYNC_INTERVAL)
                        .setPresentationDelayMicros(TEST_PRESENTATION_DELAY_MS);
        // builder expect at least one subgroup
        builder.addSubgroup(createBroadcastSubgroup());
        return builder.build();
    }

    BluetoothLeBroadcastMetadata createEmptyBroadcastMetadata() {
        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setEncrypted(false)
                        .setSourceDevice(
                                mBluetoothAdapter.getRemoteLeDevice(
                                        "00:00:00:00:00:00", BluetoothDevice.ADDRESS_TYPE_RANDOM),
                                BluetoothDevice.ADDRESS_TYPE_RANDOM)
                        .setSourceAdvertisingSid(TEST_ADVERTISER_SID)
                        .setBroadcastId(0)
                        .setBroadcastCode(null)
                        .setPaSyncInterval(TEST_PA_SYNC_INTERVAL)
                        .setPresentationDelayMicros(TEST_PRESENTATION_DELAY_MS);
        // builder expect at least one subgroup
        builder.addSubgroup(createBroadcastSubgroup());
        return builder.build();
    }

    @Before
    public void setUp() throws Exception {
        if (!mFlagDexmarker.equals("true")) {
            System.setProperty("dexmaker.share_classloader", "true");
        }

        mTargetContext = InstrumentationRegistry.getTargetContext();
        TestUtils.setAdapterService(mAdapterService);
        BassObjectsFactory.setInstanceForTesting(mObjectsFactory);
        BluetoothMethodProxy.setInstanceForTesting(mMethodProxy);

        doReturn(true).when(mMethodProxy).initializePeriodicAdvertisingManagerOnDefaultAdapter();
        doNothing()
                .when(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        doNothing().when(mMethodProxy).periodicAdvertisingManagerUnregisterSync(any(), any());

        doReturn(new ParcelUuid[] {BluetoothUuid.BASS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        // This line must be called to make sure relevant objects are initialized properly
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // Mock methods in AdapterService
        doReturn(FAKE_SERVICE_UUIDS)
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        doReturn(BluetoothDevice.BOND_BONDED)
                .when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        doAnswer(
                        invocation -> {
                            Set<BluetoothDevice> keys = mStateMachines.keySet();
                            return keys.toArray(new BluetoothDevice[keys.size()]);
                        })
                .when(mAdapterService)
                .getBondedDevices();

        // Mock methods in BassObjectsFactory
        doAnswer(
                        invocation -> {
                            assertThat(mCurrentDevice).isNotNull();
                            final BassClientStateMachine stateMachine =
                                    mock(BassClientStateMachine.class);
                            doReturn(new ArrayList<>()).when(stateMachine).getAllSources();
                            doReturn(TEST_NUM_SOURCES)
                                    .when(stateMachine)
                                    .getMaximumSourceCapacity();
                            doReturn((BluetoothDevice) invocation.getArgument(0))
                                    .when(stateMachine)
                                    .getDevice();
                            mStateMachines.put(
                                    (BluetoothDevice) invocation.getArgument(0), stateMachine);
                            return stateMachine;
                        })
                .when(mObjectsFactory)
                .makeStateMachine(any(), any(), any(), any());
        doReturn(mBluetoothLeScannerWrapper)
                .when(mObjectsFactory)
                .getBluetoothLeScannerWrapper(any());

        mBassClientService = new BassClientService(mTargetContext);
        mBassClientService.start();
        mBassClientService.setAvailable(true);

        mBassClientService.mServiceFactory = mServiceFactory;
        doReturn(mCsipService).when(mServiceFactory).getCsipSetCoordinatorService();
        doReturn(mLeAudioService).when(mServiceFactory).getLeAudioService();

        when(mCallback.asBinder()).thenReturn(mBinder);
        mBassClientService.registerCallback(mCallback);

        mIntentQueue = new HashMap<>();
        mIntentQueue.put(mCurrentDevice, new LinkedBlockingQueue<>());
        mIntentQueue.put(mCurrentDevice1, new LinkedBlockingQueue<>());

        // Set up the Connection State Changed receiver
        IntentFilter filter = new IntentFilter();
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        filter.addAction(BluetoothLeBroadcastAssistant.ACTION_CONNECTION_STATE_CHANGED);

        mBassIntentReceiver = new BassIntentReceiver();
        mTargetContext.registerReceiver(mBassIntentReceiver, filter, Context.RECEIVER_EXPORTED);

        mSourceDevice =
                mBluetoothAdapter.getRemoteLeDevice(
                        TEST_MAC_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);
        mSourceDevice2 =
                mBluetoothAdapter.getRemoteLeDevice(
                        TEST_MAC_ADDRESS_2, BluetoothDevice.ADDRESS_TYPE_RANDOM);

        mInOrderMethodProxy = inOrder(mMethodProxy);
    }

    @After
    public void tearDown() throws Exception {
        if (mBassClientService == null) {
            return;
        }
        mBassClientService.unregisterCallback(mCallback);

        mBassClientService.stop();
        mBassClientService = BassClientService.getBassClientService();
        assertThat(mBassClientService).isNull();
        mStateMachines.clear();
        mCurrentDevice = null;
        mCurrentDevice1 = null;
        mSourceDevice = null;
        mSourceDevice2 = null;
        mTargetContext.unregisterReceiver(mBassIntentReceiver);
        mIntentQueue.clear();
        BassObjectsFactory.setInstanceForTesting(null);
        TestUtils.clearAdapterService(mAdapterService);

        if (!mFlagDexmarker.equals("true")) {
            System.setProperty("dexmaker.share_classloader", mFlagDexmarker);
        }
    }

    private class BassIntentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            /* Ignore intent when service is inactive */
            if (mBassClientService == null) {
                return;
            }

            try {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                assertThat(device).isNotNull();
                LinkedBlockingQueue<Intent> queue = mIntentQueue.get(device);
                assertThat(queue).isNotNull();
                queue.put(intent);
            } catch (InterruptedException e) {
                throw new AssertionError("Cannot add Intent to the queue: " + e.getMessage());
            }
        }
    }

    /** Test to verify that BassClientService can be successfully started */
    @Test
    public void testGetBassClientService() {
        assertThat(mBassClientService).isEqualTo(BassClientService.getBassClientService());
        // Verify default connection and audio states
        mCurrentDevice = TestUtils.getTestDevice(mBluetoothAdapter, 0);
        assertThat(mBassClientService.getConnectionState(mCurrentDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
    }

    /** Test if getProfileConnectionPolicy works after the service is stopped. */
    @Test
    public void testGetPolicyAfterStopped() {
        mBassClientService.stop();
        when(mDatabaseManager.getProfileConnectionPolicy(
                        mCurrentDevice, BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
        Assert.assertEquals(
                "Initial device policy",
                BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                mBassClientService.getConnectionPolicy(mCurrentDevice));
    }

    /**
     * Test connecting to a test device. - service.connect() should return false -
     * bassClientStateMachine.sendMessage(CONNECT) should be called.
     */
    @Test
    public void testConnect() {
        when(mDatabaseManager.getProfileConnectionPolicy(
                        any(BluetoothDevice.class),
                        eq(BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT)))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        mCurrentDevice = TestUtils.getTestDevice(mBluetoothAdapter, 0);

        assertThat(mBassClientService.connect(mCurrentDevice)).isTrue();
        verify(mObjectsFactory)
                .makeStateMachine(
                        eq(mCurrentDevice), eq(mBassClientService), eq(mAdapterService), any());
        BassClientStateMachine stateMachine = mStateMachines.get(mCurrentDevice);
        assertThat(stateMachine).isNotNull();
        verify(stateMachine).sendMessage(BassClientStateMachine.CONNECT);
    }

    /** Test connecting to a null device. - service.connect() should return false. */
    @Test
    public void testConnect_nullDevice() {
        when(mDatabaseManager.getProfileConnectionPolicy(
                        any(BluetoothDevice.class),
                        eq(BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT)))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        BluetoothDevice nullDevice = null;

        assertThat(mBassClientService.connect(nullDevice)).isFalse();
    }

    /**
     * Test connecting to a device when the connection policy is forbidden. - service.connect()
     * should return false.
     */
    @Test
    public void testConnect_whenConnectionPolicyIsForbidden() {
        when(mDatabaseManager.getProfileConnectionPolicy(
                        any(BluetoothDevice.class),
                        eq(BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT)))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        mCurrentDevice = TestUtils.getTestDevice(mBluetoothAdapter, 0);
        assertThat(mCurrentDevice).isNotNull();

        assertThat(mBassClientService.connect(mCurrentDevice)).isFalse();
    }

    /**
     * Test whether service.startSearchingForSources() calls BluetoothLeScannerWrapper.startScan().
     */
    @Test
    public void testStartSearchingForSources() {
        prepareConnectedDeviceGroup();
        List<ScanFilter> scanFilters = new ArrayList<>();

        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            Mockito.clearInvocations(sm);
        }

        mBassClientService.startSearchingForSources(scanFilters);

        verify(mBluetoothLeScannerWrapper).startScan(notNull(), notNull(), notNull());
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm).sendMessage(BassClientStateMachine.START_SCAN_OFFLOAD);
        }
    }

    /**
     * Test whether service.startSearchingForSources() does not call
     * BluetoothLeScannerWrapper.startScan() when the scanner instance cannot be achieved.
     */
    @Test
    public void testStartSearchingForSources_whenScannerIsNull() {
        doReturn(null).when(mObjectsFactory).getBluetoothLeScannerWrapper(any());
        List<ScanFilter> scanFilters = new ArrayList<>();

        mBassClientService.startSearchingForSources(scanFilters);

        verify(mBluetoothLeScannerWrapper, never()).startScan(any(), any(), any());
    }

    private void prepareConnectedDeviceGroup() {
        when(mDatabaseManager.getProfileConnectionPolicy(
                        any(BluetoothDevice.class),
                        eq(BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT)))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        mCurrentDevice = TestUtils.getTestDevice(mBluetoothAdapter, 0);
        mCurrentDevice1 = TestUtils.getTestDevice(mBluetoothAdapter, 1);

        // Prepare intent queues
        mIntentQueue.put(mCurrentDevice, new LinkedBlockingQueue<>());
        mIntentQueue.put(mCurrentDevice1, new LinkedBlockingQueue<>());

        // Mock the CSIP group
        List<BluetoothDevice> groupDevices = new ArrayList<>();
        groupDevices.add(mCurrentDevice);
        groupDevices.add(mCurrentDevice1);
        doReturn(groupDevices)
                .when(mCsipService)
                .getGroupDevicesOrdered(mCurrentDevice, BluetoothUuid.CAP);
        doReturn(groupDevices)
                .when(mCsipService)
                .getGroupDevicesOrdered(mCurrentDevice1, BluetoothUuid.CAP);

        // Prepare connected devices
        assertThat(mBassClientService.connect(mCurrentDevice)).isTrue();
        assertThat(mBassClientService.connect(mCurrentDevice1)).isTrue();

        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            // Verify the call
            verify(sm).sendMessage(eq(BassClientStateMachine.CONNECT));

            // Notify the service about the connection event
            BluetoothDevice dev = sm.getDevice();
            doCallRealMethod()
                    .when(sm)
                    .broadcastConnectionState(eq(dev), any(Integer.class), any(Integer.class));
            sm.mService = mBassClientService;
            sm.mDevice = dev;
            sm.broadcastConnectionState(
                    dev, BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_CONNECTED);

            doReturn(BluetoothProfile.STATE_CONNECTED).when(sm).getConnectionState();
            doReturn(true).when(sm).isConnected();

            // Inject initial broadcast source state
            BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        meta,
                        TEST_SOURCE_ID,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null);
                injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        meta,
                        TEST_SOURCE_ID + 1,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null);
                injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID + 1);
            }
        }
    }

    private void startSearchingForSources() {
        List<ScanFilter> scanFilters = new ArrayList<>();

        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            Mockito.clearInvocations(sm);
        }

        clearInvocations(mBluetoothLeScannerWrapper);

        mBassClientService.startSearchingForSources(scanFilters);

        mCallbackCaptor = ArgumentCaptor.forClass(ScanCallback.class);

        verify(mBluetoothLeScannerWrapper)
                .startScan(notNull(), notNull(), mCallbackCaptor.capture());
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm).sendMessage(BassClientStateMachine.START_SCAN_OFFLOAD);
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testStopSearchingForSources() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 1
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);

        // Stop searching
        mBassClientService.stopSearchingForSources();

        verify(mBluetoothLeScannerWrapper).stopScan(mCallbackCaptor.getValue());
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm).sendMessage(BassClientStateMachine.STOP_SCAN_OFFLOAD);
        }

        // Check if unsyced
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerUnregisterSync(any(), any());
        expect.that(mBassClientService.getActiveSyncedSources()).isEmpty();
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isEqualTo(null);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testStop() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 1
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);

        // Stop
        mBassClientService.stop();

        verify(mBluetoothLeScannerWrapper).stopScan(mCallbackCaptor.getValue());

        // Check if unsyced
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerUnregisterSync(any(), any());
        expect.that(mBassClientService.getActiveSyncedSources()).isEmpty();
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isEqualTo(null);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testStopSearchingForSources_startAndSyncAgain() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 1
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Stop searching
        mBassClientService.stopSearchingForSources();

        // Start searching again
        startSearchingForSources();

        // Sync the same device again
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testStop_startAndSyncAgain() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 1
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Stop
        mBassClientService.stop();

        // Start again
        mBassClientService.start();

        // Start searching again
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Sync the same device again
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testStopSearchingForSources_addSourceCauseSyncEvenWithoutScanning() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 1
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Stop searching
        mBassClientService.stopSearchingForSources();

        // Add source to unsynced broadcast, causes synchronization first
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        mBassClientService.addSource(mCurrentDevice, meta, true);
        handleHandoverSupport();
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        // Verify not getting ADD_BCAST_SOURCE message before source sync
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }

        // Source synced which cause execute pending add source
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);

        // Verify all group members getting ADD_BCAST_SOURCE message
        expect.that(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Message msg =
                    messageCaptor.getAllValues().stream()
                            .filter(
                                    m ->
                                            (m.what == BassClientStateMachine.ADD_BCAST_SOURCE)
                                                    && (m.obj == meta))
                            .findFirst()
                            .orElse(null);
            expect.that(msg).isNotNull();
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    @DisableFlags(Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER)
    public void testNotRemovingCachedBroadcastOnLostWithoutScanning_noResyncFlag() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 1
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Sync lost during scanning removes cached broadcast
        onSyncLost();

        // Add source to not cached broadcast cause addFailed notification
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        mBassClientService.addSource(mCurrentDevice, meta, true);
        handleHandoverSupport();
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        try {
            verify(mCallback)
                    .onSourceAddFailed(
                            eq(mCurrentDevice),
                            eq(meta),
                            eq(BluetoothStatusCodes.ERROR_BAD_PARAMETERS));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // Add broadcast to cache
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        // Stop searching
        mBassClientService.stopSearchingForSources();

        // Add sync handle by add source
        mBassClientService.addSource(mCurrentDevice, meta, true);
        handleHandoverSupport();
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Sync lost without active scanning should not remove broadcast cache
        onSyncLost();

        // Add source to unsynced broadcast, causes synchronization first
        mBassClientService.addSource(mCurrentDevice, meta, true);
        handleHandoverSupport();
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE,
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER
    })
    public void testNotRemovingCachedBroadcastOnLostWithoutScanning() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 1
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Sync lost during scanning removes cached broadcast
        onSyncLost();
        checkAndDispatchTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_SYNC_LOST_TIMEOUT);

        // Add source to not cached broadcast cause addFailed notification
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        mBassClientService.addSource(mCurrentDevice, meta, true);
        handleHandoverSupport();
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        try {
            verify(mCallback)
                    .onSourceAddFailed(
                            eq(mCurrentDevice),
                            eq(meta),
                            eq(BluetoothStatusCodes.ERROR_BAD_PARAMETERS));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // Add broadcast to cache
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        // Stop searching
        mBassClientService.stopSearchingForSources();

        // Add sync handle by add source
        mBassClientService.addSource(mCurrentDevice, meta, true);
        handleHandoverSupport();
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Sync lost without active scanning should not remove broadcast cache
        onSyncLost();
        checkAndDispatchTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_SYNC_LOST_TIMEOUT);

        // Add source to unsynced broadcast, causes synchronization first
        mBassClientService.addSource(mCurrentDevice, meta, true);
        handleHandoverSupport();
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testNotRemovingCachedBroadcastOnFailEstablishWithoutScanning() {
        final BluetoothDevice device1 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:11", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device2 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:22", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device3 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:33", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device4 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:44", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device5 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:55", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final int handle1 = 0;
        final int handle2 = 1;
        final int handle3 = 2;
        final int handle4 = 3;
        final int handle5 = 4;
        final int broadcastId1 = 1111;
        final int broadcastId2 = 2222;
        final int broadcastId3 = 3333;
        final int broadcastId4 = 4444;
        final int broadcastId5 = 5555;

        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 5 sources cause removing 1 synced element
        onScanResult(device1, broadcastId1);
        onSyncEstablished(device1, handle1);
        onScanResult(device2, broadcastId2);
        onSyncEstablished(device2, handle2);
        onScanResult(device3, broadcastId3);
        onSyncEstablished(device3, handle3);
        onScanResult(device4, broadcastId4);
        onSyncEstablished(device4, handle4);
        onScanResult(device5, broadcastId5);
        onSyncEstablished(device5, handle5);
        mInOrderMethodProxy
                .verify(mMethodProxy, times(5))
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setEncrypted(false)
                        .setSourceDevice(device1, BluetoothDevice.ADDRESS_TYPE_RANDOM)
                        .setSourceAdvertisingSid(TEST_ADVERTISER_SID)
                        .setBroadcastId(broadcastId1)
                        .setBroadcastCode(null)
                        .setPaSyncInterval(TEST_PA_SYNC_INTERVAL)
                        .setPresentationDelayMicros(TEST_PRESENTATION_DELAY_MS);
        // builder expect at least one subgroup
        builder.addSubgroup(createBroadcastSubgroup());
        BluetoothLeBroadcastMetadata meta = builder.build();

        // Add source to unsynced broadcast, causes synchronization first
        mBassClientService.addSource(mCurrentDevice, meta, true);
        handleHandoverSupport();
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        // Error in syncEstablished causes soureLost, sourceAddFailed notification
        // and removing cache because scanning is active
        onSyncEstablishedFailed(device1, handle1);
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        InOrder inOrderCallback = inOrder(mCallback);
        try {
            inOrderCallback.verify(mCallback).onSourceLost(eq(broadcastId1));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        try {
            inOrderCallback
                    .verify(mCallback)
                    .onSourceAddFailed(
                            eq(mCurrentDevice),
                            eq(meta),
                            eq(BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // Add source to not cached broadcast causes addFailed notification
        mBassClientService.addSource(mCurrentDevice, meta, true);
        handleHandoverSupport();
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        try {
            inOrderCallback
                    .verify(mCallback)
                    .onSourceAddFailed(
                            eq(mCurrentDevice),
                            eq(meta),
                            eq(BluetoothStatusCodes.ERROR_BAD_PARAMETERS));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // Scan and sync again
        onScanResult(device1, broadcastId1);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(device1, handle1);

        // Stop searching
        mBassClientService.stopSearchingForSources();

        // Add source to unsynced broadcast, causes synchronization first
        mBassClientService.addSource(mCurrentDevice, meta, true);
        handleHandoverSupport();
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        // Error in syncEstablished causes soureLost, sourceAddFailed notification
        // and not removing cache because scanning is inactice
        onSyncEstablishedFailed(device1, handle1);
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        try {
            inOrderCallback.verify(mCallback).onSourceLost(eq(broadcastId1));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        try {
            inOrderCallback
                    .verify(mCallback)
                    .onSourceAddFailed(
                            eq(mCurrentDevice),
                            eq(meta),
                            eq(BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // Add source to unsynced broadcast, causes synchronization first
        mBassClientService.addSource(mCurrentDevice, meta, true);
        handleHandoverSupport();
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testMultipleAddSourceToUnsyncedBroadcaster() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 1
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Stop searching to unsync broadcaster
        mBassClientService.stopSearchingForSources();

        // Sink1 aAdd source to unsynced broadcast, causes synchronization first
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        mBassClientService.addSource(mCurrentDevice, meta, false);
        handleHandoverSupport();
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        // Sink2 add source to unsynced broadcast
        mBassClientService.addSource(mCurrentDevice1, meta, false);
        handleHandoverSupport();

        // Sync established
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());

        // Both add sources should be called to state machines
        expect.that(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Message msg =
                    messageCaptor.getAllValues().stream()
                            .filter(
                                    m ->
                                            (m.what == BassClientStateMachine.ADD_BCAST_SOURCE)
                                                    && (m.obj == meta))
                            .findFirst()
                            .orElse(null);
            expect.that(msg).isNotNull();
        }

        // There should be no second selectSource call
        mInOrderMethodProxy
                .verify(mMethodProxy, never())
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testMultipleAddSourceToUnsyncedInactiveBroadcaster() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 1
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Stop searching to unsync broadcaster
        mBassClientService.stopSearchingForSources();

        // Sink1 aAdd source to unsynced broadcast, causes synchronization first
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        mBassClientService.addSource(mCurrentDevice, meta, false);
        handleHandoverSupport();
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        // Sink2 add source to unsynced broadcast
        mBassClientService.addSource(mCurrentDevice1, meta, false);
        handleHandoverSupport();

        // Error in syncEstablished causes soureLost, sourceAddFailed notification for both sinks
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        InOrder inOrderCallback = inOrder(mCallback);
        try {
            inOrderCallback.verify(mCallback).onSourceLost(eq(TEST_BROADCAST_ID));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        try {
            inOrderCallback
                    .verify(mCallback)
                    .onSourceAddFailed(
                            eq(mCurrentDevice),
                            eq(meta),
                            eq(BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        try {
            inOrderCallback
                    .verify(mCallback)
                    .onSourceAddFailed(
                            eq(mCurrentDevice1),
                            eq(meta),
                            eq(BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // There should be no second selectSource call
        mInOrderMethodProxy
                .verify(mMethodProxy, never())
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
    }

    private void checkNoTimeout(int broadcastId, int message) {
        assertThat(mBassClientService.mTimeoutHandler.isStarted(broadcastId, message)).isFalse();
    }

    private void checkTimeout(int broadcastId, int message) {
        assertThat(mBassClientService.mTimeoutHandler.isStarted(broadcastId, message)).isTrue();
    }

    private void checkAndDispatchTimeout(int broadcastId, int message) {
        checkTimeout(broadcastId, message);
        mBassClientService.mTimeoutHandler.stop(broadcastId, message);
        Handler handler = mBassClientService.mTimeoutHandler.getOrCreateHandler(broadcastId);
        Message newMsg = handler.obtainMessage(message);
        handler.dispatchMessage(newMsg);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    @DisableFlags(Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER)
    public void testStopSearchingForSources_timeoutForActiveSync() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 1
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Stop searching
        mBassClientService.stopSearchingForSources();
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerUnregisterSync(any(), any());

        // Add source to unsynced broadcast, causes synchronization first
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        mBassClientService.addSource(mCurrentDevice, meta, true);
        handleHandoverSupport();

        // Source synced which cause start timeout event
        assertThat(mBassClientService.mHandler.hasMessages(BassClientService.MESSAGE_SYNC_TIMEOUT))
                .isFalse();
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);

        assertThat(mBassClientService.mHandler.hasMessages(BassClientService.MESSAGE_SYNC_TIMEOUT))
                .isTrue();
        mBassClientService.mHandler.removeMessages(BassClientService.MESSAGE_SYNC_TIMEOUT);
        Message newMsg =
                mBassClientService.mHandler.obtainMessage(BassClientService.MESSAGE_SYNC_TIMEOUT);
        newMsg.arg1 = TEST_BROADCAST_ID;
        mBassClientService.mHandler.dispatchMessage(newMsg);

        // Check if unsyced
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerUnregisterSync(any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    @DisableFlags(Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER)
    public void testStopSearchingForSources_clearTimeoutForActiveSync() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 1
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Stop searching
        mBassClientService.stopSearchingForSources();
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerUnregisterSync(any(), any());

        // Add source to unsynced broadcast, causes synchronization first
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        mBassClientService.addSource(mCurrentDevice, meta, true);
        handleHandoverSupport();

        // Source synced which cause start timeout event
        assertThat(mBassClientService.mHandler.hasMessages(BassClientService.MESSAGE_SYNC_TIMEOUT))
                .isFalse();
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.mHandler.hasMessages(BassClientService.MESSAGE_SYNC_TIMEOUT))
                .isTrue();

        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);

        // Start searching again should clear timeout
        startSearchingForSources();
        assertThat(mBassClientService.mHandler.hasMessages(BassClientService.MESSAGE_SYNC_TIMEOUT))
                .isFalse();

        mInOrderMethodProxy
                .verify(mMethodProxy, never())
                .periodicAdvertisingManagerUnregisterSync(any(), any());
        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
    }

    private byte[] getScanRecord(int broadcastId) {
        return new byte[] {
            0x02,
            0x01,
            0x1a, // advertising flags
            0x05,
            0x02,
            0x52,
            0x18,
            0x0a,
            0x11, // 16 bit service uuids
            0x04,
            0x09,
            0x50,
            0x65,
            0x64, // name
            0x02,
            0x0A,
            (byte) 0xec, // tx power level
            0x05,
            0x30,
            0x54,
            0x65,
            0x73,
            0x74, // broadcast name: Test
            0x06,
            0x16,
            0x52,
            0x18,
            (byte) broadcastId,
            (byte) (broadcastId >> 8),
            (byte) (broadcastId >> 16), // service data, broadcast id
            0x08,
            0x16,
            0x56,
            0x18,
            0x07,
            0x03,
            0x06,
            0x07,
            0x08,
            // service data - public broadcast,
            // feature - 0x7, metadata len - 0x3, metadata - 0x6, 0x7, 0x8
            0x05,
            (byte) 0xff,
            (byte) 0xe0,
            0x00,
            0x02,
            0x15, // manufacturer specific data
            0x03,
            0x50,
            0x01,
            0x02, // an unknown data type won't cause trouble
        };
    }

    private void onScanResult(BluetoothDevice testDevice, int broadcastId) {
        byte[] scanRecord = getScanRecord(broadcastId);
        ScanResult scanResult =
                new ScanResult(
                        testDevice,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI,
                        0,
                        ScanRecord.parseFromBytes(scanRecord),
                        0);

        mCallbackCaptor.getValue().onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult);
    }

    private byte[] getPAScanRecord() {
        return new byte[] {
            (byte) 0x02,
            (byte) 0x01,
            (byte) 0x1a, // advertising flags
            (byte) 0x05,
            (byte) 0x02,
            (byte) 0x51,
            (byte) 0x18,
            (byte) 0x0a,
            (byte) 0x11, // 16 bit service uuids
            (byte) 0x04,
            (byte) 0x09,
            (byte) 0x50,
            (byte) 0x65,
            (byte) 0x64, // name
            (byte) 0x02,
            (byte) 0x0A,
            (byte) 0xec, // tx power level
            (byte) 0x19,
            (byte) 0x16,
            (byte) 0x51,
            (byte) 0x18, // service data (base data with 18 bytes)
            // LEVEL 1
            (byte) 0x01,
            (byte) 0x02,
            (byte) 0x03, // presentationDelay
            (byte) 0x01, // numSubGroups
            // LEVEL 2
            (byte) 0x01, // numSubGroups
            (byte) 0x00,
            (byte) 0x00,
            (byte) 0x00,
            (byte) 0x00,
            (byte) 0x00, // UNKNOWN_CODEC
            (byte) 0x02, // codecConfigLength
            (byte) 0x01,
            (byte) 'A', // codecConfigInfo
            (byte) 0x03, // metaDataLength
            (byte) 0x06,
            (byte) 0x07,
            (byte) 0x08, // metaData
            // LEVEL 3
            (byte) 0x04, // index
            (byte) 0x03, // codecConfigLength
            (byte) 0x02,
            (byte) 'B',
            (byte) 'C', // codecConfigInfo
            (byte) 0x05,
            (byte) 0xff,
            (byte) 0xe0,
            (byte) 0x00,
            (byte) 0x02,
            (byte) 0x15, // manufacturer specific data
            (byte) 0x03,
            (byte) 0x50,
            (byte) 0x01,
            (byte) 0x02, // an unknown data type won't cause trouble
        };
    }

    private void onPeriodicAdvertisingReport() {
        byte[] scanRecord = getPAScanRecord();
        ScanRecord record = ScanRecord.parseFromBytes(scanRecord);
        PeriodicAdvertisingReport report =
                new PeriodicAdvertisingReport(TEST_SYNC_HANDLE, 0, 0, 0, record);
        BassClientService.PACallback callback = mBassClientService.new PACallback();
        callback.onPeriodicAdvertisingReport(report);
    }

    private void onBigInfoAdvertisingReport() {
        BassClientService.PACallback callback = mBassClientService.new PACallback();
        callback.onBigInfoAdvertisingReport(TEST_SYNC_HANDLE, true);
    }

    private void onSyncLost() {
        BassClientService.PACallback callback = mBassClientService.new PACallback();
        callback.onSyncLost(TEST_SYNC_HANDLE);
    }

    private void onSyncEstablished(BluetoothDevice testDevice, int syncHandle) {
        BassClientService.PACallback callback = mBassClientService.new PACallback();
        callback.onSyncEstablished(
                syncHandle, testDevice, TEST_ADVERTISER_SID, 0, 200, BluetoothGatt.GATT_SUCCESS);
    }

    private void onSyncEstablishedFailed(BluetoothDevice testDevice, int syncHandle) {
        BassClientService.PACallback callback = mBassClientService.new PACallback();
        callback.onSyncEstablished(
                syncHandle, testDevice, TEST_ADVERTISER_SID, 0, 200, BluetoothGatt.GATT_FAILURE);
    }

    private void verifyConnectionStateIntent(
            int timeoutMs, BluetoothDevice device, int newState, int prevState) {
        Intent intent = TestUtils.waitForIntent(timeoutMs, mIntentQueue.get(device));
        assertThat(intent.getAction())
                .isEqualTo(BluetoothLeBroadcastAssistant.ACTION_CONNECTION_STATE_CHANGED);
        assertThat(device).isEqualTo(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
        assertThat(newState).isEqualTo(intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1));
        assertThat(prevState)
                .isEqualTo(intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1));
    }

    private void handleHandoverSupport() {
        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                2 /* STATUS_LOCAL_STREAM_SUSPENDED */);
    }

    private void verifyAddSourceForGroup(BluetoothLeBroadcastMetadata meta) {
        // Add broadcast source
        mBassClientService.addSource(mCurrentDevice, meta, true);

        /* In case if device supporth handover, Source stream status needs to be updated */
        handleHandoverSupport();

        // Verify all group members getting ADD_BCAST_SOURCE message
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Message msg =
                    messageCaptor.getAllValues().stream()
                            .filter(
                                    m ->
                                            (m.what == BassClientStateMachine.ADD_BCAST_SOURCE)
                                                    && (m.obj == meta))
                            .findFirst()
                            .orElse(null);
            assertThat(msg).isNotNull();
            clearInvocations(sm);
        }
    }

    private BluetoothLeBroadcastReceiveState injectRemoteSourceState(
            BassClientStateMachine sm,
            BluetoothLeBroadcastMetadata meta,
            int sourceId,
            int paSynState,
            int encryptionState,
            byte[] badCode,
            long bisSyncState) {
        BluetoothLeBroadcastReceiveState recvState =
                new BluetoothLeBroadcastReceiveState(
                        sourceId,
                        meta.getSourceAddressType(),
                        meta.getSourceDevice(),
                        meta.getSourceAdvertisingSid(),
                        meta.getBroadcastId(),
                        paSynState,
                        encryptionState,
                        badCode,
                        meta.getSubgroups().size(),
                        // Bis sync states
                        meta.getSubgroups().stream()
                                .map(e -> bisSyncState)
                                .collect(Collectors.toList()),
                        meta.getSubgroups().stream()
                                .map(e -> e.getContentMetadata())
                                .collect(Collectors.toList()));
        doReturn(meta).when(sm).getCurrentBroadcastMetadata(eq(sourceId));

        List<BluetoothLeBroadcastReceiveState> stateList = sm.getAllSources();
        if (stateList == null) {
            stateList = new ArrayList<BluetoothLeBroadcastReceiveState>();
        } else {
            stateList.removeIf(e -> e.getSourceId() == sourceId);
        }
        stateList.add(recvState);
        doReturn(stateList).when(sm).getAllSources();

        return recvState;
    }

    private BluetoothLeBroadcastReceiveState injectRemoteSourceStateSourceAdded(
            BassClientStateMachine sm,
            BluetoothLeBroadcastMetadata meta,
            int sourceId,
            int paSynState,
            int encryptionState,
            byte[] badCode) {
        BluetoothLeBroadcastReceiveState recvState =
                injectRemoteSourceState(
                        sm,
                        meta,
                        sourceId,
                        paSynState,
                        encryptionState,
                        badCode,
                        (long) 0x00000000);

        mBassClientService
                .getCallbacks()
                .notifySourceAdded(
                        sm.getDevice(), recvState, BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());

        return recvState;
    }

    private BluetoothLeBroadcastReceiveState injectRemoteSourceStateChanged(
            BassClientStateMachine sm,
            BluetoothLeBroadcastMetadata meta,
            int sourceId,
            int paSynState,
            int encryptionState,
            byte[] badCode,
            long bisSyncState) {
        BluetoothLeBroadcastReceiveState recvState =
                injectRemoteSourceState(
                        sm, meta, sourceId, paSynState, encryptionState, badCode, bisSyncState);

        mBassClientService
                .getCallbacks()
                .notifyReceiveStateChanged(sm.getDevice(), recvState.getSourceId(), recvState);
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());

        return recvState;
    }

    private void injectRemoteSourceStateChanged(
            BluetoothLeBroadcastMetadata meta, boolean isPaSynced, boolean isBisSynced) {
        for (BassClientStateMachine sm : mStateMachines.values()) {
            // Update receiver state
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateChanged(
                        sm,
                        meta,
                        TEST_SOURCE_ID,
                        isPaSynced
                                ? BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED
                                : BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        isBisSynced ? (long) 0x00000001 : (long) 0x00000000);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                injectRemoteSourceStateChanged(
                        sm,
                        meta,
                        TEST_SOURCE_ID + 1,
                        isPaSynced
                                ? BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED
                                : BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        isBisSynced ? (long) 0x00000002 : (long) 0x00000000);
            }
        }
    }

    private void injectRemoteSourceStateChanged(
            BluetoothLeBroadcastMetadata meta, int paSynState, boolean isBisSynced) {
        for (BassClientStateMachine sm : mStateMachines.values()) {
            // Update receiver state
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateChanged(
                        sm,
                        meta,
                        TEST_SOURCE_ID,
                        paSynState,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        isBisSynced ? (long) 0x00000001 : (long) 0x00000000);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                injectRemoteSourceStateChanged(
                        sm,
                        meta,
                        TEST_SOURCE_ID + 1,
                        paSynState,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        isBisSynced ? (long) 0x00000002 : (long) 0x00000000);
            }
        }
    }

    private void injectRemoteSourceStateRemoval(BassClientStateMachine sm, int sourceId) {
        List<BluetoothLeBroadcastReceiveState> stateList = sm.getAllSources();
        if (stateList == null) {
            stateList = new ArrayList<BluetoothLeBroadcastReceiveState>();
        }
        stateList.replaceAll(
                e -> {
                    if (e.getSourceId() != sourceId) return e;
                    return new BluetoothLeBroadcastReceiveState(
                            sourceId,
                            BluetoothDevice.ADDRESS_TYPE_PUBLIC,
                            mBluetoothAdapter.getRemoteLeDevice(
                                    "00:00:00:00:00:00", BluetoothDevice.ADDRESS_TYPE_PUBLIC),
                            0,
                            0,
                            BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                            BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                            null,
                            0,
                            Arrays.asList(new Long[0]),
                            Arrays.asList(new BluetoothLeAudioContentMetadata[0]));
                });
        doReturn(stateList).when(sm).getAllSources();

        Optional<BluetoothLeBroadcastReceiveState> receiveState =
                stateList.stream().filter(e -> e.getSourceId() == sourceId).findFirst();

        mBassClientService
                .getCallbacks()
                .notifySourceRemoved(
                        sm.getDevice(), sourceId, BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
        mBassClientService
                .getCallbacks()
                .notifyReceiveStateChanged(sm.getDevice(), sourceId, receiveState.get());
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
    }

    private void prepareRemoteSourceState(
            BluetoothLeBroadcastMetadata meta, boolean isPaSynced, boolean isBisSynced) {
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        meta,
                        TEST_SOURCE_ID,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        meta,
                        TEST_SOURCE_ID + 1,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null);
            }
        }
        injectRemoteSourceStateChanged(meta, isPaSynced, isBisSynced);
    }

    /**
     * Test whether service.addSource() does send proper messages to all the state machines within
     * the Csip coordinated group
     */
    @Test
    public void testAddSourceForGroup() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        verifyAddSourceForGroup(meta);
    }

    /** Test whether service.addSource() source id can be propagated through callback correctly */
    @Test
    public void testAddSourceCallbackForGroup() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        verifyAddSourceForGroup(meta);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        meta,
                        TEST_SOURCE_ID,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null);
                // verify source id
                try {
                    verify(mCallback, timeout(TIMEOUT_MS).atLeastOnce())
                            .onSourceAdded(
                                    eq(mCurrentDevice),
                                    eq(TEST_SOURCE_ID),
                                    eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        meta,
                        TEST_SOURCE_ID + 1,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null);
                // verify source id
                try {
                    verify(mCallback, timeout(TIMEOUT_MS).atLeastOnce())
                            .onSourceAdded(
                                    eq(mCurrentDevice1),
                                    eq(TEST_SOURCE_ID + 1),
                                    eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Test whether service.modifySource() does send proper messages to all the state machines
     * within the Csip coordinated group
     */
    @Test
    public void testModifySourceForGroup() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        verifyAddSourceForGroup(meta);
        prepareRemoteSourceState(meta, true, false);

        // Update broadcast source using other member of the same group
        BluetoothLeBroadcastMetadata metaUpdate =
                new BluetoothLeBroadcastMetadata.Builder(meta)
                        .setBroadcastId(TEST_BROADCAST_ID + 1)
                        .build();
        mBassClientService.modifySource(mCurrentDevice1, TEST_SOURCE_ID + 1, metaUpdate);

        // Verify all group members getting UPDATE_BCAST_SOURCE message on proper sources
        expect.that(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Optional<Message> msg =
                    messageCaptor.getAllValues().stream()
                            .filter(m -> m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                            .findFirst();
            expect.that(msg.isPresent()).isEqualTo(true);
            expect.that(msg.get().obj).isEqualTo(metaUpdate);

            // Verify using the right sourceId on each device
            if (sm.getDevice().equals(mCurrentDevice)) {
                expect.that(msg.get().arg1).isEqualTo(TEST_SOURCE_ID);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                expect.that(msg.get().arg1).isEqualTo(TEST_SOURCE_ID + 1);
            }
        }
    }

    /**
     * Test whether service.removeSource() does send proper messages to all the state machines
     * within the Csip coordinated group
     */
    @Test
    public void testRemoveSourceForGroup() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        verifyAddSourceForGroup(meta);
        prepareRemoteSourceState(meta, true, false);

        // Remove broadcast source using other member of the same group
        mBassClientService.removeSource(mCurrentDevice1, TEST_SOURCE_ID + 1);

        // Verify all group members getting REMOVE_BCAST_SOURCE message
        expect.that(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Optional<Message> msg =
                    messageCaptor.getAllValues().stream()
                            .filter(m -> m.what == BassClientStateMachine.REMOVE_BCAST_SOURCE)
                            .findFirst();
            expect.that(msg.isPresent()).isEqualTo(true);

            // Verify using the right sourceId on each device
            if (sm.getDevice().equals(mCurrentDevice)) {
                expect.that(msg.get().arg1).isEqualTo(TEST_SOURCE_ID);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                expect.that(msg.get().arg1).isEqualTo(TEST_SOURCE_ID + 1);
            }
        }
    }

    /**
     * Test whether service.removeSource() does send modify source to all the state machines if
     * either PA or BIS is synced
     */
    @Test
    public void testRemoveSourceForGroupAndTriggerModifySource() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        verifyAddSourceForGroup(meta);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            injectRemoteSourceStateSourceAdded(
                    sm,
                    meta,
                    TEST_SOURCE_ID,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                    meta.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null);
            doReturn(meta).when(sm).getCurrentBroadcastMetadata(eq(TEST_SOURCE_ID));
            doReturn(true).when(sm).isSyncedToTheSource(eq(TEST_SOURCE_ID));
        }

        // Remove broadcast source
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);

        // Verify all group members getting UPDATE_BCAST_SOURCE message
        // because PA state is synced
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Optional<Message> msg =
                    messageCaptor.getAllValues().stream()
                            .filter(m -> m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                            .findFirst();
            assertThat(msg.isPresent()).isEqualTo(true);

            // Verify using the right sourceId on each device
            assertThat(msg.get().arg1).isEqualTo(TEST_SOURCE_ID);
        }

        for (BassClientStateMachine sm : mStateMachines.values()) {
            // Update receiver state
            injectRemoteSourceStateChanged(
                    sm,
                    meta,
                    TEST_SOURCE_ID,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    meta.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null,
                    (long) 0x00000001);
            verify(mLeAudioService).activeBroadcastAssistantNotification(eq(true));
        }

        // Remove broadcast source
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);

        // Verify all group members getting UPDATE_BCAST_SOURCE message if
        // bis sync state is non-zero and pa sync state is not synced
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Optional<Message> msg =
                    messageCaptor.getAllValues().stream()
                            .filter(m -> m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                            .findFirst();
            assertThat(msg.isPresent()).isEqualTo(true);

            // Verify using the right sourceId on each device
            assertThat(msg.get().arg1).isEqualTo(TEST_SOURCE_ID);
        }

        for (BassClientStateMachine sm : mStateMachines.values()) {
            injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID);
        }
    }

    private void verifyRemoveMessageAndInjectSourceRemoval() {
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Optional<Message> msg =
                    messageCaptor.getAllValues().stream()
                            .filter(m -> m.what == BassClientStateMachine.REMOVE_BCAST_SOURCE)
                            .findFirst();
            assertThat(msg.isPresent()).isEqualTo(true);

            if (sm.getDevice().equals(mCurrentDevice)) {
                assertThat(msg.get().arg1).isEqualTo(TEST_SOURCE_ID);
                injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                assertThat(msg.get().arg1).isEqualTo(TEST_SOURCE_ID + 1);
                injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID + 1);
            }
        }
    }

    /**
     * Test whether service.removeSource() does send modify source if source is from remote receive
     * state. In this case, assistant should be able to remove source which was not managed by BASS
     * service (external manager/no source metadata)
     */
    @Test
    public void testRemoveSourceForGroupAndTriggerModifySourceWithoutMetadata() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);

        for (BassClientStateMachine sm : mStateMachines.values()) {
            injectRemoteSourceStateSourceAdded(
                    sm,
                    meta,
                    TEST_SOURCE_ID,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                    meta.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null);
            // no current broadcast metadata for external broadcast source
            doReturn(null).when(sm).getCurrentBroadcastMetadata(eq(TEST_SOURCE_ID));
            doReturn(true).when(sm).isSyncedToTheSource(eq(TEST_SOURCE_ID));
        }

        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            mBassClientService.removeSource(sm.getDevice(), TEST_SOURCE_ID);
            // Verify device get update source
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Optional<Message> msg =
                    messageCaptor.getAllValues().stream()
                            .filter(m -> m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                            .findFirst();
            assertThat(msg.isPresent()).isEqualTo(true);

            assertThat(msg.get().arg1).isEqualTo(TEST_SOURCE_ID);
            assertThat(msg.get().arg2).isEqualTo(BassConstants.PA_SYNC_DO_NOT_SYNC);
            // Verify metadata is null
            assertThat(msg.get().obj).isEqualTo(null);
        }

        for (BassClientStateMachine sm : mStateMachines.values()) {
            injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID);
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_API_GET_LOCAL_METADATA)
    public void testGetSourceMetadata() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);

        for (BassClientStateMachine sm : mStateMachines.values()) {
            injectRemoteSourceStateSourceAdded(
                    sm,
                    meta,
                    TEST_SOURCE_ID,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                    meta.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null);
            doReturn(null).when(sm).getCurrentBroadcastMetadata(eq(TEST_SOURCE_ID));
            assertThat(mBassClientService.getSourceMetadata(sm.getDevice(), TEST_SOURCE_ID))
                    .isEqualTo(null);

            doReturn(meta).when(sm).getCurrentBroadcastMetadata(eq(TEST_SOURCE_ID));
            doReturn(true).when(sm).isSyncedToTheSource(eq(TEST_SOURCE_ID));
            assertThat(mBassClientService.getSourceMetadata(sm.getDevice(), TEST_SOURCE_ID))
                    .isEqualTo(meta);
        }

        for (BassClientStateMachine sm : mStateMachines.values()) {
            injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID);
        }
    }

    /** Test whether the group operation flag is set on addSource() and removed on removeSource */
    @Test
    public void testGroupStickyFlagSetUnset() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        verifyAddSourceForGroup(meta);
        prepareRemoteSourceState(meta, true, false);

        // Remove broadcast source
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);
        verifyRemoveMessageAndInjectSourceRemoval();

        // Update broadcast source
        BluetoothLeBroadcastMetadata metaUpdate = createBroadcastMetadata(TEST_BROADCAST_ID + 1);
        mBassClientService.modifySource(mCurrentDevice, TEST_SOURCE_ID, metaUpdate);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        Optional<Message> msg;

        // Verrify that one device got the message...
        verify(mStateMachines.get(mCurrentDevice), atLeast(1)).sendMessage(messageCaptor.capture());
        msg =
                messageCaptor.getAllValues().stream()
                        .filter(m -> m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                        .findFirst();
        expect.that(msg.isPresent()).isTrue();
        expect.that(msg.orElse(null)).isNotNull();

        // ... but not the other one, since the sticky group flag should have been removed
        messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mStateMachines.get(mCurrentDevice1), atLeast(1))
                .sendMessage(messageCaptor.capture());
        msg =
                messageCaptor.getAllValues().stream()
                        .filter(m -> m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                        .findFirst();
        expect.that(msg.isPresent()).isFalse();
    }

    /** Test switch source will be triggered if adding new source when sink has source */
    @Test
    public void testSwitchSourceAfterSourceAdded() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        BluetoothLeBroadcastMetadata newMeta = createBroadcastMetadata(TEST_BROADCAST_ID + 1);
        verifyAddSourceForGroup(meta);
        prepareRemoteSourceState(meta, true, true);

        // Add another new broadcast source
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID + 1);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE + 1);
        mBassClientService.addSource(mCurrentDevice, newMeta, true);

        // Verify all group members getting SWITCH_BCAST_SOURCE message and first source got
        // selected to remove
        expect.that(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());
            if (sm.getDevice().equals(mCurrentDevice)) {
                Optional<Message> msg =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                (m.what
                                                                == BassClientStateMachine
                                                                        .SWITCH_BCAST_SOURCE)
                                                        && (m.obj == newMeta)
                                                        && (m.arg1 == TEST_SOURCE_ID))
                                .findFirst();
                expect.that(msg.isPresent()).isTrue();
                expect.that(msg.orElse(null)).isNotNull();
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                Optional<Message> msg =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                (m.what
                                                                == BassClientStateMachine
                                                                        .SWITCH_BCAST_SOURCE)
                                                        && (m.obj == newMeta)
                                                        && (m.arg1 == TEST_SOURCE_ID + 1))
                                .findFirst();
                expect.that(msg.isPresent()).isTrue();
                expect.that(msg.orElse(null)).isNotNull();
            } else {
                throw new AssertionError("Unexpected device");
            }
        }
    }

    /**
     * Test that after multiple calls to service.addSource() with a group operation flag set, there
     * are two call to service.removeSource() needed to clear the flag
     */
    @Test
    public void testAddRemoveMultipleSourcesForGroup() {
        prepareConnectedDeviceGroup();
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);

        // Add more room for the source broadcasts
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        meta,
                        TEST_SOURCE_ID + 1,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null);
                injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID + 1);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        meta,
                        TEST_SOURCE_ID,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null);
                injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID);
            }
        }

        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        verifyAddSourceForGroup(meta);
        assertThat(mStateMachines.size()).isEqualTo(2);
        prepareRemoteSourceState(meta, true, true);

        // Add another broadcast source
        BluetoothLeBroadcastMetadata meta1 =
                new BluetoothLeBroadcastMetadata.Builder(meta)
                        .setBroadcastId(TEST_BROADCAST_ID + 1)
                        .build();
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID + 1);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE + 1);
        verifyAddSourceForGroup(meta1);
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        meta1,
                        TEST_SOURCE_ID + 2,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        meta1.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        meta1,
                        TEST_SOURCE_ID + 3,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        meta1.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }

        // Remove the first broadcast source
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);
        assertThat(mStateMachines.size()).isEqualTo(2);
        verifyRemoveMessageAndInjectSourceRemoval();

        // Modify the second one and verify all group members getting UPDATE_BCAST_SOURCE
        BluetoothLeBroadcastMetadata metaUpdate = createBroadcastMetadata(TEST_BROADCAST_ID + 3);
        mBassClientService.modifySource(mCurrentDevice1, TEST_SOURCE_ID + 3, metaUpdate);
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Optional<Message> msg =
                    messageCaptor.getAllValues().stream()
                            .filter(m -> m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                            .findFirst();
            assertThat(msg.isPresent()).isEqualTo(true);
            assertThat(msg.get().obj).isEqualTo(metaUpdate);

            // Verify using the right sourceId on each device
            if (sm.getDevice().equals(mCurrentDevice)) {
                assertThat(msg.get().arg1).isEqualTo(TEST_SOURCE_ID + 2);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                assertThat(msg.get().arg1).isEqualTo(TEST_SOURCE_ID + 3);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }

        // Remove the second broadcast source and verify all group members getting
        // REMOVE_BCAST_SOURCE message for the second source
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID + 2);
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            if (sm.getDevice().equals(mCurrentDevice)) {
                Optional<Message> msg =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                (m.what
                                                                == BassClientStateMachine
                                                                        .REMOVE_BCAST_SOURCE)
                                                        && (m.arg1 == TEST_SOURCE_ID + 2))
                                .findFirst();
                assertThat(msg.isPresent()).isEqualTo(true);
                injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID + 2);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                Optional<Message> msg =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                (m.what
                                                                == BassClientStateMachine
                                                                        .REMOVE_BCAST_SOURCE)
                                                        && (m.arg1 == TEST_SOURCE_ID + 3))
                                .findFirst();
                assertThat(msg.isPresent()).isEqualTo(true);
                injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID + 3);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }

        // Fake the autonomous source change - or other client setting the source
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);

            BluetoothLeBroadcastMetadata metaOther =
                    createBroadcastMetadata(TEST_BROADCAST_ID + 20);
            injectRemoteSourceStateSourceAdded(
                    sm,
                    metaOther,
                    TEST_SOURCE_ID + 20,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    meta.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null);
        }

        // Modify this source and verify it is not group managed
        BluetoothLeBroadcastMetadata metaUpdate2 = createBroadcastMetadata(TEST_BROADCAST_ID + 30);
        mBassClientService.modifySource(mCurrentDevice1, TEST_SOURCE_ID + 20, metaUpdate2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                verify(sm, never()).sendMessage(any());
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
                verify(sm).sendMessage(messageCaptor.capture());
                List<Message> msgs =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                (m.what
                                                                == BassClientStateMachine
                                                                        .UPDATE_BCAST_SOURCE)
                                                        && (m.arg1 == TEST_SOURCE_ID + 20))
                                .collect(Collectors.toList());
                assertThat(msgs.size()).isEqualTo(1);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }
    }

    @Test
    public void testInvalidRequestForGroup() {
        // Prepare the initial state
        prepareConnectedDeviceGroup();

        // Verify errors are reported for the entire group
        mBassClientService.addSource(mCurrentDevice1, null, true);
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        // Prepare valid source for group
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        verifyAddSourceForGroup(meta);
        prepareRemoteSourceState(meta, true, false);

        // Verify errors are reported for the entire group
        mBassClientService.modifySource(mCurrentDevice, TEST_SOURCE_ID, null);
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            BluetoothDevice dev = sm.getDevice();
            try {
                verify(mCallback)
                        .onSourceModifyFailed(
                                eq(dev),
                                eq(TEST_SOURCE_ID),
                                eq(BluetoothStatusCodes.ERROR_BAD_PARAMETERS));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            doReturn(BluetoothProfile.STATE_DISCONNECTED).when(sm).getConnectionState();
        }

        // Verify errors are reported for the entire group
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            BluetoothDevice dev = sm.getDevice();
            try {
                verify(mCallback)
                        .onSourceRemoveFailed(
                                eq(dev),
                                eq(TEST_SOURCE_ID),
                                eq(BluetoothStatusCodes.ERROR_REMOTE_LINK_ERROR));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Test that an outgoing connection to two device that have BASS UUID is successful and a
     * connection state change intent is sent
     */
    @Test
    public void testConnectedIntent() {
        prepareConnectedDeviceGroup();

        expect.that(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            BluetoothDevice dev = sm.getDevice();
            verifyConnectionStateIntent(
                    TIMEOUT_MS,
                    dev,
                    BluetoothProfile.STATE_CONNECTED,
                    BluetoothProfile.STATE_CONNECTING);
        }

        List<BluetoothDevice> devices = mBassClientService.getConnectedDevices();
        expect.that(devices.contains(mCurrentDevice)).isTrue();
        expect.that(devices.contains(mCurrentDevice1)).isTrue();
    }

    @Test
    public void testActiveSyncedSource_AddRemoveGet() {
        if (Flags.leaudioBroadcastExtractPeriodicScannerFromStateMachine()) {
            final int handle1 = 1;
            final int handle2 = 2;
            final int handle3 = 3;

            // Check if empty
            assertThat(mBassClientService.getActiveSyncedSources()).isEmpty();

            // Check adding first handle
            mBassClientService.addActiveSyncedSource(handle1);
            assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
            assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(handle1);

            // Check if cannot add duplicate element
            mBassClientService.addActiveSyncedSource(handle1);
            assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
            assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(handle1);

            // Check adding second element
            mBassClientService.addActiveSyncedSource(handle2);
            assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(2);
            assertThat(mBassClientService.getActiveSyncedSources())
                    .containsExactly(handle1, handle2)
                    .inOrder();

            // Check removing non existing element
            mBassClientService.removeActiveSyncedSource(handle3);
            assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(2);
            assertThat(mBassClientService.getActiveSyncedSources())
                    .containsExactly(handle1, handle2)
                    .inOrder();
            // Check removing second element
            mBassClientService.removeActiveSyncedSource(handle1);
            assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
            assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(handle2);

            // Check removing first element
            mBassClientService.removeActiveSyncedSource(handle2);
            assertThat(mBassClientService.getActiveSyncedSources()).isEmpty();

            // Add 2 elements
            mBassClientService.addActiveSyncedSource(handle1);
            mBassClientService.addActiveSyncedSource(handle2);
            assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(2);

            // Check removing all at once
            mBassClientService.removeActiveSyncedSource(null);
            assertThat(mBassClientService.getActiveSyncedSources()).isEmpty();
        } else {
            final int testSyncHandle = 1;
            prepareConnectedDeviceGroup();
            assertThat(mStateMachines.size()).isEqualTo(2);

            assertThat(mBassClientService.getActiveSyncedSources(mCurrentDevice)).isEqualTo(null);
            assertThat(mBassClientService.getActiveSyncedSources(mCurrentDevice1)).isEqualTo(null);

            // Verify add active synced source
            mBassClientService.addActiveSyncedSource(mCurrentDevice, testSyncHandle);
            mBassClientService.addActiveSyncedSource(mCurrentDevice1, testSyncHandle);
            // Verify duplicated source won't be added
            mBassClientService.addActiveSyncedSource(mCurrentDevice, testSyncHandle);
            mBassClientService.addActiveSyncedSource(mCurrentDevice1, testSyncHandle);
            assertThat(mBassClientService.getActiveSyncedSources(mCurrentDevice))
                    .isNotEqualTo(null);
            assertThat(mBassClientService.getActiveSyncedSources(mCurrentDevice1))
                    .isNotEqualTo(null);
            assertThat(mBassClientService.getActiveSyncedSources(mCurrentDevice).size())
                    .isEqualTo(1);
            assertThat(mBassClientService.getActiveSyncedSources(mCurrentDevice1).size())
                    .isEqualTo(1);

            // Verify remove active synced source
            mBassClientService.removeActiveSyncedSource(mCurrentDevice, testSyncHandle);
            mBassClientService.removeActiveSyncedSource(mCurrentDevice1, testSyncHandle);
            expect.that(mBassClientService.getActiveSyncedSources(mCurrentDevice)).isEqualTo(null);
            expect.that(mBassClientService.getActiveSyncedSources(mCurrentDevice1)).isEqualTo(null);
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testScanResult_withSameBroadcastId() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // First scanResult
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        // Finish select
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Second scanResult with the same broadcast id
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy, never())
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        // Third scanResult with new broadcast id
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID + 1);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testSelectSource_withSameBroadcastId() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // First selectSource
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        // Finish select
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Second selectSource with the same broadcast id
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy, never())
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testSelectSource_wrongBassUUID() {
        byte[] scanRecord =
                new byte[] {
                    0x02,
                    0x01,
                    0x1a, // advertising flags
                    0x05,
                    0x02,
                    0x52,
                    0x18,
                    0x0a,
                    0x11, // 16 bit service uuids
                    0x04,
                    0x09,
                    0x50,
                    0x65,
                    0x64, // name
                    0x02,
                    0x0A,
                    (byte) 0xec, // tx power level
                    0x05,
                    0x30,
                    0x54,
                    0x65,
                    0x73,
                    0x74, // broadcast name: Test
                    0x06,
                    0x16,
                    0x00, // WRONG BAAS_UUID UUID
                    0x18,
                    (byte) TEST_BROADCAST_ID,
                    (byte) (TEST_BROADCAST_ID >> 8),
                    (byte) (TEST_BROADCAST_ID >> 16), // service data, broadcast id
                    0x08,
                    0x16,
                    0x56,
                    0x18,
                    0x07,
                    0x03,
                    0x06,
                    0x07,
                    0x08,
                    // service data - public broadcast,
                    // feature - 0x7, metadata len - 0x3, metadata - 0x6, 0x7, 0x8
                    0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    0x00,
                    0x02,
                    0x15, // manufacturer specific data
                    0x03,
                    0x50,
                    0x01,
                    0x02, // an unknown data type won't cause trouble
                };
        ScanResult scanResult =
                new ScanResult(
                        mSourceDevice,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI,
                        0,
                        ScanRecord.parseFromBytes(scanRecord),
                        0);

        prepareConnectedDeviceGroup();
        startSearchingForSources();
        mCallbackCaptor.getValue().onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult);
        verify(mMethodProxy, never())
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testSyncEstablished_statusFailed() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // First scanResult
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        // Finish select with failed status
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);

        // Could try to sync again
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testSelectSource_wrongPublicBroadcastUUID() {
        byte[] scanRecord =
                new byte[] {
                    0x02,
                    0x01,
                    0x1a, // advertising flags
                    0x05,
                    0x02,
                    0x52,
                    0x18,
                    0x0a,
                    0x11, // 16 bit service uuids
                    0x04,
                    0x09,
                    0x50,
                    0x65,
                    0x64, // name
                    0x02,
                    0x0A,
                    (byte) 0xec, // tx power level
                    0x05,
                    0x30,
                    0x54,
                    0x65,
                    0x73,
                    0x74, // broadcast name: Test
                    0x06,
                    0x16,
                    0x52,
                    0x18,
                    (byte) TEST_BROADCAST_ID,
                    (byte) (TEST_BROADCAST_ID >> 8),
                    (byte) (TEST_BROADCAST_ID >> 16), // service data, broadcast id
                    0x08,
                    0x16,
                    0x00, // WRONG PUBLIC_BROADCAST_UUID
                    0x18,
                    0x07,
                    0x03,
                    0x06,
                    0x07,
                    0x08,
                    // service data - public broadcast,
                    // feature - 0x7, metadata len - 0x3, metadata - 0x6, 0x7, 0x8
                    0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    0x00,
                    0x02,
                    0x15, // manufacturer specific data
                    0x03,
                    0x50,
                    0x01,
                    0x02, // an unknown data type won't cause trouble
                };
        ScanResult scanResult =
                new ScanResult(
                        mSourceDevice,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI,
                        0,
                        ScanRecord.parseFromBytes(scanRecord),
                        0);

        prepareConnectedDeviceGroup();
        startSearchingForSources();
        mCallbackCaptor.getValue().onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult);
        verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testSelectSource_wrongPublicBroadcastData() {
        byte[] scanRecord =
                new byte[] {
                    0x02,
                    0x01,
                    0x1a, // advertising flags
                    0x05,
                    0x02,
                    0x52,
                    0x18,
                    0x0a,
                    0x11, // 16 bit service uuids
                    0x04,
                    0x09,
                    0x50,
                    0x65,
                    0x64, // name
                    0x02,
                    0x0A,
                    (byte) 0xec, // tx power level
                    0x05,
                    0x30,
                    0x54,
                    0x65,
                    0x73,
                    0x74, // broadcast name: Test
                    0x06,
                    0x16,
                    0x52,
                    0x18,
                    (byte) TEST_BROADCAST_ID,
                    (byte) (TEST_BROADCAST_ID >> 8),
                    (byte) (TEST_BROADCAST_ID >> 16), // service data, broadcast id
                    0x08,
                    0x16,
                    0x56,
                    0x18,
                    0x07,
                    0x04, // WRONG PUBLIC_BROADCAST data (metada size)
                    0x06,
                    0x07,
                    0x08,
                    // service data - public broadcast,
                    // feature - 0x7, metadata len - 0x3, metadata - 0x6, 0x7, 0x8
                    0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    0x00,
                    0x02,
                    0x15, // manufacturer specific data
                    0x03,
                    0x50,
                    0x01,
                    0x02, // an unknown data type won't cause trouble
                };
        ScanResult scanResult =
                new ScanResult(
                        mSourceDevice,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI,
                        0,
                        ScanRecord.parseFromBytes(scanRecord),
                        0);

        prepareConnectedDeviceGroup();
        startSearchingForSources();
        mCallbackCaptor.getValue().onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult);
        verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testSelectSource_queueAndRemoveAfterMaxLimit() {
        final BluetoothDevice device1 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:11", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device2 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:22", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device3 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:33", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device4 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:44", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device5 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:55", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final int handle1 = 0;
        final int handle2 = 1;
        final int handle3 = 2;
        final int handle4 = 3;
        final int handle5 = 4;
        final int broadcastId1 = 1111;
        final int broadcastId2 = 2222;
        final int broadcastId3 = 3333;
        final int broadcastId4 = 4444;
        final int broadcastId5 = 5555;

        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Queue two scan requests
        onScanResult(device1, broadcastId1);
        onScanResult(device2, broadcastId2);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        // Two SyncRequest queued but not synced yet
        assertThat(mBassClientService.getActiveSyncedSources()).isEmpty();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(null);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(null);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(null);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(null);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isEqualTo(null);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);

        // Sync 1
        onSyncEstablished(device1, handle1);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(handle1);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(device1);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(null);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(null);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(null);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isEqualTo(null);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1)).isEqualTo(broadcastId1);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);

        // Sync 2
        onSyncEstablished(device2, handle2);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(2);
        assertThat(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle1, handle2)
                .inOrder();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(device1);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(null);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(null);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isEqualTo(null);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1)).isEqualTo(broadcastId1);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2)).isEqualTo(broadcastId2);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);

        // Scan and sync 3
        onScanResult(device3, broadcastId3);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(device3, handle3);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(3);
        assertThat(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle1, handle2, handle3)
                .inOrder();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(null);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isEqualTo(null);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1)).isEqualTo(broadcastId1);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2)).isEqualTo(broadcastId2);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3)).isEqualTo(broadcastId3);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);

        // Scan and sync 4
        onScanResult(device4, broadcastId4);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(device4, handle4);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(4);
        assertThat(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle1, handle2, handle3, handle4)
                .inOrder();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(device1);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isEqualTo(null);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1)).isEqualTo(broadcastId1);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2)).isEqualTo(broadcastId2);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3)).isEqualTo(broadcastId3);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4)).isEqualTo(broadcastId4);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);

        // Scan 5 cause removing first element
        onScanResult(device5, broadcastId5);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerUnregisterSync(any(), any());
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(3);
        assertThat(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle2, handle3, handle4)
                .inOrder();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(null);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isEqualTo(null);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2)).isEqualTo(broadcastId2);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3)).isEqualTo(broadcastId3);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4)).isEqualTo(broadcastId4);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);

        // Sync 5
        onSyncEstablished(device5, handle5);
        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(4);
        expect.that(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle2, handle3, handle4, handle5)
                .inOrder();
        expect.that(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(null);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle5)).isEqualTo(device5);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle1))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle2))
                .isEqualTo(broadcastId2);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle3))
                .isEqualTo(broadcastId3);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle4))
                .isEqualTo(broadcastId4);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(broadcastId5);
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void testSelectSource_removeAfterMaxLimit_notSyncedToAnySink() {
        final BluetoothDevice device1 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:11", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device2 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:22", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device3 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:33", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device4 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:44", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device5 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:55", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final int handle1 = 0;
        final int handle2 = 1;
        final int handle3 = 2;
        final int handle4 = 3;
        final int handle5 = 4;
        final int broadcastId1 = 1111;
        final int broadcastId2 = 2222;
        final int broadcastId3 = 3333;
        final int broadcastId4 = 4444;
        final int broadcastId5 = 5555;

        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 4 sources
        onScanResult(device1, broadcastId1);
        onSyncEstablished(device1, handle1);
        onScanResult(device2, broadcastId2);
        onSyncEstablished(device2, handle2);
        onScanResult(device3, broadcastId3);
        onSyncEstablished(device3, handle3);
        onScanResult(device4, broadcastId4);
        onSyncEstablished(device4, handle4);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(4);
        assertThat(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle1, handle2, handle3, handle4)
                .inOrder();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(device1);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isEqualTo(null);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1)).isEqualTo(broadcastId1);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2)).isEqualTo(broadcastId2);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3)).isEqualTo(broadcastId3);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4)).isEqualTo(broadcastId4);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);

        // Add source 1
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(broadcastId1);
        verifyAddSourceForGroup(meta);
        prepareRemoteSourceState(meta, true, true);

        // Scan 5 cause removing first element which is not synced to any sink
        onScanResult(device5, broadcastId5);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerUnregisterSync(any(), any());
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(3);
        expect.that(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle1, handle3, handle4)
                .inOrder();
        expect.that(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(device1);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(null);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle5)).isEqualTo(null);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle1))
                .isEqualTo(broadcastId1);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle2))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle3))
                .isEqualTo(broadcastId3);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle4))
                .isEqualTo(broadcastId4);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void testSelectSource_removeAfterMaxLimit_firstIfAllSyncedToSinks() {
        final BluetoothDevice device1 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:11", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device2 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:22", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device3 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:33", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device4 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:44", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device5 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:55", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final int handle1 = 0;
        final int handle2 = 1;
        final int handle3 = 2;
        final int handle4 = 3;
        final int handle5 = 4;
        final int broadcastId1 = 1111;
        final int broadcastId2 = 2222;
        final int broadcastId3 = 3333;
        final int broadcastId4 = 4444;
        final int broadcastId5 = 5555;

        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 4 sources
        onScanResult(device1, broadcastId1);
        onSyncEstablished(device1, handle1);
        onScanResult(device2, broadcastId2);
        onSyncEstablished(device2, handle2);
        onScanResult(device3, broadcastId3);
        onSyncEstablished(device3, handle3);
        onScanResult(device4, broadcastId4);
        onSyncEstablished(device4, handle4);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(4);
        assertThat(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle1, handle2, handle3, handle4)
                .inOrder();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(device1);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isEqualTo(null);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1)).isEqualTo(broadcastId1);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2)).isEqualTo(broadcastId2);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3)).isEqualTo(broadcastId3);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4)).isEqualTo(broadcastId4);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);

        // Fake add 4 sources
        BluetoothLeBroadcastMetadata meta1 = createBroadcastMetadata(broadcastId1);
        BluetoothLeBroadcastMetadata meta2 = createBroadcastMetadata(broadcastId2);
        BluetoothLeBroadcastMetadata meta3 = createBroadcastMetadata(broadcastId3);
        BluetoothLeBroadcastMetadata meta4 = createBroadcastMetadata(broadcastId4);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            injectRemoteSourceStateSourceAdded(
                    sm,
                    meta1,
                    TEST_SOURCE_ID + 1,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    meta1.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null);
            injectRemoteSourceStateSourceAdded(
                    sm,
                    meta2,
                    TEST_SOURCE_ID + 2,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    meta2.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null);
            injectRemoteSourceStateSourceAdded(
                    sm,
                    meta3,
                    TEST_SOURCE_ID + 3,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    meta3.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null);
            injectRemoteSourceStateSourceAdded(
                    sm,
                    meta4,
                    TEST_SOURCE_ID + 4,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    meta4.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null);
        }

        // Scan 5 cause removing first element which is not synced to any sink or first at all
        onScanResult(device5, broadcastId5);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerUnregisterSync(any(), any());
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(3);
        expect.that(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle2, handle3, handle4)
                .inOrder();
        expect.that(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(null);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle5)).isEqualTo(null);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle1))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle2))
                .isEqualTo(broadcastId2);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle3))
                .isEqualTo(broadcastId3);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle4))
                .isEqualTo(broadcastId4);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testAddSourceToUnsyncedSource_causesSyncBeforeAddingSource() {
        final BluetoothDevice device1 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:11", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device2 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:22", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device3 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:33", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device4 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:44", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device5 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:55", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final int handle1 = 0;
        final int handle2 = 1;
        final int handle3 = 2;
        final int handle4 = 3;
        final int handle5 = 4;
        final int broadcastId1 = 1111;
        final int broadcastId2 = 2222;
        final int broadcastId3 = 3333;
        final int broadcastId4 = 4444;
        final int broadcastId5 = 5555;

        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 5 sources cause removing 1 synced element
        onScanResult(device1, broadcastId1);
        onSyncEstablished(device1, handle1);
        onScanResult(device2, broadcastId2);
        onSyncEstablished(device2, handle2);
        onScanResult(device3, broadcastId3);
        onSyncEstablished(device3, handle3);
        onScanResult(device4, broadcastId4);
        onSyncEstablished(device4, handle4);
        onScanResult(device5, broadcastId5);
        mInOrderMethodProxy
                .verify(mMethodProxy, times(4))
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerUnregisterSync(any(), any());
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(device5, handle5);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(4);
        assertThat(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle2, handle3, handle4, handle5)
                .inOrder();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(null);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isEqualTo(device5);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2)).isEqualTo(broadcastId2);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3)).isEqualTo(broadcastId3);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4)).isEqualTo(broadcastId4);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5)).isEqualTo(broadcastId5);

        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setEncrypted(false)
                        .setSourceDevice(device1, BluetoothDevice.ADDRESS_TYPE_RANDOM)
                        .setSourceAdvertisingSid(TEST_ADVERTISER_SID)
                        .setBroadcastId(broadcastId1)
                        .setBroadcastCode(null)
                        .setPaSyncInterval(TEST_PA_SYNC_INTERVAL)
                        .setPresentationDelayMicros(TEST_PRESENTATION_DELAY_MS);
        // builder expect at least one subgroup
        builder.addSubgroup(createBroadcastSubgroup());
        BluetoothLeBroadcastMetadata meta = builder.build();
        ArgumentCaptor<ScanResult> resultCaptor = ArgumentCaptor.forClass(ScanResult.class);

        // Add source to unsynced broadcast, causes synchronization first
        mBassClientService.addSource(mCurrentDevice, meta, true);
        handleHandoverSupport();
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), resultCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertThat(
                        BassUtils.parseBroadcastId(
                                resultCaptor
                                        .getValue()
                                        .getScanRecord()
                                        .getServiceData()
                                        .get(BassConstants.BAAS_UUID)))
                .isEqualTo(broadcastId1);

        // Verify not getting ADD_BCAST_SOURCE message before source sync
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }

        // Source synced which cause execute pending add source
        onSyncEstablished(device1, handle1);

        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(4);
        expect.that(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle3, handle4, handle5, handle1)
                .inOrder();
        expect.that(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(device1);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(null);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle5)).isEqualTo(device5);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle1))
                .isEqualTo(broadcastId1);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle2))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle3))
                .isEqualTo(broadcastId3);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle4))
                .isEqualTo(broadcastId4);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(broadcastId5);

        // Verify all group members getting ADD_BCAST_SOURCE message
        expect.that(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Message msg =
                    messageCaptor.getAllValues().stream()
                            .filter(
                                    m ->
                                            (m.what == BassClientStateMachine.ADD_BCAST_SOURCE)
                                                    && (m.obj == meta))
                            .findFirst()
                            .orElse(null);
            expect.that(msg).isNotNull();
        }
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_ALLOWED_CONTEXT_MASK,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE,
        Flags.FLAG_LEAUDIO_BROADCAST_ASSISTANT_PERIPHERAL_ENTRUSTMENT
    })
    public void testAddSourceForExternalBroadcast_triggerSetContextMask() {
        final int testGroupId = 1;
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        /* Fake external broadcast - no Broadcast Metadata from LE Audio service */
        doReturn(new ArrayList<BluetoothLeBroadcastMetadata>())
                .when(mLeAudioService)
                .getAllBroadcastMetadata();
        doReturn(testGroupId).when(mLeAudioService).getActiveGroupId();
        doReturn(new ArrayList<BluetoothDevice>(Arrays.asList(mCurrentDevice)))
                .when(mLeAudioService)
                .getActiveDevices();

        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);

        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);

        // Add source to unsynced broadcast, causes synchronization first
        mBassClientService.addSource(mCurrentDevice, meta, true);

        // Verify setting allowed context mask is triggered
        verify(mLeAudioService)
                .setActiveGroupAllowedContextMask(
                        eq(
                                BluetoothLeAudio.CONTEXTS_ALL
                                        & ~BluetoothLeAudio.CONTEXT_TYPE_SOUND_EFFECTS),
                        eq(BluetoothLeAudio.CONTEXTS_ALL));
        handleHandoverSupport();

        // Verify all group members getting ADD_BCAST_SOURCE message
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Message msg =
                    messageCaptor.getAllValues().stream()
                            .filter(
                                    m ->
                                            (m.what == BassClientStateMachine.ADD_BCAST_SOURCE)
                                                    && (m.obj == meta))
                            .findFirst()
                            .orElse(null);
            assertThat(msg).isNotNull();
        }

        mBassClientService
                .getCallbacks()
                .notifySourceAddFailed(mCurrentDevice, meta, BluetoothStatusCodes.ERROR_UNKNOWN);
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());

        // Verify resetting allowed context mask is triggered when switching source failed
        verify(mLeAudioService)
                .setActiveGroupAllowedContextMask(
                        eq(BluetoothLeAudio.CONTEXTS_ALL), eq(BluetoothLeAudio.CONTEXTS_ALL));
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testSelectSource_orderOfSyncRegisteringByPriorityAndRssi() {
        final BluetoothDevice device1 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:11", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device2 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:22", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device3 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:33", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device4 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:44", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device5 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:55", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device6 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:66", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device7 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:77", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final int broadcastId1 = 1111;
        final int broadcastId2 = 2222;
        final int broadcastId3 = 3333;
        final int broadcastId4 = 4444;
        final int broadcastId5 = 5555;
        final int broadcastId6 = 6666;
        final int broadcastId7 = 7777;

        byte[] scanRecord1 = getScanRecord(broadcastId1);
        byte[] scanRecord2 = getScanRecord(broadcastId2);
        byte[] scanRecord3 = getScanRecord(broadcastId3);
        byte[] scanRecord4 = getScanRecord(broadcastId4);
        byte[] scanRecord5 = getScanRecord(broadcastId5);
        byte[] scanRecord6 = getScanRecord(broadcastId6);
        byte[] scanRecord7 = getScanRecord(broadcastId7);

        ScanResult scanResult1 =
                new ScanResult(
                        device1,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI,
                        0,
                        ScanRecord.parseFromBytes(scanRecord1),
                        0);
        ScanResult scanResult2 =
                new ScanResult(
                        device2,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI + 3,
                        0,
                        ScanRecord.parseFromBytes(scanRecord2),
                        0);
        ScanResult scanResult3 =
                new ScanResult(
                        device3,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI + 7,
                        0,
                        ScanRecord.parseFromBytes(scanRecord3),
                        0);
        ScanResult scanResult4 =
                new ScanResult(
                        device4,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI + 5,
                        0,
                        ScanRecord.parseFromBytes(scanRecord4),
                        0);
        ScanResult scanResult5 =
                new ScanResult(
                        device5,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI + 2,
                        0,
                        ScanRecord.parseFromBytes(scanRecord5),
                        0);
        ScanResult scanResult6 =
                new ScanResult(
                        device6,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI + 6,
                        0,
                        ScanRecord.parseFromBytes(scanRecord6),
                        0);
        ScanResult scanResult7 =
                new ScanResult(
                        device7,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI + 4,
                        0,
                        ScanRecord.parseFromBytes(scanRecord7),
                        0);

        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Added and executed immidiatelly as no other in queue
        mCallbackCaptor
                .getValue()
                .onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult1);
        // Added to queue with worst rssi
        mCallbackCaptor
                .getValue()
                .onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult2);
        // Added to queue with best rssi
        mCallbackCaptor
                .getValue()
                .onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult3);
        // Added to queue with medium rssi
        mCallbackCaptor
                .getValue()
                .onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult4);
        // Added to queue with worst rssi (increase priority after all)
        mCallbackCaptor
                .getValue()
                .onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult5);
        // Added to queue with best rssi (increase priority after all)
        mCallbackCaptor
                .getValue()
                .onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult6);
        // Added to queue with medium rssi (increase priority after all)
        mCallbackCaptor
                .getValue()
                .onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult7);

        // Increase priority of last 3 of them
        mBassClientService.addSelectSourceRequest(broadcastId5, true);
        mBassClientService.addSelectSourceRequest(broadcastId6, true);
        mBassClientService.addSelectSourceRequest(broadcastId7, true);

        ArgumentCaptor<ScanResult> resultCaptor = ArgumentCaptor.forClass(ScanResult.class);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), resultCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertThat(
                        BassUtils.parseBroadcastId(
                                resultCaptor
                                        .getValue()
                                        .getScanRecord()
                                        .getServiceData()
                                        .get(BassConstants.BAAS_UUID)))
                .isEqualTo(broadcastId1);

        onSyncEstablished(device1, TEST_SYNC_HANDLE);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), resultCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertThat(
                        BassUtils.parseBroadcastId(
                                resultCaptor
                                        .getValue()
                                        .getScanRecord()
                                        .getServiceData()
                                        .get(BassConstants.BAAS_UUID)))
                .isEqualTo(broadcastId6);

        onSyncEstablished(device6, TEST_SYNC_HANDLE + 1);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), resultCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertThat(
                        BassUtils.parseBroadcastId(
                                resultCaptor
                                        .getValue()
                                        .getScanRecord()
                                        .getServiceData()
                                        .get(BassConstants.BAAS_UUID)))
                .isEqualTo(broadcastId7);

        onSyncEstablished(device7, TEST_SYNC_HANDLE + 2);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), resultCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertThat(
                        BassUtils.parseBroadcastId(
                                resultCaptor
                                        .getValue()
                                        .getScanRecord()
                                        .getServiceData()
                                        .get(BassConstants.BAAS_UUID)))
                .isEqualTo(broadcastId5);

        onSyncEstablished(device5, TEST_SYNC_HANDLE + 3);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), resultCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertThat(
                        BassUtils.parseBroadcastId(
                                resultCaptor
                                        .getValue()
                                        .getScanRecord()
                                        .getServiceData()
                                        .get(BassConstants.BAAS_UUID)))
                .isEqualTo(broadcastId3);

        onSyncEstablished(device3, TEST_SYNC_HANDLE + 4);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), resultCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertThat(
                        BassUtils.parseBroadcastId(
                                resultCaptor
                                        .getValue()
                                        .getScanRecord()
                                        .getServiceData()
                                        .get(BassConstants.BAAS_UUID)))
                .isEqualTo(broadcastId4);

        onSyncEstablished(device4, TEST_SYNC_HANDLE + 5);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), resultCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertThat(
                        BassUtils.parseBroadcastId(
                                resultCaptor
                                        .getValue()
                                        .getScanRecord()
                                        .getServiceData()
                                        .get(BassConstants.BAAS_UUID)))
                .isEqualTo(broadcastId2);
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_SORT_SCANS_TO_SYNC_BY_FAILS,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void testSelectSource_orderOfSyncRegisteringByRssiAndFailsCounter() {
        final BluetoothDevice device1 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:11", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device2 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:22", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device3 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:33", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final int broadcastId1 = 1111;
        final int broadcastId2 = 2222;
        final int broadcastId3 = 3333;

        byte[] scanRecord1 = getScanRecord(broadcastId1);
        byte[] scanRecord2 = getScanRecord(broadcastId2);
        byte[] scanRecord3 = getScanRecord(broadcastId3);

        ScanResult scanResult1 =
                new ScanResult(
                        device1,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI + 10,
                        0,
                        ScanRecord.parseFromBytes(scanRecord1),
                        0);
        ScanResult scanResult2 =
                new ScanResult(
                        device2,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI + 9,
                        0,
                        ScanRecord.parseFromBytes(scanRecord2),
                        0);
        ScanResult scanResult3 =
                new ScanResult(
                        device3,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI,
                        0,
                        ScanRecord.parseFromBytes(scanRecord3),
                        0);

        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Test using onSyncEstablishedFailed

        // Added and executed immidiatelly as no other in queue, high rssi
        mCallbackCaptor
                .getValue()
                .onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult1);
        // Added to queue, medium rssi
        mCallbackCaptor
                .getValue()
                .onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult2);
        // Added to queue, low rssi
        mCallbackCaptor
                .getValue()
                .onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult3);

        ArgumentCaptor<ScanResult> resultCaptor = ArgumentCaptor.forClass(ScanResult.class);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), resultCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertThat(
                        BassUtils.parseBroadcastId(
                                resultCaptor
                                        .getValue()
                                        .getScanRecord()
                                        .getServiceData()
                                        .get(BassConstants.BAAS_UUID)))
                .isEqualTo(broadcastId1);

        onSyncEstablishedFailed(device1, TEST_SYNC_HANDLE);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), resultCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertThat(
                        BassUtils.parseBroadcastId(
                                resultCaptor
                                        .getValue()
                                        .getScanRecord()
                                        .getServiceData()
                                        .get(BassConstants.BAAS_UUID)))
                .isEqualTo(broadcastId2);

        // Added to queue again, high rssi
        mCallbackCaptor
                .getValue()
                .onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult1);

        onSyncEstablishedFailed(device2, TEST_SYNC_HANDLE + 1);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), resultCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertThat(
                        BassUtils.parseBroadcastId(
                                resultCaptor
                                        .getValue()
                                        .getScanRecord()
                                        .getServiceData()
                                        .get(BassConstants.BAAS_UUID)))
                .isEqualTo(broadcastId3);

        onSyncEstablished(device3, TEST_SYNC_HANDLE + 2);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), resultCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertThat(
                        BassUtils.parseBroadcastId(
                                resultCaptor
                                        .getValue()
                                        .getScanRecord()
                                        .getServiceData()
                                        .get(BassConstants.BAAS_UUID)))
                .isEqualTo(broadcastId1);

        // Restart searching clears the mSyncFailureCounter
        mBassClientService.stopSearchingForSources();
        mInOrderMethodProxy
                .verify(mMethodProxy, times(2))
                .periodicAdvertisingManagerUnregisterSync(any(), any());
        startSearchingForSources();

        // Test using onSyncLost

        // Added and executed immidiatelly as no other in queue, high rssi
        mCallbackCaptor
                .getValue()
                .onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult1);
        // Added to queue, medium rssi
        mCallbackCaptor
                .getValue()
                .onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult2);
        // Added to queue, low rssi
        mCallbackCaptor
                .getValue()
                .onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult3);

        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), resultCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertThat(
                        BassUtils.parseBroadcastId(
                                resultCaptor
                                        .getValue()
                                        .getScanRecord()
                                        .getServiceData()
                                        .get(BassConstants.BAAS_UUID)))
                .isEqualTo(broadcastId1);

        onSyncEstablished(device1, TEST_SYNC_HANDLE);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), resultCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertThat(
                        BassUtils.parseBroadcastId(
                                resultCaptor
                                        .getValue()
                                        .getScanRecord()
                                        .getServiceData()
                                        .get(BassConstants.BAAS_UUID)))
                .isEqualTo(broadcastId2);
        onSyncLost();

        // Added to queue again, high rssi
        mCallbackCaptor
                .getValue()
                .onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult1);

        onSyncEstablished(device2, TEST_SYNC_HANDLE + 1);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), resultCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertThat(
                        BassUtils.parseBroadcastId(
                                resultCaptor
                                        .getValue()
                                        .getScanRecord()
                                        .getServiceData()
                                        .get(BassConstants.BAAS_UUID)))
                .isEqualTo(broadcastId3);
    }

    @Test
    @DisableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testSelectSource_invalidActiveSource() {
        final int testSyncHandle = 0;
        final int testSyncHandle1 = 1;
        final int testSyncHandle2 = 2;
        final int testSyncHandle3 = 3;
        byte[] scanRecord = getScanRecord(12345);
        ScanRecord record = ScanRecord.parseFromBytes(scanRecord);

        prepareConnectedDeviceGroup();
        assertThat(mStateMachines.size()).isEqualTo(2);

        // Verify add active synced source
        mBassClientService.addActiveSyncedSource(mCurrentDevice, testSyncHandle);
        mBassClientService.addActiveSyncedSource(mCurrentDevice1, testSyncHandle);
        assertThat(mBassClientService.getActiveSyncedSources(mCurrentDevice)).isNotEqualTo(null);
        assertThat(mBassClientService.getActiveSyncedSources(mCurrentDevice1)).isNotEqualTo(null);
        assertThat(mBassClientService.getActiveSyncedSources(mCurrentDevice).size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources(mCurrentDevice1).size()).isEqualTo(1);

        // Verify selectSource with max synced device should not proceed
        mBassClientService.addActiveSyncedSource(mCurrentDevice, testSyncHandle1);
        mBassClientService.addActiveSyncedSource(mCurrentDevice1, testSyncHandle1);
        mBassClientService.addActiveSyncedSource(mCurrentDevice, testSyncHandle2);
        mBassClientService.addActiveSyncedSource(mCurrentDevice1, testSyncHandle2);
        mBassClientService.addActiveSyncedSource(mCurrentDevice, testSyncHandle3);
        mBassClientService.addActiveSyncedSource(mCurrentDevice1, testSyncHandle3);

        assertThat(mBassClientService.getActiveSyncedSources(mCurrentDevice)).isNotEqualTo(null);
        assertThat(mBassClientService.getActiveSyncedSources(mCurrentDevice1)).isNotEqualTo(null);
        assertThat(mBassClientService.getActiveSyncedSources(mCurrentDevice).size()).isEqualTo(4);
        assertThat(mBassClientService.getActiveSyncedSources(mCurrentDevice1).size()).isEqualTo(4);

        BluetoothDevice testDevice4 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:01:02:03:04:05", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        ScanResult scanResult1 = new ScanResult(testDevice4, 0, 0, 0, 0, 0, 0, 0, record, 0);
        mBassClientService.selectSource(mCurrentDevice, scanResult1, false);
        mBassClientService.selectSource(mCurrentDevice1, scanResult1, false);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Optional<Message> msg =
                    messageCaptor.getAllValues().stream()
                            .filter(m -> m.what == BassClientStateMachine.REACHED_MAX_SOURCE_LIMIT)
                            .findFirst();
            assertThat(msg.isPresent()).isEqualTo(true);
        }

        // Verify remove all active synced source
        mBassClientService.removeActiveSyncedSource(mCurrentDevice, null);
        mBassClientService.removeActiveSyncedSource(mCurrentDevice1, null);
        expect.that(mBassClientService.getActiveSyncedSources(mCurrentDevice)).isEqualTo(null);
        expect.that(mBassClientService.getActiveSyncedSources(mCurrentDevice1)).isEqualTo(null);
    }

    @Test
    public void testPeriodicAdvertisementResultMap_updateGetAndModifyNotifiedFlag() {
        final String testBroadcastName = "Test";
        final int testSyncHandle = 1;
        final int testBroadcastId = 42;
        final int testBroadcastIdInvalid = 43;
        final int testAdvertiserSid = 1234;
        final int testAdvInterval = 100;

        // mock the update in selectSource
        mBassClientService.updateSyncHandleForBroadcastId(
                BassConstants.INVALID_SYNC_HANDLE, testBroadcastId);
        mBassClientService.updatePeriodicAdvertisementResultMap(
                mSourceDevice,
                mSourceDevice.getAddressType(),
                BassConstants.INVALID_SYNC_HANDLE,
                BassConstants.INVALID_ADV_SID,
                testAdvInterval,
                testBroadcastId,
                null,
                testBroadcastName);

        // mock the update in onSyncEstablished
        mBassClientService.updatePeriodicAdvertisementResultMap(
                mSourceDevice,
                BassConstants.INVALID_ADV_ADDRESS_TYPE,
                testSyncHandle,
                testAdvertiserSid,
                BassConstants.INVALID_ADV_INTERVAL,
                BassConstants.INVALID_BROADCAST_ID,
                null,
                null);

        assertThat(
                        mBassClientService.getPeriodicAdvertisementResult(
                                mSourceDevice, testBroadcastIdInvalid))
                .isEqualTo(null);
        PeriodicAdvertisementResult paResult =
                mBassClientService.getPeriodicAdvertisementResult(mSourceDevice, testBroadcastId);
        assertThat(paResult.getAddressType()).isEqualTo(BluetoothDevice.ADDRESS_TYPE_RANDOM);
        assertThat(paResult.getSyncHandle()).isEqualTo(testSyncHandle);
        assertThat(paResult.getAdvSid()).isEqualTo(testAdvertiserSid);
        assertThat(paResult.getAdvInterval()).isEqualTo(testAdvInterval);
        assertThat(paResult.getBroadcastName()).isEqualTo(testBroadcastName);

        // validate modify notified flag
        paResult.setNotified(true);
        assertThat(paResult.isNotified()).isEqualTo(true);
        mBassClientService.clearNotifiedFlags();
        assertThat(paResult.isNotified()).isEqualTo(false);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void testPeriodicAdvertisementResultMap_syncEstablishedOnTheSameSyncHandle() {
        final String testBroadcastName1 = "Test1";
        final String testBroadcastName2 = "Test2";
        final int testSyncHandle = 1;
        final int testBroadcastId1 = 42;
        final int testBroadcastId2 = 43;
        final int testAdvertiserSid1 = 1234;
        final int testAdvertiserSid2 = 2345;
        final int testAdvInterval1 = 100;
        final int testAdvInterval2 = 200;

        // mock the update in selectSource
        mBassClientService.updateSyncHandleForBroadcastId(
                BassConstants.INVALID_SYNC_HANDLE, testBroadcastId1);
        mBassClientService.updatePeriodicAdvertisementResultMap(
                mSourceDevice,
                mSourceDevice.getAddressType(),
                BassConstants.INVALID_SYNC_HANDLE,
                BassConstants.INVALID_ADV_SID,
                testAdvInterval1,
                testBroadcastId1,
                null,
                testBroadcastName1);

        // mock the update in onSyncEstablished
        mBassClientService.updatePeriodicAdvertisementResultMap(
                mSourceDevice,
                BassConstants.INVALID_ADV_ADDRESS_TYPE,
                testSyncHandle,
                testAdvertiserSid1,
                BassConstants.INVALID_ADV_INTERVAL,
                BassConstants.INVALID_BROADCAST_ID,
                null,
                null);

        assertThat(
                        mBassClientService.getPeriodicAdvertisementResult(
                                mSourceDevice, testBroadcastId2))
                .isEqualTo(null);
        PeriodicAdvertisementResult paResult =
                mBassClientService.getPeriodicAdvertisementResult(mSourceDevice, testBroadcastId1);
        assertThat(paResult.getAddressType()).isEqualTo(BluetoothDevice.ADDRESS_TYPE_RANDOM);
        assertThat(paResult.getSyncHandle()).isEqualTo(testSyncHandle);
        assertThat(paResult.getAdvSid()).isEqualTo(testAdvertiserSid1);
        assertThat(paResult.getAdvInterval()).isEqualTo(testAdvInterval1);
        assertThat(paResult.getBroadcastName()).isEqualTo(testBroadcastName1);

        // mock the update in selectSource
        mBassClientService.updateSyncHandleForBroadcastId(
                BassConstants.INVALID_SYNC_HANDLE, testBroadcastId2);
        mBassClientService.updatePeriodicAdvertisementResultMap(
                mSourceDevice,
                mSourceDevice.getAddressType(),
                BassConstants.INVALID_SYNC_HANDLE,
                BassConstants.INVALID_ADV_SID,
                testAdvInterval2,
                testBroadcastId2,
                null,
                testBroadcastName2);

        // mock the update in onSyncEstablished
        mBassClientService.updatePeriodicAdvertisementResultMap(
                mSourceDevice,
                BassConstants.INVALID_ADV_ADDRESS_TYPE,
                testSyncHandle,
                testAdvertiserSid2,
                BassConstants.INVALID_ADV_INTERVAL,
                BassConstants.INVALID_BROADCAST_ID,
                null,
                null);

        expect.that(
                        mBassClientService.getPeriodicAdvertisementResult(
                                mSourceDevice, testBroadcastId1))
                .isEqualTo(null);
        paResult =
                mBassClientService.getPeriodicAdvertisementResult(mSourceDevice, testBroadcastId2);
        expect.that(paResult.getAddressType()).isEqualTo(BluetoothDevice.ADDRESS_TYPE_RANDOM);
        expect.that(paResult.getSyncHandle()).isEqualTo(testSyncHandle);
        expect.that(paResult.getAdvSid()).isEqualTo(testAdvertiserSid2);
        expect.that(paResult.getAdvInterval()).isEqualTo(testAdvInterval2);
        expect.that(paResult.getBroadcastName()).isEqualTo(testBroadcastName2);
    }

    @Test
    public void testSyncHandleToBroadcastIdMap_getSyncHandleAndGetBroadcastId() {
        if (Flags.leaudioBroadcastExtractPeriodicScannerFromStateMachine()) {
            final int testSyncHandle = 1;
            final int testSyncHandleInvalid = 2;
            final int testBroadcastId = 42;
            final int testBroadcastIdInvalid = 43;

            prepareConnectedDeviceGroup();
            startSearchingForSources();
            onScanResult(mSourceDevice, testBroadcastId);
            onSyncEstablished(mSourceDevice, testSyncHandle);

            assertThat(mBassClientService.getSyncHandleForBroadcastId(testBroadcastIdInvalid))
                    .isEqualTo(BassConstants.INVALID_SYNC_HANDLE);
            assertThat(mBassClientService.getBroadcastIdForSyncHandle(testSyncHandleInvalid))
                    .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
            assertThat(mBassClientService.getSyncHandleForBroadcastId(testBroadcastId))
                    .isEqualTo(testSyncHandle);
            assertThat(mBassClientService.getBroadcastIdForSyncHandle(testSyncHandle))
                    .isEqualTo(testBroadcastId);
        } else {
            final String testBroadcastName = "Test";
            final int testSyncHandle = 1;
            final int testSyncHandleInvalid = 2;
            final int testBroadcastId = 42;
            final int testBroadcastIdInvalid = 43;
            final int testAdvertiserSid = 1234;
            final int testAdvInterval = 100;

            // mock the update in selectSource
            mBassClientService.updatePeriodicAdvertisementResultMap(
                    mSourceDevice,
                    mSourceDevice.getAddressType(),
                    BassConstants.INVALID_SYNC_HANDLE,
                    BassConstants.INVALID_ADV_SID,
                    testAdvInterval,
                    testBroadcastId,
                    null,
                    testBroadcastName);

            // mock the update in onSyncEstablished
            mBassClientService.updatePeriodicAdvertisementResultMap(
                    mSourceDevice,
                    BassConstants.INVALID_ADV_ADDRESS_TYPE,
                    testSyncHandle,
                    testAdvertiserSid,
                    BassConstants.INVALID_ADV_INTERVAL,
                    BassConstants.INVALID_BROADCAST_ID,
                    null,
                    null);

            expect.that(mBassClientService.getSyncHandleForBroadcastId(testBroadcastIdInvalid))
                    .isEqualTo(BassConstants.INVALID_SYNC_HANDLE);
            expect.that(mBassClientService.getBroadcastIdForSyncHandle(testSyncHandleInvalid))
                    .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
            expect.that(mBassClientService.getSyncHandleForBroadcastId(testBroadcastId))
                    .isEqualTo(testSyncHandle);
            expect.that(mBassClientService.getBroadcastIdForSyncHandle(testSyncHandle))
                    .isEqualTo(testBroadcastId);
        }
    }

    private void verifyAllGroupMembersGettingUpdateOrAddSource(BluetoothLeBroadcastMetadata meta) {
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());
            long count;

            if (sm.getDevice().equals(mCurrentDevice)) {
                count =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                ((m.what
                                                                        == BassClientStateMachine
                                                                                .UPDATE_BCAST_SOURCE)
                                                                && (m.obj.equals(meta))
                                                                && (m.arg1 == TEST_SOURCE_ID)
                                                                && (m.arg2
                                                                        == BassConstants
                                                                                .PA_SYNC_PAST_AVAILABLE))
                                                        || ((m.what
                                                                        == BassClientStateMachine
                                                                                .ADD_BCAST_SOURCE)
                                                                && (m.obj.equals(meta)))
                                                        || ((m.what
                                                                        == BassClientStateMachine
                                                                                .SWITCH_BCAST_SOURCE)
                                                                && (m.obj.equals(meta))
                                                                && (m.arg1 == TEST_SOURCE_ID)))
                                .count();
                assertThat(count).isEqualTo(1);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                count =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                ((m.what
                                                                        == BassClientStateMachine
                                                                                .UPDATE_BCAST_SOURCE)
                                                                && (m.obj.equals(meta))
                                                                && (m.arg1 == TEST_SOURCE_ID + 1)
                                                                && (m.arg2
                                                                        == BassConstants
                                                                                .PA_SYNC_PAST_AVAILABLE))
                                                        || ((m.what
                                                                        == BassClientStateMachine
                                                                                .ADD_BCAST_SOURCE)
                                                                && (m.obj.equals(meta)))
                                                        || ((m.what
                                                                        == BassClientStateMachine
                                                                                .SWITCH_BCAST_SOURCE)
                                                                && (m.obj.equals(meta))
                                                                && (m.arg1 == TEST_SOURCE_ID + 1)))
                                .count();
                assertThat(count).isEqualTo(1);
            }
        }
    }

    @Test
    public void testSuspendResumeSourceSynchronization() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        verifyAddSourceForGroup(meta);
        prepareRemoteSourceState(meta, true, false);

        if (Flags.leaudioBroadcastAssistantPeripheralEntrustment()) {
            injectRemoteSourceStateChanged(meta, true, true);
            verify(mLeAudioService).activeBroadcastAssistantNotification(eq(true));
            Mockito.clearInvocations(mLeAudioService);

            /* Imitate broadcast source stop, sink notify about loosing PA and BIS sync */
            injectRemoteSourceStateChanged(meta, false, false);

            /* Unicast would like to stream */
            mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID);
        } else {
            mBassClientService.suspendAllReceiversSourceSynchronization();
            verifyRemoveMessageAndInjectSourceRemoval();
        }

        mBassClientService.resumeReceiversSourceSynchronization();
        handleHandoverSupport();
        verifyAllGroupMembersGettingUpdateOrAddSource(meta);
    }

    @Test
    public void testHandleUnicastSourceStreamStatusChange() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);

        /* Fake external broadcast - no Broadcast Metadata from LE Audio service */
        doReturn(new ArrayList<BluetoothLeBroadcastMetadata>())
                .when(mLeAudioService)
                .getAllBroadcastMetadata();

        verifyAddSourceForGroup(meta);
        prepareRemoteSourceState(meta, true, true);

        verify(mLeAudioService).activeBroadcastAssistantNotification(eq(true));

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                0 /* STATUS_LOCAL_STREAM_REQUESTED */);

        if (Flags.leaudioBroadcastAssistantPeripheralEntrustment()) {
            /* Imitate broadcast source stop, sink notify about loosing PA and BIS sync */
            injectRemoteSourceStateChanged(meta, false, false);
        } else {
            verifyRemoveMessageAndInjectSourceRemoval();
        }

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                2 /* STATUS_LOCAL_STREAM_SUSPENDED */);

        verifyAllGroupMembersGettingUpdateOrAddSource(meta);

        // Update receiver state with lost BIS sync
        injectRemoteSourceStateChanged(meta, true, false);
        if (!Flags.leaudioBroadcastResyncHelper()) {
            verify(mLeAudioService).activeBroadcastAssistantNotification(eq(false));
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_ASSISTANT_PERIPHERAL_ENTRUSTMENT)
    public void testHandleUnicastSourceStreamStatusChange_MultipleRequests() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);

        /* Fake external broadcast - no Broadcast Metadata from LE Audio service */
        doReturn(new ArrayList<BluetoothLeBroadcastMetadata>())
                .when(mLeAudioService)
                .getAllBroadcastMetadata();

        verifyAddSourceForGroup(meta);
        prepareRemoteSourceState(meta, true, true);

        verify(mLeAudioService).activeBroadcastAssistantNotification(eq(true));

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                3 /* STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE */);

        /* Imitate broadcast source stop, sink notify about loosing BIS sync */
        verifyRemoveMessageAndInjectSourceRemoval();

        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            Mockito.clearInvocations(sm);
        }
        // Make another stream request with no context validate
        // and verify sm didn't get REMOVE_BCAST_SOURCE
        mBassClientService.handleUnicastSourceStreamStatusChange(
                3 /* STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE */);

        // Make another stream request
        // and verify sinks to resume remain unchanged later
        mBassClientService.handleUnicastSourceStreamStatusChange(
                0 /* STATUS_LOCAL_STREAM_REQUESTED */);

        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                2 /* STATUS_LOCAL_STREAM_SUSPENDED */);

        // Verify all group members resume with the previous cached source
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Message msg =
                    messageCaptor.getAllValues().stream()
                            .filter(
                                    m ->
                                            (m.what == BassClientStateMachine.ADD_BCAST_SOURCE)
                                                    && (m.obj == meta))
                            .findFirst()
                            .orElse(null);
            expect.that(msg).isNotNull();
        }
    }

    @Test
    public void testIsAnyReceiverReceivingBroadcast() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        verifyAddSourceForGroup(meta);
        prepareRemoteSourceState(meta, true, false);

        List<BluetoothDevice> devices = mBassClientService.getConnectedDevices();
        // Verify isAnyReceiverReceivingBroadcast returns false if no BIS synced
        assertThat(mBassClientService.isAnyReceiverReceivingBroadcast(devices)).isFalse();

        // Update receiver state with BIS sync
        injectRemoteSourceStateChanged(meta, true, true);
        BluetoothDevice invalidDevice = TestUtils.getTestDevice(mBluetoothAdapter, 2);
        // Verify isAnyReceiverReceivingBroadcast returns false if invalid device
        expect.that(mBassClientService.isAnyReceiverReceivingBroadcast(List.of(invalidDevice)))
                .isFalse();
        // Verify isAnyReceiverReceivingBroadcast returns true if BIS synced
        expect.that(mBassClientService.isAnyReceiverReceivingBroadcast(devices)).isTrue();
    }

    @Test
    public void testGetSyncedBroadcastSinks() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        BluetoothLeBroadcastMetadata metaNoBroadcast = createEmptyBroadcastMetadata();

        verifyAddSourceForGroup(meta);
        prepareRemoteSourceState(metaNoBroadcast, true, false);

        // Verify getSyncedBroadcastSinks returns empty device list if no broadcst ID
        assertThat(mBassClientService.getSyncedBroadcastSinks().isEmpty()).isTrue();

        // Update receiver state with broadcast ID
        injectRemoteSourceStateChanged(meta, true, false);

        List<BluetoothDevice> activeSinks = mBassClientService.getSyncedBroadcastSinks();
        if (Flags.leaudioBigDependsOnAudioState()) {
            // Verify getSyncedBroadcastSinks returns correct device list if no BIS synced
            assertThat(activeSinks.size()).isEqualTo(2);
            assertThat(activeSinks.contains(mCurrentDevice)).isTrue();
            assertThat(activeSinks.contains(mCurrentDevice1)).isTrue();
        } else {
            // Verify getSyncedBroadcastSinks returns empty device list if no BIS synced
            assertThat(mBassClientService.getSyncedBroadcastSinks().isEmpty()).isTrue();
        }

        // Update receiver state with BIS sync
        injectRemoteSourceStateChanged(meta, true, true);

        // Verify getSyncedBroadcastSinks returns correct device list if BIS synced
        activeSinks = mBassClientService.getSyncedBroadcastSinks();
        expect.that(activeSinks.size()).isEqualTo(2);
        expect.that(activeSinks.contains(mCurrentDevice)).isTrue();
        expect.that(activeSinks.contains(mCurrentDevice1)).isTrue();
    }

    private void prepareTwoSynchronizedDevicesForLocalBroadcast() {
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);

        doReturn(new ArrayList<BluetoothLeBroadcastMetadata>(Arrays.asList(meta)))
                .when(mLeAudioService)
                .getAllBroadcastMetadata();
        prepareConnectedDeviceGroup();
        verifyAddSourceForGroup(meta);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        meta,
                        TEST_SOURCE_ID,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null);
                // verify source id
                try {
                    verify(mCallback, timeout(TIMEOUT_MS).atLeastOnce())
                            .onSourceAdded(
                                    eq(mCurrentDevice),
                                    eq(TEST_SOURCE_ID),
                                    eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        meta,
                        TEST_SOURCE_ID + 1,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null);
                // verify source id
                try {
                    verify(mCallback, timeout(TIMEOUT_MS).atLeastOnce())
                            .onSourceAdded(
                                    eq(mCurrentDevice1),
                                    eq(TEST_SOURCE_ID + 1),
                                    eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_ASSISTANT_PERIPHERAL_ENTRUSTMENT)
    public void testLocalAddSourceWhenBroadcastIsPlaying() {
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        if (Flags.leaudioBigDependsOnAudioState()) {
            doReturn(false).when(mLeAudioService).isPaused(TEST_BROADCAST_ID);
        }

        prepareTwoSynchronizedDevicesForLocalBroadcast();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_ASSISTANT_PERIPHERAL_ENTRUSTMENT,
        Flags.FLAG_LEAUDIO_BIG_DEPENDS_ON_AUDIO_STATE
    })
    public void testLocalAddSourceWhenBroadcastIsPaused() {
        doReturn(false).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        doReturn(true).when(mLeAudioService).isPaused(TEST_BROADCAST_ID);

        prepareTwoSynchronizedDevicesForLocalBroadcast();
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_ASSISTANT_PERIPHERAL_ENTRUSTMENT)
    public void testLocalAddSourceWhenBroadcastIsStopped() {
        doReturn(false).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        if (Flags.leaudioBigDependsOnAudioState()) {
            doReturn(false).when(mLeAudioService).isPaused(TEST_BROADCAST_ID);
        }

        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);

        doReturn(new ArrayList<BluetoothLeBroadcastMetadata>(Arrays.asList(meta)))
                .when(mLeAudioService)
                .getAllBroadcastMetadata();
        prepareConnectedDeviceGroup();
        mBassClientService.addSource(mCurrentDevice, meta, true);
        try {
            verify(mCallback, timeout(TIMEOUT_MS).atLeastOnce())
                    .onSourceAddFailed(
                            eq(mCurrentDevice),
                            eq(meta),
                            eq(BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Test
    public void testSinksDisconnectionWhenBroadcastIsPlaying() {
        /* Imitate broadcast being active */
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        if (Flags.leaudioBigDependsOnAudioState()) {
            doReturn(false).when(mLeAudioService).isPaused(TEST_BROADCAST_ID);
        }

        prepareTwoSynchronizedDevicesForLocalBroadcast();

        /* Imitiate scenario when if there would be broadcast - stop would be called */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice, true);
        mBassClientService.handleDeviceDisconnection(mCurrentDevice1, true);

        verify(mLeAudioService).stopBroadcast(eq(TEST_BROADCAST_ID));
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BIG_DEPENDS_ON_AUDIO_STATE)
    public void testSinksDisconnectionWhenBroadcastIsPaused() {
        /* Imitate broadcast being active */
        doReturn(false).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        doReturn(true).when(mLeAudioService).isPaused(TEST_BROADCAST_ID);

        prepareTwoSynchronizedDevicesForLocalBroadcast();

        /* Imitiate scenario when if there would be broadcast - stop would be called */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice, true);
        mBassClientService.handleDeviceDisconnection(mCurrentDevice1, true);

        verify(mLeAudioService).stopBroadcast(eq(TEST_BROADCAST_ID));
    }

    @Test
    public void testSinksDisconnectionWhenBroadcastIsStopped() {
        /* Imitate broadcast being active */
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        if (Flags.leaudioBigDependsOnAudioState()) {
            doReturn(false).when(mLeAudioService).isPaused(TEST_BROADCAST_ID);
        }

        prepareTwoSynchronizedDevicesForLocalBroadcast();

        doReturn(false).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);

        /* Imitiate scenario when if there would be broadcast - stop would be called */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice, true);
        mBassClientService.handleDeviceDisconnection(mCurrentDevice1, true);

        verify(mLeAudioService, never()).stopBroadcast(eq(TEST_BROADCAST_ID));
    }

    @Test
    public void testPrivateBroadcastIntentionalDisconnection() {
        /* Imitate broadcast being active */
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);

        prepareTwoSynchronizedDevicesForLocalBroadcast();

        /* Imitate devices being primary */
        doReturn(true).when(mLeAudioService).isPrimaryDevice(mCurrentDevice);
        doReturn(true).when(mLeAudioService).isPrimaryDevice(mCurrentDevice1);

        /* Imitate device 1/2 disconnection from StateMachine context */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice, true);

        /* After first device disconnection and de-synchronization expect not stopping broadcast */
        verify(mLeAudioService, never()).stopBroadcast(eq(TEST_BROADCAST_ID));

        /* Imitate first device being in disconnected state */
        doReturn(BluetoothProfile.STATE_DISCONNECTED)
                .when(mStateMachines.get(mCurrentDevice))
                .getConnectionState();

        /* Imitate device 2/2 disconnection from StateMachine context */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice1, true);

        /* After second device disconnection and de-synchronization expect stopping broadcast */
        verify(mLeAudioService).stopBroadcast(eq(TEST_BROADCAST_ID));
    }

    @Test
    public void testPrivateBroadcastUnintentionalDisconnection() {
        /* Imitate broadcast being active */
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);

        prepareTwoSynchronizedDevicesForLocalBroadcast();

        /* Imitate devices being primary */
        doReturn(true).when(mLeAudioService).isPrimaryDevice(mCurrentDevice);
        doReturn(true).when(mLeAudioService).isPrimaryDevice(mCurrentDevice1);

        /* Imitate device 1/2 disconnection from StateMachine context */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice, false);

        /* After first device disconnection and de-synchronization expect not stopping broadcast */
        verify(mLeAudioService, never()).stopBroadcast(eq(TEST_BROADCAST_ID));

        /* Imitate first device being in disconnected state */
        doReturn(BluetoothProfile.STATE_DISCONNECTED)
                .when(mStateMachines.get(mCurrentDevice))
                .getConnectionState();

        /* Imitate device 2/2 disconnection from StateMachine context */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice1, false);

        /* After second device disconnection and de-synchronization expect stopping broadcast */
        verify(mLeAudioService).stopBroadcast(eq(TEST_BROADCAST_ID));
    }

    @Test
    public void testAudioSharingIntentionalDisconnection() {
        /* Imitate broadcast being active */
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);

        prepareTwoSynchronizedDevicesForLocalBroadcast();

        /* Imitate devices being primary */
        doReturn(true).when(mLeAudioService).isPrimaryDevice(mCurrentDevice);
        doReturn(false).when(mLeAudioService).isPrimaryDevice(mCurrentDevice1);

        /* Imitate device 1/2 disconnection from StateMachine context */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice, true);

        /* After first device disconnection and de-synchronization expect stopping broadcast */
        verify(mLeAudioService).stopBroadcast(eq(TEST_BROADCAST_ID));

        /* Imitate first device being in disconnected state */
        doReturn(BluetoothProfile.STATE_DISCONNECTED)
                .when(mStateMachines.get(mCurrentDevice))
                .getConnectionState();

        /* Imitate device 2/2 disconnection from StateMachine context */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice1, true);

        /* After second device disconnection and de-synchronization expect not stopping broadcast */
        verify(mLeAudioService).stopBroadcast(eq(TEST_BROADCAST_ID));
    }

    @Test
    public void testAudioSharingUnintentionalDisconnection() {
        /* Imitate broadcast being active */
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);

        prepareTwoSynchronizedDevicesForLocalBroadcast();

        /* Imitate devices being primary */
        doReturn(true).when(mLeAudioService).isPrimaryDevice(mCurrentDevice);
        doReturn(false).when(mLeAudioService).isPrimaryDevice(mCurrentDevice1);

        /* Imitate device 1/2 disconnection from StateMachine context */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice, false);

        /* After first device disconnection and de-synchronization expect not stopping broadcast */
        verify(mLeAudioService, never()).stopBroadcast(eq(TEST_BROADCAST_ID));

        /* Imitate first device being in disconnected state */
        doReturn(BluetoothProfile.STATE_DISCONNECTED)
                .when(mStateMachines.get(mCurrentDevice))
                .getConnectionState();

        /* Imitate device 2/2 disconnection from StateMachine context */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice1, false);

        /* After second device disconnection and de-synchronization timeout to be fired */
        verify(mLeAudioService, never()).stopBroadcast(eq(TEST_BROADCAST_ID));
    }

    @Test
    public void testNotifyBroadcastStateChangedStopped() {
        /* Imitate broadcast being active */
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);

        prepareTwoSynchronizedDevicesForLocalBroadcast();

        mBassClientService.notifyBroadcastStateChanged(
                0 /* BROADCAST_STATE_STOPPED */, TEST_BROADCAST_ID);

        /* Imitiate scenario when if there would be broadcast - stop would be called */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice, true);
        mBassClientService.handleDeviceDisconnection(mCurrentDevice1, true);

        /* After second device disconnection and de-synchronization expect not calling broadcast to
         * stop due to previous broadcast stream stopped */
        verify(mLeAudioService, never()).stopBroadcast(eq(TEST_BROADCAST_ID));
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void onPeriodicAdvertisingReport_withoutBaseData_cancelActiveSync() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isEqualTo(null);

        byte[] scanRecord =
                new byte[] {
                    0x02,
                    0x01,
                    0x1a, // advertising flags
                    0x05,
                    0x02,
                    0x0b,
                    0x11,
                    0x0a,
                    0x11, // 16 bit service uuids
                    0x04,
                    0x09,
                    0x50,
                    0x65,
                    0x64, // name
                    0x02,
                    0x0A,
                    (byte) 0xec, // tx power level
                    0x05,
                    0x16,
                    0x0b,
                    0x11,
                    0x50,
                    0x64, // service data
                    0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    0x00,
                    0x02,
                    0x15, // manufacturer specific data
                    0x03,
                    0x50,
                    0x01,
                    0x02, // an unknown data type won't cause trouble
                };
        PeriodicAdvertisingReport report =
                new PeriodicAdvertisingReport(
                        TEST_SYNC_HANDLE, 0, 0, 0, ScanRecord.parseFromBytes(scanRecord));

        BassClientService.PACallback callback = mBassClientService.new PACallback();

        callback.onPeriodicAdvertisingReport(report);
        callback.onPeriodicAdvertisingReport(report);
        callback.onPeriodicAdvertisingReport(report);
        callback.onPeriodicAdvertisingReport(report);

        // Not canceled, not updated base
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isEqualTo(null);
        mInOrderMethodProxy
                .verify(mMethodProxy, never())
                .periodicAdvertisingManagerUnregisterSync(any(), any());

        callback.onPeriodicAdvertisingReport(report);

        // Canceled, not updated base
        expect.that(mBassClientService.getActiveSyncedSources()).isEmpty();
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isEqualTo(null);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        expect.that(mBassClientService.getBase(TEST_SYNC_HANDLE)).isEqualTo(null);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerUnregisterSync(any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void onPeriodicAdvertisingReport_wrongBaseData_cancelActiveSync() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isEqualTo(null);

        byte[] scanRecord =
                new byte[] {
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x1a, // advertising flags
                    (byte) 0x05,
                    (byte) 0x02,
                    (byte) 0x51,
                    (byte) 0x18,
                    (byte) 0x0a,
                    (byte) 0x11, // 16 bit service uuids
                    (byte) 0x04,
                    (byte) 0x09,
                    (byte) 0x50,
                    (byte) 0x65,
                    (byte) 0x64, // name
                    (byte) 0x02,
                    (byte) 0x0A,
                    (byte) 0xec, // tx power level
                    (byte) 0x19,
                    (byte) 0x16,
                    (byte) 0x51,
                    (byte) 0x18, // service data (base data with 18 bytes)
                    // LEVEL 1
                    (byte) 0x01,
                    (byte) 0x02,
                    (byte) 0x03, // presentationDelay
                    (byte) 0x01, // numSubGroups
                    // LEVEL 3
                    (byte) 0x04, // index
                    (byte) 0x03, // codecConfigLength
                    (byte) 0x02,
                    (byte) 'B',
                    (byte) 'C', // codecConfigInfo
                    (byte) 0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    (byte) 0x00,
                    (byte) 0x02,
                    (byte) 0x15, // manufacturer specific data
                    (byte) 0x03,
                    (byte) 0x50,
                    (byte) 0x01,
                    (byte) 0x02, // an unknown data type won't cause trouble
                };
        PeriodicAdvertisingReport report =
                new PeriodicAdvertisingReport(
                        TEST_SYNC_HANDLE, 0, 0, 0, ScanRecord.parseFromBytes(scanRecord));

        BassClientService.PACallback callback = mBassClientService.new PACallback();

        callback.onPeriodicAdvertisingReport(report);
        callback.onPeriodicAdvertisingReport(report);
        callback.onPeriodicAdvertisingReport(report);
        callback.onPeriodicAdvertisingReport(report);

        // Not canceled, not updated base
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isEqualTo(null);
        mInOrderMethodProxy
                .verify(mMethodProxy, never())
                .periodicAdvertisingManagerUnregisterSync(any(), any());

        callback.onPeriodicAdvertisingReport(report);

        // Canceled, not updated base
        expect.that(mBassClientService.getActiveSyncedSources()).isEmpty();
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isEqualTo(null);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);
        expect.that(mBassClientService.getBase(TEST_SYNC_HANDLE)).isEqualTo(null);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerUnregisterSync(any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void onPeriodicAdvertisingReport_updateBase() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isEqualTo(null);

        onPeriodicAdvertisingReport();

        // Not canceled, updated base
        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        expect.that(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        expect.that(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNotEqualTo(null);
        mInOrderMethodProxy
                .verify(mMethodProxy, never())
                .periodicAdvertisingManagerUnregisterSync(any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void onPeriodicAdvertisingReport_updateBaseAfterWrongBaseData() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isEqualTo(null);

        byte[] scanRecordNoBaseData =
                new byte[] {
                    0x02,
                    0x01,
                    0x1a, // advertising flags
                    0x05,
                    0x02,
                    0x0b,
                    0x11,
                    0x0a,
                    0x11, // 16 bit service uuids
                    0x04,
                    0x09,
                    0x50,
                    0x65,
                    0x64, // name
                    0x02,
                    0x0A,
                    (byte) 0xec, // tx power level
                    0x05,
                    0x16,
                    0x0b,
                    0x11,
                    0x50,
                    0x64, // service data
                    0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    0x00,
                    0x02,
                    0x15, // manufacturer specific data
                    0x03,
                    0x50,
                    0x01,
                    0x02, // an unknown data type won't cause trouble
                };

        byte[] scanRecordWrongBaseData =
                new byte[] {
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x1a, // advertising flags
                    (byte) 0x05,
                    (byte) 0x02,
                    (byte) 0x51,
                    (byte) 0x18,
                    (byte) 0x0a,
                    (byte) 0x11, // 16 bit service uuids
                    (byte) 0x04,
                    (byte) 0x09,
                    (byte) 0x50,
                    (byte) 0x65,
                    (byte) 0x64, // name
                    (byte) 0x02,
                    (byte) 0x0A,
                    (byte) 0xec, // tx power level
                    (byte) 0x19,
                    (byte) 0x16,
                    (byte) 0x51,
                    (byte) 0x18, // service data (base data with 18 bytes)
                    // LEVEL 1
                    (byte) 0x01,
                    (byte) 0x02,
                    (byte) 0x03, // presentationDelay
                    (byte) 0x01, // numSubGroups
                    // LEVEL 3
                    (byte) 0x04, // index
                    (byte) 0x03, // codecConfigLength
                    (byte) 0x02,
                    (byte) 'B',
                    (byte) 'C', // codecConfigInfo
                    (byte) 0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    (byte) 0x00,
                    (byte) 0x02,
                    (byte) 0x15, // manufacturer specific data
                    (byte) 0x03,
                    (byte) 0x50,
                    (byte) 0x01,
                    (byte) 0x02, // an unknown data type won't cause trouble
                };

        BassClientService.PACallback callback = mBassClientService.new PACallback();

        PeriodicAdvertisingReport report =
                new PeriodicAdvertisingReport(
                        TEST_SYNC_HANDLE, 0, 0, 0, ScanRecord.parseFromBytes(scanRecordNoBaseData));
        callback.onPeriodicAdvertisingReport(report);
        report =
                new PeriodicAdvertisingReport(
                        TEST_SYNC_HANDLE,
                        0,
                        0,
                        0,
                        ScanRecord.parseFromBytes(scanRecordWrongBaseData));
        callback.onPeriodicAdvertisingReport(report);

        // Not canceled, not updated base
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isEqualTo(null);
        mInOrderMethodProxy
                .verify(mMethodProxy, never())
                .periodicAdvertisingManagerUnregisterSync(any(), any());

        onPeriodicAdvertisingReport();

        // Not canceled, updated base
        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        expect.that(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        expect.that(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNotEqualTo(null);
        mInOrderMethodProxy
                .verify(mMethodProxy, never())
                .periodicAdvertisingManagerUnregisterSync(any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void notifySourceFound_once_updateRssi() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isEqualTo(null);

        onPeriodicAdvertisingReport();

        // Not canceled, updated base
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNotEqualTo(null);

        if (!Flags.leaudioBigDependsOnAudioState()) {
            onBigInfoAdvertisingReport();
        }

        // Notified
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        ArgumentCaptor<BluetoothLeBroadcastMetadata> metaData =
                ArgumentCaptor.forClass(BluetoothLeBroadcastMetadata.class);
        InOrder inOrder = inOrder(mCallback);
        try {
            inOrder.verify(mCallback).onSourceFound(metaData.capture());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        Assert.assertEquals(TEST_RSSI, metaData.getValue().getRssi());

        // Any of them should not notified second time
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();

        // Not notified second time
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        try {
            inOrder.verify(mCallback, never()).onSourceFound(any());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE,
        Flags.FLAG_LEAUDIO_BIG_DEPENDS_ON_AUDIO_STATE
    })
    public void notifySourceFound_without_public_announcement() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        byte[] broadcastScanRecord =
                new byte[] {
                    0x02,
                    0x01,
                    0x1a, // advertising flags
                    0x05,
                    0x02,
                    0x52,
                    0x18,
                    0x0a,
                    0x11, // 16 bit service uuids
                    0x04,
                    0x09,
                    0x50,
                    0x65,
                    0x64, // name
                    0x02,
                    0x0A,
                    (byte) 0xec, // tx power level
                    0x05,
                    0x30,
                    0x54,
                    0x65,
                    0x73,
                    0x74, // broadcast name: Test
                    0x06,
                    0x16,
                    0x52,
                    0x18,
                    (byte) TEST_BROADCAST_ID,
                    (byte) (TEST_BROADCAST_ID >> 8),
                    (byte) (TEST_BROADCAST_ID >> 16), // service data, broadcast id
                    0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    0x00,
                    0x02,
                    0x15, // manufacturer specific data
                    0x03,
                    0x50,
                    0x01,
                    0x02, // an unknown data type won't cause trouble
                };
        ScanResult scanResult =
                new ScanResult(
                        mSourceDevice,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI,
                        0,
                        ScanRecord.parseFromBytes(broadcastScanRecord),
                        0);

        mCallbackCaptor.getValue().onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isEqualTo(null);

        // No public announcement so it will not notify
        onPeriodicAdvertisingReport();

        // Not canceled, updated base
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNotEqualTo(null);

        // Not notified
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        InOrder inOrder = inOrder(mCallback);
        try {
            inOrder.verify(mCallback, never()).onSourceFound(any());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // onBigInfoAdvertisingReport causes notification
        onBigInfoAdvertisingReport();

        // Notified
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        try {
            inOrder.verify(mCallback).onSourceFound(any());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void notifySourceFound_periodic_after_big() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isEqualTo(null);

        // Big report before periodic so before base update
        onBigInfoAdvertisingReport();

        // Not notified
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        InOrder inOrder = inOrder(mCallback);
        try {
            inOrder.verify(mCallback, never()).onSourceFound(any());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        onPeriodicAdvertisingReport();

        // Not canceled, updated base
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNotEqualTo(null);

        if (!Flags.leaudioBigDependsOnAudioState()) {
            // onBigInfoAdvertisingReport causes notification
            onBigInfoAdvertisingReport();
        }

        // Notified
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        try {
            inOrder.verify(mCallback).onSourceFound(any());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE,
        Flags.FLAG_LEAUDIO_BIG_DEPENDS_ON_AUDIO_STATE
    })
    public void notifySourceFound_periodic_after_wrong_periodic() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isEqualTo(null);

        byte[] scanRecordNoBaseData =
                new byte[] {
                    0x02,
                    0x01,
                    0x1a, // advertising flags
                    0x05,
                    0x02,
                    0x0b,
                    0x11,
                    0x0a,
                    0x11, // 16 bit service uuids
                    0x04,
                    0x09,
                    0x50,
                    0x65,
                    0x64, // name
                    0x02,
                    0x0A,
                    (byte) 0xec, // tx power level
                    0x05,
                    0x16,
                    0x0b,
                    0x11,
                    0x50,
                    0x64, // service data
                    0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    0x00,
                    0x02,
                    0x15, // manufacturer specific data
                    0x03,
                    0x50,
                    0x01,
                    0x02, // an unknown data type won't cause trouble
                };

        BassClientService.PACallback callback = mBassClientService.new PACallback();

        PeriodicAdvertisingReport report =
                new PeriodicAdvertisingReport(
                        TEST_SYNC_HANDLE, 0, 0, 0, ScanRecord.parseFromBytes(scanRecordNoBaseData));

        // Wrong base data not cause notification
        callback.onPeriodicAdvertisingReport(report);

        // Not notified
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        InOrder inOrder = inOrder(mCallback);
        try {
            inOrder.verify(mCallback, never()).onSourceFound(any());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        onPeriodicAdvertisingReport();

        // Not canceled, updated base
        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        expect.that(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        expect.that(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNotEqualTo(null);

        // Notified
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        try {
            inOrder.verify(mCallback).onSourceFound(any());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    public void notifySourceFound_alreadySynced_clearFlag() {
        // Scan
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);

        // Source synced
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        onPeriodicAdvertisingReport();
        if (!Flags.leaudioBigDependsOnAudioState()) {
            // onBigInfoAdvertisingReport causes notification
            onBigInfoAdvertisingReport();
        }

        // Notified
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        InOrder inOrder = inOrder(mCallback);
        try {
            inOrder.verify(mCallback).onSourceFound(any());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // Stop searching, unsyc all broadcasters and clear all data except mCachedBroadcasts
        mBassClientService.stopSearchingForSources();

        // Add source to unsynced broadcast, causes synchronization first
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        mBassClientService.addSource(mCurrentDevice, meta, true);
        handleHandoverSupport();

        // Source synced
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        onPeriodicAdvertisingReport();
        if (!Flags.leaudioBigDependsOnAudioState()) {
            // onBigInfoAdvertisingReport causes notification
            onBigInfoAdvertisingReport();
        }

        // Notified
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        try {
            inOrder.verify(mCallback).onSourceFound(any());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // Start searching again clears timeout, mCachedBroadcasts and notifiedFlags but keep syncs
        startSearchingForSources();

        onPeriodicAdvertisingReport();
        if (!Flags.leaudioBigDependsOnAudioState()) {
            // onBigInfoAdvertisingReport should notified again
            onBigInfoAdvertisingReport();
        }
        // Notified
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        try {
            inOrder.verify(mCallback).onSourceFound(any());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE)
    @DisableFlags(Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER)
    public void onSyncLost_notifySourceLostAndCancelSync_noResyncFlag() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);

        onSyncLost();

        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        try {
            verify(mCallback).onSourceLost(eq(TEST_BROADCAST_ID));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // Cleaned all
        assertThat(mBassClientService.getActiveSyncedSources()).isEmpty();
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isEqualTo(null);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);

        // Could try to sync again
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE,
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER
    })
    public void onSyncLost_notifySourceLostAndCancelSync() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);

        onSyncLost();
        checkAndDispatchTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_SYNC_LOST_TIMEOUT);

        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        try {
            verify(mCallback).onSourceLost(eq(TEST_BROADCAST_ID));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // Cleaned all
        assertThat(mBassClientService.getActiveSyncedSources()).isEmpty();
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isEqualTo(null);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(BassConstants.INVALID_BROADCAST_ID);

        // Could try to sync again
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE,
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER
    })
    public void monitorBroadcastAfterSyncMaxLimit() {
        final BluetoothDevice device1 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:11", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device2 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:22", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device3 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:33", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device4 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:44", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device5 =
                mBluetoothAdapter.getRemoteLeDevice(
                        "00:11:22:33:44:55", BluetoothDevice.ADDRESS_TYPE_RANDOM);
        final int handle1 = 0;
        final int handle2 = 1;
        final int handle3 = 2;
        final int handle4 = 3;
        final int handle5 = 4;
        final int broadcastId1 = 1111;
        final int broadcastId2 = 2222;
        final int broadcastId3 = 3333;
        final int broadcastId4 = 4444;
        final int broadcastId5 = 5555;

        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 5 sources cause removing 1 synced element
        onScanResult(device1, broadcastId1);
        onSyncEstablished(device1, handle1);
        onScanResult(device2, broadcastId2);
        onSyncEstablished(device2, handle2);
        onScanResult(device3, broadcastId3);
        onSyncEstablished(device3, handle3);
        onScanResult(device4, broadcastId4);
        onSyncEstablished(device4, handle4);
        onScanResult(device5, broadcastId5);
        mInOrderMethodProxy
                .verify(mMethodProxy, times(4))
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerUnregisterSync(any(), any());

        checkTimeout(broadcastId1, BassClientService.MESSAGE_SYNC_LOST_TIMEOUT);

        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(device5, handle5);

        // Couldn't sync again as broadcast is in the cache
        onScanResult(device1, broadcastId1);
        mInOrderMethodProxy
                .verify(mMethodProxy, never())
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        // Lost should notify about lost and clear cache
        checkAndDispatchTimeout(broadcastId1, BassClientService.MESSAGE_SYNC_LOST_TIMEOUT);

        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());
        try {
            verify(mCallback).onSourceLost(eq(broadcastId1));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // Could try to sync again
        onScanResult(device1, broadcastId1);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
    }

    private void prepareSynchronizedPair() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Add source
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        verifyAddSourceForGroup(meta);

        // Bis syned
        prepareRemoteSourceState(meta, true, true);
        verify(mLeAudioService).activeBroadcastAssistantNotification(eq(true));
    }

    private void prepareSynchronizedPairAndStopSearching() {
        prepareSynchronizedPair();

        // Stop searching
        mBassClientService.stopSearchingForSources();
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerUnregisterSync(any(), any());
    }

    private void sinkUnintentionalWithoutScanning() {
        prepareSynchronizedPairAndStopSearching();

        // Bis and PA unsynced, SINK_UNINTENTIONAL
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        injectRemoteSourceStateChanged(meta, false, false);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
    }

    private void sinkUnintentionalDuringScanning() {
        prepareSynchronizedPair();

        // Bis and PA unsynced, SINK_UNINTENTIONAL
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        injectRemoteSourceStateChanged(meta, false, false);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
    }

    private void checkResumeSynchronizationByBig() {
        // BIG causes resume synchronization
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE); // In case of add source to inactive
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        verifyAllGroupMembersGettingUpdateOrAddSource(meta);
    }

    private void checkNoResumeSynchronizationByBig() {
        // BIG not cause resume synchronization
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE); // In case of add source to inactive
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }
    }

    private void checkResumeSynchronizationByHost() {
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        mBassClientService.resumeReceiversSourceSynchronization();
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE); // In case of add source to inactive
        verifyAllGroupMembersGettingUpdateOrAddSource(createBroadcastMetadata(TEST_BROADCAST_ID));
    }

    private void checkNoResumeSynchronizationByHost() {
        // Verify empty resume list
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE); // In case of add source to inactive
        mBassClientService.resumeReceiversSourceSynchronization();
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }
    }

    private void verifyStopBigMonitoringWithUnsync() {
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BROADCAST_MONITOR_TIMEOUT);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerUnregisterSync(any(), any());
    }

    private void verifyStopBigMonitoringWithoutUnsync() {
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BROADCAST_MONITOR_TIMEOUT);
        mInOrderMethodProxy
                .verify(mMethodProxy, never())
                .periodicAdvertisingManagerUnregisterSync(any(), any());
    }

    private void resyncAndVerifyWithUnsync() {
        // Resync, verify stopBigMonitoring with broadcast unsync
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        injectRemoteSourceStateChanged(meta, true, true);
        verifyStopBigMonitoringWithUnsync();
    }

    private void resyncAndVerifyWithoutUnsync() {
        // Resync, verify stopBigMonitoring without broadcast unsync
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        injectRemoteSourceStateChanged(meta, true, true);
        verifyStopBigMonitoringWithoutUnsync();
    }

    private void checkNoSinkPause() {
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        injectRemoteSourceStateChanged(meta, false, false);
        mInOrderMethodProxy
                .verify(mMethodProxy, never())
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BROADCAST_MONITOR_TIMEOUT);
    }

    private void checkSinkPause() {
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        injectRemoteSourceStateChanged(meta, false, false);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_resync_withoutScanning() {
        sinkUnintentionalWithoutScanning();

        checkResumeSynchronizationByBig();
        resyncAndVerifyWithUnsync();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_resync_duringScanning() {
        sinkUnintentionalDuringScanning();

        checkResumeSynchronizationByBig();
        resyncAndVerifyWithoutUnsync();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_resyncByRemote_withoutScanning() {
        sinkUnintentionalWithoutScanning();

        resyncAndVerifyWithUnsync();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_resyncByRemote_duringScanning() {
        sinkUnintentionalDuringScanning();

        resyncAndVerifyWithoutUnsync();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_addNewSource() {
        sinkUnintentionalDuringScanning();

        // Scan and sync second broadcast
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID + 1);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE + 1);

        // Add second source, HOST_INTENTIONAL
        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setEncrypted(false)
                        .setSourceDevice(mSourceDevice2, BluetoothDevice.ADDRESS_TYPE_RANDOM)
                        .setSourceAdvertisingSid(TEST_ADVERTISER_SID)
                        .setBroadcastId(TEST_BROADCAST_ID + 1)
                        .setBroadcastCode(null)
                        .setPaSyncInterval(TEST_PA_SYNC_INTERVAL)
                        .setPresentationDelayMicros(TEST_PRESENTATION_DELAY_MS);
        // builder expect at least one subgroup
        builder.addSubgroup(createBroadcastSubgroup());
        BluetoothLeBroadcastMetadata meta2 = builder.build();
        mBassClientService.addSource(mCurrentDevice, meta2, true);
        verifyStopBigMonitoringWithoutUnsync();

        // BIG for first broadcast not cause resume synchronization
        checkNoResumeSynchronizationByBig();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_addSameSource() {
        sinkUnintentionalDuringScanning();

        // Verify add source clear the SINK_UNINTENTIONAL
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        mBassClientService.addSource(mCurrentDevice, meta, true);
        checkSinkPause();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_removeSource_withoutScanning() {
        sinkUnintentionalWithoutScanning();

        // Remove source, HOST_INTENTIONAL
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);
        verifyStopBigMonitoringWithUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_removeSource_duringScanning() {
        sinkUnintentionalDuringScanning();

        // Remove source, HOST_INTENTIONAL
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);
        verifyStopBigMonitoringWithoutUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_stopReceivers_withoutScanning() {
        sinkUnintentionalWithoutScanning();

        // Stop receivers, HOST_INTENTIONAL
        mBassClientService.stopReceiversSourceSynchronization(TEST_BROADCAST_ID);
        verifyStopBigMonitoringWithUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
        checkNoResumeSynchronizationByHost();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_stopReceivers_duringScanning() {
        sinkUnintentionalDuringScanning();

        // Stop receivers, HOST_INTENTIONAL
        mBassClientService.stopReceiversSourceSynchronization(TEST_BROADCAST_ID);
        verifyStopBigMonitoringWithoutUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
        checkNoResumeSynchronizationByHost();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_suspendReceivers_withoutScanning() {
        sinkUnintentionalWithoutScanning();

        // Suspend receivers, HOST_INTENTIONAL
        mBassClientService.suspendReceiversSourceSynchronization(TEST_BROADCAST_ID);
        verifyStopBigMonitoringWithUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
        checkResumeSynchronizationByHost();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_suspendReceivers_duringScanning() {
        sinkUnintentionalDuringScanning();

        // Suspend receivers, HOST_INTENTIONAL
        mBassClientService.suspendReceiversSourceSynchronization(TEST_BROADCAST_ID);
        verifyStopBigMonitoringWithoutUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
        checkResumeSynchronizationByHost();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_suspendAllReceivers_withoutScanning() {
        sinkUnintentionalWithoutScanning();

        // Suspend all receivers, HOST_INTENTIONAL
        mBassClientService.suspendAllReceiversSourceSynchronization();
        verifyStopBigMonitoringWithUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
        checkResumeSynchronizationByHost();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_suspendAllReceivers_duringScanning() {
        sinkUnintentionalDuringScanning();

        // Suspend all receivers, HOST_INTENTIONAL
        mBassClientService.suspendAllReceiversSourceSynchronization();
        verifyStopBigMonitoringWithoutUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
        checkResumeSynchronizationByHost();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_publicStopBigMonitoring_withoutScanning() {
        sinkUnintentionalWithoutScanning();

        mBassClientService.stopBigMonitoring();
        verifyStopBigMonitoringWithUnsync();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_publicStopBigMonitoring_duringScanning() {
        sinkUnintentionalDuringScanning();

        mBassClientService.stopBigMonitoring();
        verifyStopBigMonitoringWithoutUnsync();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_unsync_withoutScanning() {
        sinkUnintentionalWithoutScanning();

        // Unsync not all sinks not cause stop monitoring
        for (BassClientStateMachine sm : mStateMachines.values()) {
            // Update receiver state
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateChanged(
                        sm,
                        createEmptyBroadcastMetadata(),
                        TEST_SOURCE_ID,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        (long) 0x00000000);
            }
        }
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);

        // Unsync all sinks cause stop monitoring
        for (BassClientStateMachine sm : mStateMachines.values()) {
            // Update receiver state
            if (sm.getDevice().equals(mCurrentDevice1)) {
                injectRemoteSourceStateChanged(
                        sm,
                        createEmptyBroadcastMetadata(),
                        TEST_SOURCE_ID + 1,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        (long) 0x00000000);
            }
        }
        verifyStopBigMonitoringWithUnsync();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_unsync_duringScanning() {
        sinkUnintentionalDuringScanning();

        // Unsync not all sinks not cause stop monitoring
        for (BassClientStateMachine sm : mStateMachines.values()) {
            // Update receiver state
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateChanged(
                        sm,
                        createEmptyBroadcastMetadata(),
                        TEST_SOURCE_ID,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        (long) 0x00000000);
            }
        }
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);

        // Unsync all sinks cause stop monitoring
        for (BassClientStateMachine sm : mStateMachines.values()) {
            // Update receiver state
            if (sm.getDevice().equals(mCurrentDevice1)) {
                injectRemoteSourceStateChanged(
                        sm,
                        createEmptyBroadcastMetadata(),
                        TEST_SOURCE_ID + 1,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        (long) 0x00000000);
            }
        }
        verifyStopBigMonitoringWithoutUnsync();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_disconnect_withoutScanning() {
        sinkUnintentionalWithoutScanning();

        // Disconnect not all sinks not cause stop monitoring
        doReturn(BluetoothProfile.STATE_DISCONNECTED)
                .when(mStateMachines.get(mCurrentDevice))
                .getConnectionState();
        doReturn(false).when(mStateMachines.get(mCurrentDevice)).isConnected();
        mBassClientService.connectionStateChanged(
                mCurrentDevice,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_DISCONNECTED);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);

        // Disconnect all sinks cause stop monitoring
        doReturn(BluetoothProfile.STATE_DISCONNECTED)
                .when(mStateMachines.get(mCurrentDevice1))
                .getConnectionState();
        doReturn(false).when(mStateMachines.get(mCurrentDevice1)).isConnected();
        mBassClientService.connectionStateChanged(
                mCurrentDevice1,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_DISCONNECTED);
        verifyStopBigMonitoringWithUnsync();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_disconnect_duringScanning() {
        sinkUnintentionalDuringScanning();

        // Disconnect not all sinks not cause stop monitoring
        doReturn(BluetoothProfile.STATE_DISCONNECTED)
                .when(mStateMachines.get(mCurrentDevice))
                .getConnectionState();
        doReturn(false).when(mStateMachines.get(mCurrentDevice)).isConnected();
        mBassClientService.connectionStateChanged(
                mCurrentDevice,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_DISCONNECTED);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);

        // Disconnect all sinks cause stop monitoring
        doReturn(BluetoothProfile.STATE_DISCONNECTED)
                .when(mStateMachines.get(mCurrentDevice1))
                .getConnectionState();
        doReturn(false).when(mStateMachines.get(mCurrentDevice1)).isConnected();
        mBassClientService.connectionStateChanged(
                mCurrentDevice1,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_DISCONNECTED);
        verifyStopBigMonitoringWithoutUnsync();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_syncLost_withoutScanning_outOfRange() {
        sinkUnintentionalWithoutScanning();

        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BROADCAST_MONITOR_TIMEOUT);

        onSyncLost();
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BROADCAST_MONITOR_TIMEOUT);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        checkAndDispatchTimeout(
                TEST_BROADCAST_ID, BassClientService.MESSAGE_BROADCAST_MONITOR_TIMEOUT);
        verifyStopBigMonitoringWithUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_syncLost_duringScanning_outOfRange() {
        sinkUnintentionalDuringScanning();

        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BROADCAST_MONITOR_TIMEOUT);

        onSyncLost();
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BROADCAST_MONITOR_TIMEOUT);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        checkAndDispatchTimeout(
                TEST_BROADCAST_ID, BassClientService.MESSAGE_BROADCAST_MONITOR_TIMEOUT);
        verifyStopBigMonitoringWithoutUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_bigMonitorTimeout_withoutScanning() {
        sinkUnintentionalWithoutScanning();

        checkAndDispatchTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        verifyStopBigMonitoringWithUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_bigMonitorTimeout_duringScanning() {
        sinkUnintentionalDuringScanning();

        checkAndDispatchTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        verifyStopBigMonitoringWithoutUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_handleUnicastSourceStreamStatusChange_withoutScanning() {
        sinkUnintentionalWithoutScanning();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                0 /* STATUS_LOCAL_STREAM_REQUESTED */);
        verifyStopBigMonitoringWithUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                2 /* STATUS_LOCAL_STREAM_SUSPENDED */);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        verifyAllGroupMembersGettingUpdateOrAddSource(createBroadcastMetadata(TEST_BROADCAST_ID));
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_handleUnicastSourceStreamStatusChange_duringScanning() {
        sinkUnintentionalDuringScanning();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                0 /* STATUS_LOCAL_STREAM_REQUESTED */);
        verifyStopBigMonitoringWithoutUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                2 /* STATUS_LOCAL_STREAM_SUSPENDED */);
        verifyAllGroupMembersGettingUpdateOrAddSource(createBroadcastMetadata(TEST_BROADCAST_ID));
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_handleUnicastSourceStreamStatusChangeNoContext_withoutScanning() {
        sinkUnintentionalWithoutScanning();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                3 /* STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE */);
        verifyStopBigMonitoringWithUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                2 /* STATUS_LOCAL_STREAM_SUSPENDED */);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        verifyAllGroupMembersGettingUpdateOrAddSource(createBroadcastMetadata(TEST_BROADCAST_ID));
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_handleUnicastSourceStreamStatusChangeNoContext_duringScanning() {
        sinkUnintentionalDuringScanning();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                3 /* STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE */);
        verifyStopBigMonitoringWithoutUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                2 /* STATUS_LOCAL_STREAM_SUSPENDED */);
        verifyAllGroupMembersGettingUpdateOrAddSource(createBroadcastMetadata(TEST_BROADCAST_ID));
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkUnintentional_autoSyncToBroadcast_onStopSearching() {
        sinkUnintentionalDuringScanning();

        // Verify that start searching cause sync when broadcaster synced to sinks
        mBassClientService.stopSearchingForSources();
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerUnregisterSync(any(), any());
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void hostIntentional_addSameSource() {
        prepareSynchronizedPair();

        // Remove source, HOST_INTENTIONAL
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);
        checkNoSinkPause();
        verifyRemoveMessageAndInjectSourceRemoval();

        // Verify add source clear the HOST_INTENTIONAL
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        mBassClientService.addSource(mCurrentDevice, meta, true);
        verifyAddSourceForGroup(meta);
        checkSinkPause();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void hostIntentional_removeSource_withoutScanning() {
        prepareSynchronizedPairAndStopSearching();

        // Remove source, HOST_INTENTIONAL
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);
        checkNoSinkPause();
        verifyRemoveMessageAndInjectSourceRemoval();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void hostIntentional_removeSource_duringScanning() {
        prepareSynchronizedPair();

        // Remove source, HOST_INTENTIONAL
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);
        checkNoSinkPause();
        verifyRemoveMessageAndInjectSourceRemoval();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void hostIntentional_stopReceivers_withoutScanning() {
        prepareSynchronizedPairAndStopSearching();

        // Stop receivers, HOST_INTENTIONAL
        mBassClientService.stopReceiversSourceSynchronization(TEST_BROADCAST_ID);
        checkNoSinkPause();
        verifyRemoveMessageAndInjectSourceRemoval();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void hostIntentional_stopReceivers_duringScanning() {
        prepareSynchronizedPair();

        // Stop receivers, HOST_INTENTIONAL
        mBassClientService.stopReceiversSourceSynchronization(TEST_BROADCAST_ID);
        checkNoSinkPause();
        verifyRemoveMessageAndInjectSourceRemoval();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void hostIntentional_suspendReceivers_withoutScanning() {
        prepareSynchronizedPairAndStopSearching();

        // Suspend receivers, HOST_INTENTIONAL
        mBassClientService.suspendReceiversSourceSynchronization(TEST_BROADCAST_ID);
        checkNoSinkPause();
        if (!Flags.leaudioBroadcastAssistantPeripheralEntrustment()) {
            verifyRemoveMessageAndInjectSourceRemoval();
        }
        checkResumeSynchronizationByHost();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void hostIntentional_suspendReceivers_duringScanning() {
        prepareSynchronizedPair();

        // Suspend receivers, HOST_INTENTIONAL
        mBassClientService.suspendReceiversSourceSynchronization(TEST_BROADCAST_ID);
        checkNoSinkPause();
        if (!Flags.leaudioBroadcastAssistantPeripheralEntrustment()) {
            verifyRemoveMessageAndInjectSourceRemoval();
        }
        checkResumeSynchronizationByHost();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void hostIntentional_suspendAllReceivers_withoutScanning() {
        prepareSynchronizedPairAndStopSearching();

        // Suspend all receivers, HOST_INTENTIONAL
        mBassClientService.suspendAllReceiversSourceSynchronization();
        checkNoSinkPause();
        if (!Flags.leaudioBroadcastAssistantPeripheralEntrustment()) {
            verifyRemoveMessageAndInjectSourceRemoval();
        }
        checkResumeSynchronizationByHost();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void hostIntentional_suspendAllReceivers_duringScanning() {
        prepareSynchronizedPair();

        // Suspend all receivers, HOST_INTENTIONAL
        mBassClientService.suspendAllReceiversSourceSynchronization();
        checkNoSinkPause();
        if (!Flags.leaudioBroadcastAssistantPeripheralEntrustment()) {
            verifyRemoveMessageAndInjectSourceRemoval();
        }
        checkResumeSynchronizationByHost();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void hostIntentional_handleUnicastSourceStreamStatusChange_withoutScanning() {
        prepareSynchronizedPairAndStopSearching();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                0 /* STATUS_LOCAL_STREAM_REQUESTED */);
        checkNoSinkPause();
        if (!Flags.leaudioBroadcastAssistantPeripheralEntrustment()) {
            verifyRemoveMessageAndInjectSourceRemoval();
        }

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                2 /* STATUS_LOCAL_STREAM_SUSPENDED */);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE); // In case of add source to inactive
        verifyAllGroupMembersGettingUpdateOrAddSource(createBroadcastMetadata(TEST_BROADCAST_ID));
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void hostIntentional_handleUnicastSourceStreamStatusChange_duringScanning() {
        prepareSynchronizedPair();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                0 /* STATUS_LOCAL_STREAM_REQUESTED */);
        checkNoSinkPause();
        if (!Flags.leaudioBroadcastAssistantPeripheralEntrustment()) {
            verifyRemoveMessageAndInjectSourceRemoval();
        }

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                2 /* STATUS_LOCAL_STREAM_SUSPENDED */);
        verifyAllGroupMembersGettingUpdateOrAddSource(createBroadcastMetadata(TEST_BROADCAST_ID));
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void hostIntentional_handleUnicastSourceStreamStatusChangeNoContext_withoutScanning() {
        prepareSynchronizedPairAndStopSearching();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                3 /* STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE */);
        checkNoSinkPause();
        verifyRemoveMessageAndInjectSourceRemoval();

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                2 /* STATUS_LOCAL_STREAM_SUSPENDED */);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        verifyAllGroupMembersGettingUpdateOrAddSource(createBroadcastMetadata(TEST_BROADCAST_ID));
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void hostIntentional_handleUnicastSourceStreamStatusChangeNoContext_duringScanning() {
        prepareSynchronizedPair();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                3 /* STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE */);
        checkNoSinkPause();
        verifyRemoveMessageAndInjectSourceRemoval();

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                2 /* STATUS_LOCAL_STREAM_SUSPENDED */);
        verifyAllGroupMembersGettingUpdateOrAddSource(createBroadcastMetadata(TEST_BROADCAST_ID));
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void outOfRange_syncEstablishedFailed_stopMonitoringAfterTimeout() {
        prepareSynchronizedPairAndStopSearching();

        // Bis and PA unsynced, SINK_UNINTENTIONAL
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        injectRemoteSourceStateChanged(meta, false, false);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BROADCAST_MONITOR_TIMEOUT);

        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BROADCAST_MONITOR_TIMEOUT);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        checkAndDispatchTimeout(
                TEST_BROADCAST_ID, BassClientService.MESSAGE_BROADCAST_MONITOR_TIMEOUT);
        verifyStopBigMonitoringWithUnsync();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void outOfRange_syncEstablishedFailed_clearTimeout() {
        prepareSynchronizedPairAndStopSearching();

        // Bis and PA unsynced, SINK_UNINTENTIONAL
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);
        injectRemoteSourceStateChanged(meta, false, false);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BROADCAST_MONITOR_TIMEOUT);

        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BROADCAST_MONITOR_TIMEOUT);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BROADCAST_MONITOR_TIMEOUT);
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void autoSyncToBroadcast_AlreadySyncedToSink_onStartSearching() {
        prepareSynchronizedPairAndStopSearching();

        // Verify that start searching cause sync when broadcaster synced to sinks
        startSearchingForSources();
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());
    }

    /**
     * Test add source will be triggered if new device connected and its peer is synced to broadcast
     * source
     */
    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void sinkBassStateReady_addSourceIfPeerDeviceSynced() {
        // Imitate broadcast being active
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        prepareTwoSynchronizedDevicesForLocalBroadcast();

        mBassClientService.getCallbacks().notifyBassStateReady(mCurrentDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());

        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            // No adding source if device remain synced
            verify(sm, never()).sendMessage(any());
        }

        // Remove source on the mCurrentDevice
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID);
            }
        }

        mBassClientService.getCallbacks().notifyBassStateReady(mCurrentDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mBassClientService.getCallbacks().getLooper());

        for (BassClientStateMachine sm : mStateMachines.values()) {
            // Verify mCurrentDevice is resuming the broadcast
            if (sm.getDevice().equals(mCurrentDevice1)) {
                verify(sm, never()).sendMessage(any());
            } else if (sm.getDevice().equals(mCurrentDevice)) {
                ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
                verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

                Message msg =
                        messageCaptor.getAllValues().stream()
                                .filter(m -> (m.what == BassClientStateMachine.ADD_BCAST_SOURCE))
                                .findFirst()
                                .orElse(null);
                assertThat(msg).isNotNull();
                clearInvocations(sm);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }
    }

    @Test
    public void testIsLocalBroadacst() {
        int broadcastId = 12345;

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata(broadcastId);
        BluetoothLeBroadcastReceiveState receiveState =
                new BluetoothLeBroadcastReceiveState(
                        TEST_SOURCE_ID,
                        metadata.getSourceAddressType(),
                        metadata.getSourceDevice(),
                        metadata.getSourceAdvertisingSid(),
                        metadata.getBroadcastId(),
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                        BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        metadata.getSubgroups().size(),
                        // Bis sync states
                        metadata.getSubgroups().stream()
                                .map(e -> (long) 0x00000001)
                                .collect(Collectors.toList()),
                        metadata.getSubgroups().stream()
                                .map(e -> e.getContentMetadata())
                                .collect(Collectors.toList()));

        /* External broadcast check */
        doReturn(new ArrayList<BluetoothLeBroadcastMetadata>())
                .when(mLeAudioService)
                .getAllBroadcastMetadata();

        assertThat(mBassClientService.isLocalBroadcast(metadata)).isFalse();
        assertThat(mBassClientService.isLocalBroadcast(receiveState)).isFalse();

        /* Local broadcast check */
        doReturn(new ArrayList<BluetoothLeBroadcastMetadata>(Arrays.asList(metadata)))
                .when(mLeAudioService)
                .getAllBroadcastMetadata();

        assertThat(mBassClientService.isLocalBroadcast(metadata)).isTrue();
        assertThat(mBassClientService.isLocalBroadcast(receiveState)).isTrue();
    }

    private void verifyInitiatePaSyncTransferAndNoOthers() {
        expect.that(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());
            long count;
            if (sm.getDevice().equals(mCurrentDevice)) {
                count =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                (m.what
                                                                == BassClientStateMachine
                                                                        .INITIATE_PA_SYNC_TRANSFER)
                                                        && (m.arg1 == TEST_SYNC_HANDLE)
                                                        && (m.arg2 == TEST_SOURCE_ID))
                                .count();
                assertThat(count).isEqualTo(1);
                count =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                m.what
                                                        != BassClientStateMachine
                                                                .INITIATE_PA_SYNC_TRANSFER)
                                .count();
                assertThat(count).isEqualTo(0);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                count =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                (m.what
                                                                == BassClientStateMachine
                                                                        .INITIATE_PA_SYNC_TRANSFER)
                                                        && (m.arg1 == TEST_SYNC_HANDLE)
                                                        && (m.arg2 == TEST_SOURCE_ID + 1))
                                .count();
                assertThat(count).isEqualTo(1);
                count =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                m.what
                                                        != BassClientStateMachine
                                                                .INITIATE_PA_SYNC_TRANSFER)
                                .count();
                assertThat(count).isEqualTo(0);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void initiatePaSyncTransfer() {
        prepareSynchronizedPairAndStopSearching();

        // Sync info request force syncing to broadcaster and add sinks pending for PAST
        mBassClientService.syncRequestForPast(mCurrentDevice, TEST_BROADCAST_ID, TEST_SOURCE_ID);
        mBassClientService.syncRequestForPast(
                mCurrentDevice1, TEST_BROADCAST_ID, TEST_SOURCE_ID + 1);
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        // Sync will INITIATE_PA_SYNC_TRANSFER
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        verifyInitiatePaSyncTransferAndNoOthers();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void InitiatePaSyncTransfer_concurrentWithResume() {
        prepareSynchronizedPairAndStopSearching();

        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);

        // Cache sinks for resume and set HOST_INTENTIONAL pause
        mBassClientService.handleUnicastSourceStreamStatusChange(
                0 /* STATUS_LOCAL_STREAM_REQUESTED */);
        injectRemoteSourceStateChanged(meta, false, false);

        // Resume source will force syncing to broadcaser and put pending source to add
        mBassClientService.resumeReceiversSourceSynchronization();
        mInOrderMethodProxy
                .verify(mMethodProxy)
                .periodicAdvertisingManagerRegisterSync(
                        any(), any(), anyInt(), anyInt(), any(), any());

        // Sync info request add sinks pending for PAST
        mBassClientService.syncRequestForPast(mCurrentDevice, TEST_BROADCAST_ID, TEST_SOURCE_ID);
        mBassClientService.syncRequestForPast(
                mCurrentDevice1, TEST_BROADCAST_ID, TEST_SOURCE_ID + 1);

        // Sync will send INITIATE_PA_SYNC_TRANSFER and remove pending soure to add
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        verifyInitiatePaSyncTransferAndNoOthers();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER,
        Flags.FLAG_LEAUDIO_BROADCAST_EXTRACT_PERIODIC_SCANNER_FROM_STATE_MACHINE
    })
    public void resumeSourceSynchronization_omitWhenPaSyncedOrRequested() {
        prepareSynchronizedPair();

        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(TEST_BROADCAST_ID);

        // Cache sinks for resume and set HOST_INTENTIONAL pause
        // Try resume while sync info requested
        mBassClientService.handleUnicastSourceStreamStatusChange(
                0 /* STATUS_LOCAL_STREAM_REQUESTED */);
        injectRemoteSourceStateChanged(
                meta, BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCINFO_REQUEST, false);
        checkNoResumeSynchronizationByHost();

        // Cache sinks for resume and set HOST_INTENTIONAL pause
        // Try resume while pa synced
        mBassClientService.handleUnicastSourceStreamStatusChange(
                0 /* STATUS_LOCAL_STREAM_REQUESTED */);
        injectRemoteSourceStateChanged(meta, true, false);
        checkNoResumeSynchronizationByHost();

        // Cache sinks for resume and set HOST_INTENTIONAL pause
        // Try resume while pa unsynced
        mBassClientService.handleUnicastSourceStreamStatusChange(
                0 /* STATUS_LOCAL_STREAM_REQUESTED */);
        injectRemoteSourceStateChanged(meta, false, false);
        checkResumeSynchronizationByHost();
    }
}
