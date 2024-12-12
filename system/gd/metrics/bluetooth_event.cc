/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include "bluetooth_event.h"

#include <frameworks/proto_logging/stats/enums/bluetooth/enums.pb.h>

#include "main/shim/helpers.h"
#include "os/metrics.h"

namespace bluetooth {
namespace metrics {

using android::bluetooth::EventType;
using android::bluetooth::State;
using hci::ErrorCode;

State MapErrorCodeToState(ErrorCode reason) {
  // TODO - map the error codes to the state enum variants.
  switch (reason) {
    case ErrorCode::SUCCESS:
      return State::SUCCESS;
    // Timeout related errors
    case ErrorCode::PAGE_TIMEOUT:
      return State::PAGE_TIMEOUT;
    case ErrorCode::CONNECTION_TIMEOUT:
      return State::CONNECTION_TIMEOUT;
    case ErrorCode::CONNECTION_ACCEPT_TIMEOUT:
      return State::CONNECTION_ACCEPT_TIMEOUT;
    case ErrorCode::TRANSACTION_RESPONSE_TIMEOUT:
      return State::TRANSACTION_RESPONSE_TIMEOUT;
    case ErrorCode::AUTHENTICATION_FAILURE:
      return State::AUTH_FAILURE;
    case ErrorCode::REMOTE_USER_TERMINATED_CONNECTION:
    case ErrorCode::REMOTE_DEVICE_TERMINATED_CONNECTION_LOW_RESOURCES:
    case ErrorCode::REMOTE_DEVICE_TERMINATED_CONNECTION_POWER_OFF:
      return State::REMOTE_USER_TERMINATED_CONNECTION;
    case ErrorCode::CONNECTION_ALREADY_EXISTS:
      return State::ALREADY_CONNECTED;
    case ErrorCode::REPEATED_ATTEMPTS:
      return State::REPEATED_ATTEMPTS;
    case ErrorCode::PIN_OR_KEY_MISSING:
      return State::KEY_MISSING;
    case ErrorCode::PAIRING_NOT_ALLOWED:
      return State::PAIRING_NOT_ALLOWED;
    case ErrorCode::CONNECTION_REJECTED_LIMITED_RESOURCES:
      return State::RESOURCES_EXCEEDED;
    default:
      return State::STATE_UNKNOWN;
  }
}

State MapHCIStatusToState(tHCI_STATUS status) {
  // TODO - map the error codes to the state enum variants.
  switch (status) {
    case tHCI_STATUS::HCI_SUCCESS:
      return State::SUCCESS;
    // Timeout related errors
    case tHCI_STATUS::HCI_ERR_PAGE_TIMEOUT:
      return State::PAGE_TIMEOUT;
    case tHCI_STATUS::HCI_ERR_CONNECTION_TOUT:
      return State::CONNECTION_TIMEOUT;
    case tHCI_STATUS::HCI_ERR_HOST_TIMEOUT:
      return State::CONNECTION_ACCEPT_TIMEOUT;
    case tHCI_STATUS::HCI_ERR_LMP_RESPONSE_TIMEOUT:
      return State::TRANSACTION_RESPONSE_TIMEOUT;
    case tHCI_STATUS::HCI_ERR_AUTH_FAILURE:
      return State::AUTH_FAILURE;
    case tHCI_STATUS::HCI_ERR_CONNECTION_EXISTS:
      return State::ALREADY_CONNECTED;
    case tHCI_STATUS::HCI_ERR_REPEATED_ATTEMPTS:
      return State::REPEATED_ATTEMPTS;
    case tHCI_STATUS::HCI_ERR_KEY_MISSING:
      return State::KEY_MISSING;
    case tHCI_STATUS::HCI_ERR_PAIRING_NOT_ALLOWED:
      return State::PAIRING_NOT_ALLOWED;
    case tHCI_STATUS::HCI_ERR_HOST_REJECT_RESOURCES:
      return State::RESOURCES_EXCEEDED;
    default:
      return State::STATE_UNKNOWN;
  }
}

void LogIncomingAclStartEvent(const hci::Address& address) {
  bluetooth::os::LogMetricBluetoothEvent(address, EventType::ACL_CONNECTION_RESPONDER,
                                         State::START);
}

void LogAclCompletionEvent(const hci::Address& address, ErrorCode reason,
                           bool is_locally_initiated) {
  bluetooth::os::LogMetricBluetoothEvent(address,
                                         is_locally_initiated ? EventType::ACL_CONNECTION_INITIATOR
                                                              : EventType::ACL_CONNECTION_RESPONDER,
                                         MapErrorCodeToState(reason));
}

void LogRemoteNameRequestCompletion(const RawAddress& raw_address, tHCI_STATUS hci_status) {
  hci::Address address = bluetooth::ToGdAddress(raw_address);
  bluetooth::os::LogMetricBluetoothEvent(
          address, EventType::REMOTE_NAME_REQUEST,
          MapHCIStatusToState(hci_status));
}

void LogAclDisconnectionEvent(const hci::Address& address, ErrorCode reason,
                              bool is_locally_initiated) {
  bluetooth::os::LogMetricBluetoothEvent(address,
                                         is_locally_initiated
                                                 ? EventType::ACL_DISCONNECTION_INITIATOR
                                                 : EventType::ACL_DISCONNECTION_RESPONDER,
                                         MapErrorCodeToState(reason));
}

void LogAclAfterRemoteNameRequest(const RawAddress& raw_address, tBTM_STATUS status) {
  hci::Address address = bluetooth::ToGdAddress(raw_address);

  switch (status) {
    case tBTM_STATUS::BTM_SUCCESS:
      bluetooth::os::LogMetricBluetoothEvent(address, EventType::ACL_CONNECTION_INITIATOR,
                                             State::ALREADY_CONNECTED);
      break;
    case tBTM_STATUS::BTM_NO_RESOURCES:
      bluetooth::os::LogMetricBluetoothEvent(
              address, EventType::ACL_CONNECTION_INITIATOR,
              MapErrorCodeToState(ErrorCode::CONNECTION_REJECTED_LIMITED_RESOURCES));
      break;
    default:
      break;
  }
}

void LogUserConfirmationRequestResponse(const hci::Address& address, bool positive) {
  bluetooth::os::LogMetricBluetoothEvent(address, EventType::USER_CONF_REQUEST,
                                         positive ? State::SUCCESS : State::FAIL);
}

void LogAuthenticationComplete(const RawAddress& raw_address, tHCI_STATUS hci_status) {
  hci::Address address = bluetooth::ToGdAddress(raw_address);
  bluetooth::os::LogMetricBluetoothEvent(address,
                                         hci_status == tHCI_STATUS::HCI_SUCCESS
                                                 ? EventType::AUTHENTICATION_COMPLETE
                                                 : EventType::AUTHENTICATION_COMPLETE_FAIL,
                                         MapHCIStatusToState(hci_status));
}

void LogSDPComplete(const RawAddress& raw_address, tBTA_STATUS status) {
  hci::Address address = bluetooth::ToGdAddress(raw_address);
  bluetooth::os::LogMetricBluetoothEvent(
          address, EventType::SERVICE_DISCOVERY,
          status == tBTA_STATUS::BTA_SUCCESS ? State::SUCCESS : State::FAIL);
}

}  // namespace metrics
}  // namespace bluetooth
