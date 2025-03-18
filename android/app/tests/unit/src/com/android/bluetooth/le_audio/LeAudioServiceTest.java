/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
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

package com.android.bluetooth.le_audio;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothLeAudioCodecConfig;
import android.bluetooth.BluetoothLeAudioCodecStatus;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcastSettings;
import android.bluetooth.BluetoothLeBroadcastSubgroupSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothLeAudioCallback;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.BluetoothProfileConnectionInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.sysprop.BluetoothProperties;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.bass_client.BassClientService;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.gatt.GattService;
import com.android.bluetooth.hap.HapClientService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.le_scan.ScanController;
import com.android.bluetooth.le_scan.TransitionalScanHelper;
import com.android.bluetooth.mcp.McpService;
import com.android.bluetooth.tbs.TbsService;
import com.android.bluetooth.vc.VolumeControlService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class LeAudioServiceTest {
    private static final int ASYNC_CALL_TIMEOUT_MILLIS = 250;
    private static final int TIMEOUT_MS = 1000;
    private static final int AUDIO_MANAGER_DEVICE_ADD_TIMEOUT_MS = 3000;
    private static final int MAX_LE_AUDIO_CONNECTIONS = 5;
    private static final int LE_AUDIO_GROUP_ID_INVALID = -1;
    private static final String TEST_BROADCAST_NAME = "Name Test";

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private BluetoothAdapter mAdapter;
    private Context mTargetContext;

    private LeAudioService mService;
    private BluetoothDevice mLeftDevice;
    private BluetoothDevice mRightDevice;
    private BluetoothDevice mSingleDevice;
    private BluetoothDevice mSingleDevice_2;
    private HashSet<BluetoothDevice> mBondedDevices = new HashSet<>();
    private HashMap<BluetoothDevice, LinkedBlockingQueue<Intent>> mDeviceQueueMap;
    private LinkedBlockingQueue<Intent> mGroupIntentQueue = new LinkedBlockingQueue<>();
    private int testGroupId = 1;
    private boolean onGroupStatusCallbackCalled = false;
    private boolean onGroupStreamStatusCallbackCalled = false;
    private boolean onGroupCodecConfChangedCallbackCalled = false;
    private BluetoothLeAudioCodecStatus testCodecStatus = null;

    private BroadcastReceiver mLeAudioIntentReceiver;

    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock private AdapterService mAdapterService;
    @Mock private GattService mGattService;
    @Mock private ScanController mScanController;
    @Mock private TransitionalScanHelper mTransitionalScanHelper;
    @Mock private ActiveDeviceManager mActiveDeviceManager;
    @Mock private AudioManager mAudioManager;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private LeAudioBroadcasterNativeInterface mLeAudioBroadcasterNativeInterface;
    @Mock private LeAudioNativeInterface mNativeInterface;
    @Mock private LeAudioTmapGattServer mTmapGattServer;
    @Mock private McpService mMcpService;
    @Mock private TbsService mTbsService;
    @Mock private VolumeControlService mVolumeControlService;
    @Mock private HapClientService mHapClientService;
    @Mock private CsipSetCoordinatorService mCsipSetCoordinatorService;
    @Mock private BassClientService mBassClientService;
    @Spy private LeAudioObjectsFactory mObjectsFactory = LeAudioObjectsFactory.getInstance();
    @Spy private ServiceFactory mServiceFactory = new ServiceFactory();

    private static final BluetoothLeAudioCodecConfig EMPTY_CONFIG =
            new BluetoothLeAudioCodecConfig.Builder().build();

    private static final BluetoothLeAudioCodecConfig LC3_16KHZ_CONFIG =
            new BluetoothLeAudioCodecConfig.Builder()
                    .setCodecType(BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3)
                    .setSampleRate(BluetoothLeAudioCodecConfig.SAMPLE_RATE_16000)
                    .build();
    private static final BluetoothLeAudioCodecConfig LC3_48KHZ_CONFIG =
            new BluetoothLeAudioCodecConfig.Builder()
                    .setCodecType(BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3)
                    .setSampleRate(BluetoothLeAudioCodecConfig.SAMPLE_RATE_48000)
                    .build();

    private static final BluetoothLeAudioCodecConfig LC3_48KHZ_16KHZ_CONFIG =
            new BluetoothLeAudioCodecConfig.Builder()
                    .setCodecType(BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3)
                    .setSampleRate(
                            BluetoothLeAudioCodecConfig.SAMPLE_RATE_48000
                                    | BluetoothLeAudioCodecConfig.SAMPLE_RATE_16000)
                    .build();

    private static final List<BluetoothLeAudioCodecConfig> INPUT_CAPABILITIES_CONFIG =
            List.of(LC3_48KHZ_16KHZ_CONFIG);

    private static final List<BluetoothLeAudioCodecConfig> OUTPUT_CAPABILITIES_CONFIG =
            List.of(LC3_48KHZ_16KHZ_CONFIG);

    private static final List<BluetoothLeAudioCodecConfig> INPUT_SELECTABLE_CONFIG =
            List.of(LC3_16KHZ_CONFIG);

    private static final List<BluetoothLeAudioCodecConfig> INPUT_EMPTY_CONFIG =
            List.of(EMPTY_CONFIG);

    private static final List<BluetoothLeAudioCodecConfig> OUTPUT_SELECTABLE_CONFIG =
            List.of(LC3_48KHZ_16KHZ_CONFIG);

    @Before
    public void setUp() throws Exception {
        mTargetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // Use spied objects factory
        doNothing().when(mTmapGattServer).start(anyInt());
        doNothing().when(mTmapGattServer).stop();
        LeAudioObjectsFactory.setInstanceForTesting(mObjectsFactory);
        doReturn(mTmapGattServer).when(mObjectsFactory).getTmapGattServer(any());

        /* If previous test failed, make sure to clear adapter. */
        if (AdapterService.getAdapterService() != null) {
            TestUtils.clearAdapterService(AdapterService.getAdapterService());
        }

        TestUtils.setAdapterService(mAdapterService);
        doReturn(MAX_LE_AUDIO_CONNECTIONS).when(mAdapterService).getMaxConnectedAudioDevices();
        doReturn(
                        (long) (1 << BluetoothProfile.LE_AUDIO_BROADCAST)
                                | (1 << BluetoothProfile.LE_AUDIO))
                .when(mAdapterService)
                .getSupportedProfilesBitMask();
        doReturn(new ParcelUuid[] {BluetoothUuid.LE_AUDIO})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        doReturn(mActiveDeviceManager).when(mAdapterService).getActiveDeviceManager();
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();

        BluetoothManager manager = mTargetContext.getSystemService(BluetoothManager.class);
        assertThat(manager).isNotNull();
        mAdapter = manager.getAdapter();
        // Mock methods in AdapterService
        doAnswer(invocation -> mBondedDevices.toArray(new BluetoothDevice[] {}))
                .when(mAdapterService)
                .getBondedDevices();
        doReturn(mGattService).when(mAdapterService).getBluetoothGattService();
        doReturn(mScanController).when(mAdapterService).getBluetoothScanController();
        doReturn(mTransitionalScanHelper).when(mGattService).getTransitionalScanHelper();
        doReturn(mTransitionalScanHelper).when(mScanController).getTransitionalScanHelper();

        LeAudioBroadcasterNativeInterface.setInstance(mLeAudioBroadcasterNativeInterface);
        LeAudioNativeInterface.setInstance(mNativeInterface);
        startService();

        mService.mAudioManager = mAudioManager;
        mService.mMcpService = mMcpService;
        mService.mTbsService = mTbsService;
        mService.mHapClientService = mHapClientService;
        mService.mBassClientService = mBassClientService;
        mService.mServiceFactory = mServiceFactory;
        when(mServiceFactory.getVolumeControlService()).thenReturn(mVolumeControlService);
        when(mServiceFactory.getHapClientService()).thenReturn(mHapClientService);
        when(mServiceFactory.getCsipSetCoordinatorService()).thenReturn(mCsipSetCoordinatorService);
        when(mServiceFactory.getBassClientService()).thenReturn(mBassClientService);

        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_NATIVE_INITIALIZED);
        mService.messageFromNative(stackEvent);
        assertThat(mService.mLeAudioNativeIsInitialized).isTrue();

        // Override the timeout value to speed up the test
        LeAudioStateMachine.sConnectTimeoutMs = TIMEOUT_MS; // 1s

        mGroupIntentQueue = new LinkedBlockingQueue<>();

        // Set up the Connection State Changed receiver
        IntentFilter filter = new IntentFilter();
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        filter.addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED);
        mLeAudioIntentReceiver = new LeAudioIntentReceiver();
        mTargetContext.registerReceiver(mLeAudioIntentReceiver, filter);

        doAnswer(invocation -> mBondedDevices.toArray(new BluetoothDevice[] {}))
                .when(mAdapterService)
                .getBondedDevices();

        // Get a device for testing
        mLeftDevice = TestUtils.getTestDevice(mAdapter, 0);
        mRightDevice = TestUtils.getTestDevice(mAdapter, 1);
        mSingleDevice = TestUtils.getTestDevice(mAdapter, 2);
        mSingleDevice_2 = TestUtils.getTestDevice(mAdapter, 3);
        mDeviceQueueMap = new HashMap<>();
        mDeviceQueueMap.put(mLeftDevice, new LinkedBlockingQueue<>());
        mDeviceQueueMap.put(mRightDevice, new LinkedBlockingQueue<>());
        mDeviceQueueMap.put(mSingleDevice, new LinkedBlockingQueue<>());
        mDeviceQueueMap.put(mSingleDevice_2, new LinkedBlockingQueue<>());
        doReturn(BluetoothDevice.BOND_BONDED)
                .when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        doReturn(new ParcelUuid[] {BluetoothUuid.LE_AUDIO})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        verify(mNativeInterface, timeout(3000).times(1)).init(any());
    }

    @After
    public void tearDown() throws Exception {
        if ((mService == null) || (mAdapter == null)) {
            return;
        }

        if (mLeAudioIntentReceiver != null) {
            mTargetContext.unregisterReceiver(mLeAudioIntentReceiver);
        }

        mBondedDevices.clear();
        mGroupIntentQueue.clear();
        stopService();
        if (mDeviceQueueMap != null) {
            mDeviceQueueMap.clear();
        }
        TestUtils.clearAdapterService(mAdapterService);
        LeAudioNativeInterface.setInstance(null);
    }

    private void startService() throws Exception {
        mService = new LeAudioService(mTargetContext);
        // LeAudioService#start post on the main Looper a call to
        // LeAudioService#init and expect it to be run after start
        // has finished.
        // To ensure that we run start on the main looper as well.
        mService.setAvailable(true);
        FutureTask task = new FutureTask(mService::start, null);
        new Handler(Looper.getMainLooper()).post(task);
        task.get();
    }

    private void stopService() throws TimeoutException {
        mService.stop();
        mService = LeAudioService.getLeAudioService();
        assertThat(mService).isNull();
    }

    private class LeAudioIntentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            /* Ignore intent when service is inactive */
            if (mService == null) {
                return;
            }

            if (BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED.equals(
                    intent.getAction())) {
                try {
                    BluetoothDevice device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    assertThat(device).isNotNull();
                    LinkedBlockingQueue<Intent> queue = mDeviceQueueMap.get(device);
                    assertThat(queue).isNotNull();
                    queue.put(intent);
                } catch (InterruptedException e) {
                    assertWithMessage(
                                    "Cannot add Intent to the Connection State queue: "
                                            + e.getMessage())
                            .fail();
                }
            } else if (BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED.equals(
                    intent.getAction())) {
                try {
                    BluetoothDevice device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) {
                        LinkedBlockingQueue<Intent> queue = mDeviceQueueMap.get(device);
                        assertThat(queue).isNotNull();
                        queue.put(intent);
                    }
                } catch (InterruptedException e) {
                    assertWithMessage(
                                    "Cannot add Le Audio Intent to the Connection State queue: "
                                            + e.getMessage())
                            .fail();
                }
            }
        }
    }

    private void verifyConnectionStateIntent(
            int timeoutMs, BluetoothDevice device, int newState, int prevState) {
        Intent intent = TestUtils.waitForIntent(timeoutMs, mDeviceQueueMap.get(device));
        assertThat(intent).isNotNull();
        assertThat(intent.getAction())
                .isEqualTo(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        assertThat((BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE))
                .isEqualTo(device);
        assertThat(intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)).isEqualTo(newState);
        assertThat(intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1))
                .isEqualTo(prevState);

        if (newState == BluetoothProfile.STATE_CONNECTED) {
            // ActiveDeviceManager calls deviceConnected when connected.
            mService.deviceConnected(device);
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            // ActiveDeviceManager calls deviceDisconnected when connected.
            mService.deviceDisconnected(device, false);
        }
    }

    /** Test getting LeAudio Service: getLeAudioService() */
    @Test
    public void testGetLeAudioService() {
        assertThat(mService).isEqualTo(LeAudioService.getLeAudioService());
    }

    /** Test enabling disabling device autoconnections when connection policy is set */
    @Test
    public void testEnableDisableProfile() {
        // Make sure the device is known to the service and is not forbidden to connect
        mService.createDeviceDescriptor(mSingleDevice, true);
        when(mDatabaseManager.getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);

        // Verify the device is enabled in the service when policy is not FORBIDDEN during BT Enable
        mService.handleBluetoothEnabled();
        verify(mNativeInterface).setEnableState(eq(mSingleDevice), eq(true));

        // Verify the device is disabled in the service when policy is set to FORBIDDEN
        when(mDatabaseManager.setProfileConnectionPolicy(
                        eq(mSingleDevice), eq(BluetoothProfile.LE_AUDIO), anyInt()))
                .thenReturn(true);
        mService.setConnectionPolicy(mSingleDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        verify(mNativeInterface).setEnableState(eq(mSingleDevice), eq(false));
    }

    /** Test stop LeAudio Service */
    @Test
    public void testStopLeAudioService() {
        // Prepare: connect
        connectDevice(mLeftDevice);
        // LeAudio Service is already running: test stop(). Note: must be done on the main thread
        InstrumentationRegistry.getInstrumentation().runOnMainSync(mService::stop);
    }

    /** Test get/set priority for BluetoothDevice */
    @Test
    public void testGetSetPriority() {
        when(mDatabaseManager.getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
        assertWithMessage("Initial device priority")
                .that(BluetoothProfile.CONNECTION_POLICY_UNKNOWN)
                .isEqualTo(mService.getConnectionPolicy(mLeftDevice));

        when(mDatabaseManager.getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        assertWithMessage("Setting device priority to PRIORITY_OFF")
                .that(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN)
                .isEqualTo(mService.getConnectionPolicy(mLeftDevice));

        when(mDatabaseManager.getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        assertWithMessage("Setting device priority to PRIORITY_ON")
                .that(BluetoothProfile.CONNECTION_POLICY_ALLOWED)
                .isEqualTo(mService.getConnectionPolicy(mLeftDevice));
    }

    /**
     * Helper function to test okToConnect() method
     *
     * @param device test device
     * @param bondState bond state value, could be invalid
     * @param priority value, could be invalid, could be invalid
     * @param expected expected result from okToConnect()
     */
    private void testOkToConnectCase(
            BluetoothDevice device, int bondState, int priority, boolean expected) {
        doReturn(bondState).when(mAdapterService).getBondState(device);
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.LE_AUDIO))
                .thenReturn(priority);
        assertThat(expected).isEqualTo(mService.okToConnect(device));
    }

    /** Test okToConnect method using various test cases */
    @Test
    public void testOkToConnect() {
        int badPriorityValue = 1024;
        int badBondState = 42;
        testOkToConnectCase(
                mSingleDevice,
                BluetoothDevice.BOND_NONE,
                BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                false);
        testOkToConnectCase(
                mSingleDevice,
                BluetoothDevice.BOND_NONE,
                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                false);
        testOkToConnectCase(
                mSingleDevice,
                BluetoothDevice.BOND_NONE,
                BluetoothProfile.CONNECTION_POLICY_ALLOWED,
                false);
        testOkToConnectCase(mSingleDevice, BluetoothDevice.BOND_NONE, badPriorityValue, false);
        testOkToConnectCase(
                mSingleDevice,
                BluetoothDevice.BOND_BONDING,
                BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                false);
        testOkToConnectCase(
                mSingleDevice,
                BluetoothDevice.BOND_BONDING,
                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                false);
        testOkToConnectCase(
                mSingleDevice,
                BluetoothDevice.BOND_BONDING,
                BluetoothProfile.CONNECTION_POLICY_ALLOWED,
                false);
        testOkToConnectCase(mSingleDevice, BluetoothDevice.BOND_BONDING, badPriorityValue, false);
        testOkToConnectCase(
                mSingleDevice,
                BluetoothDevice.BOND_BONDED,
                BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                true);
        testOkToConnectCase(
                mSingleDevice,
                BluetoothDevice.BOND_BONDED,
                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                false);
        testOkToConnectCase(
                mSingleDevice,
                BluetoothDevice.BOND_BONDED,
                BluetoothProfile.CONNECTION_POLICY_ALLOWED,
                true);
        testOkToConnectCase(mSingleDevice, BluetoothDevice.BOND_BONDED, badPriorityValue, false);
        testOkToConnectCase(
                mSingleDevice, badBondState, BluetoothProfile.CONNECTION_POLICY_UNKNOWN, false);
        testOkToConnectCase(
                mSingleDevice, badBondState, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(
                mSingleDevice, badBondState, BluetoothProfile.CONNECTION_POLICY_ALLOWED, false);
        testOkToConnectCase(mSingleDevice, badBondState, badPriorityValue, false);
    }

    /** Test that an outgoing connection to device that does not have Le Audio UUID is rejected */
    @Test
    public void testOutgoingConnectMissingLeAudioUuid() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        when(mDatabaseManager.getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        when(mDatabaseManager.getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        // Return No UUID
        doReturn(new ParcelUuid[] {})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        // Send a connect request
        assertWithMessage("Connect expected to fail").that(mService.connect(mLeftDevice)).isFalse();
    }

    /** Test that an outgoing connection to device with PRIORITY_OFF is rejected */
    @Test
    public void testOutgoingConnectPriorityOff() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        // Set the device priority to PRIORITY_OFF so connect() should fail
        when(mDatabaseManager.getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);

        // Send a connect request
        assertWithMessage("Connect expected to fail").that(mService.connect(mLeftDevice)).isFalse();
    }

    /** Test that an outgoing connection times out */
    @Test
    public void testOutgoingConnectTimeout() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        when(mDatabaseManager.getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        when(mDatabaseManager.getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        // Send a connect request
        assertWithMessage("Connect failed").that(mService.connect(mLeftDevice)).isTrue();

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mLeftDevice,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTING);

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(
                LeAudioStateMachine.sConnectTimeoutMs * 2,
                mLeftDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
    }

    private void injectAudioDeviceAdded(
            BluetoothDevice device,
            int type,
            boolean isSink,
            boolean isSource,
            boolean expectedIntent) {
        mService.handleAudioDeviceAdded(device, type, isSink, isSource);
        if (expectedIntent) {
            verifyActiveDeviceStateIntent(AUDIO_MANAGER_DEVICE_ADD_TIMEOUT_MS, device);
        } else {
            Intent intent = TestUtils.waitForNoIntent(TIMEOUT_MS, mDeviceQueueMap.get(device));
            assertThat(intent).isNull();
        }
    }

    private void injectAudioDeviceRemoved(
            BluetoothDevice device,
            int type,
            boolean isSink,
            boolean isSource,
            boolean expectedIntent) {
        mService.handleAudioDeviceRemoved(device, type, isSink, isSource);
        if (expectedIntent) {
            verifyActiveDeviceStateIntent(AUDIO_MANAGER_DEVICE_ADD_TIMEOUT_MS, null);
        } else {
            Intent intent = TestUtils.waitForNoIntent(TIMEOUT_MS, mDeviceQueueMap.get(device));
            assertThat(intent).isNull();
        }
    }

    private void injectNoVerifyDeviceConnected(BluetoothDevice device) {
        generateUnexpectedConnectionMessageFromNative(
                device, LeAudioStackEvent.CONNECTION_STATE_CONNECTED);
    }

    private void injectAndVerifyDeviceDisconnected(BluetoothDevice device) {
        generateConnectionMessageFromNative(
                device,
                LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED,
                LeAudioStackEvent.CONNECTION_STATE_CONNECTED);
    }

    private void injectNoVerifyDeviceDisconnected(BluetoothDevice device) {
        generateUnexpectedConnectionMessageFromNative(
                device, LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED);
    }

    /** Test that the outgoing connect/disconnect and audio switch is successful. */
    @Test
    public void testAudioManagerConnectDisconnect() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        when(mDatabaseManager.getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        when(mDatabaseManager.getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        // Send a connect request
        assertWithMessage("Connect failed").that(mService.connect(mLeftDevice)).isTrue();
        assertWithMessage("Connect failed").that(mService.connect(mRightDevice)).isTrue();

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mLeftDevice,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTING);
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mRightDevice,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mRightDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTING);

        LeAudioStackEvent connCompletedEvent;
        // Send a message to trigger connection completed
        connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mLeftDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mLeftDevice,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTED);

        // Send a message to trigger connection completed for right side
        connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mRightDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Connected state for right side
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mRightDevice,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        assertThat(mService.getConnectionState(mRightDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTED);

        // Verify the list of connected devices
        assertThat(mService.getConnectedDevices().contains(mLeftDevice)).isTrue();
        assertThat(mService.getConnectedDevices().contains(mRightDevice)).isTrue();

        // Send a disconnect request
        assertWithMessage("Disconnect failed").that(mService.disconnect(mLeftDevice)).isTrue();
        assertWithMessage("Disconnect failed").that(mService.disconnect(mRightDevice)).isTrue();

        // Verify the connection state broadcast, and that we are in Disconnecting state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mLeftDevice,
                BluetoothProfile.STATE_DISCONNECTING,
                BluetoothProfile.STATE_CONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTING);
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mRightDevice,
                BluetoothProfile.STATE_DISCONNECTING,
                BluetoothProfile.STATE_CONNECTED);
        assertThat(mService.getConnectionState(mRightDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTING);

        // Send a message to trigger disconnection completed
        connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mLeftDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED;
        mService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mLeftDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);

        // Send a message to trigger disconnection completed to the right device
        connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mRightDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED;
        mService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mRightDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mRightDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);

        // Verify the list of connected devices
        assertThat(mService.getConnectedDevices().contains(mLeftDevice)).isFalse();
        assertThat(mService.getConnectedDevices().contains(mRightDevice)).isFalse();
    }

    /**
     * Test that only CONNECTION_STATE_CONNECTED or CONNECTION_STATE_CONNECTING Le Audio stack
     * events will create a state machine.
     */
    @Test
    public void testCreateStateMachineStackEvents() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        when(mDatabaseManager.getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        when(mDatabaseManager.getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        // Create device descriptor with connect request
        assertWithMessage("Connect failed").that(mService.connect(mLeftDevice)).isTrue();

        // Le Audio stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(
                mLeftDevice,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTING);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_DISCONNECTED - state machine should be removed
        generateConnectionMessageFromNative(
                mLeftDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();

        // Remove bond will remove also device descriptor. Device has to be connected again
        assertWithMessage("Connect failed").that(mService.connect(mLeftDevice)).isTrue();
        verifyConnectionStateIntent(
                LeAudioStateMachine.sConnectTimeoutMs * 2,
                mLeftDevice,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);

        // stack event: CONNECTION_STATE_CONNECTED - state machine should be created
        generateConnectionMessageFromNative(
                mLeftDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // stack event: CONNECTION_STATE_DISCONNECTED - state machine should be removed
        generateConnectionMessageFromNative(
                mLeftDevice, BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_CONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();

        // stack event: CONNECTION_STATE_DISCONNECTING - state machine should not be created
        generateUnexpectedConnectionMessageFromNative(
                mLeftDevice, BluetoothProfile.STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();

        // stack event: CONNECTION_STATE_DISCONNECTED - state machine should not be created
        generateUnexpectedConnectionMessageFromNative(
                mLeftDevice, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();
    }

    /**
     * Test that a state machine in DISCONNECTED state is removed only after the device is unbond.
     */
    @Test
    public void testDeleteStateMachineUnbondEvents() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        when(mDatabaseManager.getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        when(mDatabaseManager.getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        // Create device descriptor with connect request
        assertWithMessage("Connect failed").that(mService.connect(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(
                mLeftDevice,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTING);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        // Device unbond - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mLeftDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTING);

        // LeAudio stack event: CONNECTION_STATE_CONNECTED - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_BONDED);
        generateConnectionMessageFromNative(
                mLeftDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        // Device unbond - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mLeftDevice,
                BluetoothProfile.STATE_DISCONNECTING,
                BluetoothProfile.STATE_CONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTING);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_DISCONNECTING - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_BONDED);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTING);
        // Device unbond - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_DISCONNECTED - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_BONDED);
        generateConnectionMessageFromNative(
                mLeftDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        // Device unbond - state machine is removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();
    }

    /**
     * Test that a CONNECTION_STATE_DISCONNECTED Le Audio stack event will remove the state machine
     * only if the device is unbond.
     */
    @Test
    public void testDeleteStateMachineDisconnectEvents() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        when(mDatabaseManager.getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        when(mDatabaseManager.getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        // Create device descriptor with connect request
        assertWithMessage("Connect failed").that(mService.connect(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(
                mLeftDevice,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTING);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_DISCONNECTED - state machine is not removed
        generateConnectionMessageFromNative(
                mLeftDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_CONNECTING - state machine remains
        generateConnectionMessageFromNative(
                mLeftDevice,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTING);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // Device bond state marked as unbond - state machine is not removed
        doReturn(BluetoothDevice.BOND_NONE)
                .when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_DISCONNECTED - state machine is removed
        generateConnectionMessageFromNative(
                mLeftDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();
    }

    private void connectDevice(BluetoothDevice device) {
        LeAudioStackEvent connCompletedEvent;

        List<BluetoothDevice> prevConnectedDevices = mService.getConnectedDevices();

        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectLeAudio(device);
        doReturn(true).when(mNativeInterface).disconnectLeAudio(device);

        // Send a connect request
        assertWithMessage("Connect failed").that(mService.connect(device)).isTrue();

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                device,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(device))
                .isEqualTo(BluetoothProfile.STATE_CONNECTING);

        // Send a message to trigger connection completed
        connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = device;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                device,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        assertThat(mService.getConnectionState(device)).isEqualTo(BluetoothProfile.STATE_CONNECTED);

        // Verify that the device is in the list of connected devices
        assertThat(mService.getConnectedDevices().contains(device)).isTrue();
        // Verify the list of previously connected devices
        for (BluetoothDevice prevDevice : prevConnectedDevices) {
            assertThat(mService.getConnectedDevices().contains(prevDevice)).isTrue();
        }
    }

    private void generateConnectionMessageFromNative(
            BluetoothDevice device, int newConnectionState, int oldConnectionState) {
        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt1 = newConnectionState;
        mService.messageFromNative(stackEvent);
        // Verify the connection state broadcast
        verifyConnectionStateIntent(TIMEOUT_MS, device, newConnectionState, oldConnectionState);
    }

    private void generateUnexpectedConnectionMessageFromNative(
            BluetoothDevice device, int newConnectionState) {
        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt1 = newConnectionState;
        mService.messageFromNative(stackEvent);
        // Verify the connection state broadcast
        verifyNoConnectionStateIntent(TIMEOUT_MS, device);
    }

    private void generateGroupNodeAdded(BluetoothDevice device, int groupId) {
        LeAudioStackEvent nodeGroupAdded =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        nodeGroupAdded.device = device;
        nodeGroupAdded.valueInt1 = groupId;
        nodeGroupAdded.valueInt2 = LeAudioStackEvent.GROUP_NODE_ADDED;
        mService.messageFromNative(nodeGroupAdded);
    }

    private void generateGroupNodeRemoved(BluetoothDevice device, int groupId) {
        LeAudioStackEvent nodeGroupRemoved =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        nodeGroupRemoved.device = device;
        nodeGroupRemoved.valueInt1 = groupId;
        nodeGroupRemoved.valueInt2 = LeAudioStackEvent.GROUP_NODE_REMOVED;
        mService.messageFromNative(nodeGroupRemoved);
    }

    private void verifyNoConnectionStateIntent(int timeoutMs, BluetoothDevice device) {
        Intent intent = TestUtils.waitForNoIntent(timeoutMs, mDeviceQueueMap.get(device));
        assertThat(intent).isNull();
    }

    /** Test setting connection policy */
    @Test
    public void testSetConnectionPolicy() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));
        doReturn(true)
                .when(mDatabaseManager)
                .setProfileConnectionPolicy(any(BluetoothDevice.class), anyInt(), anyInt());
        when(mVolumeControlService.setConnectionPolicy(any(), anyInt())).thenReturn(true);
        when(mCsipSetCoordinatorService.setConnectionPolicy(any(), anyInt())).thenReturn(true);
        when(mHapClientService.setConnectionPolicy(any(), anyInt())).thenReturn(true);
        when(mBassClientService.setConnectionPolicy(any(), anyInt())).thenReturn(true);
        when(mDatabaseManager.getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);

        assertThat(
                        mService.setConnectionPolicy(
                                mSingleDevice, BluetoothProfile.CONNECTION_POLICY_ALLOWED))
                .isTrue();

        // Verify connection policy for CSIP and VCP are also set
        verify(mVolumeControlService)
                .setConnectionPolicy(mSingleDevice, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        verify(mCsipSetCoordinatorService)
                .setConnectionPolicy(mSingleDevice, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        verify(mHapClientService)
                .setConnectionPolicy(mSingleDevice, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        if (BluetoothProperties.isProfileBapBroadcastAssistEnabled().orElse(false)) {
            verify(mBassClientService)
                    .setConnectionPolicy(mSingleDevice, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        }
        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mSingleDevice,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mSingleDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTING);

        LeAudioStackEvent connCompletedEvent;
        // Send a message to trigger connection completed
        connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mSingleDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mSingleDevice,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        assertThat(mService.getConnectionState(mSingleDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTED);

        // Set connection policy to forbidden
        assertThat(
                        mService.setConnectionPolicy(
                                mSingleDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN))
                .isTrue();

        // Verify connection policy for CSIP and VCP are also set
        verify(mVolumeControlService)
                .setConnectionPolicy(mSingleDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        verify(mCsipSetCoordinatorService)
                .setConnectionPolicy(mSingleDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        verify(mHapClientService)
                .setConnectionPolicy(mSingleDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        if (BluetoothProperties.isProfileBapBroadcastAssistEnabled().orElse(false)) {
            verify(mBassClientService)
                    .setConnectionPolicy(
                            mSingleDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        }
        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mSingleDevice,
                BluetoothProfile.STATE_DISCONNECTING,
                BluetoothProfile.STATE_CONNECTED);
        assertThat(mService.getConnectionState(mSingleDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTING);

        // Send a message to trigger disconnection completed
        connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mSingleDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED;
        mService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mSingleDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mSingleDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
    }

    /**
     * Helper function to connect Test device
     *
     * @param device test device
     */
    private void connectTestDevice(BluetoothDevice device, int GroupId) {
        List<BluetoothDevice> prevConnectedDevices = mService.getConnectedDevices();

        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
        // Send a connect request
        assertWithMessage("Connect failed").that(mService.connect(device)).isTrue();

        // Make device bonded
        mBondedDevices.add(device);

        // Wait ASYNC_CALL_TIMEOUT_MILLIS for state to settle, timing is also tested here and
        // 250ms for processing two messages should be way more than enough. Anything that breaks
        // this indicate some breakage in other part of Android OS

        verifyConnectionStateIntent(
                ASYNC_CALL_TIMEOUT_MILLIS,
                device,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(device))
                .isEqualTo(BluetoothProfile.STATE_CONNECTING);

        // Use connected event to indicate that device is connected
        LeAudioStackEvent connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = device;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(connCompletedEvent);

        verifyConnectionStateIntent(
                ASYNC_CALL_TIMEOUT_MILLIS,
                device,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING);

        assertThat(mService.getConnectionState(device)).isEqualTo(BluetoothProfile.STATE_CONNECTED);

        LeAudioStackEvent nodeGroupAdded =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        nodeGroupAdded.device = device;
        nodeGroupAdded.valueInt1 = GroupId;
        nodeGroupAdded.valueInt2 = LeAudioStackEvent.GROUP_NODE_ADDED;
        mService.messageFromNative(nodeGroupAdded);

        // Verify that the device is in the list of connected devices
        assertThat(mService.getConnectedDevices().contains(device)).isTrue();
        // Verify the list of previously connected devices
        for (BluetoothDevice prevDevice : prevConnectedDevices) {
            assertThat(mService.getConnectedDevices().contains(prevDevice)).isTrue();
        }
    }

    /** Test adding node */
    @Test
    public void testGroupAddRemoveNode() {
        int groupId = 1;

        doReturn(true).when(mNativeInterface).groupAddNode(groupId, mSingleDevice);
        doReturn(true).when(mNativeInterface).groupRemoveNode(groupId, mSingleDevice);

        assertThat(mService.groupAddNode(groupId, mSingleDevice)).isTrue();
        assertThat(mService.groupRemoveNode(groupId, mSingleDevice)).isTrue();
    }

    /** Test setting active device group with Ringtone context */
    @Test
    public void testSetActiveDeviceGroup() {
        int groupId = 1;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Connected device
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        // Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.device = mSingleDevice;
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        verify(mNativeInterface).groupSetActive(groupId);

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = groupId;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);

        verify(mTbsService).setInbandRingtoneSupport(mSingleDevice);

        // no active device
        assertThat(mService.removeActiveDevice(false)).isTrue();
        verify(mNativeInterface).groupSetActive(BluetoothLeAudio.GROUP_ID_INVALID);

        // Set group and device as inactive active
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);

        verify(mTbsService).clearInbandRingtoneSupport(mSingleDevice);
    }

    /** Test setting active device group for already active group */
    @Test
    public void testSetActiveDeviceGroupTwice() {
        int groupId = 1;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Connected device
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        // Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.device = mSingleDevice;
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        verify(mNativeInterface).groupSetActive(groupId);

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = groupId;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);

        verify(mTbsService).setInbandRingtoneSupport(mSingleDevice);

        /* Expect 2 calles to Audio Manager - one for output  as this is
         * Ringtone use case */
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(), any(), any(BluetoothProfileConnectionInfo.class));
        /* Since LeAudioService called AudioManager - assume Audio manager calles properly callback
         * mAudioManager.onAudioDeviceAdded
         */
        injectAudioDeviceAdded(mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, true);

        reset(mNativeInterface);

        // set active device again
        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        verify(mNativeInterface, times(0)).groupSetActive(groupId);

        String action = BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED;
        Intent intent = TestUtils.waitForIntent(TIMEOUT_MS, mDeviceQueueMap.get(mSingleDevice));
        assertThat(intent).isNotNull();
        assertThat(action).isEqualTo(intent.getAction());
        assertThat(mSingleDevice)
                .isEqualTo(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
    }

    /** Test setting active devices from the same group */
    @Test
    public void testSetActiveDevicesFromSameGroup() {
        int groupId = 1;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        /* AUDIO_DIRECTION_INPUT_BIT = 0x02 */
        int direction = 3;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        ArgumentCaptor<BluetoothProfileConnectionInfo> connectionInfoArgumentCaptor =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Connected device
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mLeftDevice, groupId);
        connectTestDevice(mRightDevice, groupId);

        // Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.device = mSingleDevice;
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        assertThat(mService.setActiveDevice(mLeftDevice)).isTrue();
        verify(mNativeInterface).groupSetActive(groupId);

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = groupId;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);

        verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        eq(mLeftDevice), eq(null), connectionInfoArgumentCaptor.capture());
        List<BluetoothProfileConnectionInfo> connInfos =
                connectionInfoArgumentCaptor.getAllValues();
        assertThat(connInfos.size()).isEqualTo(2);
        assertThat(connInfos.get(0).isLeOutput()).isEqualTo(true);
        assertThat(connInfos.get(1).isLeOutput()).isEqualTo(false);

        reset(mAudioManager);

        assertThat(mService.setActiveDevice(mRightDevice)).isTrue();
        verify(mAudioManager, times(0))
                .handleBluetoothActiveDeviceChanged(
                        any(), any(), any(BluetoothProfileConnectionInfo.class));
        connInfos = connectionInfoArgumentCaptor.getAllValues();
        assertThat(connInfos.size()).isEqualTo(2);
    }

    /** Test setting active device group with not available contexts */
    @Test
    public void testSetActiveDeviceGroupWithNoContextTypes() {
        int groupId = 1;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 0;

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Connected device
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        // Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.device = mSingleDevice;
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();
        verify(mNativeInterface, times(0)).groupSetActive(groupId);
    }

    /** Test switching active groups */
    @Test
    public void testSwitchActiveGroups() {
        int groupId_1 = 1;
        int groupId_2 = 2;

        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Define some return values needed in test
        doReturn(-1).when(mVolumeControlService).getAudioDeviceGroupVolume(anyInt());
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));

        // Connect both
        connectTestDevice(mSingleDevice, groupId_1);
        connectTestDevice(mSingleDevice_2, groupId_2);

        // Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.device = mSingleDevice;
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId_1;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        // Add location support
        audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.device = mSingleDevice_2;
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId_2;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        verify(mNativeInterface).groupSetActive(groupId_1);

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = groupId_1;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);

        verify(mTbsService).setInbandRingtoneSupport(mSingleDevice);

        ArgumentCaptor<BluetoothProfileConnectionInfo> connectionInfoArgumentCaptor =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);

        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mSingleDevice), eq(null), connectionInfoArgumentCaptor.capture());

        injectAudioDeviceAdded(mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, true);

        BluetoothProfileConnectionInfo connInfo = connectionInfoArgumentCaptor.getValue();
        assertThat(connInfo.isSuppressNoisyIntent()).isTrue();

        reset(mAudioManager);

        // set active device again
        assertThat(mService.setActiveDevice(mSingleDevice_2)).isTrue();
        verify(mNativeInterface).groupSetActive(groupId_2);


        // First wait for ACTIVE state will be sent from native
        LeAudioStackEvent activeGroupState =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        activeGroupState.valueInt1 = groupId_2;
        activeGroupState.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        activeGroupState.valueInt3 = groupId_1;
        mService.messageFromNative(activeGroupState);

        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mSingleDevice_2),
                        eq(mSingleDevice),
                        connectionInfoArgumentCaptor.capture());
        connInfo = connectionInfoArgumentCaptor.getValue();
        assertThat(connInfo.isSuppressNoisyIntent()).isTrue();

        injectAudioDeviceRemoved(
                mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, false);
        injectAudioDeviceAdded(
                mSingleDevice_2, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, true);
        verify(mNativeInterface, times(0)).groupSetActive(-1);
    }

    /** Test switching active groups */
    @Test
    public void testAudioFrameworkAutonomousDeviceRemoval() {
        int groupId_1 = 1;

        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Define some return values needed in test
        doReturn(-1).when(mVolumeControlService).getAudioDeviceGroupVolume(anyInt());
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));

        // Connect both
        connectTestDevice(mSingleDevice, groupId_1);

        // Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.device = mSingleDevice;
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId_1;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        verify(mNativeInterface).groupSetActive(groupId_1);

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = groupId_1;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);

        verify(mTbsService).setInbandRingtoneSupport(mSingleDevice);

        ArgumentCaptor<BluetoothProfileConnectionInfo> connectionInfoArgumentCaptor =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);

        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mSingleDevice), eq(null), connectionInfoArgumentCaptor.capture());

        injectAudioDeviceAdded(mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, true);

        BluetoothProfileConnectionInfo connInfo = connectionInfoArgumentCaptor.getValue();
        assertThat(connInfo.isSuppressNoisyIntent()).isTrue();

        // AudioManager removes audio device
        injectAudioDeviceRemoved(
                mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, false);
        verify(mNativeInterface, times(1)).groupSetActive(-1);
    }

    /** Test setting active device group without Ringtone context */
    @Test
    public void testSetActiveDeviceGroupWithoutRingtoneContext() {
        int groupId = 1;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5;

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Connected device
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        // Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.device = mSingleDevice;
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        verify(mNativeInterface).groupSetActive(groupId);

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = groupId;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);

        // no active device
        assertThat(mService.removeActiveDevice(false)).isTrue();
        verify(mNativeInterface).groupSetActive(BluetoothLeAudio.GROUP_ID_INVALID);

        // Set group and device as inactive active
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);

        verify(mTbsService, times(0)).clearInbandRingtoneSupport(mSingleDevice);
    }

    private BluetoothLeBroadcastSettings buildBroadcastSettingsFromMetadata(
            BluetoothLeAudioContentMetadata contentMetadata,
            @Nullable byte[] broadcastCode,
            int numOfGroups) {
        BluetoothLeAudioContentMetadata.Builder publicMetaBuilder =
                new BluetoothLeAudioContentMetadata.Builder();
        publicMetaBuilder.setProgramInfo("Public broadcast info");

        BluetoothLeBroadcastSubgroupSettings.Builder subgroupBuilder =
                new BluetoothLeBroadcastSubgroupSettings.Builder()
                        .setContentMetadata(contentMetadata)
                        .setPreferredQuality(BluetoothLeBroadcastSubgroupSettings.QUALITY_HIGH);

        BluetoothLeBroadcastSettings.Builder builder =
                new BluetoothLeBroadcastSettings.Builder()
                        .setPublicBroadcast(true)
                        .setBroadcastName(TEST_BROADCAST_NAME)
                        .setBroadcastCode(broadcastCode)
                        .setPublicBroadcastMetadata(publicMetaBuilder.build());
        // builder expect at least one subgroup setting
        for (int i = 0; i < numOfGroups; i++) {
            // add subgroup settings with the same content
            builder.addSubgroupSettings(subgroupBuilder.build());
        }
        return builder.build();
    }

    /** Test update unicast fallback active group when broadcast is ongoing */
    @Test
    @DisableFlags(Flags.FLAG_LEAUDIO_BROADCAST_PRIMARY_GROUP_SELECTION)
    public void testUpdateUnicastFallbackActiveDeviceGroupDuringBroadcast() {
        int groupId = 1;
        int preGroupId = 2;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Connected device
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        mService.mUnicastGroupIdDeactivatedForBroadcastTransition = preGroupId;
        // mock create broadcast and currentlyActiveGroupId remains LE_AUDIO_GROUP_ID_INVALID
        BluetoothLeAudioContentMetadata.Builder meta_builder =
                new BluetoothLeAudioContentMetadata.Builder();
        meta_builder.setLanguage("deu");
        meta_builder.setProgramInfo("Public broadcast info");
        BluetoothLeAudioContentMetadata meta = meta_builder.build();
        BluetoothLeBroadcastSettings settings = buildBroadcastSettingsFromMetadata(meta, code, 1);
        mService.createBroadcast(settings);

        LeAudioStackEvent broadcastCreatedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED);
        broadcastCreatedEvent.device = mSingleDevice;
        broadcastCreatedEvent.valueInt1 = broadcastId;
        broadcastCreatedEvent.valueBool1 = true;
        mService.messageFromNative(broadcastCreatedEvent);

        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.device = mSingleDevice;
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        // Verify only update the fallback group and not proceed to change active
        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        assertThat(mService.mUnicastGroupIdDeactivatedForBroadcastTransition).isEqualTo(groupId);

        // Verify only update the fallback group to INVALID and not proceed to change active
        assertThat(mService.setActiveDevice(null)).isTrue();
        assertThat(mService.mUnicastGroupIdDeactivatedForBroadcastTransition)
                .isEqualTo(BluetoothLeAudio.GROUP_ID_INVALID);

        verify(mNativeInterface, times(0)).groupSetActive(anyInt());
    }

    /** Test getting active device */
    @Test
    public void testGetActiveDevices() {
        int groupId = 1;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5;
        int nodeStatus = LeAudioStackEvent.GROUP_NODE_ADDED;
        int groupStatus = LeAudioStackEvent.GROUP_STATUS_ACTIVE;

        // Single active device
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        // Add device to group
        LeAudioStackEvent nodeStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        nodeStatusChangedEvent.device = mSingleDevice;
        nodeStatusChangedEvent.valueInt1 = groupId;
        nodeStatusChangedEvent.valueInt2 = nodeStatus;
        mService.messageFromNative(nodeStatusChangedEvent);

        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.device = mSingleDevice;
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.device = mSingleDevice;
        groupStatusChangedEvent.valueInt1 = groupId;
        groupStatusChangedEvent.valueInt2 = groupStatus;
        mService.messageFromNative(groupStatusChangedEvent);

        assertThat(mService.getActiveDevices().contains(mSingleDevice)).isTrue();

        // Remove device from group
        groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        groupStatusChangedEvent.device = mSingleDevice;
        groupStatusChangedEvent.valueInt1 = groupId;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_NODE_REMOVED;
        mService.messageFromNative(groupStatusChangedEvent);

        assertThat(mService.getActiveDevices().contains(mSingleDevice)).isFalse();
    }

    private void injectGroupStatusChange(int groupId, int groupStatus) {
        int eventType = LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED;
        LeAudioStackEvent groupStatusChangedEvent = new LeAudioStackEvent(eventType);
        groupStatusChangedEvent.valueInt1 = groupId;
        groupStatusChangedEvent.valueInt2 = groupStatus;
        mService.messageFromNative(groupStatusChangedEvent);
    }

    private void injectGroupStreamStatusChange(int groupId, int groupStreamStatus) {
        int eventType = LeAudioStackEvent.EVENT_TYPE_GROUP_STREAM_STATUS_CHANGED;
        LeAudioStackEvent groupStreamStatusChangedEvent = new LeAudioStackEvent(eventType);
        groupStreamStatusChangedEvent.valueInt1 = groupId;
        groupStreamStatusChangedEvent.valueInt2 = groupStreamStatus;
        mService.messageFromNative(groupStreamStatusChangedEvent);
    }

    private void injectAudioConfChanged(int groupId, Integer availableContexts, int direction) {
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int eventType = LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED;

        // Add device to group
        LeAudioStackEvent audioConfChangedEvent = new LeAudioStackEvent(eventType);
        audioConfChangedEvent.device = mSingleDevice;
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);
    }

    /** Test group direction changed */
    @Test
    public void testGroupDirectionChanged_AudioConfChangedActiveGroup() {

        int testVolume = 100;

        ArgumentCaptor<BluetoothProfileConnectionInfo> testConnectioInfoCapture =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);

        doReturn(testVolume).when(mVolumeControlService).getAudioDeviceGroupVolume(testGroupId);

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);
        injectAudioConfChanged(
                testGroupId,
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL,
                3);
        injectGroupStatusChange(testGroupId, BluetoothLeAudio.GROUP_STATUS_ACTIVE);

        verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        eq(mSingleDevice), eq(null), testConnectioInfoCapture.capture());

        injectAudioDeviceAdded(mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, true);
        injectAudioDeviceAdded(mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, false, true, false);

        reset(mAudioManager);
        /* Verify input and output has been connected to AF*/
        List<BluetoothProfileConnectionInfo> connInfos = testConnectioInfoCapture.getAllValues();
        assertThat(connInfos.size()).isEqualTo(2);
        assertThat(connInfos.get(0).isLeOutput()).isEqualTo(true);
        assertThat(connInfos.get(1).isLeOutput()).isEqualTo(false);

        // Remove source direction
        injectAudioConfChanged(
                testGroupId,
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL,
                1);

        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(mSingleDevice), testConnectioInfoCapture.capture());

        injectAudioDeviceAdded(mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, false);
        injectAudioDeviceRemoved(
                mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, false, true, false);

        reset(mAudioManager);

        connInfos = testConnectioInfoCapture.getAllValues();
        assertThat(connInfos.size()).isEqualTo(3);
        assertThat(connInfos.get(2).isLeOutput()).isEqualTo(false);

        // remove Sink and add Source back

        injectAudioConfChanged(
                testGroupId,
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL,
                2);

        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(mSingleDevice), testConnectioInfoCapture.capture());
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mSingleDevice), eq(null), testConnectioInfoCapture.capture());

        injectAudioDeviceRemoved(
                mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, false);
        injectAudioDeviceAdded(mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, false, true, false);

        reset(mAudioManager);

        connInfos = testConnectioInfoCapture.getAllValues();
        assertThat(connInfos.size()).isEqualTo(5);
        assertThat(connInfos.get(3).isLeOutput()).isEqualTo(true);
        assertThat(connInfos.get(4).isLeOutput()).isEqualTo(false);
    }

    /** Test native interface audio configuration changed message handling */
    @Test
    public void testMessageFromNativeAudioConfChangedActiveGroup() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);
        injectAudioConfChanged(
                testGroupId,
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL,
                3);
        injectGroupStatusChange(testGroupId, BluetoothLeAudio.GROUP_STATUS_ACTIVE);

        /* Expect 2 calles to Audio Manager - one for output and second for input as this is
         * Conversational use case */
        verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        any(), any(), any(BluetoothProfileConnectionInfo.class));
        /* Since LeAudioService called AudioManager - assume Audio manager calles properly callback
         * mAudioManager.onAudioDeviceAdded
         */
        injectAudioDeviceAdded(mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, true);
    }

    /** Test native interface audio configuration changed message handling */
    @Test
    public void testMessageFromNativeAudioConfChangedInactiveGroup() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        Integer contexts =
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL;
        injectAudioConfChanged(testGroupId, contexts, 3);

        Intent intent = TestUtils.waitForNoIntent(TIMEOUT_MS, mDeviceQueueMap.get(mSingleDevice));
        assertThat(intent).isNull();
    }

    /** Test native interface audio configuration changed message handling */
    @Test
    public void testMessageFromNativeAudioConfChangedNoGroupChanged() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        injectAudioConfChanged(testGroupId, 0, 3);
        Intent intent = TestUtils.waitForNoIntent(TIMEOUT_MS, mDeviceQueueMap.get(mSingleDevice));
        assertThat(intent).isNull();
    }

    /**
     * Test native interface health base action message handling. It does not much, just chects
     * stack even and that service not crash
     */
    @Test
    public void testHealthBaseDeviceAction() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        LeAudioStackEvent healthBaseDevAction =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_HEALTH_BASED_DEV_RECOMMENDATION);
        healthBaseDevAction.device = mSingleDevice;
        healthBaseDevAction.valueInt1 = LeAudioStackEvent.HEALTH_RECOMMENDATION_ACTION_DISABLE;
        mService.messageFromNative(healthBaseDevAction);
        assertThat(mService.mLeAudioNativeIsInitialized).isTrue();
    }

    @Test
    public void testHealthBasedGroupAction() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        LeAudioStackEvent healthBasedGroupAction =
                new LeAudioStackEvent(
                        LeAudioStackEvent.EVENT_TYPE_HEALTH_BASED_GROUP_RECOMMENDATION);
        healthBasedGroupAction.valueInt1 = testGroupId;
        healthBasedGroupAction.valueInt2 = LeAudioStackEvent.HEALTH_RECOMMENDATION_ACTION_DISABLE;
        mService.messageFromNative(healthBasedGroupAction);
        assertThat(mService.mLeAudioNativeIsInitialized).isTrue();
    }

    private void sendEventAndVerifyIntentForGroupStatusChanged(int groupId, int groupStatus) {

        onGroupStatusCallbackCalled = false;

        IBluetoothLeAudioCallback leAudioCallbacks =
                new IBluetoothLeAudioCallback.Stub() {
                    @Override
                    public void onCodecConfigChanged(int gid, BluetoothLeAudioCodecStatus status) {}

                    @Override
                    public void onGroupStatusChanged(int gid, int gStatus) {
                        onGroupStatusCallbackCalled = true;
                        assertThat(gid == groupId).isTrue();
                        assertThat(gStatus == groupStatus).isTrue();
                    }

                    @Override
                    public void onGroupNodeAdded(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupNodeRemoved(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupStreamStatusChanged(int groupId, int groupStreamStatus) {}

                    @Override
                    public void onBroadcastToUnicastFallbackGroupChanged(int groupId) {}
                };

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.register(leAudioCallbacks);
        }

        injectGroupStatusChange(groupId, groupStatus);

        TestUtils.waitForLooperToFinishScheduledTask(mService.getMainLooper());
        assertThat(onGroupStatusCallbackCalled).isTrue();

        onGroupStatusCallbackCalled = false;
        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.unregister(leAudioCallbacks);
        }
    }

    /** Test native interface group status message handling */
    @Test
    public void testMessageFromNativeGroupStatusChanged() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        injectAudioConfChanged(
                testGroupId,
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL,
                3);

        sendEventAndVerifyIntentForGroupStatusChanged(
                testGroupId, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        sendEventAndVerifyIntentForGroupStatusChanged(
                testGroupId, LeAudioStackEvent.GROUP_STATUS_INACTIVE);
    }

    private void sendEventAndVerifyGroupStreamStatusChanged(int groupId, int groupStreamStatus) {

        onGroupStreamStatusCallbackCalled = false;

        IBluetoothLeAudioCallback leAudioCallbacks =
                new IBluetoothLeAudioCallback.Stub() {
                    @Override
                    public void onCodecConfigChanged(int gid, BluetoothLeAudioCodecStatus status) {}

                    @Override
                    public void onGroupStatusChanged(int gid, int gStatus) {}

                    @Override
                    public void onGroupNodeAdded(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupNodeRemoved(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupStreamStatusChanged(int gid, int gStreamStatus) {
                        onGroupStreamStatusCallbackCalled = true;
                        assertThat(gid == groupId).isTrue();
                        assertThat(gStreamStatus == groupStreamStatus).isTrue();
                    }

                    @Override
                    public void onBroadcastToUnicastFallbackGroupChanged(int groupId) {}
                };

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.register(leAudioCallbacks);
        }

        injectGroupStreamStatusChange(groupId, groupStreamStatus);

        TestUtils.waitForLooperToFinishScheduledTask(mService.getMainLooper());
        assertThat(onGroupStreamStatusCallbackCalled).isTrue();

        onGroupStreamStatusCallbackCalled = false;
        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.unregister(leAudioCallbacks);
        }
    }

    /** Test native interface group stream status message handling */
    @Test
    public void testMessageFromNativeGroupStreamStatusChanged() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        injectAudioConfChanged(
                testGroupId,
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL,
                3);

        sendEventAndVerifyGroupStreamStatusChanged(
                testGroupId, LeAudioStackEvent.GROUP_STREAM_STATUS_IDLE);
        sendEventAndVerifyGroupStreamStatusChanged(
                testGroupId, LeAudioStackEvent.GROUP_STREAM_STATUS_STREAMING);
    }

    private void injectLocalCodecConfigCapaChanged(
            List<BluetoothLeAudioCodecConfig> inputCodecCapa,
            List<BluetoothLeAudioCodecConfig> outputCodecCapa) {
        int eventType = LeAudioStackEvent.EVENT_TYPE_AUDIO_LOCAL_CODEC_CONFIG_CAPA_CHANGED;

        // Add device to group
        LeAudioStackEvent localCodecCapaEvent = new LeAudioStackEvent(eventType);
        localCodecCapaEvent.valueCodecList1 = inputCodecCapa;
        localCodecCapaEvent.valueCodecList2 = outputCodecCapa;
        mService.messageFromNative(localCodecCapaEvent);
    }

    private void injectGroupCurrentCodecConfigChanged(
            int groupId,
            BluetoothLeAudioCodecConfig inputCodecConfig,
            BluetoothLeAudioCodecConfig outputCodecConfig) {
        int eventType = LeAudioStackEvent.EVENT_TYPE_AUDIO_GROUP_CURRENT_CODEC_CONFIG_CHANGED;

        // Add device to group
        LeAudioStackEvent groupCodecConfigChangedEvent = new LeAudioStackEvent(eventType);
        groupCodecConfigChangedEvent.valueInt1 = groupId;
        groupCodecConfigChangedEvent.valueCodec1 = inputCodecConfig;
        groupCodecConfigChangedEvent.valueCodec2 = outputCodecConfig;
        mService.messageFromNative(groupCodecConfigChangedEvent);
    }

    private void injectGroupSelectableCodecConfigChanged(
            int groupId,
            List<BluetoothLeAudioCodecConfig> inputSelectableCodecConfig,
            List<BluetoothLeAudioCodecConfig> outputSelectableCodecConfig) {
        int eventType = LeAudioStackEvent.EVENT_TYPE_AUDIO_GROUP_SELECTABLE_CODEC_CONFIG_CHANGED;

        // Add device to group
        LeAudioStackEvent groupCodecConfigChangedEvent = new LeAudioStackEvent(eventType);
        groupCodecConfigChangedEvent.valueInt1 = groupId;
        groupCodecConfigChangedEvent.valueCodecList1 = inputSelectableCodecConfig;
        groupCodecConfigChangedEvent.valueCodecList2 = outputSelectableCodecConfig;
        mService.messageFromNative(groupCodecConfigChangedEvent);
    }

    /** Test native interface group status message handling */
    @Test
    public void testMessageFromNativeGroupCodecConfigChangedNonActiveDevice() {
        mSetFlagsRule.enableFlags(Flags.FLAG_LEAUDIO_CODEC_CONFIG_CALLBACK_ORDER_FIX);
        onGroupCodecConfChangedCallbackCalled = false;

        injectLocalCodecConfigCapaChanged(INPUT_CAPABILITIES_CONFIG, OUTPUT_CAPABILITIES_CONFIG);

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        testCodecStatus =
                new BluetoothLeAudioCodecStatus(
                        LC3_16KHZ_CONFIG,
                        LC3_48KHZ_CONFIG,
                        INPUT_CAPABILITIES_CONFIG,
                        OUTPUT_CAPABILITIES_CONFIG,
                        INPUT_SELECTABLE_CONFIG,
                        OUTPUT_SELECTABLE_CONFIG);

        IBluetoothLeAudioCallback leAudioCallbacks =
                new IBluetoothLeAudioCallback.Stub() {
                    @Override
                    public void onCodecConfigChanged(int gid, BluetoothLeAudioCodecStatus status) {
                        onGroupCodecConfChangedCallbackCalled = true;
                        assertThat(status.equals(testCodecStatus)).isTrue();
                    }

                    @Override
                    public void onGroupStatusChanged(int gid, int gStatus) {}

                    @Override
                    public void onGroupNodeAdded(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupNodeRemoved(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupStreamStatusChanged(int groupId, int groupStreamStatus) {}

                    @Override
                    public void onBroadcastToUnicastFallbackGroupChanged(int groupId) {}
                };

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.register(leAudioCallbacks);
        }

        injectGroupSelectableCodecConfigChanged(
                testGroupId, INPUT_SELECTABLE_CONFIG, OUTPUT_SELECTABLE_CONFIG);
        // Inject configuration and check that AF is NOT notified.
        injectGroupCurrentCodecConfigChanged(testGroupId, LC3_16KHZ_CONFIG, LC3_48KHZ_CONFIG);

        TestUtils.waitForLooperToFinishScheduledTask(mService.getMainLooper());
        assertThat(onGroupCodecConfChangedCallbackCalled).isTrue();

        verify(mAudioManager, times(0))
                .handleBluetoothActiveDeviceChanged(
                        any(), any(), any(BluetoothProfileConnectionInfo.class));

        onGroupCodecConfChangedCallbackCalled = false;

        // Now inject again new configuration and check that AF is not notified.
        testCodecStatus =
                new BluetoothLeAudioCodecStatus(
                        LC3_16KHZ_CONFIG,
                        LC3_16KHZ_CONFIG,
                        INPUT_CAPABILITIES_CONFIG,
                        OUTPUT_CAPABILITIES_CONFIG,
                        INPUT_SELECTABLE_CONFIG,
                        OUTPUT_SELECTABLE_CONFIG);

        injectGroupCurrentCodecConfigChanged(testGroupId, LC3_16KHZ_CONFIG, LC3_16KHZ_CONFIG);

        TestUtils.waitForLooperToFinishScheduledTask(mService.getMainLooper());
        assertThat(onGroupCodecConfChangedCallbackCalled).isTrue();

        verify(mAudioManager, times(0))
                .handleBluetoothActiveDeviceChanged(
                        any(), any(), any(BluetoothProfileConnectionInfo.class));

        onGroupCodecConfChangedCallbackCalled = false;
        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.unregister(leAudioCallbacks);
        }
    }

    /** Test native interface group status message handling */
    @Test
    public void testMessageFromNativeGroupCodecConfigChangedActiveDevice_DifferentConfiguration() {
        mSetFlagsRule.enableFlags(Flags.FLAG_LEAUDIO_CODEC_CONFIG_CALLBACK_ORDER_FIX);
        onGroupCodecConfChangedCallbackCalled = false;

        injectLocalCodecConfigCapaChanged(INPUT_CAPABILITIES_CONFIG, OUTPUT_CAPABILITIES_CONFIG);

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        testCodecStatus =
                new BluetoothLeAudioCodecStatus(
                        LC3_16KHZ_CONFIG,
                        LC3_48KHZ_CONFIG,
                        INPUT_CAPABILITIES_CONFIG,
                        OUTPUT_CAPABILITIES_CONFIG,
                        INPUT_SELECTABLE_CONFIG,
                        OUTPUT_SELECTABLE_CONFIG);

        IBluetoothLeAudioCallback leAudioCallbacks =
                new IBluetoothLeAudioCallback.Stub() {
                    @Override
                    public void onCodecConfigChanged(int gid, BluetoothLeAudioCodecStatus status) {
                        onGroupCodecConfChangedCallbackCalled = true;
                        assertThat(status.equals(testCodecStatus)).isTrue();
                    }

                    @Override
                    public void onGroupStatusChanged(int gid, int gStatus) {}

                    @Override
                    public void onGroupNodeAdded(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupNodeRemoved(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupStreamStatusChanged(int groupId, int groupStreamStatus) {}

                    @Override
                    public void onBroadcastToUnicastFallbackGroupChanged(int groupId) {}
                };

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.register(leAudioCallbacks);
        }

        injectGroupSelectableCodecConfigChanged(
                testGroupId, INPUT_SELECTABLE_CONFIG, OUTPUT_SELECTABLE_CONFIG);

        injectGroupCurrentCodecConfigChanged(testGroupId, LC3_16KHZ_CONFIG, LC3_48KHZ_CONFIG);

        injectAudioConfChanged(
                testGroupId,
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL,
                3);

        injectGroupStatusChange(testGroupId, LeAudioStackEvent.GROUP_STATUS_ACTIVE);

        TestUtils.waitForLooperToFinishScheduledTask(mService.getMainLooper());
        assertThat(onGroupCodecConfChangedCallbackCalled).isTrue();

        verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        any(), any(), any(BluetoothProfileConnectionInfo.class));

        onGroupCodecConfChangedCallbackCalled = false;
        reset(mAudioManager);

        // Now inject configuration different sample rate on one direction
        testCodecStatus =
                new BluetoothLeAudioCodecStatus(
                        LC3_16KHZ_CONFIG,
                        LC3_16KHZ_CONFIG,
                        INPUT_CAPABILITIES_CONFIG,
                        OUTPUT_CAPABILITIES_CONFIG,
                        INPUT_SELECTABLE_CONFIG,
                        OUTPUT_SELECTABLE_CONFIG);

        injectGroupCurrentCodecConfigChanged(testGroupId, LC3_16KHZ_CONFIG, LC3_16KHZ_CONFIG);

        TestUtils.waitForLooperToFinishScheduledTask(mService.getMainLooper());
        assertThat(onGroupCodecConfChangedCallbackCalled).isTrue();

        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(), any(), any(BluetoothProfileConnectionInfo.class));

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.unregister(leAudioCallbacks);
        }

        onGroupCodecConfChangedCallbackCalled = false;
        reset(mAudioManager);
    }

    /** Test native interface group status message handling */
    @Test
    public void testMessageFromNativeGroupCodecConfigChanged_OneDirectionOnly() {
        onGroupCodecConfChangedCallbackCalled = false;

        injectLocalCodecConfigCapaChanged(INPUT_CAPABILITIES_CONFIG, OUTPUT_CAPABILITIES_CONFIG);

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        testCodecStatus =
                new BluetoothLeAudioCodecStatus(
                        null,
                        LC3_48KHZ_CONFIG,
                        INPUT_CAPABILITIES_CONFIG,
                        OUTPUT_CAPABILITIES_CONFIG,
                        new ArrayList<>(),
                        OUTPUT_SELECTABLE_CONFIG);

        IBluetoothLeAudioCallback leAudioCallbacks =
                new IBluetoothLeAudioCallback.Stub() {
                    @Override
                    public void onCodecConfigChanged(int gid, BluetoothLeAudioCodecStatus status) {
                        onGroupCodecConfChangedCallbackCalled = true;
                        assertThat(status.equals(testCodecStatus)).isTrue();
                    }

                    @Override
                    public void onGroupStatusChanged(int gid, int gStatus) {}

                    @Override
                    public void onGroupNodeAdded(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupNodeRemoved(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupStreamStatusChanged(int groupId, int groupStreamStatus) {}

                    @Override
                    public void onBroadcastToUnicastFallbackGroupChanged(int groupId) {}
                };

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.register(leAudioCallbacks);
        }

        injectGroupSelectableCodecConfigChanged(
                testGroupId, INPUT_EMPTY_CONFIG, OUTPUT_SELECTABLE_CONFIG);
        injectGroupCurrentCodecConfigChanged(testGroupId, EMPTY_CONFIG, LC3_48KHZ_CONFIG);

        TestUtils.waitForLooperToFinishScheduledTask(mService.getMainLooper());
        assertThat(onGroupCodecConfChangedCallbackCalled).isTrue();

        onGroupCodecConfChangedCallbackCalled = false;
        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.unregister(leAudioCallbacks);
        }

        BluetoothLeAudioCodecStatus codecStatus = mService.getCodecStatus(testGroupId);
        assertThat(codecStatus.getInputCodecConfig()).isNull();
        assertThat(codecStatus.getOutputCodecConfig()).isNotNull();
    }

    private void verifyActiveDeviceStateIntent(int timeoutMs, BluetoothDevice device) {
        Intent intent = TestUtils.waitForIntent(timeoutMs, mDeviceQueueMap.get(device));
        assertThat(intent).isNotNull();
        assertThat(intent.getAction())
                .isEqualTo(BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED);
        assertThat(device).isEqualTo(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
    }

    /** Test native interface group status message handling */
    @Test
    public void testLeadGroupDeviceDisconnects() {
        int groupId = 1;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;
        ;
        int groupStatus = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        BluetoothDevice leadDevice;
        BluetoothDevice memberDevice = mLeftDevice;

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mLeftDevice, groupId);
        connectTestDevice(mRightDevice, groupId);

        leadDevice = mService.getConnectedGroupLeadDevice(groupId);
        if (Objects.equals(leadDevice, mLeftDevice)) {
            memberDevice = mRightDevice;
        }

        assertThat(mService.setActiveDevice(leadDevice)).isFalse();

        // Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        assertThat(mService.setActiveDevice(leadDevice)).isTrue();

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = groupId;
        groupStatusChangedEvent.valueInt2 = groupStatus;
        mService.messageFromNative(groupStatusChangedEvent);

        assertThat(mService.getActiveDevices().contains(leadDevice)).isTrue();
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(leadDevice), any(), any(BluetoothProfileConnectionInfo.class));
        /* Since LeAudioService called AudioManager - assume Audio manager calles properly callback
         * mAudioManager.onAudioDeviceAdded
         */
        injectAudioDeviceAdded(leadDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, true);
        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(leadDevice);
        injectNoVerifyDeviceDisconnected(leadDevice);

        // We should not change the audio device
        assertThat(mService.getConnectionState(leadDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTED);

        injectAndVerifyDeviceDisconnected(memberDevice);

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                leadDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTED);

        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(), eq(leadDevice), any(BluetoothProfileConnectionInfo.class));

        verify(mTbsService).setInbandRingtoneSupport(mLeftDevice);
        verify(mTbsService).setInbandRingtoneSupport(mRightDevice);
    }

    /** Test native interface group status message handling */
    @Test
    public void testLeadGroupDeviceReconnects() {
        int groupId = 1;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;
        ;
        int groupStatus = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        BluetoothDevice leadDevice;
        BluetoothDevice memberDevice = mLeftDevice;

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mLeftDevice, groupId);
        connectTestDevice(mRightDevice, groupId);

        leadDevice = mService.getConnectedGroupLeadDevice(groupId);
        if (Objects.equals(leadDevice, mLeftDevice)) {
            memberDevice = mRightDevice;
        }

        assertThat(mService.setActiveDevice(leadDevice)).isFalse();

        // Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        assertThat(mService.setActiveDevice(leadDevice)).isTrue();

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = groupId;
        groupStatusChangedEvent.valueInt2 = groupStatus;
        mService.messageFromNative(groupStatusChangedEvent);

        assertThat(mService.getActiveDevices().contains(leadDevice)).isTrue();
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(leadDevice), any(), any(BluetoothProfileConnectionInfo.class));
        /* Since LeAudioService called AudioManager - assume Audio manager calles properly callback
         * mAudioManager.onAudioDeviceAdded
         */
        injectAudioDeviceAdded(leadDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, true);

        /* We don't want to distribute DISCONNECTION event, instead will try to reconnect
         * (in native)
         */
        injectNoVerifyDeviceDisconnected(leadDevice);
        assertThat(mService.getConnectionState(leadDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTED);

        /* Reconnect device, there should be no intent about that, as device was pretending
         * connected
         */
        injectNoVerifyDeviceConnected(leadDevice);

        injectAndVerifyDeviceDisconnected(memberDevice);
        injectAndVerifyDeviceDisconnected(leadDevice);

        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(leadDevice), any(BluetoothProfileConnectionInfo.class));

        verify(mTbsService).setInbandRingtoneSupport(mLeftDevice);
        verify(mTbsService).setInbandRingtoneSupport(mRightDevice);
    }

    /** Test volume caching for the group */
    @Test
    public void testVolumeCache() {
        int groupId = 1;
        int volume = 100;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 4;

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mLeftDevice, groupId);
        connectTestDevice(mRightDevice, groupId);

        assertThat(mService.setActiveDevice(mLeftDevice)).isFalse();

        ArgumentCaptor<BluetoothProfileConnectionInfo> profileInfo =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);

        // Add location support.
        injectAudioConfChanged(groupId, availableContexts, direction);

        assertThat(mService.setActiveDevice(mLeftDevice)).isTrue();
        TestUtils.waitForLooperToFinishScheduledTask(mService.getMainLooper());

        doReturn(-1).when(mVolumeControlService).getAudioDeviceGroupVolume(groupId);
        // Set group and device as active.
        injectGroupStatusChange(groupId, LeAudioStackEvent.GROUP_STATUS_ACTIVE);

        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(any(), eq(null), profileInfo.capture());
        assertThat(profileInfo.getValue().getVolume()).isEqualTo(-1);

        mService.setVolume(volume);
        verify(mVolumeControlService).setGroupVolume(groupId, volume);

        // Set group to inactive.
        injectGroupStatusChange(groupId, LeAudioStackEvent.GROUP_STATUS_INACTIVE);

        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), any(), any(BluetoothProfileConnectionInfo.class));
        TestUtils.waitForLooperToFinishScheduledTask(mService.getMainLooper());

        doReturn(100).when(mVolumeControlService).getAudioDeviceGroupVolume(groupId);

        // Set back to active and check if last volume is restored.
        injectGroupStatusChange(groupId, LeAudioStackEvent.GROUP_STATUS_ACTIVE);

        verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(any(), eq(null), profileInfo.capture());

        assertThat(profileInfo.getValue().getVolume()).isEqualTo(volume);
    }

    /** Test volume setting for broadcast sink devices */
    @Test
    public void testSetVolumeForBroadcastSinks() {
        mSetFlagsRule.enableFlags(Flags.FLAG_LEAUDIO_BROADCAST_VOLUME_CONTROL_WITH_SET_VOLUME);
        mSetFlagsRule.enableFlags(Flags.FLAG_LEAUDIO_BROADCAST_VOLUME_CONTROL_PRIMARY_GROUP_ONLY);

        int groupId = 1;
        int groupId2 = 2;
        int volume = 100;
        int newVolume = 120;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 4;

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mLeftDevice, groupId);
        connectTestDevice(mRightDevice, groupId);
        assertThat(mService.setActiveDevice(mLeftDevice)).isFalse();

        connectTestDevice(mSingleDevice, groupId2);

        ArgumentCaptor<BluetoothProfileConnectionInfo> profileInfo =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);

        // Add location support.
        injectAudioConfChanged(groupId, availableContexts, direction);
        assertThat(mService.setActiveDevice(mLeftDevice)).isTrue();
        TestUtils.waitForLooperToFinishScheduledTask(mService.getMainLooper());

        doReturn(volume).when(mVolumeControlService).getAudioDeviceGroupVolume(groupId);
        doReturn(volume).when(mVolumeControlService).getAudioDeviceGroupVolume(groupId2);
        // Set group and device as active.
        injectGroupStatusChange(groupId, LeAudioStackEvent.GROUP_STATUS_ACTIVE);

        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(any(), eq(null), profileInfo.capture());
        assertThat(profileInfo.getValue().getVolume()).isEqualTo(volume);

        // Set group to inactive, only keep them connected as broadcast sink devices.
        injectGroupStatusChange(groupId, LeAudioStackEvent.GROUP_STATUS_INACTIVE);
        injectGroupStatusChange(groupId2, LeAudioStackEvent.GROUP_STATUS_INACTIVE);

        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), any(), any(BluetoothProfileConnectionInfo.class));
        TestUtils.waitForLooperToFinishScheduledTask(mService.getMainLooper());

        // Verify setGroupVolume will not be called if no synced sinks
        doReturn(new ArrayList<>()).when(mBassClientService).getSyncedBroadcastSinks();
        mService.setVolume(newVolume);
        verify(mVolumeControlService, never()).setGroupVolume(groupId, newVolume);

        mService.mUnicastGroupIdDeactivatedForBroadcastTransition = groupId;
        // Verify setGroupVolume will be called if synced sinks
        doReturn(List.of(mLeftDevice, mRightDevice, mSingleDevice))
                .when(mBassClientService)
                .getSyncedBroadcastSinks();
        mService.setVolume(newVolume);

        // Verify set volume only on primary group
        verify(mVolumeControlService).setGroupVolume(groupId, newVolume);
        verify(mVolumeControlService, never()).setGroupVolume(groupId2, newVolume);
    }

    @Test
    public void testGetAudioDeviceGroupVolume_whenVolumeControlServiceIsNull() {
        mService.mVolumeControlService = null;
        doReturn(null).when(mServiceFactory).getVolumeControlService();

        int groupId = 1;
        assertThat(mService.getAudioDeviceGroupVolume(groupId)).isEqualTo(-1);
    }

    @Test
    public void testGetAudioLocation() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        assertThat(mService.getAudioLocation(null))
                .isEqualTo(BluetoothLeAudio.AUDIO_LOCATION_INVALID);

        int sinkAudioLocation = 10;
        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_SINK_AUDIO_LOCATION_AVAILABLE);
        stackEvent.device = mSingleDevice;
        stackEvent.valueInt1 = sinkAudioLocation;
        mService.messageFromNative(stackEvent);

        assertThat(mService.getAudioLocation(mSingleDevice)).isEqualTo(sinkAudioLocation);
    }

    @Test
    public void testGetConnectedPeerDevices() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mLeftDevice, testGroupId);
        connectTestDevice(mRightDevice, testGroupId);

        List<BluetoothDevice> peerDevices = mService.getConnectedPeerDevices(testGroupId);
        assertThat(peerDevices.contains(mLeftDevice)).isTrue();
        assertThat(peerDevices.contains(mRightDevice)).isTrue();
    }

    @Test
    public void testGetDevicesMatchingConnectionStates() {
        assertThat(mService.getDevicesMatchingConnectionStates(null)).isEmpty();

        int[] states = new int[] {BluetoothProfile.STATE_CONNECTED};
        doReturn(null).when(mAdapterService).getBondedDevices();
        assertThat(mService.getDevicesMatchingConnectionStates(states)).isEmpty();

        doReturn(new BluetoothDevice[] {mSingleDevice}).when(mAdapterService).getBondedDevices();
        assertThat(mService.getDevicesMatchingConnectionStates(states)).isEmpty();
    }

    @Test
    public void testDefaultValuesOfSeveralGetters() {
        assertThat(mService.getMaximumNumberOfBroadcasts()).isEqualTo(1);
        assertThat(mService.getMaximumStreamsPerBroadcast()).isEqualTo(1);
        assertThat(mService.getMaximumSubgroupsPerBroadcast()).isEqualTo(1);
        assertThat(mService.isPlaying(100)).isFalse();
        assertThat(mService.isValidDeviceGroup(LE_AUDIO_GROUP_ID_INVALID)).isFalse();
    }

    @Test
    public void testHandleGroupIdleDuringCall() {
        BluetoothDevice headsetDevice = TestUtils.getTestDevice(mAdapter, 5);
        HeadsetService headsetService = Mockito.mock(HeadsetService.class);
        when(mServiceFactory.getHeadsetService()).thenReturn(headsetService);

        mService.mHfpHandoverDevice = null;
        mService.handleGroupIdleDuringCall();
        verify(headsetService, never()).getActiveDevice();

        mService.mHfpHandoverDevice = headsetDevice;
        when(headsetService.getActiveDevice()).thenReturn(headsetDevice);
        mService.handleGroupIdleDuringCall();
        verify(headsetService).connectAudio();
        assertThat(mService.mHfpHandoverDevice).isNull();

        mService.mHfpHandoverDevice = headsetDevice;
        when(headsetService.getActiveDevice()).thenReturn(null);
        mService.handleGroupIdleDuringCall();
        verify(headsetService).setActiveDevice(headsetDevice);
        assertThat(mService.mHfpHandoverDevice).isNull();
    }

    @Test
    public void testDump_doesNotCrash() {
        doReturn(new ParcelUuid[] {BluetoothUuid.LE_AUDIO})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        doReturn(new BluetoothDevice[] {mSingleDevice}).when(mAdapterService).getBondedDevices();
        when(mDatabaseManager.getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        connectTestDevice(mSingleDevice, testGroupId);

        StringBuilder sb = new StringBuilder();
        mService.dump(sb);
    }

    /** Test setting authorization for LeAudio device in the McpService */
    @Test
    public void testAuthorizeMcpServiceWhenDeviceConnecting() {
        int groupId = 1;

        mService.handleBluetoothEnabled();
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mLeftDevice, groupId);
        connectTestDevice(mRightDevice, groupId);
        verify(mMcpService).setDeviceAuthorized(mLeftDevice, true);
        verify(mMcpService).setDeviceAuthorized(mRightDevice, true);
    }

    /** Test setting authorization for LeAudio device in the McpService */
    @Test
    public void testAuthorizeMcpServiceOnBluetoothEnableAndNodeRemoval() {
        int groupId = 1;

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mLeftDevice, groupId);
        connectTestDevice(mRightDevice, groupId);

        generateGroupNodeAdded(mLeftDevice, groupId);
        generateGroupNodeAdded(mRightDevice, groupId);

        verify(mMcpService, times(0)).setDeviceAuthorized(mLeftDevice, true);
        verify(mMcpService, times(0)).setDeviceAuthorized(mRightDevice, true);

        mService.handleBluetoothEnabled();

        verify(mMcpService).setDeviceAuthorized(mLeftDevice, true);
        verify(mMcpService).setDeviceAuthorized(mRightDevice, true);

        generateGroupNodeRemoved(mLeftDevice, groupId);
        verify(mMcpService).setDeviceAuthorized(mLeftDevice, false);

        generateGroupNodeRemoved(mRightDevice, groupId);
        verify(mMcpService).setDeviceAuthorized(mRightDevice, false);
    }

    /**
     * Test verifying that when the LE Audio connection policy of a device is set to {@link
     * BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, we unauthorize McpService and TbsService. When
     * the LE Audio connection policy is set to {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * we will authorize these services.
     */
    @Test
    public void testMcsAndTbsAuthorizationWithConnectionPolicy() {
        int groupId = 1;

        mService.handleBluetoothEnabled();
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));
        doReturn(true)
                .when(mDatabaseManager)
                .setProfileConnectionPolicy(any(BluetoothDevice.class), anyInt(), anyInt());
        when(mDatabaseManager.getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);

        // Ensures GATT server services are not authorized when the device does not have a group
        assertThat(
                        mService.setConnectionPolicy(
                                mSingleDevice, BluetoothProfile.CONNECTION_POLICY_ALLOWED))
                .isTrue();
        verify(mMcpService, never()).setDeviceAuthorized(mSingleDevice, false);
        verify(mTbsService, never()).setDeviceAuthorized(mSingleDevice, false);

        // Connects the test device and verifies GATT server services are authorized
        connectTestDevice(mSingleDevice, groupId);
        verify(mMcpService).setDeviceAuthorized(mSingleDevice, true);
        verify(mTbsService).setDeviceAuthorized(mSingleDevice, true);

        // Ensure that disconnecting unauthorizes GATT server services
        assertThat(
                        mService.setConnectionPolicy(
                                mSingleDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN))
                .isTrue();
        verify(mMcpService).setDeviceAuthorized(mSingleDevice, false);
        verify(mTbsService).setDeviceAuthorized(mSingleDevice, false);

        // Connecting a device that has a group re-authorizes the GATT server services
        assertThat(
                        mService.setConnectionPolicy(
                                mSingleDevice, BluetoothProfile.CONNECTION_POLICY_ALLOWED))
                .isTrue();
        verify(mMcpService, times(2)).setDeviceAuthorized(mSingleDevice, true);
        verify(mTbsService, times(2)).setDeviceAuthorized(mSingleDevice, true);
    }

    @Test
    public void testGetGroupDevices() {
        int firstGroupId = 1;
        int secondGroupId = 2;

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mLeftDevice, firstGroupId);
        connectTestDevice(mRightDevice, firstGroupId);
        connectTestDevice(mSingleDevice, secondGroupId);

        // Checks group device lists for groupId 1
        List<BluetoothDevice> firstGroupDevicesById = mService.getGroupDevices(firstGroupId);
        List<BluetoothDevice> firstGroupDevicesByLeftDevice = mService.getGroupDevices(mLeftDevice);
        List<BluetoothDevice> firstGroupDevicesByRightDevice =
                mService.getGroupDevices(mRightDevice);

        assertThat(firstGroupDevicesById.size()).isEqualTo(2);
        assertThat(firstGroupDevicesById.contains(mLeftDevice)).isTrue();
        assertThat(firstGroupDevicesById.contains(mRightDevice)).isTrue();
        assertThat(firstGroupDevicesById.contains(mSingleDevice)).isFalse();
        assertThat(firstGroupDevicesById.equals(firstGroupDevicesByLeftDevice)).isTrue();
        assertThat(firstGroupDevicesById.equals(firstGroupDevicesByRightDevice)).isTrue();

        // Checks group device lists for groupId 2
        List<BluetoothDevice> secondGroupDevicesById = mService.getGroupDevices(secondGroupId);
        List<BluetoothDevice> secondGroupDevicesByDevice = mService.getGroupDevices(mSingleDevice);

        assertThat(secondGroupDevicesById.size()).isEqualTo(1);
        assertThat(secondGroupDevicesById.contains(mSingleDevice)).isTrue();
        assertThat(secondGroupDevicesById.contains(mLeftDevice)).isFalse();
        assertThat(secondGroupDevicesById.contains(mRightDevice)).isFalse();
        assertThat(secondGroupDevicesById.equals(secondGroupDevicesByDevice)).isTrue();
    }

    /**
     * Tests that {@link LeAudioService#sendPreferredAudioProfileChangeToAudioFramework()} sends
     * requests to the audio framework for each active LEA device.
     */
    @Test
    public void testSendPreferredAudioProfileChangeToAudioFramework() {
        when(mAdapterService.isAllSupportedClassicAudioProfilesActive(any())).thenReturn(true);

        // TEST 1: Verify no requests are sent to the audio framework if there is no active device
        assertThat(mService.removeActiveDevice(false)).isTrue();
        List<BluetoothDevice> activeDevices = mService.getActiveDevices();
        assertThat(activeDevices.get(0)).isNull();
        assertThat(activeDevices.get(1)).isNull();
        assertThat(mService.sendPreferredAudioProfileChangeToAudioFramework()).isEqualTo(0);

        // TEST 2: Verify we send one request for each active direction
        int groupId = 1;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 | AUDIO_DIRECTION_INPUT_BIT = 0x02; */
        int direction = 3;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5;
        int nodeStatus = LeAudioStackEvent.GROUP_NODE_ADDED;
        int groupStatus = LeAudioStackEvent.GROUP_STATUS_ACTIVE;

        // Single active device
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        // Add device to group
        LeAudioStackEvent nodeStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        nodeStatusChangedEvent.device = mSingleDevice;
        nodeStatusChangedEvent.valueInt1 = groupId;
        nodeStatusChangedEvent.valueInt2 = nodeStatus;
        mService.messageFromNative(nodeStatusChangedEvent);

        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.device = mSingleDevice;
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.device = mSingleDevice;
        groupStatusChangedEvent.valueInt1 = groupId;
        groupStatusChangedEvent.valueInt2 = groupStatus;
        mService.messageFromNative(groupStatusChangedEvent);

        assertThat(mService.getActiveDevices().contains(mSingleDevice)).isTrue();
        assertThat(mService.sendPreferredAudioProfileChangeToAudioFramework()).isEqualTo(2);
    }

    @Test
    public void testInactivateDeviceWhenNoAvailableContextTypes() {
        int groupId = 1;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mLeftDevice, groupId);
        connectTestDevice(mRightDevice, groupId);

        // Checks group device lists for groupId 1
        List<BluetoothDevice> groupDevicesById = mService.getGroupDevices(groupId);

        assertThat(groupDevicesById.size()).isEqualTo(2);
        assertThat(groupDevicesById.contains(mLeftDevice)).isTrue();
        assertThat(groupDevicesById.contains(mRightDevice)).isTrue();

        // Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        assertThat(mService.setActiveDevice(mLeftDevice)).isTrue();
        verify(mNativeInterface).groupSetActive(groupId);

        // Set group and device as active.
        injectGroupStatusChange(groupId, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(BluetoothDevice.class),
                        eq(null),
                        any(BluetoothProfileConnectionInfo.class));

        reset(mAudioManager);
        reset(mNativeInterface);

        /* Don't expect any change. */
        mService.messageFromNative(audioConfChangedEvent);
        verify(mNativeInterface, times(0)).groupSetActive(groupId);
        reset(mNativeInterface);

        /* Expect device to be incactive */
        audioConfChangedEvent.valueInt5 = 0;
        mService.messageFromNative(audioConfChangedEvent);

        verify(mNativeInterface).groupSetActive(-1);
        injectGroupStatusChange(groupId, LeAudioStackEvent.GROUP_STATUS_INACTIVE);

        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null),
                        any(BluetoothDevice.class),
                        any(BluetoothProfileConnectionInfo.class));

        reset(mNativeInterface);
        reset(mAudioManager);

        /* Expect device to be incactive */
        audioConfChangedEvent.valueInt5 = 1;
        mService.messageFromNative(audioConfChangedEvent);

        verify(mNativeInterface).groupSetActive(groupId);
        reset(mNativeInterface);

        // Set group and device as active.
        injectGroupStatusChange(groupId, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(BluetoothDevice.class),
                        eq(null),
                        any(BluetoothProfileConnectionInfo.class));
    }

    /**
     * Test the group is activated once the available contexts are back.
     *
     * Scenario:
     *  1. Have a group of 2 devices that initially does not expose any available contexts.
     *     The group shall be inactive at this point.
     *  2. Once the available contexts are updated with non-zero value,
     *     the group shall become active.
     *  3. The available contexts are changed to zero. Group becomes inactive.
     *  4. The available contexts are back again. Group becomes active.
     */
    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_UNICAST_NO_AVAILABLE_CONTEXTS)
    public void testActivateGroupWhenAvailableContextAreBack_Scenario1() {
        int groupId = 1;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mLeftDevice, groupId);
        connectTestDevice(mRightDevice, groupId);

        // Checks group device lists for groupId 1
        List<BluetoothDevice> groupDevicesById = mService.getGroupDevices(groupId);

        assertThat(groupDevicesById.size()).isEqualTo(2);
        assertThat(groupDevicesById.contains(mLeftDevice)).isTrue();
        assertThat(groupDevicesById.contains(mRightDevice)).isTrue();

        // Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = 0;
        mService.messageFromNative(audioConfChangedEvent);

        assertThat(mService.setActiveDevice(mLeftDevice)).isFalse();
        verify(mNativeInterface, times(0)).groupSetActive(groupId);

        // Expect device to be active
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        verify(mNativeInterface).groupSetActive(groupId);

        // Set group and device as active.
        injectGroupStatusChange(groupId, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(BluetoothDevice.class),
                        eq(null),
                        any(BluetoothProfileConnectionInfo.class));

        reset(mAudioManager);
        reset(mNativeInterface);

        // Expect device to be inactive
        audioConfChangedEvent.valueInt5 = 0;
        mService.messageFromNative(audioConfChangedEvent);

        verify(mNativeInterface).groupSetActive(-1);
        injectGroupStatusChange(groupId, LeAudioStackEvent.GROUP_STATUS_INACTIVE);

        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null),
                        any(BluetoothDevice.class),
                        any(BluetoothProfileConnectionInfo.class));

        reset(mNativeInterface);
        reset(mAudioManager);

        // Expect device to be active
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        verify(mNativeInterface).groupSetActive(groupId);
        reset(mNativeInterface);

        // Set group and device as active.
        injectGroupStatusChange(groupId, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(BluetoothDevice.class),
                        eq(null),
                        any(BluetoothProfileConnectionInfo.class));
    }

    /**
     * Test the group is activated once the available contexts are back.
     *
     * Scenario:
     *  1. Have a group of 2 devices. The available contexts are non-zero.
     *     The group shall be active at this point.
     *  2. Once the available contexts are updated with zero value,
     *     the group shall become inactive.
     *  3. All group devices are disconnected.
     *  4. Group devices are reconnected. The available contexts are still zero.
     *  4. The available contexts are updated with non-zero value. Group becomes active.
     */
    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_UNICAST_NO_AVAILABLE_CONTEXTS)
    public void testActivateDeviceWhenAvailableContextAreBack_Scenario2() {
        int groupId = 1;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mLeftDevice, groupId);
        connectTestDevice(mRightDevice, groupId);

        // Checks group device lists for groupId 1
        List<BluetoothDevice> groupDevicesById = mService.getGroupDevices(groupId);

        assertThat(groupDevicesById.size()).isEqualTo(2);
        assertThat(groupDevicesById.contains(mLeftDevice)).isTrue();
        assertThat(groupDevicesById.contains(mRightDevice)).isTrue();

        // Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        assertThat(mService.setActiveDevice(mLeftDevice)).isTrue();
        verify(mNativeInterface).groupSetActive(groupId);

        // Set group and device as active.
        injectGroupStatusChange(groupId, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(BluetoothDevice.class),
                        eq(null),
                        any(BluetoothProfileConnectionInfo.class));

        reset(mAudioManager);
        reset(mNativeInterface);

        // Expect device to be inactive
        audioConfChangedEvent.valueInt5 = 0;
        mService.messageFromNative(audioConfChangedEvent);

        verify(mNativeInterface).groupSetActive(-1);
        injectGroupStatusChange(groupId, LeAudioStackEvent.GROUP_STATUS_INACTIVE);

        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null),
                        any(BluetoothDevice.class),
                        any(BluetoothProfileConnectionInfo.class));

        reset(mNativeInterface);
        reset(mAudioManager);

        // Send a message to trigger disconnection completed to the left device
        injectAndVerifyDeviceDisconnected(mLeftDevice);

        // Send a message to trigger disconnection completed to the right device
        injectAndVerifyDeviceDisconnected(mRightDevice);

        // Verify the list of connected devices
        assertThat(mService.getConnectedDevices().contains(mLeftDevice)).isFalse();
        assertThat(mService.getConnectedDevices().contains(mRightDevice)).isFalse();

        // Expect device to be inactive
        audioConfChangedEvent.valueInt5 = 0;
        mService.messageFromNative(audioConfChangedEvent);

        generateConnectionMessageFromNative(
                mLeftDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTED);
        assertThat(mService.getConnectedDevices().contains(mLeftDevice)).isTrue();

        // Expect device to be inactive
        audioConfChangedEvent.valueInt5 = 0;
        mService.messageFromNative(audioConfChangedEvent);

        generateConnectionMessageFromNative(
                mRightDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mRightDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTED);
        assertThat(mService.getConnectedDevices().contains(mRightDevice)).isTrue();

        // Expect device to be active
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        verify(mNativeInterface).groupSetActive(groupId);

        // Set group and device as active.
        injectGroupStatusChange(groupId, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(BluetoothDevice.class),
                        eq(null),
                        any(BluetoothProfileConnectionInfo.class));
    }

    /**
     * Test the group is activated once the available contexts are back.
     *
     * Scenario:
     *  1. Have a group of 2 devices. The available contexts are non-zero.
     *     The group shall be active at this point.
     *  2. All group devices are disconnected.
     *  3. Group devices are reconnected. The available contexts are zero.
     *  4. The available contexts are updated with non-zero value. Group becomes active.
     */
    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_UNICAST_NO_AVAILABLE_CONTEXTS)
    public void testActivateDeviceWhenAvailableContextAreBack_Scenario3() {
        int groupId = 1;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mLeftDevice, groupId);
        connectTestDevice(mRightDevice, groupId);

        // Checks group device lists for groupId 1
        List<BluetoothDevice> groupDevicesById = mService.getGroupDevices(groupId);

        assertThat(groupDevicesById.size()).isEqualTo(2);
        assertThat(groupDevicesById.contains(mLeftDevice)).isTrue();
        assertThat(groupDevicesById.contains(mRightDevice)).isTrue();

        // Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        assertThat(mService.setActiveDevice(mLeftDevice)).isTrue();
        verify(mNativeInterface).groupSetActive(groupId);

        // Set group and device as active.
        injectGroupStatusChange(groupId, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(BluetoothDevice.class),
                        eq(null),
                        any(BluetoothProfileConnectionInfo.class));

        reset(mNativeInterface);
        reset(mAudioManager);

        // Send a message to trigger disconnection completed to the right device
        injectAndVerifyDeviceDisconnected(mRightDevice);

        // Send a message to trigger disconnection completed to the left device
        injectAndVerifyDeviceDisconnected(mLeftDevice);

        reset(mNativeInterface);
        reset(mAudioManager);

        // Expect device to be inactive
        audioConfChangedEvent.valueInt5 = 0;
        mService.messageFromNative(audioConfChangedEvent);

        generateConnectionMessageFromNative(
                mLeftDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTED);
        assertThat(mService.getConnectedDevices().contains(mLeftDevice)).isTrue();

        // Expect device to be inactive
        audioConfChangedEvent.valueInt5 = 0;
        mService.messageFromNative(audioConfChangedEvent);

        generateConnectionMessageFromNative(
                mRightDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mRightDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTED);
        assertThat(mService.getConnectedDevices().contains(mRightDevice)).isTrue();

        // Expect device to be active
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        verify(mNativeInterface).groupSetActive(groupId);

        // Set group and device as active.
        injectGroupStatusChange(groupId, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(BluetoothDevice.class),
                        eq(null),
                        any(BluetoothProfileConnectionInfo.class));
    }

    /** Test setting allowed contexts for active group */
    @Test
    public void testSetAllowedContextsForActiveGroup() {
        mSetFlagsRule.enableFlags(Flags.FLAG_LEAUDIO_GETTING_ACTIVE_STATE_SUPPORT);
        mSetFlagsRule.enableFlags(Flags.FLAG_LEAUDIO_ALLOWED_CONTEXT_MASK);
        int groupId = 1;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Connected device
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mSingleDevice, testGroupId);

        // Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.device = mSingleDevice;
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        verify(mNativeInterface).groupSetActive(groupId);

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = groupId;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);

        // Trigger update of allowed context for active group
        int sinkContextTypes =
                BluetoothLeAudio.CONTEXTS_ALL & ~BluetoothLeAudio.CONTEXT_TYPE_SOUND_EFFECTS;
        int sourceContextTypes =
                BluetoothLeAudio.CONTEXTS_ALL
                        & ~(BluetoothLeAudio.CONTEXT_TYPE_NOTIFICATIONS
                                | BluetoothLeAudio.CONTEXT_TYPE_GAME);

        mService.setActiveGroupAllowedContextMask(sinkContextTypes, sourceContextTypes);
        verify(mNativeInterface)
                .setGroupAllowedContextMask(groupId, sinkContextTypes, sourceContextTypes);

        // no active device, allowed context should be reset
        assertThat(mService.removeActiveDevice(false)).isTrue();
        verify(mNativeInterface).groupSetActive(BluetoothLeAudio.GROUP_ID_INVALID);

        // Set group and device as inactive active
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);

        verify(mNativeInterface)
                .setGroupAllowedContextMask(
                        groupId, BluetoothLeAudio.CONTEXTS_ALL, BluetoothLeAudio.CONTEXTS_ALL);
    }

    /** Test managing broadcast to unicast fallback group */
    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_PRIMARY_GROUP_SELECTION,
        Flags.FLAG_LEAUDIO_BROADCAST_API_MANAGE_PRIMARY_GROUP
    })
    public void testManageBroadcastToUnicastFallbackGroup() {
        int firstGroupId = 1;
        int secondGroupId = 2;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;
        List<BluetoothDevice> devices = new ArrayList<>();

        when(mDatabaseManager.getMostRecentlyConnectedDevices()).thenReturn(devices);

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();
        assertThat(mService.getBroadcastToUnicastFallbackGroup())
                .isEqualTo(BluetoothLeAudio.GROUP_ID_INVALID);

        // Connected device
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        devices.add(mSingleDevice);
        connectTestDevice(mSingleDevice, testGroupId);

        // Group should be updated to default (earliest connected)
        assertThat(mService.getBroadcastToUnicastFallbackGroup()).isEqualTo(firstGroupId);

        // Add location support
        LeAudioStackEvent audioConfChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        audioConfChangedEvent.device = mSingleDevice;
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = firstGroupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        verify(mNativeInterface).groupSetActive(firstGroupId);

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = firstGroupId;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);

        // Set fallback group to not valid (not connected)
        mService.setBroadcastToUnicastFallbackGroup(secondGroupId);

        // Connect second device
        devices.add(mLeftDevice);
        connectTestDevice(mLeftDevice, secondGroupId);
        mService.deviceConnected(mLeftDevice);

        // Fallback device should remain earliest connected
        assertThat(mService.getBroadcastToUnicastFallbackGroup()).isEqualTo(firstGroupId);

        // Set fallback group to valid second
        mService.setBroadcastToUnicastFallbackGroup(secondGroupId);

        // Fallback device should be changed to second
        assertThat(mService.getBroadcastToUnicastFallbackGroup()).isEqualTo(secondGroupId);

        // no active device
        assertThat(mService.removeActiveDevice(false)).isTrue();
        verify(mNativeInterface).groupSetActive(BluetoothLeAudio.GROUP_ID_INVALID);

        // Set group and device as inactive active
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);
    }
}
