# Copyright 2024 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import asyncio
import avatar
import dataclasses
import itertools
import logging
import numpy as np

from avatar import BumblePandoraDevice, PandoraDevice, PandoraDevices, pandora
from avatar.pandora_server import AndroidPandoraServer
import bumble
from bumble.avctp import AVCTP_PSM
from bumble.a2dp import (
    A2DP_MPEG_2_4_AAC_CODEC_TYPE,
    MPEG_2_AAC_LC_OBJECT_TYPE,
    A2DP_SBC_CODEC_TYPE,
    SBC_DUAL_CHANNEL_MODE,
    SBC_JOINT_STEREO_CHANNEL_MODE,
    SBC_LOUDNESS_ALLOCATION_METHOD,
    SBC_MONO_CHANNEL_MODE,
    SBC_SNR_ALLOCATION_METHOD,
    SBC_STEREO_CHANNEL_MODE,
    AacMediaCodecInformation,
    SbcMediaCodecInformation,
    make_audio_sink_service_sdp_records,
)
from bumble.avdtp import (AVDTP_AUDIO_MEDIA_TYPE, AVDTP_OPEN_STATE, AVDTP_PSM, AVDTP_STREAMING_STATE, AVDTP_IDLE_STATE,
                          AVDTP_CLOSING_STATE, Listener, MediaCodecCapabilities, Protocol, AVDTP_BAD_STATE_ERROR,
                          Suspend_Reject)
from bumble.l2cap import (ChannelManager, ClassicChannel, ClassicChannelSpec, L2CAP_Configure_Request,
                          L2CAP_Connection_Response, L2CAP_SIGNALING_CID)
from bumble.pairing import PairingDelegate
from mobly import base_test, test_runner
from mobly.asserts import assert_equal  # type: ignore
from mobly.asserts import assert_in  # type: ignore
from mobly.asserts import assert_is_not_none  # type: ignore
from mobly.asserts import fail  # type: ignore
from pandora.a2dp_grpc_aio import A2DP
from pandora.a2dp_pb2 import PlaybackAudioRequest, Source, Configuration, STEREO
from pandora.host_pb2 import Connection
from pandora.security_pb2 import LEVEL2
from typing import Optional, Tuple

logger = logging.getLogger(__name__)

AVRCP_CONNECT_A2DP_WITH_DELAY = 'com.android.bluetooth.flags.avrcp_connect_a2dp_with_delay'
AVDTP_HANDLE_SUSPEND_CFM_BAD_STATE = 'com.android.bluetooth.flags.avdt_handle_suspend_cfm_bad_state'


async def initiate_pairing(device, address) -> Connection:
    """Connect and pair a remote device."""

    result = await device.aio.host.Connect(address=address)
    connection = result.connection
    assert connection

    bond = await device.aio.security.Secure(connection=connection, classic=LEVEL2)
    assert bond.success

    return connection


async def accept_pairing(device, address) -> Connection:
    """Accept connection and pairing from a remote device."""

    result = await device.aio.host.WaitConnection(address=address)
    connection = result.connection
    assert connection

    bond = await device.aio.security.WaitSecurity(connection=connection, classic=LEVEL2)
    assert bond.success

    return connection


async def open_source(device, connection) -> Source:
    """Initiate AVDTP connection from Android device."""

    result = await device.a2dp.OpenSource(connection=connection)
    source = result.source
    assert source

    return source


def sbc_codec_capabilities() -> MediaCodecCapabilities:
    """Codec capabilities for the Bumble sink devices."""

    return MediaCodecCapabilities(
        media_type=AVDTP_AUDIO_MEDIA_TYPE,
        media_codec_type=A2DP_SBC_CODEC_TYPE,
        media_codec_information=SbcMediaCodecInformation.from_lists(
            sampling_frequencies=[48000, 44100, 32000, 16000],
            channel_modes=[
                SBC_MONO_CHANNEL_MODE,
                SBC_DUAL_CHANNEL_MODE,
                SBC_STEREO_CHANNEL_MODE,
                SBC_JOINT_STEREO_CHANNEL_MODE,
            ],
            block_lengths=[4, 8, 12, 16],
            subbands=[4, 8],
            allocation_methods=[
                SBC_LOUDNESS_ALLOCATION_METHOD,
                SBC_SNR_ALLOCATION_METHOD,
            ],
            minimum_bitpool_value=2,
            maximum_bitpool_value=53,
        ),
    )


