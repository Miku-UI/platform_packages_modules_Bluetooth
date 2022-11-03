# Copyright 2022 Google LLC
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
"""HFP proxy module."""

from mmi2grpc._helpers import assert_description
from mmi2grpc._proxy import ProfileProxy

from pandora_experimental.hfp_grpc import HFP
from pandora_experimental.host_grpc import Host
from pandora_experimental.security_grpc import Security

import sys
import threading
import time

# Standard time to wait before asking for waitConnection
WAIT_DELAY_BEFORE_CONNECTION = 2

# The tests needs the MMI to accept pairing confirmation request.
NEEDS_WAIT_CONNECTION_BEFORE_TEST = {'HFP/AG/WBS/BV-01-I', 'HFP/AG/SLC/BV-05-I'}


class HFPProxy(ProfileProxy):

    def __init__(self, channel):
        super().__init__(channel)
        self.hfp = HFP(channel)
        self.host = Host(channel)
        self.security = Security(channel)

        self.connection = None

    def asyncWaitConnection(self, pts_addr, delay=WAIT_DELAY_BEFORE_CONNECTION):
        """
        Send a WaitConnection in a grpc callback
        """

        def waitConnectionCallback(self, pts_addr):
            self.connection = self.host.WaitConnection(address=pts_addr).connection

        print(f'HFP placeholder mmi: asyncWaitConnection', file=sys.stderr)
        th = threading.Timer(interval=delay, function=waitConnectionCallback, args=(self, pts_addr))
        th.start()

    def test_started(self, test: str, pts_addr: bytes, **kwargs):
        if test in NEEDS_WAIT_CONNECTION_BEFORE_TEST:
            self.asyncWaitConnection(pts_addr)

        return "OK"

    @assert_description
    def TSC_delete_pairing_iut(self, pts_addr: bytes, **kwargs):
        """
        Delete the pairing with the PTS using the Implementation Under Test
        (IUT), then click Ok.
        """

        self.security.DeletePairing(address=pts_addr)
        return "OK"

    @assert_description
    def TSC_iut_enable_slc(self, pts_addr: bytes, **kwargs):
        """
        Click Ok, then initiate a service level connection from the
        Implementation Under Test (IUT) to the PTS.
        """

        if not self.connection:
            self.connection = self.host.Connect(address=pts_addr).connection
        self.hfp.EnableSlc(connection=self.connection)
        return "OK"

    @assert_description
    def TSC_iut_search(self, **kwargs):
        """
        Using the Implementation Under Test (IUT), perform a search for the PTS.
        If found, click OK.
        """

        return "OK"

    @assert_description
    def TSC_iut_connect(self, pts_addr: bytes, **kwargs):
        """
        Click Ok, then make a connection request to the PTS from the
        Implementation Under Test (IUT).
        """

        self.connection = self.host.Connect(address=pts_addr).connection
        return "OK"

    @assert_description
    def TSC_iut_connectable(self, pts_addr: str, test: str, **kwargs):
        """
        Make the Implementation Under Test (IUT) connectable, then click Ok.
        """

        if "HFP/AG/SLC/BV-03-C" in test:
            self.connection = self.host.WaitConnection(pts_addr).connection

        return "OK"

    @assert_description
    def TSC_iut_disable_slc(self, pts_addr: bytes, **kwargs):
        """
        Click Ok, then disable the service level connection using the
        Implementation Under Test (IUT).
        """

        def go():
            time.sleep(2)
            self.hfp.DisableSlc(connection=self.connection)

        threading.Thread(target=go).start()

        return "OK"

    @assert_description
    def TSC_make_battery_charged(self, **kwargs):
        """
        Click Ok, then manipulate the Implementation Under Test (IUT) so that
        the battery is fully charged.
        """

        self.hfp.SetBatteryLevel(connection=self.connection, battery_percentage=100)

        return "OK"

    @assert_description
    def TSC_make_battery_discharged(self, **kwargs):
        """
        Manipulate the Implementation Under Test (IUT) so that the battery level
        is not fully charged, then click Ok.
        """

        self.hfp.SetBatteryLevel(connection=self.connection, battery_percentage=42)

        return "OK"