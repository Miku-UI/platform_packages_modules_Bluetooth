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
import itertools
import logging
import math
import numpy as np
import os

from avatar import BumblePandoraDevice, PandoraDevice, PandoraDevices, pandora
from avatar.pandora_server import AndroidPandoraServer
from bumble import l2cap
from bumble.avctp import AVCTP_PSM
from bumble.a2dp import (
    A2DP_SBC_CODEC_TYPE,
    SBC_DUAL_CHANNEL_MODE,
    SBC_JOINT_STEREO_CHANNEL_MODE,
    SBC_LOUDNESS_ALLOCATION_METHOD,
    SBC_MONO_CHANNEL_MODE,
    SBC_SNR_ALLOCATION_METHOD,
    SBC_STEREO_CHANNEL_MODE,
    SbcMediaCodecInformation,
    make_audio_sink_service_sdp_records,
)
from bumble.avdtp import (
    AVDTP_AUDIO_MEDIA_TYPE,
    AVDTP_OPEN_STATE,
    AVDTP_STREAMING_STATE,
    Listener,
    MediaCodecCapabilities,
)
from bumble.pairing import PairingDelegate
from mobly import base_test, test_runner
from mobly.asserts import assert_equal  # type: ignore
from mobly.asserts import assert_in  # type: ignore
from mobly.asserts import assert_is_none  # type: ignore
from mobly.asserts import assert_is_not_none  # type: ignore
from mobly.asserts import fail  # type: ignore
from pandora.a2dp_grpc_aio import A2DP
from pandora.a2dp_pb2 import PlaybackAudioRequest, Sink, Source
from pandora.host_pb2 import Connection
from pandora.security_pb2 import LEVEL2
from threading import Thread
from typing import Optional

AVRCP_CONNECT_A2DP_WITH_DELAY = 'persist.device_config.aconfig_flags.bluetooth.com.android.bluetooth.flags.avrcp_connect_a2dp_with_delay'

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


def codec_capabilities():
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
            self.ref1.a2dp_sink = server.add_sink(codec_capabilities())

        def on_ref2_avdtp_connection(server):
            self.ref2.a2dp_sink = server.add_sink(codec_capabilities())

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
                server.device.adb.shell(['setprop', AVRCP_CONNECT_A2DP_WITH_DELAY, 'true'])  # type: ignore
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
            self.ref1.a2dp_sink = server.add_sink(codec_capabilities())
            self.ref1.log.info(f'Sink: {self.ref1.a2dp_sink}')
            avdtp_future.set_result(None)

        self.ref1.a2dp.on('connection', on_avdtp_connection)

        # Retrieve Bumble connection object from Pandora connection token
        connection = pandora.get_raw_connection(device=self.ref1, connection=ref1_dut)

        # Open AVCTP L2CAP channel
        avctp = await connection.create_l2cap_channel(spec=l2cap.ClassicChannelSpec(AVCTP_PSM))
        self.ref1.log.info(f'AVCTP: {avctp}')

        # Wait for AVDTP L2CAP channel
        await asyncio.wait_for(avdtp_future, timeout=10.0)

if __name__ == '__main__':
    logging.basicConfig(level=logging.DEBUG)
    test_runner.main()  # type: ignore