def aac_codec_capabilities() -> MediaCodecCapabilities:
    """Codec capabilities for the Bumble sink devices."""

    return MediaCodecCapabilities(
        media_type=AVDTP_AUDIO_MEDIA_TYPE,
        media_codec_type=A2DP_MPEG_2_4_AAC_CODEC_TYPE,
        media_codec_information=AacMediaCodecInformation.from_lists(
            object_types=[MPEG_2_AAC_LC_OBJECT_TYPE],
            sampling_frequencies=[48000, 44100],
            channels=[1, 2],
            vbr=1,
            bitrate=256000,
        ),
    )


class AudioSignal:
    """Audio signal generator and verifier."""

    SINE_FREQUENCY = 440
    SINE_DURATION = 0.1

    def __init__(self, a2dp: A2DP, source: Source, amplitude, fs):
        """Init AudioSignal class.

        Args:
            a2dp: A2DP profile interface.
            source: Source connection object to send the data to.
            amplitude: amplitude of the signal to generate.
            fs: sampling rate of the signal to generate.
        """
        self.a2dp = a2dp
        self.source = source
        self.amplitude = amplitude
        self.fs = fs
        self.task = None

    def start(self):
        """Generates the audio signal and send it to the transport."""
        self.task = asyncio.create_task(self._run())

    async def _run(self):
        sine = self._generate_sine(self.SINE_FREQUENCY, self.SINE_DURATION)

        # Interleaved audio.
        stereo = np.zeros(sine.size * 2, dtype=sine.dtype)
        stereo[0::2] = sine

        # Send 4 second of audio.
        audio = itertools.repeat(stereo.tobytes(), int(4 / self.SINE_DURATION))

        for frame in audio:
            await self.a2dp.PlaybackAudio(PlaybackAudioRequest(data=frame, source=self.source))

    def _generate_sine(self, f, duration):
        sine = self.amplitude * np.sin(2 * np.pi * np.arange(self.fs * duration) * (f / self.fs))
        s16le = (sine * 32767).astype('<i2')
        return s16le


