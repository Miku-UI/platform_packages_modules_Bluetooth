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
package android.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.test_utils.EnableBluetoothRule
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth
import com.google.protobuf.ByteString
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import pandora.RfcommProto
import pandora.RfcommProto.ServerId
import pandora.RfcommProto.StartServerRequest

@SuppressLint("MissingPermission")
@RunWith(AndroidJUnit4::class)
@ExperimentalCoroutinesApi
class RfcommTest {
    private val mContext = ApplicationProvider.getApplicationContext<Context>()
    private val mManager = mContext.getSystemService(BluetoothManager::class.java)
    private val mAdapter = mManager!!.adapter

    // Gives shell permissions during the test.
    @Rule(order = 0)
    @JvmField
    val mPermissionsRule =
        AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_PRIVILEGED,
            Manifest.permission.MODIFY_PHONE_STATE,
        )

    // Set up a Bumble Pandora device for the duration of the test.
    @Rule(order = 1) @JvmField val mBumble = PandoraDevice()

    @Rule(order = 2) @JvmField val enableBluetoothRule = EnableBluetoothRule(false, true)

    private lateinit var mRemoteDevice: BluetoothDevice
    private lateinit var host: Host
    private var mConnectionCounter = 1
    private var mProfileServiceListener = mock<BluetoothProfile.ServiceListener>()

    @Before
    fun setUp() {
        mRemoteDevice = mBumble.remoteDevice
        host = Host(mContext)
        val bluetoothA2dp = getProfileProxy(mContext, BluetoothProfile.A2DP) as BluetoothA2dp
        bluetoothA2dp.setConnectionPolicy(
            mRemoteDevice,
            BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
        )
        val bluetoothHfp = getProfileProxy(mContext, BluetoothProfile.HEADSET) as BluetoothHeadset
        bluetoothHfp.setConnectionPolicy(
            mRemoteDevice,
            BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
        )
        val bluetoothHidHost =
            getProfileProxy(mContext, BluetoothProfile.HID_HOST) as BluetoothHidHost
        bluetoothHidHost.setConnectionPolicy(
            mRemoteDevice,
            BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
        )
        host.createBondAndVerify(mRemoteDevice)
        if (mRemoteDevice.isConnected) {
            host.disconnectAndVerify(mRemoteDevice)
        }
    }

    @After
    fun tearDown() {
        if (mAdapter.bondedDevices.contains(mRemoteDevice)) {
            host.removeBondAndVerify(mRemoteDevice)
        }
        host.close()
    }

    @Test
    fun clientConnectToOpenServerSocketBondedInsecure() {
        startServer { serverId -> createConnectAcceptSocket(isSecure = false, serverId) }
    }

    @Test
    fun clientConnectToOpenServerSocketBondedSecure() {
        startServer { serverId -> createConnectAcceptSocket(isSecure = true, serverId) }
    }

    @Test
    fun clientSendDataOverInsecureSocket() {
        startServer { serverId ->
            val (insecureSocket, connection) = createConnectAcceptSocket(isSecure = false, serverId)
            val data: ByteArray = "Test data for clientSendDataOverInsecureSocket".toByteArray()
            val socketOs = insecureSocket.outputStream

            socketOs.write(data)
            val rxResponse: RfcommProto.RxResponse =
                mBumble
                    .rfcommBlocking()
                    .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .receive(RfcommProto.RxRequest.newBuilder().setConnection(connection).build())
            Truth.assertThat(rxResponse.data).isEqualTo(ByteString.copyFrom(data))
        }
    }

    @Test
    fun clientSendDataOverSecureSocket() {
        startServer { serverId ->
            val (secureSocket, connection) = createConnectAcceptSocket(isSecure = true, serverId)
            val data: ByteArray = "Test data for clientSendDataOverSecureSocket".toByteArray()
            val socketOs = secureSocket.outputStream

            socketOs.write(data)
            val rxResponse: RfcommProto.RxResponse =
                mBumble
                    .rfcommBlocking()
                    .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .receive(RfcommProto.RxRequest.newBuilder().setConnection(connection).build())
            Truth.assertThat(rxResponse.data).isEqualTo(ByteString.copyFrom(data))
        }
    }

    @Test
    fun clientReceiveDataOverInsecureSocket() {
        startServer { serverId ->
            val (insecureSocket, connection) = createConnectAcceptSocket(isSecure = false, serverId)
            val buffer = ByteArray(64)
            val socketIs = insecureSocket.inputStream
            val data: ByteString =
                ByteString.copyFromUtf8("Test data for clientReceiveDataOverInsecureSocket")

            val txRequest =
                RfcommProto.TxRequest.newBuilder().setConnection(connection).setData(data).build()
            mBumble.rfcommBlocking().send(txRequest)
            val numBytesFromBumble = socketIs.read(buffer)
            Truth.assertThat(ByteString.copyFrom(buffer).substring(0, numBytesFromBumble))
                .isEqualTo(data)
        }
    }

    @Test
    fun clientReceiveDataOverSecureSocket() {
        startServer { serverId ->
            val (secureSocket, connection) = createConnectAcceptSocket(isSecure = true, serverId)
            val buffer = ByteArray(64)
            val socketIs = secureSocket.inputStream
            val data: ByteString =
                ByteString.copyFromUtf8("Test data for clientReceiveDataOverSecureSocket")

            val txRequest =
                RfcommProto.TxRequest.newBuilder().setConnection(connection).setData(data).build()
            mBumble.rfcommBlocking().send(txRequest)
            val numBytesFromBumble = socketIs.read(buffer)
            Truth.assertThat(ByteString.copyFrom(buffer).substring(0, numBytesFromBumble))
                .isEqualTo(data)
        }
    }

    @Test
    fun connectTwoInsecureClientsSimultaneously() {
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket1 = createSocket(mRemoteDevice, isSecure = false, TEST_UUID)
                val socket2 = createSocket(mRemoteDevice, isSecure = false, SERIAL_PORT_UUID)

                acceptSocket(serverId1)
                Truth.assertThat(socket1.isConnected).isTrue()

                acceptSocket(serverId2)
                Truth.assertThat(socket2.isConnected).isTrue()
            }
        }
    }

    @Test
    fun connectTwoInsecureClientsSequentially() {
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket1 = createSocket(mRemoteDevice, isSecure = false, TEST_UUID)
                acceptSocket(serverId1)
                Truth.assertThat(socket1.isConnected).isTrue()

                val socket2 = createSocket(mRemoteDevice, isSecure = false, SERIAL_PORT_UUID)
                acceptSocket(serverId2)
                Truth.assertThat(socket2.isConnected).isTrue()
            }
        }
    }

    @Test
    fun connectTwoSecureClientsSimultaneously() {
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket2 = createSocket(mRemoteDevice, isSecure = true, SERIAL_PORT_UUID)
                val socket1 = createSocket(mRemoteDevice, isSecure = true, TEST_UUID)

                acceptSocket(serverId1)
                Truth.assertThat(socket1.isConnected).isTrue()

                acceptSocket(serverId2)
                Truth.assertThat(socket2.isConnected).isTrue()
            }
        }
    }

    @Test
    fun connectTwoSecureClientsSequentially() {
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket1 = createSocket(mRemoteDevice, isSecure = true, TEST_UUID)
                acceptSocket(serverId1)
                Truth.assertThat(socket1.isConnected).isTrue()

                val socket2 = createSocket(mRemoteDevice, isSecure = true, SERIAL_PORT_UUID)
                acceptSocket(serverId2)
                Truth.assertThat(socket2.isConnected).isTrue()
            }
        }
    }

    @Test
    fun connectTwoMixedClientsInsecureThenSecure() {
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket2 = createSocket(mRemoteDevice, isSecure = false, SERIAL_PORT_UUID)
                acceptSocket(serverId2)
                Truth.assertThat(socket2.isConnected).isTrue()

                val socket1 = createSocket(mRemoteDevice, isSecure = true, TEST_UUID)
                acceptSocket(serverId1)
                Truth.assertThat(socket1.isConnected).isTrue()
            }
        }
    }

    @Test
    fun connectTwoMixedClientsSecureThenInsecure() {
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket2 = createSocket(mRemoteDevice, isSecure = true, SERIAL_PORT_UUID)
                acceptSocket(serverId2)
                Truth.assertThat(socket2.isConnected).isTrue()

                val socket1 = createSocket(mRemoteDevice, isSecure = false, TEST_UUID)
                acceptSocket(serverId1)
                Truth.assertThat(socket1.isConnected).isTrue()
            }
        }
    }

    private fun createConnectAcceptSocket(
        isSecure: Boolean,
        server: ServerId,
        uuid: String = TEST_UUID,
    ): Pair<BluetoothSocket, RfcommProto.RfcommConnection> {
        val socket = createSocket(mRemoteDevice, isSecure, uuid)

        val connection = acceptSocket(server)
        Truth.assertThat(socket.isConnected).isTrue()

        return Pair(socket, connection)
    }

    private fun createSocket(
        device: BluetoothDevice,
        isSecure: Boolean,
        uuid: String,
    ): BluetoothSocket {
        val socket =
            if (isSecure) {
                device.createRfcommSocketToServiceRecord(UUID.fromString(uuid))
            } else {
                device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(uuid))
            }
        socket.connect()
        return socket
    }

    private fun acceptSocket(server: ServerId): RfcommProto.RfcommConnection {
        val connectionResponse =
            mBumble
                .rfcommBlocking()
                .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .acceptConnection(
                    RfcommProto.AcceptConnectionRequest.newBuilder().setServer(server).build()
                )
        Truth.assertThat(connectionResponse.connection.id).isEqualTo(mConnectionCounter)

        mConnectionCounter += 1
        return connectionResponse.connection
    }

    private fun startServer(
        name: String = TEST_SERVER_NAME,
        uuid: String = TEST_UUID,
        block: (ServerId) -> Unit,
    ) {
        val request = StartServerRequest.newBuilder().setName(name).setUuid(uuid).build()
        val response = mBumble.rfcommBlocking().startServer(request)

        try {
            block(response.server)
        } finally {
            mBumble
                .rfcommBlocking()
                .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .stopServer(
                    RfcommProto.StopServerRequest.newBuilder().setServer(response.server).build()
                )
        }
    }

    private fun getProfileProxy(context: Context, profile: Int): BluetoothProfile {
        mAdapter.getProfileProxy(context, mProfileServiceListener, profile)
        val proxyCaptor = argumentCaptor<BluetoothProfile>()
        verify(mProfileServiceListener, timeout(GRPC_TIMEOUT.toMillis()))
            .onServiceConnected(eq(profile), proxyCaptor.capture())
        return proxyCaptor.lastValue
    }

    companion object {
        private val TAG = RfcommTest::class.java.getSimpleName()
        private val GRPC_TIMEOUT = Duration.ofSeconds(10)
        private const val TEST_UUID = "2ac5d8f1-f58d-48ac-a16b-cdeba0892d65"
        private const val SERIAL_PORT_UUID = "00001101-0000-1000-8000-00805F9B34FB"
        private const val TEST_SERVER_NAME = "RFCOMM Server"
    }
}
