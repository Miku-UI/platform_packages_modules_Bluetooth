/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
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

package android.bluetooth;

import android.bluetooth.BluetoothLeCall;

import android.os.ParcelUuid;

/**
 * Method definitions for interacting Telephone Bearer Service instance
 * @hide
 */
oneway interface IBluetoothLeCallControlCallback {
    void onBearerRegistered(in int ccid);
    void onAcceptCall(in int requestId, in ParcelUuid uuid);
    void onTerminateCall(in int requestId, in ParcelUuid uuid);
    void onHoldCall(in int requestId, in ParcelUuid uuid);
    void onUnholdCall(in int requestId, in ParcelUuid uuid);
    void onPlaceCall(in int requestId, in ParcelUuid uuid, in String uri);
    void onJoinCalls(in int requestId, in List<ParcelUuid> uuids);
}
