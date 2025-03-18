/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.bluetooth.telephony;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothLeCallControl;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.platform.test.flag.junit.SetFlagsRule;
import android.telecom.BluetoothCallQualityReport;
import android.telecom.Call;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.GatewayInfo;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.hfp.BluetoothHeadsetProxy;
import com.android.bluetooth.tbs.BluetoothLeCallControlProxy;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Tests for {@link BluetoothInCallService} */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class BluetoothInCallServiceTest {
    private static final String TAG = "BluetoothInCallServiceTest";

    private static final int TEST_DTMF_TONE = 0;
    private static final String TEST_ACCOUNT_ADDRESS = "//foo.com/";
    private static final int TEST_ACCOUNT_INDEX = 0;

    private static final int CALL_STATE_ACTIVE = 0;
    private static final int CALL_STATE_HELD = 1;
    private static final int CALL_STATE_DIALING = 2;
    private static final int CALL_STATE_ALERTING = 3;
    private static final int CALL_STATE_INCOMING = 4;
    private static final int CALL_STATE_WAITING = 5;
    private static final int CALL_STATE_IDLE = 6;
    private static final int CALL_STATE_DISCONNECTED = 7;
    // Terminate all held or set UDUB("busy") to a waiting call
    private static final int CHLD_TYPE_RELEASEHELD = 0;
    // Terminate all active calls and accepts a waiting/held call
    private static final int CHLD_TYPE_RELEASEACTIVE_ACCEPTHELD = 1;
    // Hold all active calls and accepts a waiting/held call
    private static final int CHLD_TYPE_HOLDACTIVE_ACCEPTHELD = 2;
    // Add all held calls to a conference
    private static final int CHLD_TYPE_ADDHELDTOCONF = 3;

    private BluetoothInCallService mBluetoothInCallService;

    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private BluetoothHeadsetProxy mMockBluetoothHeadset;
    @Mock private BluetoothLeCallControlProxy mLeCallControl;
    @Mock private BluetoothInCallService.CallInfo mMockCallInfo;

    private TelephonyManager mMockTelephonyManager;

    @Before
    public void setUp() {
        doReturn(true).when(mMockCallInfo).isNullCall(null);
        doReturn(false).when(mMockCallInfo).isNullCall(notNull());

        Context spiedContext = spy(new ContextWrapper(ApplicationProvider.getApplicationContext()));
        mMockTelephonyManager =
                TestUtils.mockGetSystemService(
                        spiedContext, Context.TELEPHONY_SERVICE, TelephonyManager.class);

        mBluetoothInCallService =
                new BluetoothInCallService(
                        spiedContext, mMockCallInfo, mMockBluetoothHeadset, mLeCallControl);
        mBluetoothInCallService.onCreate();
    }

    @Test
    public void headsetAnswerCall() {
        BluetoothCall mockCall = createRingingCall(UUID.randomUUID());

        boolean callAnswered = mBluetoothInCallService.answerCall();
        verify(mockCall).answer(any(int.class));

        Assert.assertTrue(callAnswered);
    }

    @Test
    public void headsetAnswerCallNull() {
        boolean callAnswered = mBluetoothInCallService.answerCall();
        Assert.assertFalse(callAnswered);
    }

    @Test
    public void headsetHangupCall() {
        BluetoothCall mockCall = createForegroundCall(UUID.randomUUID());

        boolean callHungup = mBluetoothInCallService.hangupCall();

        verify(mockCall).disconnect();
        Assert.assertTrue(callHungup);
    }

    @Test
    public void headsetHangupCallNull() {
        boolean callHungup = mBluetoothInCallService.hangupCall();
        Assert.assertFalse(callHungup);
    }

    @Test
    public void headsetSendDTMF() {
        BluetoothCall mockCall = createForegroundCall(UUID.randomUUID());

        boolean sentDtmf = mBluetoothInCallService.sendDtmf(TEST_DTMF_TONE);

        verify(mockCall).playDtmfTone(eq((char) TEST_DTMF_TONE));
        verify(mockCall).stopDtmfTone();
        Assert.assertTrue(sentDtmf);
    }

    @Test
    public void headsetSendDTMFNull() {
        boolean sentDtmf = mBluetoothInCallService.sendDtmf(TEST_DTMF_TONE);
        Assert.assertFalse(sentDtmf);
    }

    @Test
    public void getNetworkOperator() {
        PhoneAccount fakePhoneAccount = makeQuickAccount("id0", TEST_ACCOUNT_INDEX);
        doReturn(fakePhoneAccount).when(mMockCallInfo).getBestPhoneAccount();

        String networkOperator = mBluetoothInCallService.getNetworkOperator();
        Assert.assertEquals(networkOperator, "label0");
    }

    @Test
    public void getNetworkOperatorNoPhoneAccount() {
        final String fakeOperator = "label1";
        doReturn(fakeOperator).when(mMockTelephonyManager).getNetworkOperatorName();

        String networkOperator = mBluetoothInCallService.getNetworkOperator();
        Assert.assertEquals(networkOperator, fakeOperator);
    }

    @Test
    public void getSubscriberNumber() {
        PhoneAccount fakePhoneAccount = makeQuickAccount("id0", TEST_ACCOUNT_INDEX);
        doReturn(fakePhoneAccount).when(mMockCallInfo).getBestPhoneAccount();

        String subscriberNumber = mBluetoothInCallService.getSubscriberNumber();
        Assert.assertEquals(subscriberNumber, TEST_ACCOUNT_ADDRESS + TEST_ACCOUNT_INDEX);
    }

    @Test
    public void getSubscriberNumberFallbackToTelephony() {
        final String fakeNumber = "8675309";
        doReturn(fakeNumber).when(mMockTelephonyManager).getLine1Number();

        String subscriberNumber = mBluetoothInCallService.getSubscriberNumber();
        Assert.assertEquals(subscriberNumber, fakeNumber);
    }

    @Test
    public void listCurrentCallsOneCall() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        doReturn(Call.STATE_ACTIVE).when(activeCall).getState();
        doReturn(Uri.parse("tel:555-000")).when(activeCall).getHandle();

        doReturn(List.of(activeCall)).when(mMockCallInfo).getBluetoothCalls();
        mBluetoothInCallService.onCallAdded(activeCall);

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();

        verify(mMockBluetoothHeadset)
                .clccResponse(
                        eq(1),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(false),
                        eq("555000"),
                        eq(PhoneNumberUtils.TOA_Unknown));
        verify(mMockBluetoothHeadset).clccResponse(0, 0, 0, 0, false, null, 0);
    }

    /**
     * Verifies bluetooth call quality reports are properly parceled and set as a call event to
     * Telecom.
     */
    @Test
    public void bluetoothCallQualityReport() {
        BluetoothCall activeCall = createForegroundCall(UUID.randomUUID());
        mBluetoothInCallService.onCallAdded(activeCall);

        mBluetoothInCallService.sendBluetoothCallQualityReport(
                10, // long timestamp
                20, // int rssi
                30, // int snr
                40, // int retransmissionCount
                50, // int packetsNotReceiveCount
                60 // int negativeAcknowledgementCount
                );

        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(activeCall)
                .sendCallEvent(
                        eq(BluetoothCallQualityReport.EVENT_BLUETOOTH_CALL_QUALITY_REPORT),
                        bundleCaptor.capture());
        Bundle bundle = bundleCaptor.getValue();
        BluetoothCallQualityReport report =
                (BluetoothCallQualityReport)
                        bundle.get(BluetoothCallQualityReport.EXTRA_BLUETOOTH_CALL_QUALITY_REPORT);
        Assert.assertEquals(10, report.getSentTimestampMillis());
        Assert.assertEquals(20, report.getRssiDbm());
        Assert.assertEquals(30, report.getSnrDb());
        Assert.assertEquals(40, report.getRetransmittedPacketsCount());
        Assert.assertEquals(50, report.getPacketsNotReceivedCount());
        Assert.assertEquals(60, report.getNegativeAcknowledgementCount());
    }

    @Test
    public void listCurrentCallsSilentRinging() {
        BluetoothCall silentRingingCall = createActiveCall(UUID.randomUUID());
        doReturn(Call.STATE_RINGING).when(silentRingingCall).getState();
        doReturn(true).when(silentRingingCall).isSilentRingingRequested();
        doReturn(Uri.parse("tel:555-000")).when(silentRingingCall).getHandle();

        doReturn(List.of(silentRingingCall)).when(mMockCallInfo).getBluetoothCalls();
        doReturn(silentRingingCall).when(mMockCallInfo).getRingingOrSimulatedRingingCall();
        mBluetoothInCallService.onCallAdded(silentRingingCall);

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();

        verify(mMockBluetoothHeadset, never())
                .clccResponse(
                        eq(1),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(false),
                        eq("555000"),
                        eq(PhoneNumberUtils.TOA_Unknown));
        verify(mMockBluetoothHeadset).clccResponse(0, 0, 0, 0, false, null, 0);
    }

    @Test
    public void conferenceInProgressCDMA() {
        // If two calls are being conferenced and updateHeadsetWithCallState runs while this is
        // still occurring, it will look like there is an active and held BluetoothCall still while
        // we are transitioning into a conference.
        // BluetoothCall has been put into a CDMA "conference" with one BluetoothCall on hold.
        BluetoothCall parentCall = createActiveCall(UUID.randomUUID());
        final BluetoothCall confCall1 = getMockCall(UUID.randomUUID());
        final BluetoothCall confCall2 = createHeldCall(UUID.randomUUID());
        mBluetoothInCallService.onCallAdded(parentCall);
        mBluetoothInCallService.onCallAdded(confCall1);
        mBluetoothInCallService.onCallAdded(confCall2);

        doReturn(List.of(parentCall, confCall1, confCall2)).when(mMockCallInfo).getBluetoothCalls();
        doReturn(Call.STATE_ACTIVE).when(confCall1).getState();
        doReturn(Call.STATE_ACTIVE).when(confCall2).getState();
        doReturn(true).when(confCall2).isIncoming();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0000")))
                .when(confCall1)
                .getGatewayInfo();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0001")))
                .when(confCall2)
                .getGatewayInfo();
        addCallCapability(parentCall, Connection.CAPABILITY_MERGE_CONFERENCE);
        addCallCapability(parentCall, Connection.CAPABILITY_SWAP_CONFERENCE);
        Integer confCall1Id = confCall1.getId();
        doReturn(confCall1Id).when(parentCall).getGenericConferenceActiveChildCallId();
        doReturn(true).when(parentCall).isConference();
        List<Integer> childrenIds = Arrays.asList(confCall1.getId(), confCall2.getId());
        doReturn(childrenIds).when(parentCall).getChildrenIds();
        // Add links from child calls to parent
        Integer parentId = parentCall.getId();
        doReturn(parentId).when(confCall1).getParentId();
        doReturn(parentId).when(confCall2).getParentId();

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.queryPhoneState();
        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(1), eq(1), eq(CALL_STATE_IDLE), eq(""), eq(128), nullable(String.class));

        doReturn(true).when(parentCall).wasConferencePreviouslyMerged();
        List<BluetoothCall> children =
                mBluetoothInCallService.getBluetoothCallsByIds(parentCall.getChildrenIds());
        mBluetoothInCallService.getCallback(parentCall).onChildrenChanged(parentCall, children);
        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(1), eq(0), eq(CALL_STATE_IDLE), eq(""), eq(128), nullable(String.class));

        // Spurious BluetoothCall to onIsConferencedChanged.
        mBluetoothInCallService.getCallback(parentCall).onChildrenChanged(parentCall, children);
        // Make sure the BluetoothCall has only occurred collectively 2 times (not on the third)
        verify(mMockBluetoothHeadset, times(2))
                .phoneStateChanged(
                        any(int.class),
                        any(int.class),
                        any(int.class),
                        nullable(String.class),
                        any(int.class),
                        nullable(String.class));
    }

    @Test
    public void listCurrentCallsCdmaHold() {
        // BluetoothCall has been put into a CDMA "conference" with one BluetoothCall on hold.
        BluetoothCall parentCall = createActiveCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0000")).when(parentCall).getHandle();
        final BluetoothCall foregroundCall = getMockCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0001")).when(foregroundCall).getHandle();
        final BluetoothCall heldCall = createHeldCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0002")).when(heldCall).getHandle();
        mBluetoothInCallService.onCallAdded(parentCall);
        mBluetoothInCallService.onCallAdded(foregroundCall);
        mBluetoothInCallService.onCallAdded(heldCall);

        doReturn(List.of(parentCall, foregroundCall, heldCall))
                .when(mMockCallInfo)
                .getBluetoothCalls();
        doReturn(Call.STATE_ACTIVE).when(foregroundCall).getState();
        doReturn(Call.STATE_ACTIVE).when(heldCall).getState();
        doReturn(true).when(heldCall).isIncoming();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0001")))
                .when(foregroundCall)
                .getGatewayInfo();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0002")))
                .when(heldCall)
                .getGatewayInfo();
        addCallCapability(parentCall, Connection.CAPABILITY_MERGE_CONFERENCE);
        addCallCapability(parentCall, Connection.CAPABILITY_SWAP_CONFERENCE);

        Integer foregroundCallId = foregroundCall.getId();
        doReturn(foregroundCallId).when(parentCall).getGenericConferenceActiveChildCallId();
        doReturn(true).when(parentCall).isConference();
        List<Integer> childrenIds = Arrays.asList(foregroundCall.getId(), heldCall.getId());
        doReturn(childrenIds).when(parentCall).getChildrenIds();
        // Add links from child calls to parent
        Integer parentId = parentCall.getId();
        doReturn(parentId).when(foregroundCall).getParentId();
        doReturn(parentId).when(heldCall).getParentId();
        doReturn(true).when(parentCall).hasProperty(Call.Details.PROPERTY_GENERIC_CONFERENCE);

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();

        verify(mMockBluetoothHeadset)
                .clccResponse(
                        eq(1),
                        eq(0),
                        eq(CALL_STATE_ACTIVE),
                        eq(0),
                        eq(false),
                        eq("5550001"),
                        eq(PhoneNumberUtils.TOA_Unknown));
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        eq(2),
                        eq(1),
                        eq(CALL_STATE_HELD),
                        eq(0),
                        eq(false),
                        eq("5550002"),
                        eq(PhoneNumberUtils.TOA_Unknown));
        verify(mMockBluetoothHeadset).clccResponse(0, 0, 0, 0, false, null, 0);
    }

    @Test
    public void listCurrentCallsCdmaConference() {
        // BluetoothCall is in a true CDMA conference
        BluetoothCall parentCall = createActiveCall(UUID.randomUUID());
        final BluetoothCall confCall1 = getMockCall(UUID.randomUUID());
        final BluetoothCall confCall2 = createHeldCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0000")).when(parentCall).getHandle();
        doReturn(Uri.parse("tel:555-0001")).when(confCall1).getHandle();
        doReturn(Uri.parse("tel:555-0002")).when(confCall2).getHandle();
        mBluetoothInCallService.onCallAdded(parentCall);
        mBluetoothInCallService.onCallAdded(confCall1);
        mBluetoothInCallService.onCallAdded(confCall2);

        doReturn(List.of(parentCall, confCall1, confCall2)).when(mMockCallInfo).getBluetoothCalls();
        doReturn(Call.STATE_ACTIVE).when(confCall1).getState();
        doReturn(Call.STATE_ACTIVE).when(confCall2).getState();
        doReturn(true).when(confCall2).isIncoming();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0000")))
                .when(confCall1)
                .getGatewayInfo();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0001")))
                .when(confCall2)
                .getGatewayInfo();
        doReturn(true).when(parentCall).wasConferencePreviouslyMerged();
        doReturn(true).when(parentCall).isConference();
        List<Integer> childrenIds = Arrays.asList(confCall1.getId(), confCall2.getId());
        doReturn(childrenIds).when(parentCall).getChildrenIds();
        // Add links from child calls to parent
        Integer parentId = parentCall.getId();
        doReturn(parentId).when(confCall1).getParentId();
        doReturn(parentId).when(confCall2).getParentId();
        doReturn(true).when(parentCall).hasProperty(Call.Details.PROPERTY_GENERIC_CONFERENCE);

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();

        verify(mMockBluetoothHeadset)
                .clccResponse(
                        eq(1),
                        eq(0),
                        eq(CALL_STATE_ACTIVE),
                        eq(0),
                        eq(true),
                        eq("5550000"),
                        eq(PhoneNumberUtils.TOA_Unknown));
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        eq(2),
                        eq(1),
                        eq(CALL_STATE_ACTIVE),
                        eq(0),
                        eq(true),
                        eq("5550001"),
                        eq(PhoneNumberUtils.TOA_Unknown));
        verify(mMockBluetoothHeadset).clccResponse(0, 0, 0, 0, false, null, 0);
    }

    @Test
    public void waitingCallClccResponse() {
        BluetoothCall waitingCall = createRingingCall(UUID.randomUUID());
        doReturn(List.of(waitingCall)).when(mMockCallInfo).getBluetoothCalls();
        // This test does not define a value for getForegroundCall(), so this ringing
        // BluetoothCall will be treated as if it is a waiting BluetoothCall
        // when listCurrentCalls() is invoked.
        mBluetoothInCallService.onCallAdded(waitingCall);

        doReturn(true).when(waitingCall).isIncoming();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0000")))
                .when(waitingCall)
                .getGatewayInfo();
        doReturn(Call.STATE_RINGING).when(waitingCall).getState();
        doReturn(Uri.parse("tel:555-0000")).when(waitingCall).getHandle();

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        1,
                        1,
                        CALL_STATE_WAITING,
                        0,
                        false,
                        "5550000",
                        PhoneNumberUtils.TOA_Unknown);
        verify(mMockBluetoothHeadset).clccResponse(0, 0, 0, 0, false, null, 0);
        verify(mMockBluetoothHeadset, times(2))
                .clccResponse(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyBoolean(),
                        nullable(String.class),
                        anyInt());
    }

    @Test
    public void newCallClccResponse() {
        BluetoothCall newCall = createForegroundCall(UUID.randomUUID());
        doReturn(List.of(newCall)).when(mMockCallInfo).getBluetoothCalls();
        mBluetoothInCallService.onCallAdded(newCall);

        doReturn(Call.STATE_NEW).when(newCall).getState();

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset).clccResponse(0, 0, 0, 0, false, null, 0);
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyBoolean(),
                        nullable(String.class),
                        anyInt());
    }

    @Test
    public void listCurrentCallsCallHandleChanged() {
        doReturn("").when(mMockTelephonyManager).getNetworkCountryIso();

        BluetoothCall activeCall = createForegroundCall(UUID.randomUUID());
        doReturn(List.of(activeCall)).when(mMockCallInfo).getBluetoothCalls();
        mBluetoothInCallService.onCallAdded(activeCall);

        doReturn(Call.STATE_ACTIVE).when(activeCall).getState();
        doReturn(true).when(activeCall).isIncoming();
        doReturn(Uri.parse("tel:2135550000")).when(activeCall).getHandle();
        Log.w(TAG, "call handle" + Uri.parse("tel:2135550000"));
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:2135550000")))
                .when(activeCall)
                .getGatewayInfo();

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        1,
                        1,
                        CALL_STATE_ACTIVE,
                        0,
                        false,
                        "2135550000",
                        PhoneNumberUtils.TOA_Unknown);

        // call handle changed
        doReturn(Uri.parse("tel:213-555-0000")).when(activeCall).getHandle();
        clearInvocations(mMockBluetoothHeadset);
        Log.w(TAG, "call handle" + Uri.parse("tel:213-555-0000"));
        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        1,
                        1,
                        CALL_STATE_ACTIVE,
                        0,
                        false,
                        "2135550000",
                        PhoneNumberUtils.TOA_Unknown);
    }

    @Test
    public void ringingCallClccResponse() {
        BluetoothCall ringingCall = createForegroundCall(UUID.randomUUID());
        doReturn(List.of(ringingCall)).when(mMockCallInfo).getBluetoothCalls();
        mBluetoothInCallService.onCallAdded(ringingCall);

        doReturn(Call.STATE_RINGING).when(ringingCall).getState();
        doReturn(true).when(ringingCall).isIncoming();
        doReturn(Uri.parse("tel:555-0000")).when(ringingCall).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0000")))
                .when(ringingCall)
                .getGatewayInfo();

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        1,
                        1,
                        CALL_STATE_INCOMING,
                        0,
                        false,
                        "5550000",
                        PhoneNumberUtils.TOA_Unknown);
        verify(mMockBluetoothHeadset).clccResponse(0, 0, 0, 0, false, null, 0);
        verify(mMockBluetoothHeadset, times(2))
                .clccResponse(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyBoolean(),
                        nullable(String.class),
                        anyInt());
    }

    @Test
    public void callClccCache() {
        List<BluetoothCall> calls = new ArrayList<>();
        doReturn(calls).when(mMockCallInfo).getBluetoothCalls();
        BluetoothCall ringingCall = createForegroundCall(UUID.randomUUID());
        calls.add(ringingCall);
        mBluetoothInCallService.onCallAdded(ringingCall);

        doReturn(Call.STATE_RINGING).when(ringingCall).getState();
        doReturn(true).when(ringingCall).isIncoming();
        doReturn(Uri.parse("tel:5550000")).when(ringingCall).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:5550000")))
                .when(ringingCall)
                .getGatewayInfo();

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        1,
                        1,
                        CALL_STATE_INCOMING,
                        0,
                        false,
                        "5550000",
                        PhoneNumberUtils.TOA_Unknown);

        // Test Caching of old BluetoothCall indices in clcc
        doReturn(Call.STATE_ACTIVE).when(ringingCall).getState();
        BluetoothCall newHoldingCall = createHeldCall(UUID.randomUUID());
        calls.add(0, newHoldingCall);
        mBluetoothInCallService.onCallAdded(newHoldingCall);

        doReturn(Call.STATE_HOLDING).when(newHoldingCall).getState();
        doReturn(true).when(newHoldingCall).isIncoming();
        doReturn(Uri.parse("tel:555-0001")).when(newHoldingCall).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0001")))
                .when(newHoldingCall)
                .getGatewayInfo();

        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        1, 1, CALL_STATE_ACTIVE, 0, false, "5550000", PhoneNumberUtils.TOA_Unknown);
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        2, 1, CALL_STATE_HELD, 0, false, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mMockBluetoothHeadset, times(2)).clccResponse(0, 0, 0, 0, false, null, 0);
    }

    @Test
    public void alertingCallClccResponse() {
        BluetoothCall dialingCall = createForegroundCall(UUID.randomUUID());
        doReturn(List.of(dialingCall)).when(mMockCallInfo).getBluetoothCalls();
        mBluetoothInCallService.onCallAdded(dialingCall);

        doReturn(Call.STATE_DIALING).when(dialingCall).getState();
        doReturn(Uri.parse("tel:555-0000")).when(dialingCall).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0000")))
                .when(dialingCall)
                .getGatewayInfo();

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        1,
                        0,
                        CALL_STATE_ALERTING,
                        0,
                        false,
                        "5550000",
                        PhoneNumberUtils.TOA_Unknown);
        verify(mMockBluetoothHeadset).clccResponse(0, 0, 0, 0, false, null, 0);
        verify(mMockBluetoothHeadset, times(2))
                .clccResponse(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyBoolean(),
                        nullable(String.class),
                        anyInt());
    }

    @Test
    public void holdingCallClccResponse() {
        ArrayList<BluetoothCall> calls = new ArrayList<>();
        doReturn(calls).when(mMockCallInfo).getBluetoothCalls();
        BluetoothCall dialingCall = createForegroundCall(UUID.randomUUID());
        calls.add(dialingCall);
        mBluetoothInCallService.onCallAdded(dialingCall);

        doReturn(Call.STATE_DIALING).when(dialingCall).getState();
        doReturn(Uri.parse("tel:555-0000")).when(dialingCall).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0000")))
                .when(dialingCall)
                .getGatewayInfo();
        BluetoothCall holdingCall = createHeldCall(UUID.randomUUID());
        calls.add(holdingCall);
        mBluetoothInCallService.onCallAdded(holdingCall);

        doReturn(Call.STATE_HOLDING).when(holdingCall).getState();
        doReturn(true).when(holdingCall).isIncoming();
        doReturn(Uri.parse("tel:555-0001")).when(holdingCall).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0001")))
                .when(holdingCall)
                .getGatewayInfo();

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        1,
                        0,
                        CALL_STATE_ALERTING,
                        0,
                        false,
                        "5550000",
                        PhoneNumberUtils.TOA_Unknown);
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        2, 1, CALL_STATE_HELD, 0, false, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mMockBluetoothHeadset).clccResponse(0, 0, 0, 0, false, null, 0);
        verify(mMockBluetoothHeadset, times(3))
                .clccResponse(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyBoolean(),
                        nullable(String.class),
                        anyInt());
    }

    @Test
    public void listCurrentCallsImsConference() {
        BluetoothCall parentCall = createActiveCall(UUID.randomUUID());

        addCallCapability(parentCall, Connection.CAPABILITY_CONFERENCE_HAS_NO_CHILDREN);
        doReturn(true).when(parentCall).isConference();
        doReturn(Call.STATE_ACTIVE).when(parentCall).getState();
        doReturn(true).when(parentCall).isIncoming();
        doReturn(Uri.parse("tel:555-0000")).when(parentCall).getHandle();
        doReturn(List.of(parentCall)).when(mMockCallInfo).getBluetoothCalls();

        mBluetoothInCallService.onCallAdded(parentCall);

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();

        verify(mMockBluetoothHeadset)
                .clccResponse(
                        eq(1),
                        eq(1),
                        eq(CALL_STATE_ACTIVE),
                        eq(0),
                        eq(true),
                        eq("5550000"),
                        eq(129));
        verify(mMockBluetoothHeadset).clccResponse(0, 0, 0, 0, false, null, 0);
    }

    @Test
    public void listCurrentCallsHeldImsCepConference() {
        BluetoothCall parentCall = createHeldCall(UUID.randomUUID());
        BluetoothCall childCall1 = createActiveCall(UUID.randomUUID());
        BluetoothCall childCall2 = createActiveCall(UUID.randomUUID());
        doReturn(List.of(parentCall, childCall1, childCall2))
                .when(mMockCallInfo)
                .getBluetoothCalls();
        doReturn(Uri.parse("tel:555-0000")).when(parentCall).getHandle();
        doReturn(Uri.parse("tel:555-0001")).when(childCall1).getHandle();
        doReturn(Uri.parse("tel:555-0002")).when(childCall2).getHandle();

        mBluetoothInCallService.onCallAdded(parentCall);
        mBluetoothInCallService.onCallAdded(childCall1);
        mBluetoothInCallService.onCallAdded(childCall2);

        addCallCapability(parentCall, Connection.CAPABILITY_MANAGE_CONFERENCE);
        Integer parentId = parentCall.getId();
        doReturn(parentId).when(childCall1).getParentId();
        doReturn(parentId).when(childCall2).getParentId();
        List<Integer> childrenIds = Arrays.asList(childCall1.getId(), childCall2.getId());
        doReturn(childrenIds).when(parentCall).getChildrenIds();

        doReturn(true).when(parentCall).isConference();
        doReturn(Call.STATE_HOLDING).when(parentCall).getState();
        doReturn(Call.STATE_ACTIVE).when(childCall1).getState();
        doReturn(Call.STATE_ACTIVE).when(childCall2).getState();
        doReturn(true).when(parentCall).hasProperty(Call.Details.PROPERTY_GENERIC_CONFERENCE);
        doReturn(true).when(parentCall).isIncoming();

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();

        verify(mMockBluetoothHeadset)
                .clccResponse(
                        eq(1),
                        eq(0),
                        eq(CALL_STATE_HELD),
                        eq(0),
                        eq(true),
                        eq("5550001"),
                        eq(PhoneNumberUtils.TOA_Unknown));
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        eq(2),
                        eq(0),
                        eq(CALL_STATE_HELD),
                        eq(0),
                        eq(true),
                        eq("5550002"),
                        eq(PhoneNumberUtils.TOA_Unknown));
        verify(mMockBluetoothHeadset).clccResponse(0, 0, 0, 0, false, null, 0);
    }

    @Test
    public void listCurrentCallsConferenceGetChildrenIsEmpty() {
        BluetoothCall conferenceCall = createActiveCall(UUID.randomUUID());
        doReturn(List.of(conferenceCall)).when(mMockCallInfo).getBluetoothCalls();
        doReturn(Uri.parse("tel:555-1234")).when(conferenceCall).getHandle();

        addCallCapability(conferenceCall, Connection.CAPABILITY_MANAGE_CONFERENCE);
        doReturn(true).when(conferenceCall).isConference();
        doReturn(Call.STATE_ACTIVE).when(conferenceCall).getState();
        doReturn(true).when(conferenceCall).hasProperty(Call.Details.PROPERTY_GENERIC_CONFERENCE);
        doReturn(true).when(conferenceCall).isIncoming();

        mBluetoothInCallService.onCallAdded(conferenceCall);

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset)
                .clccResponse(eq(1), eq(1), eq(0), eq(0), eq(true), eq("5551234"), eq(129));
    }

    @Test
    public void listCurrentCallsConferenceEmptyChildrenInference() {
        doReturn("").when(mMockTelephonyManager).getNetworkCountryIso();

        List<BluetoothCall> calls = new ArrayList<>();
        doReturn(calls).when(mMockCallInfo).getBluetoothCalls();

        // active call is added
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        calls.add(activeCall);
        mBluetoothInCallService.onCallAdded(activeCall);

        doReturn(Call.STATE_ACTIVE).when(activeCall).getState();
        doReturn(Uri.parse("tel:555-0001")).when(activeCall).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0001")))
                .when(activeCall)
                .getGatewayInfo();

        // holding call is added
        BluetoothCall holdingCall = createHeldCall(UUID.randomUUID());
        calls.add(holdingCall);
        mBluetoothInCallService.onCallAdded(holdingCall);

        doReturn(Call.STATE_HOLDING).when(holdingCall).getState();
        doReturn(true).when(holdingCall).isIncoming();
        doReturn(Uri.parse("tel:555-0002")).when(holdingCall).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0002")))
                .when(holdingCall)
                .getGatewayInfo();

        // needs to have at least one CLCC response before merge to enable call inference
        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        1, 0, CALL_STATE_ACTIVE, 0, false, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        2, 1, CALL_STATE_HELD, 0, false, "5550002", PhoneNumberUtils.TOA_Unknown);
        calls.clear();

        // calls merged for conference call
        DisconnectCause cause = new DisconnectCause(DisconnectCause.OTHER);
        doReturn(cause).when(activeCall).getDisconnectCause();
        doReturn(cause).when(holdingCall).getDisconnectCause();
        mBluetoothInCallService.onCallRemoved(activeCall, true);
        mBluetoothInCallService.onCallRemoved(holdingCall, true);

        BluetoothCall conferenceCall = createActiveCall(UUID.randomUUID());
        addCallCapability(conferenceCall, Connection.CAPABILITY_MANAGE_CONFERENCE);

        doReturn(Uri.parse("tel:555-1234")).when(conferenceCall).getHandle();
        doReturn(true).when(conferenceCall).isConference();
        doReturn(Call.STATE_ACTIVE).when(conferenceCall).getState();
        doReturn(true).when(conferenceCall).hasProperty(Call.Details.PROPERTY_GENERIC_CONFERENCE);
        doReturn(true).when(conferenceCall).isIncoming();
        doReturn(calls).when(mMockCallInfo).getBluetoothCalls();

        // parent call arrived, but children have not, then do inference on children
        calls.add(conferenceCall);
        Assert.assertEquals(calls.size(), 1);
        mBluetoothInCallService.onCallAdded(conferenceCall);

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        1, 0, CALL_STATE_ACTIVE, 0, true, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        2, 1, CALL_STATE_ACTIVE, 0, true, "5550002", PhoneNumberUtils.TOA_Unknown);

        // real children arrive, no change on CLCC response
        calls.add(activeCall);
        mBluetoothInCallService.onCallAdded(activeCall);
        doReturn(true).when(activeCall).isConference();
        calls.add(holdingCall);
        mBluetoothInCallService.onCallAdded(holdingCall);
        doReturn(Call.STATE_ACTIVE).when(holdingCall).getState();
        doReturn(true).when(holdingCall).isConference();
        doReturn(List.of(1, 2)).when(conferenceCall).getChildrenIds();

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        1, 0, CALL_STATE_ACTIVE, 0, true, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        2, 1, CALL_STATE_ACTIVE, 0, true, "5550002", PhoneNumberUtils.TOA_Unknown);

        // when call is terminated, children first removed, then parent
        cause = new DisconnectCause(DisconnectCause.LOCAL);
        doReturn(cause).when(activeCall).getDisconnectCause();
        doReturn(cause).when(holdingCall).getDisconnectCause();
        mBluetoothInCallService.onCallRemoved(activeCall, true);
        mBluetoothInCallService.onCallRemoved(holdingCall, true);
        calls.remove(activeCall);
        calls.remove(holdingCall);
        Assert.assertEquals(calls.size(), 1);

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset).clccResponse(0, 0, 0, 0, false, null, 0);
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyBoolean(),
                        nullable(String.class),
                        anyInt());

        // when parent is removed
        doReturn(cause).when(conferenceCall).getDisconnectCause();
        calls.remove(conferenceCall);
        mBluetoothInCallService.onCallRemoved(conferenceCall, true);

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset).clccResponse(0, 0, 0, 0, false, null, 0);
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyBoolean(),
                        nullable(String.class),
                        anyInt());
    }

    @Test
    public void conferenceLastCallIndexIsMaintained() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_MAINTAIN_CALL_INDEX_AFTER_CONFERENCE);
        doReturn("").when(mMockTelephonyManager).getNetworkCountryIso();

        List<BluetoothCall> calls = new ArrayList<>();
        doReturn(calls).when(mMockCallInfo).getBluetoothCalls();

        // Call 1 active call is added
        BluetoothCall activeCall_1 = createActiveCall(UUID.randomUUID());
        calls.add(activeCall_1);
        mBluetoothInCallService.onCallAdded(activeCall_1);

        doReturn(Call.STATE_ACTIVE).when(activeCall_1).getState();
        doReturn(Uri.parse("tel:555-0001")).when(activeCall_1).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0001")))
                .when(activeCall_1)
                .getGatewayInfo();

        // Call 2 holding call is added
        BluetoothCall activeCall_2 = createHeldCall(UUID.randomUUID());
        calls.add(activeCall_2);
        mBluetoothInCallService.onCallAdded(activeCall_2);

        doReturn(Call.STATE_HOLDING).when(activeCall_2).getState();
        doReturn(true).when(activeCall_2).isIncoming();
        doReturn(Uri.parse("tel:555-0002")).when(activeCall_2).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0002")))
                .when(activeCall_2)
                .getGatewayInfo();

        // needs to have at least one CLCC response before merge to enable call inference
        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        1, 0, CALL_STATE_ACTIVE, 0, false, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        2, 1, CALL_STATE_HELD, 0, false, "5550002", PhoneNumberUtils.TOA_Unknown);
        calls.clear();

        // calls merged for conference call
        DisconnectCause cause =
                new DisconnectCause(DisconnectCause.OTHER, "IMS_MERGED_SUCCESSFULLY");
        doReturn(cause).when(activeCall_1).getDisconnectCause();
        doReturn(cause).when(activeCall_2).getDisconnectCause();
        mBluetoothInCallService.onCallRemoved(activeCall_1, true);
        mBluetoothInCallService.onCallRemoved(activeCall_2, true);

        BluetoothCall conferenceCall = createActiveCall(UUID.randomUUID());
        addCallCapability(conferenceCall, Connection.CAPABILITY_MANAGE_CONFERENCE);

        doReturn(Uri.parse("tel:555-1234")).when(conferenceCall).getHandle();
        doReturn(true).when(conferenceCall).isConference();
        doReturn(Call.STATE_ACTIVE).when(conferenceCall).getState();
        doReturn(true).when(conferenceCall).hasProperty(Call.Details.PROPERTY_GENERIC_CONFERENCE);
        doReturn(true).when(conferenceCall).isIncoming();
        doReturn(calls).when(mMockCallInfo).getBluetoothCalls();

        // parent call arrived, but children have not, then do inference on children
        calls.add(conferenceCall);
        Assert.assertEquals(calls.size(), 1);
        mBluetoothInCallService.onCallAdded(conferenceCall);

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        1, 0, CALL_STATE_ACTIVE, 0, true, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        2, 1, CALL_STATE_ACTIVE, 0, true, "5550002", PhoneNumberUtils.TOA_Unknown);

        // real children arrive, no change on CLCC response
        calls.add(activeCall_1);
        mBluetoothInCallService.onCallAdded(activeCall_1);
        doReturn(true).when(activeCall_1).isConference();
        calls.add(activeCall_2);
        mBluetoothInCallService.onCallAdded(activeCall_2);
        doReturn(Call.STATE_ACTIVE).when(activeCall_2).getState();
        doReturn(true).when(activeCall_2).isConference();
        doReturn(List.of(1, 2)).when(conferenceCall).getChildrenIds();

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        1, 0, CALL_STATE_ACTIVE, 0, true, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        2, 1, CALL_STATE_ACTIVE, 0, true, "5550002", PhoneNumberUtils.TOA_Unknown);

        // Call 1 Disconnected and removed from conf
        doReturn(Call.STATE_DISCONNECTED).when(activeCall_1).getState();
        cause = new DisconnectCause(DisconnectCause.OTHER);
        doReturn(cause).when(activeCall_1).getDisconnectCause();
        mBluetoothInCallService.onCallRemoved(activeCall_1, true);
        doReturn(false).when(activeCall_1).isConference();
        calls.remove(activeCall_1);
        Assert.assertEquals(calls.size(), 2);

        // Call 2 removed from conf
        doReturn(cause).when(activeCall_2).getDisconnectCause();
        mBluetoothInCallService.onCallRemoved(activeCall_2, true);
        doReturn(false).when(activeCall_2).isConference();

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.listCurrentCalls();

        // Index 2 is retained
        verify(mMockBluetoothHeadset)
                .clccResponse(
                        2, 1, CALL_STATE_ACTIVE, 0, false, "5550002", PhoneNumberUtils.TOA_Unknown);
    }

    @Test
    public void queryPhoneState() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:5550000")).when(ringingCall).getHandle();

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.queryPhoneState();

        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(0),
                        eq(0),
                        eq(CALL_STATE_INCOMING),
                        eq("5550000"),
                        eq(PhoneNumberUtils.TOA_Unknown),
                        nullable(String.class));
    }

    @Test
    public void cDMAConferenceQueryState() {
        BluetoothCall parentConfCall = createActiveCall(UUID.randomUUID());
        final BluetoothCall confCall1 = getMockCall(UUID.randomUUID());
        final BluetoothCall confCall2 = getMockCall(UUID.randomUUID());
        mBluetoothInCallService.onCallAdded(confCall1);
        mBluetoothInCallService.onCallAdded(confCall2);
        doReturn(Uri.parse("tel:555-0000")).when(parentConfCall).getHandle();
        addCallCapability(parentConfCall, Connection.CAPABILITY_SWAP_CONFERENCE);
        doReturn(true).when(parentConfCall).wasConferencePreviouslyMerged();
        doReturn(true).when(parentConfCall).isConference();
        List<Integer> childrenIds = Arrays.asList(confCall1.getId(), confCall2.getId());
        doReturn(childrenIds).when(parentConfCall).getChildrenIds();

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.queryPhoneState();
        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(1), eq(0), eq(CALL_STATE_IDLE), eq(""), eq(128), nullable(String.class));
    }

    @Test
    public void processChldTypeReleaseHeldRinging() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());
        Log.i("BluetoothInCallService", "asdf start " + Integer.toString(ringingCall.hashCode()));

        boolean didProcess = mBluetoothInCallService.processChld(CHLD_TYPE_RELEASEHELD);

        verify(ringingCall).reject(eq(false), nullable(String.class));
        Assert.assertTrue(didProcess);
    }

    @Test
    public void processChldTypeReleaseHeldHold() {
        BluetoothCall onHoldCall = createHeldCall(UUID.randomUUID());
        boolean didProcess = mBluetoothInCallService.processChld(CHLD_TYPE_RELEASEHELD);

        verify(onHoldCall).disconnect();
        Assert.assertTrue(didProcess);
    }

    @Test
    public void processChldReleaseActiveRinging() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());

        boolean didProcess =
                mBluetoothInCallService.processChld(CHLD_TYPE_RELEASEACTIVE_ACCEPTHELD);

        verify(activeCall).disconnect();
        verify(ringingCall).answer(any(int.class));
        Assert.assertTrue(didProcess);
    }

    @Test
    public void processChldReleaseActiveHold() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());

        boolean didProcess =
                mBluetoothInCallService.processChld(CHLD_TYPE_RELEASEACTIVE_ACCEPTHELD);

        verify(activeCall).disconnect();
        // BluetoothCall unhold will occur as part of CallsManager auto-unholding
        // the background BluetoothCall on its own.
        Assert.assertTrue(didProcess);
    }

    @Test
    public void processChldHoldActiveRinging() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());

        boolean didProcess = mBluetoothInCallService.processChld(CHLD_TYPE_HOLDACTIVE_ACCEPTHELD);

        verify(ringingCall).answer(any(int.class));
        Assert.assertTrue(didProcess);
    }

    @Test
    public void processChldHoldActiveUnhold() {
        BluetoothCall heldCall = createHeldCall(UUID.randomUUID());

        boolean didProcess = mBluetoothInCallService.processChld(CHLD_TYPE_HOLDACTIVE_ACCEPTHELD);

        verify(heldCall).unhold();
        Assert.assertTrue(didProcess);
    }

    @Test
    public void processChldHoldActiveHold() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        addCallCapability(activeCall, Connection.CAPABILITY_HOLD);

        boolean didProcess = mBluetoothInCallService.processChld(CHLD_TYPE_HOLDACTIVE_ACCEPTHELD);

        verify(activeCall).hold();
        Assert.assertTrue(didProcess);
    }

    @Test
    public void processChldAddHeldToConfHolding() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        addCallCapability(activeCall, Connection.CAPABILITY_MERGE_CONFERENCE);

        boolean didProcess = mBluetoothInCallService.processChld(CHLD_TYPE_ADDHELDTOCONF);

        verify(activeCall).mergeConference();
        Assert.assertTrue(didProcess);
    }

    @Test
    public void processChldAddHeldToConf() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        BluetoothCall conferenceableCall = getMockCall(UUID.randomUUID());
        ArrayList<Integer> conferenceableCalls = new ArrayList<>();
        conferenceableCalls.add(conferenceableCall.getId());
        mBluetoothInCallService.onCallAdded(conferenceableCall);

        doReturn(conferenceableCalls).when(activeCall).getConferenceableCalls();

        boolean didProcess = mBluetoothInCallService.processChld(CHLD_TYPE_ADDHELDTOCONF);

        verify(activeCall).conference(conferenceableCall);
        Assert.assertTrue(didProcess);
    }

    @Test
    public void processChldHoldActiveSwapConference() {
        // Create an active CDMA BluetoothCall with a BluetoothCall on hold
        // and simulate a swapConference().
        BluetoothCall parentCall = createActiveCall(UUID.randomUUID());
        final BluetoothCall foregroundCall = getMockCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0001")).when(foregroundCall).getHandle();
        final BluetoothCall heldCall = createHeldCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0002")).when(heldCall).getHandle();
        addCallCapability(parentCall, Connection.CAPABILITY_SWAP_CONFERENCE);
        doReturn(true).when(parentCall).isConference();
        doReturn(Uri.parse("tel:555-0000")).when(heldCall).getHandle();
        List<Integer> childrenIds = Arrays.asList(foregroundCall.getId(), heldCall.getId());
        doReturn(childrenIds).when(parentCall).getChildrenIds();

        clearInvocations(mMockBluetoothHeadset);
        boolean didProcess = mBluetoothInCallService.processChld(CHLD_TYPE_HOLDACTIVE_ACCEPTHELD);

        verify(parentCall).swapConference();
        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(1), eq(1), eq(CALL_STATE_IDLE), eq(""), eq(128), nullable(String.class));
        Assert.assertTrue(didProcess);
    }

    // Testing the CallsManager Listener Functionality on Bluetooth
    @Test
    public void onCallAddedRinging() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555000")).when(ringingCall).getHandle();

        mBluetoothInCallService.onCallAdded(ringingCall);

        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(0),
                        eq(0),
                        eq(CALL_STATE_INCOMING),
                        eq("555000"),
                        eq(PhoneNumberUtils.TOA_Unknown),
                        nullable(String.class));
    }

    @Test
    public void silentRingingCallState() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());
        doReturn(true).when(ringingCall).isSilentRingingRequested();
        doReturn(Uri.parse("tel:555000")).when(ringingCall).getHandle();

        mBluetoothInCallService.onCallAdded(ringingCall);

        verify(mMockBluetoothHeadset, never())
                .phoneStateChanged(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyString(),
                        anyInt(),
                        nullable(String.class));
    }

    @Test
    public void onCallAddedCdmaActiveHold() {
        // BluetoothCall has been put into a CDMA "conference" with one BluetoothCall on hold.
        BluetoothCall parentCall = createActiveCall(UUID.randomUUID());
        final BluetoothCall foregroundCall = getMockCall(UUID.randomUUID());
        final BluetoothCall heldCall = createHeldCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0001")).when(foregroundCall).getHandle();
        doReturn(Uri.parse("tel:555-0002")).when(heldCall).getHandle();
        addCallCapability(parentCall, Connection.CAPABILITY_MERGE_CONFERENCE);
        doReturn(true).when(parentCall).isConference();
        List<Integer> childrenIds = Arrays.asList(foregroundCall.getId(), heldCall.getId());
        doReturn(childrenIds).when(parentCall).getChildrenIds();

        mBluetoothInCallService.onCallAdded(parentCall);

        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(1), eq(1), eq(CALL_STATE_IDLE), eq(""), eq(128), nullable(String.class));
    }

    @Test
    public void onCallRemoved() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        mBluetoothInCallService.onCallAdded(activeCall);
        doReturn(null).when(mMockCallInfo).getActiveCall();
        doReturn(Uri.parse("tel:555-0001")).when(activeCall).getHandle();

        mBluetoothInCallService.onCallRemoved(activeCall, true /* forceRemoveCallback */);

        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(0), eq(0), eq(CALL_STATE_IDLE), eq(""), eq(128), nullable(String.class));
    }

    @Test
    public void onDetailsChangeExternalRemovesCall() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        mBluetoothInCallService.onCallAdded(activeCall);
        doReturn(null).when(mMockCallInfo).getActiveCall();
        doReturn(Uri.parse("tel:555-0001")).when(activeCall).getHandle();

        doReturn(true).when(activeCall).isExternalCall();
        mBluetoothInCallService.getCallback(activeCall).onDetailsChanged(activeCall, null);

        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(0), eq(0), eq(CALL_STATE_IDLE), eq(""), eq(128), nullable(String.class));
    }

    @Test
    public void onDetailsChangeExternalAddsCall() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        mBluetoothInCallService.onCallAdded(activeCall);
        doReturn(Uri.parse("tel:555-0001")).when(activeCall).getHandle();
        BluetoothInCallService.CallStateCallback callBack =
                mBluetoothInCallService.getCallback(activeCall);

        doReturn(true).when(activeCall).isExternalCall();
        callBack.onDetailsChanged(activeCall, null);

        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(1), eq(0), eq(CALL_STATE_IDLE), eq(""), eq(128), nullable(String.class));
    }

    @Test
    public void onCallStateChangedConnectingCall() {
        BluetoothCall activeCall = getMockCall(UUID.randomUUID());
        BluetoothCall connectingCall = getMockCall(UUID.randomUUID());
        doReturn(Call.STATE_CONNECTING).when(connectingCall).getState();

        doReturn(List.of(connectingCall, activeCall)).when(mMockCallInfo).getBluetoothCalls();

        mBluetoothInCallService.onCallAdded(connectingCall);
        mBluetoothInCallService.onCallAdded(activeCall);

        mBluetoothInCallService
                .getCallback(activeCall)
                .onStateChanged(activeCall, Call.STATE_HOLDING);

        verify(mMockBluetoothHeadset, never())
                .phoneStateChanged(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyString(),
                        anyInt(),
                        nullable(String.class));
    }

    @Test
    public void onCallAddedAudioProcessing() {
        BluetoothCall call = getMockCall(UUID.randomUUID());
        doReturn(Call.STATE_AUDIO_PROCESSING).when(call).getState();
        mBluetoothInCallService.onCallAdded(call);

        verify(mMockBluetoothHeadset, never())
                .phoneStateChanged(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyString(),
                        anyInt(),
                        nullable(String.class));
    }

    @Test
    public void onCallStateChangedRingingToAudioProcessing() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555000")).when(ringingCall).getHandle();

        mBluetoothInCallService.onCallAdded(ringingCall);

        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(0),
                        eq(0),
                        eq(CALL_STATE_INCOMING),
                        eq("555000"),
                        eq(PhoneNumberUtils.TOA_Unknown),
                        nullable(String.class));

        doReturn(Call.STATE_AUDIO_PROCESSING).when(ringingCall).getState();
        doReturn(null).when(mMockCallInfo).getRingingOrSimulatedRingingCall();

        mBluetoothInCallService
                .getCallback(ringingCall)
                .onStateChanged(ringingCall, Call.STATE_AUDIO_PROCESSING);

        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(0), eq(0), eq(CALL_STATE_IDLE), eq(""), eq(128), nullable(String.class));
    }

    @Test
    public void onCallStateChangedAudioProcessingToSimulatedRinging() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0000")).when(ringingCall).getHandle();
        mBluetoothInCallService.onCallAdded(ringingCall);
        mBluetoothInCallService
                .getCallback(ringingCall)
                .onStateChanged(ringingCall, Call.STATE_SIMULATED_RINGING);

        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(0),
                        eq(0),
                        eq(CALL_STATE_INCOMING),
                        eq("555-0000"),
                        eq(PhoneNumberUtils.TOA_Unknown),
                        nullable(String.class));
    }

    @Test
    public void onCallStateChangedAudioProcessingToActive() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        doReturn(Call.STATE_ACTIVE).when(activeCall).getState();
        mBluetoothInCallService.onCallAdded(activeCall);
        mBluetoothInCallService
                .getCallback(activeCall)
                .onStateChanged(activeCall, Call.STATE_ACTIVE);

        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(1), eq(0), eq(CALL_STATE_IDLE), eq(""), eq(128), nullable(String.class));
    }

    @Test
    public void onCallStateChangedDialing() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());

        // make "mLastState" STATE_CONNECTING
        BluetoothInCallService.CallStateCallback callback =
                mBluetoothInCallService.new CallStateCallback(Call.STATE_CONNECTING);
        mBluetoothInCallService.mCallbacks.put(activeCall.getId(), callback);

        mBluetoothInCallService
                .mCallbacks
                .get(activeCall.getId())
                .onStateChanged(activeCall, Call.STATE_DIALING);

        verify(mMockBluetoothHeadset, never())
                .phoneStateChanged(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyString(),
                        anyInt(),
                        nullable(String.class));
    }

    @Test
    public void onCallStateChangedAlerting() {
        BluetoothCall outgoingCall = createOutgoingCall(UUID.randomUUID());
        mBluetoothInCallService.onCallAdded(outgoingCall);
        mBluetoothInCallService
                .getCallback(outgoingCall)
                .onStateChanged(outgoingCall, Call.STATE_DIALING);

        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(0),
                        eq(0),
                        eq(CALL_STATE_DIALING),
                        eq(""),
                        eq(128),
                        nullable(String.class));
        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(0),
                        eq(0),
                        eq(CALL_STATE_ALERTING),
                        eq(""),
                        eq(128),
                        nullable(String.class));
    }

    @Test
    public void onCallStateChangedDisconnected() {
        BluetoothCall disconnectedCall = createDisconnectedCall(UUID.randomUUID());
        doReturn(true).when(mMockCallInfo).hasOnlyDisconnectedCalls();
        mBluetoothInCallService.onCallAdded(disconnectedCall);
        mBluetoothInCallService
                .getCallback(disconnectedCall)
                .onStateChanged(disconnectedCall, Call.STATE_DISCONNECTED);
        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(0),
                        eq(0),
                        eq(CALL_STATE_DISCONNECTED),
                        eq(""),
                        eq(128),
                        nullable(String.class));
    }

    @Test
    public void onCallStateChanged() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0000")).when(ringingCall).getHandle();
        mBluetoothInCallService.onCallAdded(ringingCall);

        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(0),
                        eq(0),
                        eq(CALL_STATE_INCOMING),
                        eq("555-0000"),
                        eq(PhoneNumberUtils.TOA_Unknown),
                        nullable(String.class));

        // Switch to active
        doReturn(null).when(mMockCallInfo).getRingingOrSimulatedRingingCall();
        doReturn(ringingCall).when(mMockCallInfo).getActiveCall();

        mBluetoothInCallService
                .getCallback(ringingCall)
                .onStateChanged(ringingCall, Call.STATE_ACTIVE);

        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(1), eq(0), eq(CALL_STATE_IDLE), eq(""), eq(128), nullable(String.class));
    }

    @Test
    public void onCallStateChangedGSMSwap() {
        BluetoothCall heldCall = createHeldCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0000")).when(heldCall).getHandle();
        mBluetoothInCallService.onCallAdded(heldCall);
        doReturn(2).when(mMockCallInfo).getNumHeldCalls();

        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.getCallback(heldCall).onStateChanged(heldCall, Call.STATE_HOLDING);

        verify(mMockBluetoothHeadset, never())
                .phoneStateChanged(
                        eq(0),
                        eq(2),
                        eq(CALL_STATE_HELD),
                        eq("5550000"),
                        eq(PhoneNumberUtils.TOA_Unknown),
                        nullable(String.class));
    }

    @Test
    public void onParentOnChildrenChanged() {
        // Start with two calls that are being merged into a CDMA conference call. The
        // onIsConferencedChanged method will be called multiple times during the call. Make sure
        // that the bluetooth phone state is updated properly.
        BluetoothCall parentCall = createActiveCall(UUID.randomUUID());
        BluetoothCall activeCall = getMockCall(UUID.randomUUID());
        BluetoothCall heldCall = createHeldCall(UUID.randomUUID());
        mBluetoothInCallService.onCallAdded(parentCall);
        mBluetoothInCallService.onCallAdded(activeCall);
        mBluetoothInCallService.onCallAdded(heldCall);
        Integer parentId = parentCall.getId();
        doReturn(parentId).when(activeCall).getParentId();
        doReturn(parentId).when(heldCall).getParentId();

        List<Integer> calls = new ArrayList<>();
        calls.add(activeCall.getId());

        doReturn(calls).when(parentCall).getChildrenIds();
        doReturn(true).when(parentCall).isConference();

        addCallCapability(parentCall, Connection.CAPABILITY_SWAP_CONFERENCE);

        clearInvocations(mMockBluetoothHeadset);
        // Be sure that onIsConferencedChanged rejects spurious changes during set up of
        // CDMA "conference"
        mBluetoothInCallService.getCallback(activeCall).onParentChanged(activeCall);
        verify(mMockBluetoothHeadset, never())
                .phoneStateChanged(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyString(),
                        anyInt(),
                        nullable(String.class));

        mBluetoothInCallService.getCallback(heldCall).onParentChanged(heldCall);
        verify(mMockBluetoothHeadset, never())
                .phoneStateChanged(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyString(),
                        anyInt(),
                        nullable(String.class));

        mBluetoothInCallService
                .getCallback(parentCall)
                .onChildrenChanged(
                        parentCall, mBluetoothInCallService.getBluetoothCallsByIds(calls));
        verify(mMockBluetoothHeadset, never())
                .phoneStateChanged(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyString(),
                        anyInt(),
                        nullable(String.class));

        calls.add(heldCall.getId());
        mBluetoothInCallService.onCallAdded(heldCall);
        mBluetoothInCallService
                .getCallback(parentCall)
                .onChildrenChanged(
                        parentCall, mBluetoothInCallService.getBluetoothCallsByIds(calls));
        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(1), eq(1), eq(CALL_STATE_IDLE), eq(""), eq(128), nullable(String.class));
    }

    @Test
    public void bluetoothAdapterReceiver() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:5550000")).when(ringingCall).getHandle();

        Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
        intent.putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON);
        clearInvocations(mMockBluetoothHeadset);
        mBluetoothInCallService.mBluetoothAdapterReceiver =
                mBluetoothInCallService.new BluetoothAdapterReceiver();
        mBluetoothInCallService.mBluetoothAdapterReceiver.onReceive(
                mBluetoothInCallService, intent);

        verify(mMockBluetoothHeadset)
                .phoneStateChanged(
                        eq(0),
                        eq(0),
                        eq(CALL_STATE_INCOMING),
                        eq("5550000"),
                        eq(PhoneNumberUtils.TOA_Unknown),
                        nullable(String.class));
    }

    @Test
    public void clear() {
        mBluetoothInCallService.clear();

        Assert.assertNull(mBluetoothInCallService.mBluetoothAdapterReceiver);
        Assert.assertNull(mBluetoothInCallService.mBluetoothHeadset);
    }

    @Test
    public void getBearerTechnology() {

        doReturn(TelephonyManager.NETWORK_TYPE_GSM)
                .when(mMockTelephonyManager)
                .getDataNetworkType();
        Assert.assertEquals(
                mBluetoothInCallService.getBearerTechnology(),
                BluetoothLeCallControlProxy.BEARER_TECHNOLOGY_GSM);

        doReturn(TelephonyManager.NETWORK_TYPE_GPRS)
                .when(mMockTelephonyManager)
                .getDataNetworkType();
        Assert.assertEquals(
                mBluetoothInCallService.getBearerTechnology(),
                BluetoothLeCallControlProxy.BEARER_TECHNOLOGY_2G);

        doReturn(TelephonyManager.NETWORK_TYPE_EVDO_B)
                .when(mMockTelephonyManager)
                .getDataNetworkType();
        Assert.assertEquals(
                mBluetoothInCallService.getBearerTechnology(),
                BluetoothLeCallControlProxy.BEARER_TECHNOLOGY_3G);

        doReturn(TelephonyManager.NETWORK_TYPE_TD_SCDMA)
                .when(mMockTelephonyManager)
                .getDataNetworkType();
        Assert.assertEquals(
                mBluetoothInCallService.getBearerTechnology(),
                BluetoothLeCallControlProxy.BEARER_TECHNOLOGY_WCDMA);

        doReturn(TelephonyManager.NETWORK_TYPE_LTE)
                .when(mMockTelephonyManager)
                .getDataNetworkType();
        Assert.assertEquals(
                mBluetoothInCallService.getBearerTechnology(),
                BluetoothLeCallControlProxy.BEARER_TECHNOLOGY_LTE);

        doReturn(TelephonyManager.NETWORK_TYPE_1xRTT)
                .when(mMockTelephonyManager)
                .getDataNetworkType();
        Assert.assertEquals(
                mBluetoothInCallService.getBearerTechnology(),
                BluetoothLeCallControlProxy.BEARER_TECHNOLOGY_CDMA);

        doReturn(TelephonyManager.NETWORK_TYPE_HSPAP)
                .when(mMockTelephonyManager)
                .getDataNetworkType();
        Assert.assertEquals(
                mBluetoothInCallService.getBearerTechnology(),
                BluetoothLeCallControlProxy.BEARER_TECHNOLOGY_4G);

        doReturn(TelephonyManager.NETWORK_TYPE_IWLAN)
                .when(mMockTelephonyManager)
                .getDataNetworkType();
        Assert.assertEquals(
                mBluetoothInCallService.getBearerTechnology(),
                BluetoothLeCallControlProxy.BEARER_TECHNOLOGY_WIFI);

        doReturn(TelephonyManager.NETWORK_TYPE_NR).when(mMockTelephonyManager).getDataNetworkType();
        Assert.assertEquals(
                mBluetoothInCallService.getBearerTechnology(),
                BluetoothLeCallControlProxy.BEARER_TECHNOLOGY_5G);

        doReturn(TelephonyManager.NETWORK_TYPE_LTE_CA)
                .when(mMockTelephonyManager)
                .getDataNetworkType();
        Assert.assertEquals(
                mBluetoothInCallService.getBearerTechnology(),
                BluetoothLeCallControlProxy.BEARER_TECHNOLOGY_GSM);
    }

    @Test
    public void getTbsTerminationReason() {
        BluetoothCall call = getMockCall(UUID.randomUUID());

        Assert.assertEquals(
                mBluetoothInCallService.getTbsTerminationReason(call),
                BluetoothLeCallControl.TERMINATION_REASON_FAIL);

        DisconnectCause cause = new DisconnectCause(DisconnectCause.BUSY, null, null, null, 1);
        doReturn(cause).when(call).getDisconnectCause();
        Assert.assertEquals(
                mBluetoothInCallService.getTbsTerminationReason(call),
                BluetoothLeCallControl.TERMINATION_REASON_LINE_BUSY);

        cause = new DisconnectCause(DisconnectCause.REJECTED, null, null, null, 1);
        doReturn(cause).when(call).getDisconnectCause();
        Assert.assertEquals(
                mBluetoothInCallService.getTbsTerminationReason(call),
                BluetoothLeCallControl.TERMINATION_REASON_REMOTE_HANGUP);

        cause = new DisconnectCause(DisconnectCause.LOCAL, null, null, null, 1);
        doReturn(cause).when(call).getDisconnectCause();
        mBluetoothInCallService.mIsTerminatedByClient = false;
        Assert.assertEquals(
                mBluetoothInCallService.getTbsTerminationReason(call),
                BluetoothLeCallControl.TERMINATION_REASON_SERVER_HANGUP);

        cause = new DisconnectCause(DisconnectCause.LOCAL, null, null, null, 1);
        doReturn(cause).when(call).getDisconnectCause();
        mBluetoothInCallService.mIsTerminatedByClient = true;
        Assert.assertEquals(
                mBluetoothInCallService.getTbsTerminationReason(call),
                BluetoothLeCallControl.TERMINATION_REASON_CLIENT_HANGUP);

        cause = new DisconnectCause(DisconnectCause.ERROR, null, null, null, 1);
        doReturn(cause).when(call).getDisconnectCause();
        Assert.assertEquals(
                mBluetoothInCallService.getTbsTerminationReason(call),
                BluetoothLeCallControl.TERMINATION_REASON_NETWORK_CONGESTION);

        cause =
                new DisconnectCause(
                        DisconnectCause.CONNECTION_MANAGER_NOT_SUPPORTED, null, null, null, 1);
        doReturn(cause).when(call).getDisconnectCause();
        Assert.assertEquals(
                mBluetoothInCallService.getTbsTerminationReason(call),
                BluetoothLeCallControl.TERMINATION_REASON_INVALID_URI);

        cause = new DisconnectCause(DisconnectCause.ERROR, null, null, null, 1);
        doReturn(cause).when(call).getDisconnectCause();
        Assert.assertEquals(
                mBluetoothInCallService.getTbsTerminationReason(call),
                BluetoothLeCallControl.TERMINATION_REASON_NETWORK_CONGESTION);
    }

    @Test
    public void onDestroy() {
        Assert.assertTrue(mBluetoothInCallService.mOnCreateCalled);

        mBluetoothInCallService.onDestroy();

        Assert.assertFalse(mBluetoothInCallService.mOnCreateCalled);
    }

    @Test
    public void leCallControlCallback_onAcceptCall_withUnknownCallId() {
        int requestId = 1;
        UUID unknownCallId = UUID.randomUUID();
        mBluetoothInCallService.mBluetoothLeCallControlCallback.onAcceptCall(
                requestId, unknownCallId);

        verify(mLeCallControl)
                .requestResult(requestId, BluetoothLeCallControl.RESULT_ERROR_UNKNOWN_CALL_ID);
    }

    @Test
    public void leCallControlCallback_onTerminateCall_withUnknownCallId() {
        int requestId = 1;
        UUID unknownCallId = UUID.randomUUID();
        mBluetoothInCallService.mBluetoothLeCallControlCallback.onTerminateCall(
                requestId, unknownCallId);

        verify(mLeCallControl)
                .requestResult(requestId, BluetoothLeCallControl.RESULT_ERROR_UNKNOWN_CALL_ID);
    }

    @Test
    public void leCallControlCallback_onHoldCall_withUnknownCallId() {
        int requestId = 1;
        UUID unknownCallId = UUID.randomUUID();
        mBluetoothInCallService.mBluetoothLeCallControlCallback.onHoldCall(
                requestId, unknownCallId);

        verify(mLeCallControl)
                .requestResult(requestId, BluetoothLeCallControl.RESULT_ERROR_UNKNOWN_CALL_ID);
    }

    @Test
    public void leCallControlCallback_onUnholdCall_withUnknownCallId() {
        int requestId = 1;
        UUID unknownCallId = UUID.randomUUID();
        mBluetoothInCallService.mBluetoothLeCallControlCallback.onUnholdCall(
                requestId, unknownCallId);

        verify(mLeCallControl)
                .requestResult(requestId, BluetoothLeCallControl.RESULT_ERROR_UNKNOWN_CALL_ID);
    }

    @Test
    public void leCallControlCallback_onJoinCalls() {
        int requestId = 1;
        UUID baseCallId = UUID.randomUUID();
        UUID firstJoiningCallId = UUID.randomUUID();
        UUID secondJoiningCallId = UUID.randomUUID();
        List<UUID> uuids = List.of(baseCallId, firstJoiningCallId, secondJoiningCallId);

        BluetoothCall baseCall = createActiveCall(baseCallId);
        BluetoothCall firstCall = createRingingCall(firstJoiningCallId);
        BluetoothCall secondCall = createRingingCall(secondJoiningCallId);

        doReturn(Call.STATE_ACTIVE).when(baseCall).getState();
        doReturn(Call.STATE_RINGING).when(firstCall).getState();
        doReturn(Call.STATE_RINGING).when(secondCall).getState();

        doReturn(List.of(baseCall, firstCall, secondCall)).when(mMockCallInfo).getBluetoothCalls();

        mBluetoothInCallService.onCallAdded(baseCall);
        mBluetoothInCallService.onCallAdded(firstCall);
        mBluetoothInCallService.onCallAdded(secondCall);

        doReturn(Uri.parse("tel:111-111")).when(baseCall).getHandle();
        doReturn(Uri.parse("tel:222-222")).when(firstCall).getHandle();
        doReturn(Uri.parse("tel:333-333")).when(secondCall).getHandle();

        doReturn(baseCall).when(mMockCallInfo).getCallByCallId(baseCallId);
        doReturn(firstCall).when(mMockCallInfo).getCallByCallId(firstJoiningCallId);
        doReturn(secondCall).when(mMockCallInfo).getCallByCallId(secondJoiningCallId);

        mBluetoothInCallService.mBluetoothLeCallControlCallback.onJoinCalls(requestId, uuids);

        verify(mLeCallControl).requestResult(requestId, BluetoothLeCallControl.RESULT_SUCCESS);
        verify(baseCall, times(2)).conference(any(BluetoothCall.class));
    }

    @Test
    public void leCallControlCallback_onJoinCalls_omitDoubledCalls() {
        int requestId = 1;
        UUID baseCallId = UUID.randomUUID();
        UUID firstJoiningCallId = UUID.randomUUID();
        List<UUID> uuids = List.of(baseCallId, firstJoiningCallId);

        BluetoothCall baseCall = createActiveCall(baseCallId);
        BluetoothCall firstCall = createRingingCall(firstJoiningCallId);

        doReturn(List.of(baseCall, firstCall)).when(mMockCallInfo).getBluetoothCalls();

        doReturn(Call.STATE_ACTIVE).when(baseCall).getState();
        doReturn(Call.STATE_RINGING).when(firstCall).getState();

        mBluetoothInCallService.onCallAdded(baseCall);
        mBluetoothInCallService.onCallAdded(firstCall);

        doReturn(Uri.parse("tel:111-111")).when(baseCall).getHandle();
        doReturn(Uri.parse("tel:222-222")).when(firstCall).getHandle();

        doReturn(baseCall).when(mMockCallInfo).getCallByCallId(eq(baseCallId));
        doReturn(firstCall).when(mMockCallInfo).getCallByCallId(eq(firstJoiningCallId));

        mBluetoothInCallService.mBluetoothLeCallControlCallback.onJoinCalls(requestId, uuids);

        verify(mLeCallControl).requestResult(requestId, BluetoothLeCallControl.RESULT_SUCCESS);
        verify(baseCall).conference(any(BluetoothCall.class));
    }

    @Test
    public void leCallControlCallback_onJoinCalls_omitNullCalls() {
        int requestId = 1;
        UUID baseCallId = UUID.randomUUID();
        UUID firstJoiningCallId = UUID.randomUUID();
        UUID secondJoiningCallId = UUID.randomUUID();
        List<UUID> uuids = List.of(baseCallId, firstJoiningCallId, secondJoiningCallId);

        BluetoothCall baseCall = createActiveCall(baseCallId);
        BluetoothCall firstCall = createRingingCall(firstJoiningCallId);
        BluetoothCall secondCall = createRingingCall(secondJoiningCallId);

        doReturn(List.of(baseCall, firstCall, secondCall)).when(mMockCallInfo).getBluetoothCalls();

        doReturn(Call.STATE_ACTIVE).when(baseCall).getState();
        doReturn(Call.STATE_RINGING).when(firstCall).getState();
        doReturn(Call.STATE_RINGING).when(secondCall).getState();

        mBluetoothInCallService.onCallAdded(baseCall);
        mBluetoothInCallService.onCallAdded(firstCall);
        mBluetoothInCallService.onCallAdded(secondCall);

        doReturn(Uri.parse("tel:111-111")).when(baseCall).getHandle();
        doReturn(Uri.parse("tel:222-222")).when(firstCall).getHandle();
        doReturn(Uri.parse("tel:333-333")).when(secondCall).getHandle();

        doReturn(baseCall).when(mMockCallInfo).getCallByCallId(null);
        doReturn(firstCall).when(mMockCallInfo).getCallByCallId(firstJoiningCallId);
        doReturn(secondCall).when(mMockCallInfo).getCallByCallId(secondJoiningCallId);

        mBluetoothInCallService.mBluetoothLeCallControlCallback.onJoinCalls(requestId, uuids);

        verify(mLeCallControl).requestResult(requestId, BluetoothLeCallControl.RESULT_SUCCESS);
        verify(firstCall).conference(any(BluetoothCall.class));
    }

    private void addCallCapability(BluetoothCall call, int capability) {
        doReturn(true).when(call).can(eq(capability));
    }

    private BluetoothCall createActiveCall(UUID uuid) {
        BluetoothCall call = getMockCall(uuid);
        doReturn(call).when(mMockCallInfo).getActiveCall();
        return call;
    }

    private BluetoothCall createRingingCall(UUID uuid) {
        BluetoothCall call = getMockCall(uuid);
        doReturn(call).when(mMockCallInfo).getRingingOrSimulatedRingingCall();
        return call;
    }

    private BluetoothCall createHeldCall(UUID uuid) {
        BluetoothCall call = getMockCall(uuid);
        doReturn(call).when(mMockCallInfo).getHeldCall();
        return call;
    }

    private BluetoothCall createOutgoingCall(UUID uuid) {
        BluetoothCall call = getMockCall(uuid);
        doReturn(call).when(mMockCallInfo).getOutgoingCall();
        return call;
    }

    private BluetoothCall createDisconnectedCall(UUID uuid) {
        BluetoothCall call = getMockCall(uuid);
        doReturn(call).when(mMockCallInfo).getCallByState(Call.STATE_DISCONNECTED);
        return call;
    }

    private BluetoothCall createForegroundCall(UUID uuid) {
        BluetoothCall call = getMockCall(uuid);
        doReturn(call).when(mMockCallInfo).getForegroundCall();
        return call;
    }

    private static ComponentName makeQuickConnectionServiceComponentName() {
        return new ComponentName(
                "com.android.server.telecom.tests",
                "com.android.server.telecom.tests.MockConnectionService");
    }

    private static PhoneAccountHandle makeQuickAccountHandle(String id) {
        return new PhoneAccountHandle(
                makeQuickConnectionServiceComponentName(), id, Binder.getCallingUserHandle());
    }

    private PhoneAccount.Builder makeQuickAccountBuilder(String id, int idx) {
        return new PhoneAccount.Builder(makeQuickAccountHandle(id), "label" + idx);
    }

    private PhoneAccount makeQuickAccount(String id, int idx) {
        return makeQuickAccountBuilder(id, idx)
                .setAddress(Uri.parse(TEST_ACCOUNT_ADDRESS + idx))
                .setSubscriptionAddress(Uri.parse("tel:555-000" + idx))
                .setCapabilities(idx)
                .setShortDescription("desc" + idx)
                .setIsEnabled(true)
                .build();
    }

    private BluetoothCall getMockCall(UUID uuid) {
        BluetoothCall call = mock(com.android.bluetooth.telephony.BluetoothCall.class);
        Integer integerUuid = uuid.hashCode();
        doReturn(integerUuid).when(call).getId();
        return call;
    }
}
