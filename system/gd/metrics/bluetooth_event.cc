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

// TODO(b/369381361) Enfore -Wmissing-prototypes
#pragma GCC diagnostic ignored "-Wmissing-prototypes"

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
    case ErrorCode::HARDWARE_FAILURE:
      return State::HARDWARE_FAILURE;
    case ErrorCode::MEMORY_CAPACITY_EXCEEDED:
      return State::MEMORY_CAPACITY_EXCEEDED;
    case ErrorCode::CONNECTION_LIMIT_EXCEEDED:
      return State::CONNECTION_LIMIT_EXCEEDED;
    case ErrorCode::SYNCHRONOUS_CONNECTION_LIMIT_EXCEEDED:
      return State::SYNCHRONOUS_CONNECTION_LIMIT_EXCEEDED;
    case ErrorCode::CONNECTION_REJECTED_SECURITY_REASONS:
      return State::CONNECTION_REJECTED_SECURITY_REASONS;
    case ErrorCode::CONNECTION_REJECTED_UNACCEPTABLE_BD_ADDR:
      return State::CONNECTION_REJECTED_UNACCEPTABLE_BD_ADDR;
    case ErrorCode::UNSUPPORTED_FEATURE_OR_PARAMETER_VALUE:
      return State::UNSUPPORTED_FEATURE_OR_PARAMETER_VALUE;
    case ErrorCode::INVALID_HCI_COMMAND_PARAMETERS:
      return State::INVALID_HCI_COMMAND_PARAMETERS;
    case ErrorCode::CONNECTION_TERMINATED_BY_LOCAL_HOST:
      return State::CONNECTION_TERMINATED_BY_LOCAL_HOST;
    case ErrorCode::UNSUPPORTED_REMOTE_OR_LMP_FEATURE:
      return State::UNSUPPORTED_REMOTE_OR_LMP_FEATURE;
    case ErrorCode::SCO_OFFSET_REJECTED:
      return State::SCO_OFFSET_REJECTED;
    case ErrorCode::SCO_INTERVAL_REJECTED:
      return State::SCO_INTERVAL_REJECTED;
    case ErrorCode::SCO_AIR_MODE_REJECTED:
      return State::SCO_AIR_MODE_REJECTED;
    case ErrorCode::INVALID_LMP_OR_LL_PARAMETERS:
      return State::INVALID_LMP_OR_LL_PARAMETERS;
    case ErrorCode::UNSPECIFIED_ERROR:
      return State::UNSPECIFIED_ERROR;
    case ErrorCode::UNSUPPORTED_LMP_OR_LL_PARAMETER:
      return State::UNSUPPORTED_LMP_OR_LL_PARAMETER;
    case ErrorCode::ROLE_CHANGE_NOT_ALLOWED:
      return State::ROLE_CHANGE_NOT_ALLOWED;
    case ErrorCode::LINK_LAYER_COLLISION:
      return State::LINK_LAYER_COLLISION;
    case ErrorCode::LMP_PDU_NOT_ALLOWED:
      return State::LMP_PDU_NOT_ALLOWED;
    case ErrorCode::ENCRYPTION_MODE_NOT_ACCEPTABLE:
      return State::ENCRYPTION_MODE_NOT_ACCEPTABLE;
    case ErrorCode::LINK_KEY_CANNOT_BE_CHANGED:
      return State::LINK_KEY_CANNOT_BE_CHANGED;
    case ErrorCode::REQUESTED_QOS_NOT_SUPPORTED:
      return State::REQUESTED_QOS_NOT_SUPPORTED;
    case ErrorCode::INSTANT_PASSED:
      return State::INSTANT_PASSED;
    case ErrorCode::PAIRING_WITH_UNIT_KEY_NOT_SUPPORTED:
      return State::PAIRING_WITH_UNIT_KEY_NOT_SUPPORTED;
    case ErrorCode::DIFFERENT_TRANSACTION_COLLISION:
      return State::DIFFERENT_TRANSACTION_COLLISION;
    case ErrorCode::QOS_UNACCEPTABLE_PARAMETERS:
      return State::QOS_UNACCEPTABLE_PARAMETERS;
    case ErrorCode::QOS_REJECTED:
      return State::QOS_REJECTED;
    case ErrorCode::CHANNEL_ASSESSMENT_NOT_SUPPORTED:
      return State::CHANNEL_ASSESSMENT_NOT_SUPPORTED;
    case ErrorCode::INSUFFICIENT_SECURITY:
      return State::INSUFFICIENT_SECURITY;
    case ErrorCode::PARAMETER_OUT_OF_MANDATORY_RANGE:
      return State::PARAMETER_OUT_OF_MANDATORY_RANGE;
    case ErrorCode::ROLE_SWITCH_PENDING:
      return State::ROLE_SWITCH_PENDING;
    case ErrorCode::RESERVED_SLOT_VIOLATION:
      return State::RESERVED_SLOT_VIOLATION;
    case ErrorCode::ROLE_SWITCH_FAILED:
      return State::ROLE_SWITCH_FAILED;
    case ErrorCode::EXTENDED_INQUIRY_RESPONSE_TOO_LARGE:
      return State::EXTENDED_INQUIRY_RESPONSE_TOO_LARGE;
    case ErrorCode::SECURE_SIMPLE_PAIRING_NOT_SUPPORTED_BY_HOST:
      return State::SECURE_SIMPLE_PAIRING_NOT_SUPPORTED_BY_HOST;
    case ErrorCode::HOST_BUSY_PAIRING:
      return State::HOST_BUSY_PAIRING;
    case ErrorCode::CONNECTION_REJECTED_NO_SUITABLE_CHANNEL_FOUND:
      return State::CONNECTION_REJECTED_NO_SUITABLE_CHANNEL_FOUND;
    case ErrorCode::CONTROLLER_BUSY:
      return State::CONTROLLER_BUSY;
    case ErrorCode::UNACCEPTABLE_CONNECTION_PARAMETERS:
      return State::UNACCEPTABLE_CONNECTION_PARAMETERS;
    case ErrorCode::ADVERTISING_TIMEOUT:
      return State::ADVERTISING_TIMEOUT;
    case ErrorCode::CONNECTION_TERMINATED_DUE_TO_MIC_FAILURE:
      return State::CONNECTION_TERMINATED_DUE_TO_MIC_FAILURE;
    case ErrorCode::CONNECTION_FAILED_ESTABLISHMENT:
      return State::CONNECTION_FAILED_ESTABLISHMENT;
    case ErrorCode::COARSE_CLOCK_ADJUSTMENT_REJECTED:
      return State::COARSE_CLOCK_ADJUSTMENT_REJECTED;
    case ErrorCode::TYPE0_SUBMAP_NOT_DEFINED:
      return State::TYPE0_SUBMAP_NOT_DEFINED;
    case ErrorCode::UNKNOWN_ADVERTISING_IDENTIFIER:
      return State::UNKNOWN_ADVERTISING_IDENTIFIER;
    case ErrorCode::LIMIT_REACHED:
      return State::LIMIT_REACHED;
    case ErrorCode::OPERATION_CANCELLED_BY_HOST:
      return State::OPERATION_CANCELLED_BY_HOST;
    case ErrorCode::PACKET_TOO_LONG:
      return State::PACKET_TOO_LONG;
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