class A2dpTest(base_test.BaseTestClass):  # type: ignore[misc]
    """A2DP test suite."""

    devices: Optional[PandoraDevices] = None

    # pandora devices.
    dut: PandoraDevice
    ref1: PandoraDevice
    ref2: PandoraDevice

    @avatar.asynchronous
    async def setup_class(self) -> None:
        self.devices = PandoraDevices(self)
        self.dut, self.ref1, self.ref2, *_ = self.devices

        if not isinstance(self.ref1, BumblePandoraDevice):
            raise signals.TestAbortClass('Test require Bumble as reference device(s)')
        if not isinstance(self.ref2, BumblePandoraDevice):
            raise signals.TestAbortClass('Test require Bumble as reference device(s)')

        # Enable BR/EDR mode and SSP for Bumble devices.
        for device in self.devices:
            if isinstance(device, BumblePandoraDevice):
                device.config.setdefault('classic_enabled', True)
                device.config.setdefault('classic_ssp_enabled', True)
                device.config.setdefault('classic_smp_enabled', False)
                device.server_config.io_capability = PairingDelegate.NO_OUTPUT_NO_INPUT

    def teardown_class(self) -> None:
        if self.devices:
            self.devices.stop_all()

    @avatar.asynchronous
    async def setup_test(self) -> None:
        await asyncio.gather(self.dut.reset(), self.ref1.reset(), self.ref2.reset())

        self.dut.a2dp = A2DP(channel=self.dut.aio.channel)

        handle = 0x00010001
        self.ref1.device.sdp_service_records = {handle: make_audio_sink_service_sdp_records(handle)}
        self.ref2.device.sdp_service_records = {handle: make_audio_sink_service_sdp_records(handle)}

        self.ref1.a2dp = Listener.for_device(self.ref1.device)
        self.ref2.a2dp = Listener.for_device(self.ref2.device)
        self.ref1.a2dp_sink = None
        self.ref2.a2dp_sink = None

        def on_ref1_avdtp_connection(server):
            self.ref1.a2dp_sink = server.add_sink(sbc_codec_capabilities())

        def on_ref2_avdtp_connection(server):
            self.ref2.a2dp_sink = server.add_sink(sbc_codec_capabilities())
            self.ref2.a2dp_sink = server.add_sink(aac_codec_capabilities())

        self.ref1.a2dp.on('connection', on_ref1_avdtp_connection)
        self.ref2.a2dp.on('connection', on_ref2_avdtp_connection)

    @avatar.asynchronous
    async def test_connect_and_stream(self) -> None:
        """Basic A2DP connection and streaming test.
        This test wants to be a template to be reused for other tests.

        1. Pair and Connect RD1
        2. Start streaming
        3. Check AVDTP status on RD1
        4. Stop streaming
        5. Check AVDTP status on RD1
        """
        # Connect and pair RD1.
        dut_ref1, ref1_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref1.address),
            accept_pairing(self.ref1, self.dut.address),
        )

        # Connect AVDTP to RD1.
        dut_ref1_source = await open_source(self.dut, dut_ref1)
        assert_is_not_none(self.ref1.a2dp_sink)
        assert_is_not_none(self.ref1.a2dp_sink.stream)
        assert_in(self.ref1.a2dp_sink.stream.state, [AVDTP_OPEN_STATE, AVDTP_STREAMING_STATE])

        # Start streaming to RD1.
        await self.dut.a2dp.Start(source=dut_ref1_source)
        audio = AudioSignal(self.dut.a2dp, dut_ref1_source, 0.8, 44100)
        assert_equal(self.ref1.a2dp_sink.stream.state, AVDTP_STREAMING_STATE)

        # Stop streaming to RD1.
        await self.dut.a2dp.Suspend(source=dut_ref1_source)
        assert_equal(self.ref1.a2dp_sink.stream.state, AVDTP_OPEN_STATE)

    @avatar.asynchronous
    async def test_avdtp_autoconnect_when_only_avctp_connected(self) -> None:
        """Test AVDTP automatically connects if peer device connects only AVCTP.

        1. Pair and Connect RD1 -> DUT
        2. Connect AVCTP RD1 -> DUT
        3. Check AVDTP status on RD1
        """

        # Enable AVRCP connect A2DP delayed feature
        for server in self.devices._servers:
            if isinstance(server, AndroidPandoraServer):
                server.device.adb.shell(['device_config override bluetooth', AVRCP_CONNECT_A2DP_WITH_DELAY,
                                         'true'])  # type: ignore
                break

        # Connect and pair RD1.
        ref1_dut, dut_ref1 = await asyncio.gather(
            initiate_pairing(self.ref1, self.dut.address),
            accept_pairing(self.dut, self.ref1.address),
        )

        # Create a listener to wait for AVDTP connections
        avdtp_future = asyncio.get_running_loop().create_future()

        def on_avdtp_connection(server):
            nonlocal avdtp_future
            self.ref1.a2dp_sink = server.add_sink(sbc_codec_capabilities())
            self.ref1.log.info(f'Sink: {self.ref1.a2dp_sink}')
            avdtp_future.set_result(None)

        self.ref1.a2dp.on('connection', on_avdtp_connection)

        # Retrieve Bumble connection object from Pandora connection token
        connection = pandora.get_raw_connection(device=self.ref1, connection=ref1_dut)

        # Open AVCTP L2CAP channel
        avctp = await connection.create_l2cap_channel(spec=ClassicChannelSpec(AVCTP_PSM))
        self.ref1.log.info(f'AVCTP: {avctp}')

        # Wait for AVDTP L2CAP channel
        await asyncio.wait_for(avdtp_future, timeout=10.0)

    @avatar.asynchronous
    async def test_avdt_signaling_channel_connection_collision(self) -> None:
        """Test AVDTP signaling channel connection collision.

        Test steps after DUT and RD1 connected and paired:
        1. RD1 connects DUT over AVDTP - first AVDTP signaling channel
        2. AVDTP signaling channel configuration postponed until DUT tries to initiate AVDTP signaling channel connection
        3. DUT tries connecting RD1 - collision simulated
        4. RD1 rejects AVDTP signaling channel connection request from DUT
        5. RD1 proceeds with first AVDTP signaling channel configuration
        6. Channel established - collision avoided
        """

        @dataclasses.dataclass
        class L2capConfigurationRequest:
            connection: Optional[Connection] = None
            cid: Optional[int] = None
            request: Optional[L2CAP_Configure_Request] = None

        global pending_configuration_request
        pending_configuration_request = L2capConfigurationRequest()

        class TestChannelManager(ChannelManager):

            def __init__(
                self,
                device: BumblePandoraDevice,
            ) -> None:
                super().__init__(
                    device.l2cap_channel_manager.extended_features,
                    device.l2cap_channel_manager.connectionless_mtu,
                )
                self.register_fixed_channel(bumble.smp.SMP_CID, device.on_smp_pdu)
                device.sdp_server.register(self)
                self.register_fixed_channel(bumble.att.ATT_CID, device.on_gatt_pdu)
                self.host = device.host

            def on_l2cap_connection_request(self, connection: Connection, cid: int, request) -> None:
                global pending_configuration_request
                if (request.psm == AVDTP_PSM and pending_configuration_request is not None):
                    logger.info("<< 4. RD1 rejects AVDTP connection request from DUT >>")
                    self.send_control_frame(
                        connection, cid,
                        L2CAP_Connection_Response(
                            identifier=request.identifier,
                            destination_cid=0,
                            source_cid=request.source_cid,
                            result=L2CAP_Connection_Response.CONNECTION_REFUSED_NO_RESOURCES_AVAILABLE,
                            status=0x0000,
                        ))
                    logger.info("<< 5. RD1 proceeds with first AVDTP channel configuration >>")
                    chan_connection = pending_configuration_request.connection
                    chan_cid = pending_configuration_request.cid
                    chan_request = pending_configuration_request.request
                    pending_configuration_request = None
                    super().on_control_frame(connection=chan_connection, cid=chan_cid, control_frame=chan_request)
                    return
                super().on_l2cap_connection_request(connection, cid, request)

        class TestClassicChannel(ClassicChannel):

            def on_connection_response(self, response):
                assert self.state == self.State.WAIT_CONNECT_RSP
                assert response.result == L2CAP_Connection_Response.CONNECTION_SUCCESSFUL, f"Connection response: {response}"
                self.destination_cid = response.destination_cid
                self._change_state(self.State.WAIT_CONFIG)
                logger.info("<< 2. RD1 connected DUT, configuration postponed >>")

            def on_configure_request(self, request) -> None:
                global pending_configuration_request
                if (pending_configuration_request is not None):
                    logger.info("<< 3. Block RD1 until DUT tries AVDTP channel connection >>")
                    pending_configuration_request.connection = self.connection
                    pending_configuration_request.cid = self.source_cid
                    pending_configuration_request.request = request
                else:
                    super().on_configure_request(request)

        # Override L2CAP Channel Manager to control signaling
        self.ref1.device.l2cap_channel_manager = TestChannelManager(self.ref1.device)

        # Connect and pair DUT -> RD1.
        dut_ref1, ref1_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref1.address),
            accept_pairing(self.ref1, self.dut.address),
        )

        # Retrieve Bumble connection object from Pandora connection token
        connection = pandora.get_raw_connection(device=self.ref1, connection=ref1_dut)
        # Find a free CID for a new channel
        connection_channels = self.ref1.device.l2cap_channel_manager.channels.setdefault(connection.handle, {})
        source_cid = self.ref1.device.l2cap_channel_manager.find_free_br_edr_cid(connection_channels)
        assert source_cid is not None, "source_cid is None"

        spec = ClassicChannelSpec(AVDTP_PSM)
        channel = TestClassicChannel(
            self.ref1.device.l2cap_channel_manager,
            connection,
            L2CAP_SIGNALING_CID,
            AVDTP_PSM,
            source_cid,
            spec.mtu,
        )
        connection_channels[source_cid] = channel

        logger.info("<< 1. RD1 connects DUT over AVDTP - first channel >>")
        await channel.connect()
        logger.info(f"<< 6. Channel established: {channel} >>")
        assert channel.state == ClassicChannel.State.OPEN

        # Initiate AVDTP with connected L2CAP signaling channel
        protocol = Protocol(channel)
        protocol.add_sink(sbc_codec_capabilities())
        logger.info("<< Test finished! >>")

    @avatar.asynchronous
    async def test_reconfigure_codec_success(self) -> None:
        """Basic A2DP connection and codec reconfiguration.

        1. Pair and Connect RD2
        2. Check current codec configuration - should be AAC
        3. Set SBC codec configuration
        """
        # Connect and pair RD2.
        dut_ref2, ref2_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref2.address),
            accept_pairing(self.ref2, self.dut.address),
        )

        # Connect AVDTP to RD2.
        dut_ref2_source = await open_source(self.dut, dut_ref2)
        assert_is_not_none(self.ref2.a2dp_sink)
        assert_is_not_none(self.ref2.a2dp_sink.stream)
        assert_in(self.ref2.a2dp_sink.stream.state, [AVDTP_OPEN_STATE, AVDTP_STREAMING_STATE])

        # Get current codec status
        configurationResponse = await self.dut.a2dp.GetConfiguration(connection=dut_ref2)
        logger.info(f"Current codec configuration: {configurationResponse.configuration}")
        assert configurationResponse.configuration.id.HasField('mpeg_aac')

        new_configuration = Configuration()
        new_configuration.id.sbc.SetInParent()
        new_configuration.parameters.sampling_frequency_hz = 44100
        new_configuration.parameters.bit_depth = 16
        new_configuration.parameters.channel_mode = STEREO

        # Set new codec
        logger.info(f"Switching to codec: {new_configuration}")
        result = await self.dut.a2dp.SetConfiguration(connection=dut_ref2, configuration=new_configuration)
        assert result.success

        # Get current codec status
        configurationResponse = await self.dut.a2dp.GetConfiguration(connection=dut_ref2)
        logger.info(f"Current codec configuration: {configurationResponse.configuration}")
        assert configurationResponse.configuration.id.HasField('sbc')

    @avatar.asynchronous
    async def test_reconfigure_codec_error_unsupported(self) -> None:
        """Basic A2DP connection and codec reconfiguration failure.

        1. Pair and Connect RD2
        2. Check current codec configuration - should be AAC
        3. Set SBC codec configuration with unsupported parameters
        """
        # Connect and pair RD2.
        dut_ref2, ref2_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref2.address),
            accept_pairing(self.ref2, self.dut.address),
        )

        # Connect AVDTP to RD2.
        dut_ref2_source = await open_source(self.dut, dut_ref2)
        assert_is_not_none(self.ref2.a2dp_sink)
        assert_is_not_none(self.ref2.a2dp_sink.stream)
        assert_in(self.ref2.a2dp_sink.stream.state, [AVDTP_OPEN_STATE, AVDTP_STREAMING_STATE])

        # Get current codec status
        configurationResponse = await self.dut.a2dp.GetConfiguration(connection=dut_ref2)
        logger.info(f"Current codec configuration: {configurationResponse.configuration}")
        assert configurationResponse.configuration.id.HasField('mpeg_aac')

        new_configuration = Configuration()
        new_configuration.id.sbc.SetInParent()
        new_configuration.parameters.sampling_frequency_hz = 176400
        new_configuration.parameters.bit_depth = 24
        new_configuration.parameters.channel_mode = STEREO

        # Set new codec
        logger.info(f"Switching to codec: {new_configuration}")
        result = await self.dut.a2dp.SetConfiguration(connection=dut_ref2, configuration=new_configuration)
        assert result.success == False

        # Get current codec status, assure it did not change
        configurationResponse = await self.dut.a2dp.GetConfiguration(connection=dut_ref2)
        logger.info(f"Current codec configuration: {configurationResponse.configuration}")
        assert configurationResponse.configuration.id.HasField('mpeg_aac')

    @avatar.asynchronous
    async def test_reconfigure_codec_aac_error(self) -> None:
        # Connect and pair RD2.
        dut_ref2, ref2_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref2.address),
            accept_pairing(self.ref2, self.dut.address),
        )

        # Connect AVDTP to RD2.
        dut_ref2_source = await open_source(self.dut, dut_ref2)
        assert_is_not_none(self.ref2.a2dp_sink)
        assert_is_not_none(self.ref2.a2dp_sink.stream)
        assert_in(self.ref2.a2dp_sink.stream.state, [AVDTP_OPEN_STATE, AVDTP_STREAMING_STATE])

        # Get current codec status
        configurationResponse = await self.dut.a2dp.GetConfiguration(connection=dut_ref2)
        logger.info(f"Current codec configuration: {configurationResponse.configuration}")
        assert configurationResponse.configuration.id.HasField('mpeg_aac')

        new_configuration = Configuration()
        new_configuration.id.sbc.SetInParent()
        new_configuration.parameters.sampling_frequency_hz = 176400
        new_configuration.parameters.bit_depth = 24
        new_configuration.parameters.channel_mode = STEREO

        # Set new codec
        logger.info(f"Switching to codec: {new_configuration}")
        result = await self.dut.a2dp.SetConfiguration(connection=dut_ref2, configuration=new_configuration)
        assert result.success == False

        # Get current codec status, assure it did not change
        configurationResponse = await self.dut.a2dp.GetConfiguration(connection=dut_ref2)
        logger.info(f"Current codec configuration: {configurationResponse.configuration}")
        assert configurationResponse.configuration.id.HasField('mpeg_aac')

    @avatar.asynchronous
    async def test_avdt_handle_suspend_cfm_bad_state_error(self) -> None:
        """Test AVDTP handling of suspend confirmation BAD_STATE error.

        Test steps after DUT and RD1 connected and paired:
        1. Start streaming to RD1.
        2. Suspend streaming, RD1 will simulate failure response - AVDTP_BAD_STATE_ERROR.
        3. The DUT closes the AVDTP connection.
        """

        class TestAvdtProtocol(Protocol):

            def on_suspend_command(self, command):
                logger.info("<< Simulate suspend reject >>")
                for seid in command.acp_seids:
                    endpoint = self.get_local_endpoint_by_seid(seid)
                    if endpoint:
                        logger.info(f"<< Reject on endpoint: {endpoint} >>")
                        return Suspend_Reject(seid, AVDTP_BAD_STATE_ERROR)

        class TestA2dpListener(Listener):

            @classmethod
            def for_device(cls, device: bumble.device.Device, version: Tuple[int, int] = (1, 3)) -> Listener:
                listener = TestA2dpListener(registrar=None, version=version)
                l2cap_server = device.create_l2cap_server(spec=ClassicChannelSpec(psm=AVDTP_PSM))
                l2cap_server.on('connection', listener.on_l2cap_connection)
                return listener

            def on_l2cap_connection(self, channel: ClassicChannel) -> None:
                logger.info(f"<<< incoming L2CAP connection: {channel}")

                if channel.connection.handle in self.servers:
                    # This is a channel for a stream endpoint
                    server = self.servers[channel.connection.handle]
                    server.on_l2cap_connection(channel)
                else:
                    # This is a new command/response channel
                    def on_channel_open():
                        logger.info('setting up new TestAvdtProtocol for the connection')
                        server = TestAvdtProtocol(channel, self.version)
                        self.set_server(channel.connection, server)
                        self.emit('connection', server)

                    def on_channel_close():
                        logger.info('removing TestAvdtProtocol for the connection')
                        self.remove_server(channel.connection)

                    channel.on('open', on_channel_open)
                    channel.on('close', on_channel_close)

        # Enable BAD_STATE handling
        for server in self.devices._servers:
            if isinstance(server, AndroidPandoraServer):
                server.device.adb.shell(
                    ['device_config override bluetooth', AVDTP_HANDLE_SUSPEND_CFM_BAD_STATE, 'true'])  # type: ignore
                break

        self.ref1.device.l2cap_channel_manager.servers.pop(AVDTP_PSM)
        self.ref1.a2dp = TestA2dpListener.for_device(self.ref1.device)
        self.ref1.a2dp_sink = None

        def on_ref1_avdtp_connection(server):
            logger.info("<< RD1: On AVDTP Connection, adding sink >>")
            self.ref1.a2dp_sink = server.add_sink(sbc_codec_capabilities())

        self.ref1.a2dp.on('connection', on_ref1_avdtp_connection)

        # Connect and pair RD1.
        dut_ref1, ref1_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref1.address),
            accept_pairing(self.ref1, self.dut.address),
        )

        # Connect AVDTP to RD1.
        dut_ref1_source = await open_source(self.dut, dut_ref1)
        assert_is_not_none(self.ref1.a2dp_sink)
        assert_is_not_none(self.ref1.a2dp_sink.stream)
        assert_in(self.ref1.a2dp_sink.stream.state, [AVDTP_OPEN_STATE, AVDTP_STREAMING_STATE])

        # Create a listener to wait for AVDTP close
        avdtp_future = asyncio.get_running_loop().create_future()

        def on_ref1_avdtp_close():
            nonlocal avdtp_future
            logger.info("AVDTP Close received")
            avdtp_future.set_result(None)

        self.ref1.a2dp_sink.on('close', on_ref1_avdtp_close)

        # Start streaming to RD1.
        await self.dut.a2dp.Start(source=dut_ref1_source)
        audio = AudioSignal(self.dut.a2dp, dut_ref1_source, 0.8, 44100)
        assert_equal(self.ref1.a2dp_sink.stream.state, AVDTP_STREAMING_STATE)

        # Suspend streaming, peer device will simulate failure response.
        # The stack should close the stream.
        await self.dut.a2dp.Suspend(source=dut_ref1_source)

        # Wait for AVDTP Close
        await asyncio.wait_for(avdtp_future, timeout=10.0)


if __name__ == '__main__':
    logging.basicConfig(level=logging.DEBUG)
    test_runner.main()  # type: ignore