State MapSmpStatusCodeToState(tSMP_STATUS status) {
  switch (status) {
    case tSMP_STATUS::SMP_SUCCESS:
      return State::SUCCESS;
    case tSMP_STATUS::SMP_PASSKEY_ENTRY_FAIL:
      return State::PASSKEY_ENTRY_FAIL;
    case tSMP_STATUS::SMP_OOB_FAIL:
      return State::OOB_FAIL;
    case tSMP_STATUS::SMP_PAIR_AUTH_FAIL:
      return State::AUTH_FAILURE;
    case tSMP_STATUS::SMP_CONFIRM_VALUE_ERR:
      return State::CONFIRM_VALUE_ERROR;
    case tSMP_STATUS::SMP_PAIR_NOT_SUPPORT:
      return State::PAIRING_NOT_ALLOWED;
    case tSMP_STATUS::SMP_ENC_KEY_SIZE:
      return State::ENC_KEY_SIZE;
    case tSMP_STATUS::SMP_INVALID_CMD:
      return State::INVALID_CMD;
    case tSMP_STATUS::SMP_PAIR_FAIL_UNKNOWN:
      return State::STATE_UNKNOWN;  // Assuming this maps to the default
    case tSMP_STATUS::SMP_REPEATED_ATTEMPTS:
      return State::REPEATED_ATTEMPTS;
    case tSMP_STATUS::SMP_INVALID_PARAMETERS:
      return State::INVALID_PARAMETERS;
    case tSMP_STATUS::SMP_DHKEY_CHK_FAIL:
      return State::DHKEY_CHK_FAIL;
    case tSMP_STATUS::SMP_NUMERIC_COMPAR_FAIL:
      return State::NUMERIC_COMPARISON_FAIL;
    case tSMP_STATUS::SMP_BR_PARING_IN_PROGR:
      return State::BR_PAIRING_IN_PROGRESS;
    case tSMP_STATUS::SMP_XTRANS_DERIVE_NOT_ALLOW:
      return State::CROSS_TRANSPORT_NOT_ALLOWED;
    case tSMP_STATUS::SMP_PAIR_INTERNAL_ERR:
      return State::INTERNAL_ERROR;
    case tSMP_STATUS::SMP_UNKNOWN_IO_CAP:
      return State::UNKNOWN_IO_CAP;
    case tSMP_STATUS::SMP_BUSY:
      return State::BUSY_PAIRING;
    case tSMP_STATUS::SMP_ENC_FAIL:
      return State::ENCRYPTION_FAIL;
    case tSMP_STATUS::SMP_STARTED:
      return State::STATE_UNKNOWN;  // Assuming this maps to the default
    case tSMP_STATUS::SMP_RSP_TIMEOUT:
      return State::RESPONSE_TIMEOUT;
    case tSMP_STATUS::SMP_FAIL:
      return State::FAIL;
    case tSMP_STATUS::SMP_CONN_TOUT:
      return State::CONNECTION_TIMEOUT;
    case tSMP_STATUS::SMP_SIRK_DEVICE_INVALID:
      return State::SIRK_DEVICE_INVALID;
    case tSMP_STATUS::SMP_USER_CANCELLED:
      return State::USER_CANCELLATION;
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

void LogLeAclCompletionEvent(const hci::Address& address, hci::ErrorCode reason,
                             bool is_locally_initiated) {
  bluetooth::os::LogMetricBluetoothEvent(address,
                                         is_locally_initiated
                                                 ? EventType::LE_ACL_CONNECTION_INITIATOR
                                                 : EventType::LE_ACL_CONNECTION_RESPONDER,
                                         MapErrorCodeToState(reason));
}

void LogLePairingFail(const RawAddress& raw_address, uint8_t failure_reason, bool is_outgoing) {
  hci::Address address = bluetooth::ToGdAddress(raw_address);
  bluetooth::os::LogMetricBluetoothEvent(
          address, is_outgoing ? EventType::SMP_PAIRING_OUTGOING : EventType::SMP_PAIRING_INCOMING,
          MapSmpStatusCodeToState(static_cast<tSMP_STATUS>(failure_reason)));
}

}  // namespace metrics
}  // namespace bluetooth
