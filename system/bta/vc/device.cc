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

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <list>
#include <map>
#include <string>
#include <utility>
#include <vector>

#include "bta/include/bta_gatt_api.h"
#include "bta/include/bta_gatt_queue.h"
#include "bta/vc/devices.h"
#include "btm_ble_api_types.h"
#include "btm_sec_api_types.h"
#include "btm_status.h"
#include "gatt/database.h"
#include "gattdefs.h"
#include "stack/btm/btm_sec.h"
#include "stack/include/bt_types.h"
#include "stack/include/gatt_api.h"
#include "types/bluetooth/uuid.h"
#include "types/bt_transport.h"
#include "vc/types.h"

using bluetooth::vc::internal::VolumeControlDevice;

void VolumeControlDevice::DeregisterNotifications(tGATT_IF gatt_if) {
  if (volume_state_handle != 0) {
    BTA_GATTC_DeregisterForNotifications(gatt_if, address, volume_state_handle);
  }

  if (volume_flags_handle != 0) {
    BTA_GATTC_DeregisterForNotifications(gatt_if, address, volume_flags_handle);
  }

  for (const VolumeOffset& of : audio_offsets.volume_offsets) {
    BTA_GATTC_DeregisterForNotifications(gatt_if, address, of.audio_descr_handle);
    BTA_GATTC_DeregisterForNotifications(gatt_if, address, of.audio_location_handle);
    BTA_GATTC_DeregisterForNotifications(gatt_if, address, of.state_handle);
  }

  for (const VolumeAudioInput& in : audio_inputs.volume_audio_inputs) {
    BTA_GATTC_DeregisterForNotifications(gatt_if, address, in.description_handle);
    BTA_GATTC_DeregisterForNotifications(gatt_if, address, in.state_handle);
    BTA_GATTC_DeregisterForNotifications(gatt_if, address, in.status_handle);
  }
}

void VolumeControlDevice::Disconnect(tGATT_IF gatt_if) {
  log::info("{}", address);

  if (IsConnected()) {
    DeregisterNotifications(gatt_if);
    BtaGattQueue::Clean(connection_id);
    BTA_GATTC_Close(connection_id);
    connection_id = GATT_INVALID_CONN_ID;
  }

  device_ready = false;
  handles_pending.clear();
}

/*
 * Find the handle for the client characteristics configuration of a given
 * characteristics
 */
uint16_t VolumeControlDevice::find_ccc_handle(uint16_t chrc_handle) {
  const gatt::Characteristic* p_char = BTA_GATTC_GetCharacteristic(connection_id, chrc_handle);
  if (!p_char) {
    log::warn("{}, no such handle={:#x}", address, chrc_handle);
    return 0;
  }

  for (const gatt::Descriptor& desc : p_char->descriptors) {
    if (desc.uuid == Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG)) {
      return desc.handle;
    }
  }

  return 0;
}

bool VolumeControlDevice::set_volume_control_service_handles(const gatt::Service& service) {
  uint16_t state_handle = 0, state_ccc_handle = 0, control_point_handle = 0, flags_handle = 0,
           flags_ccc_handle = 0;

  for (const gatt::Characteristic& chrc : service.characteristics) {
    if (chrc.uuid == kVolumeControlStateUuid) {
      state_handle = chrc.value_handle;
      state_ccc_handle = find_ccc_handle(chrc.value_handle);
    } else if (chrc.uuid == kVolumeControlPointUuid) {
      control_point_handle = chrc.value_handle;
    } else if (chrc.uuid == kVolumeFlagsUuid) {
      flags_handle = chrc.value_handle;
      flags_ccc_handle = find_ccc_handle(chrc.value_handle);
    } else {
      log::warn("unknown characteristic={}", chrc.uuid);
    }
  }

  // Validate service handles
  if (GATT_HANDLE_IS_VALID(state_handle) && GATT_HANDLE_IS_VALID(state_ccc_handle) &&
      GATT_HANDLE_IS_VALID(control_point_handle) && GATT_HANDLE_IS_VALID(flags_handle)
      /* volume_flags_ccc_handle is optional */) {
    volume_state_handle = state_handle;
    volume_state_ccc_handle = state_ccc_handle;
    volume_control_point_handle = control_point_handle;
    volume_flags_handle = flags_handle;
    volume_flags_ccc_handle = flags_ccc_handle;
    return true;
  }

  return false;
}

void VolumeControlDevice::set_audio_input_control_service_handles(const gatt::Service& service) {
  uint16_t state_handle{0};
  uint16_t state_ccc_handle{0};
  uint16_t gain_setting_handle{0};
  uint16_t type_handle{0};
  uint16_t status_handle{0};
  uint16_t status_ccc_handle{0};
  uint16_t control_point_handle{0};
  uint16_t description_handle{0};
  uint16_t description_ccc_handle{0};
  uint16_t description_writable{0};

  for (const gatt::Characteristic& chrc : service.characteristics) {
    if (chrc.uuid == kVolumeAudioInputStateUuid) {
      state_handle = chrc.value_handle;
      state_ccc_handle = find_ccc_handle(chrc.value_handle);
      log::debug("{} state_handle={:#x} ccc={:#x}", address, state_handle, state_ccc_handle);
    } else if (chrc.uuid == kVolumeAudioInputGainSettingPropertiesUuid) {
      gain_setting_handle = chrc.value_handle;
    } else if (chrc.uuid == kVolumeAudioInputTypeUuid) {
      type_handle = chrc.value_handle;
    } else if (chrc.uuid == kVolumeAudioInputStatusUuid) {
      status_handle = chrc.value_handle;
      status_ccc_handle = find_ccc_handle(chrc.value_handle);
      log::debug("{} status_handle={:#x} ccc={:#x}", address, status_handle, status_ccc_handle);
    } else if (chrc.uuid == kVolumeAudioInputControlPointUuid) {
      control_point_handle = chrc.value_handle;
    } else if (chrc.uuid == kVolumeAudioInputDescriptionUuid) {
      description_handle = chrc.value_handle;
      description_ccc_handle = find_ccc_handle(chrc.value_handle);
      description_writable = chrc.properties & GATT_CHAR_PROP_BIT_WRITE_NR;
      log::debug("{} description_handle={:#x} ccc={:#x}", address, description_handle,
                 description_ccc_handle);
    } else {
      log::info("found unexpected characteristic={}", chrc.uuid);
    }
  }

  // Check if all mandatory attributes are present
  if (!GATT_HANDLE_IS_VALID(state_handle) || !GATT_HANDLE_IS_VALID(state_ccc_handle) ||
      !GATT_HANDLE_IS_VALID(gain_setting_handle) || !GATT_HANDLE_IS_VALID(type_handle) ||
      !GATT_HANDLE_IS_VALID(status_handle) || !GATT_HANDLE_IS_VALID(status_ccc_handle) ||
      !GATT_HANDLE_IS_VALID(control_point_handle) || !GATT_HANDLE_IS_VALID(description_handle)
      /* description_ccc_handle is optional */) {
    log::error(
            "The remote device {} does not comply with AICS 1-0, some handles are invalid. "
            "The aics service with handle {:#x} will be ignored",
            address, service.handle);
    return;
  }
  VolumeAudioInput input = VolumeAudioInput(
          audio_inputs.Size(), service.handle, state_handle, state_ccc_handle, gain_setting_handle,
          type_handle, status_handle, status_ccc_handle, control_point_handle, description_handle,
          description_ccc_handle, description_writable);
  audio_inputs.Add(input);
  log::info("{}, input added id={:#x}", address, input.id);
}

void VolumeControlDevice::set_volume_offset_control_service_handles(const gatt::Service& service) {
  VolumeOffset offset = VolumeOffset(service.handle);

  for (const gatt::Characteristic& chrc : service.characteristics) {
    if (chrc.uuid == kVolumeOffsetStateUuid) {
      offset.state_handle = chrc.value_handle;
      offset.state_ccc_handle = find_ccc_handle(chrc.value_handle);
      log::debug("{}, offset_state handle={:#x}, ccc {:#x}", address, offset.state_handle,
                 offset.state_ccc_handle);

    } else if (chrc.uuid == kVolumeOffsetLocationUuid) {
      offset.audio_location_handle = chrc.value_handle;
      offset.audio_location_ccc_handle = find_ccc_handle(chrc.value_handle);
      offset.audio_location_writable = chrc.properties & GATT_CHAR_PROP_BIT_WRITE_NR;
      log::debug("{}, offset_audio_location handle={:#x}, ccc {:#x}", address,
                 offset.audio_location_handle, offset.audio_location_ccc_handle);

    } else if (chrc.uuid == kVolumeOffsetControlPointUuid) {
      offset.control_point_handle = chrc.value_handle;

    } else if (chrc.uuid == kVolumeOffsetOutputDescriptionUuid) {
      offset.audio_descr_handle = chrc.value_handle;
      offset.audio_descr_ccc_handle = find_ccc_handle(chrc.value_handle);
      offset.audio_descr_writable = chrc.properties & GATT_CHAR_PROP_BIT_WRITE_NR;
      log::debug("{}, offset_audio_des handle={:#x}, ccc {:#x}", address, offset.audio_descr_handle,
                 offset.audio_descr_ccc_handle);

    } else {
      log::warn("unknown characteristic={}", chrc.uuid);
    }
  }

  // Check if all mandatory attributes are present
  if (GATT_HANDLE_IS_VALID(offset.state_handle) && GATT_HANDLE_IS_VALID(offset.state_ccc_handle) &&
      GATT_HANDLE_IS_VALID(offset.audio_location_handle) &&
      /* audio_location_ccc_handle is optional */
      GATT_HANDLE_IS_VALID(offset.control_point_handle) &&
      GATT_HANDLE_IS_VALID(offset.audio_descr_handle)
      /* audio_descr_ccc_handle is optional */) {
    audio_offsets.Add(offset);
    log::info("{}, offset added id={:#x}", address, offset.id);
  } else {
    log::warn("{}, ignoring offset handle={:#x}", address, service.handle);
  }
}

bool VolumeControlDevice::UpdateHandles(void) {
  ResetHandles();

  bool vcs_found = false;
  const std::list<gatt::Service>* services = BTA_GATTC_GetServices(connection_id);
  if (services == nullptr) {
    log::error("{}, no services found", address);
    return false;
  }

  for (auto const& service : *services) {
    if (service.uuid == kVolumeControlUuid) {
      log::info("{}, found VCS, handle={:#x}", address, service.handle);
      vcs_found = set_volume_control_service_handles(service);
      if (!vcs_found) {
        break;
      }

      known_service_handles_ = true;
      for (auto const& included : service.included_services) {
        const gatt::Service* service =
                BTA_GATTC_GetOwningService(connection_id, included.start_handle);
        if (service == nullptr) {
          continue;
        }

        if (included.uuid == kVolumeOffsetUuid) {
          log::info("{}, found VOCS, handle={:#x}", address, service->handle);
          set_volume_offset_control_service_handles(*service);

        } else if (included.uuid == kVolumeAudioInputUuid) {
          log::info("{}, found AICS, handle={:#x}", address, service->handle);
          if (com::android::bluetooth::flags::leaudio_add_aics_support()) {
            set_audio_input_control_service_handles(*service);
          } else {
            log::info("Flag leaudio_add_aics_support is not enabled");
          }
        } else {
          log::warn("{}, unknown service={}", address, service->uuid);
        }
      }
    }
  }

  return vcs_found;
}

void VolumeControlDevice::ResetHandles(void) {
  known_service_handles_ = false;
  device_ready = false;

  // the handles are not valid, so discard pending GATT operations
  BtaGattQueue::Clean(connection_id);

  volume_state_handle = 0;
  volume_state_ccc_handle = 0;
  volume_control_point_handle = 0;
  volume_flags_handle = 0;
  volume_flags_ccc_handle = 0;

  if (audio_offsets.Size() != 0) {
    audio_offsets.Clear();
  }

  if (audio_inputs.Size() != 0) {
    audio_inputs.Clear();
  }
}

void VolumeControlDevice::ControlPointOperation(uint8_t opcode, const std::vector<uint8_t>* arg,
                                                GATT_WRITE_OP_CB cb, void* cb_data) {
  std::vector<uint8_t> set_value({opcode, change_counter});
  if (arg != nullptr) {
    set_value.insert(set_value.end(), (*arg).begin(), (*arg).end());
  }

  BtaGattQueue::WriteCharacteristic(connection_id, volume_control_point_handle, set_value,
                                    GATT_WRITE, cb, cb_data);
}

bool VolumeControlDevice::subscribe_for_notifications(tGATT_IF gatt_if, uint16_t handle,
                                                      uint16_t ccc_handle, GATT_WRITE_OP_CB cb) {
  tGATT_STATUS status = BTA_GATTC_RegisterForNotifications(gatt_if, address, handle);
  log::debug("gatt_if:{}, {} , {:#x} : {:#x}", gatt_if, address, handle, ccc_handle);

  if (status != GATT_SUCCESS) {
    log::error("failed for {}, status={:#x}", address, status);
    return false;
  }

  log::debug("{} ok to proceed with writing descriptor {:#x}", address, ccc_handle);

  std::vector<uint8_t> value(2);
  uint8_t* ptr = value.data();
  UINT16_TO_STREAM(ptr, GATT_CHAR_CLIENT_CONFIG_NOTIFICATION);
  BtaGattQueue::WriteDescriptor(connection_id, ccc_handle, std::move(value), GATT_WRITE, cb,
                                nullptr);

  return true;
}

/**
 * Enqueue GATT requests that are required by the Volume Control to be
 * functional. This includes State characteristics read and subscription.
 * Those characteristics contain the change counter needed to send any request
 * via Control Point. Once completed successfully, the device can be stored
 * and reported as connected. In each case we subscribe first to be sure we do
 * not miss any value change.
 */
bool VolumeControlDevice::EnqueueInitialRequests(tGATT_IF gatt_if, GATT_READ_OP_CB chrc_read_cb,
                                                 GATT_WRITE_OP_CB cccd_write_cb) {
  log::debug("{}", address);

  std::map<uint16_t, uint16_t> hdls_to_subscribe{
          {volume_state_handle, volume_state_ccc_handle},
  };

  handles_pending.clear();
  // Status and Flags are mandatory
  handles_pending.insert(volume_state_handle);
  handles_pending.insert(volume_state_ccc_handle);

  handles_pending.insert(volume_flags_handle);

  if (GATT_HANDLE_IS_VALID(volume_flags_ccc_handle)) {
    hdls_to_subscribe[volume_flags_handle] = volume_flags_ccc_handle;
    handles_pending.insert(volume_flags_ccc_handle);
  }

  // Register for notifications
  for (auto const& input : audio_inputs.volume_audio_inputs) {
    // State is mandatory
    hdls_to_subscribe[input.state_handle] = input.state_ccc_handle;
    handles_pending.insert(input.state_ccc_handle);
    // State is mandatory
    hdls_to_subscribe[input.status_handle] = input.status_ccc_handle;
    handles_pending.insert(input.status_ccc_handle);

    if (GATT_HANDLE_IS_VALID(input.description_ccc_handle)) {
      hdls_to_subscribe[input.description_handle] = input.description_ccc_handle;
      handles_pending.insert(input.description_ccc_handle);
    }
  }

  for (auto const& offset : audio_offsets.volume_offsets) {
    hdls_to_subscribe[offset.state_handle] = offset.state_ccc_handle;
    handles_pending.insert(offset.state_ccc_handle);

    if (GATT_HANDLE_IS_VALID(offset.audio_descr_ccc_handle)) {
      hdls_to_subscribe[offset.audio_descr_handle] = offset.audio_descr_ccc_handle;
      handles_pending.insert(offset.audio_descr_ccc_handle);
    }

    if (GATT_HANDLE_IS_VALID(offset.audio_location_ccc_handle)) {
      hdls_to_subscribe[offset.audio_location_handle] = offset.audio_location_ccc_handle;
      handles_pending.insert(offset.audio_location_ccc_handle);
    }
  }

  for (auto const& handles : hdls_to_subscribe) {
    log::debug("{}, handle={:#x}, ccc_handle={:#x}", address, handles.first, handles.second);
    if (!subscribe_for_notifications(gatt_if, handles.first, handles.second, cccd_write_cb)) {
      log::error("{}, failed to subscribe for handle={:#x}, ccc_handle={:#x}", address,
                 handles.first, handles.second);
      return false;
    }
  }

  BtaGattQueue::ReadCharacteristic(connection_id, volume_state_handle, chrc_read_cb, nullptr);
  BtaGattQueue::ReadCharacteristic(connection_id, volume_flags_handle, chrc_read_cb, nullptr);

  return true;
}

/**
 * Enqueue the remaining requests. Those are not so crucial and can be done
 * once Volume Control instance indicates it's readiness to profile.
 * This includes characteristics read and subscription.
 * In each case we subscribe first to be sure we do not miss any value change.
 */
void VolumeControlDevice::EnqueueRemainingRequests(tGATT_IF /*gatt_if*/,
                                                   GATT_READ_OP_CB chrc_read_cb,
                                                   GATT_READ_MULTI_OP_CB chrc_multi_read_cb,
                                                   GATT_WRITE_OP_CB /*cccd_write_cb*/) {
  std::vector<uint16_t> handles_to_read;

  for (auto const& input : audio_inputs.volume_audio_inputs) {
    handles_to_read.push_back(input.state_handle);
    handles_to_read.push_back(input.gain_setting_handle);
    handles_to_read.push_back(input.type_handle);
    handles_to_read.push_back(input.status_handle);
    handles_to_read.push_back(input.description_handle);
  }

  for (auto const& offset : audio_offsets.volume_offsets) {
    handles_to_read.push_back(offset.state_handle);
    handles_to_read.push_back(offset.audio_location_handle);
    handles_to_read.push_back(offset.audio_descr_handle);
  }

  log::debug("{}, number of handles={}", address, handles_to_read.size());

  if (!com::android::bluetooth::flags::le_ase_read_multiple_variable()) {
    for (auto const& handle : handles_to_read) {
      BtaGattQueue::ReadCharacteristic(connection_id, handle, chrc_read_cb, nullptr);
    }
    return;
  }

  size_t sent_cnt = 0;

  while (sent_cnt < handles_to_read.size()) {
    tBTA_GATTC_MULTI multi_read{};
    size_t remain_cnt = (handles_to_read.size() - sent_cnt);

    multi_read.num_attr =
            remain_cnt > GATT_MAX_READ_MULTI_HANDLES ? GATT_MAX_READ_MULTI_HANDLES : remain_cnt;

    auto handles_begin = handles_to_read.begin() + sent_cnt;
    std::copy(handles_begin, handles_begin + multi_read.num_attr, multi_read.handles);

    sent_cnt += multi_read.num_attr;
    log::debug{"{}, calling multi with {} attributes, sent_cnt {} ", address, multi_read.num_attr,
               sent_cnt};

    BtaGattQueue::ReadMultiCharacteristic(connection_id, multi_read, chrc_multi_read_cb, nullptr);
  }
}

bool VolumeControlDevice::VerifyReady(uint16_t handle) {
  handles_pending.erase(handle);
  device_ready = handles_pending.size() == 0;

  log::debug("{}, handles_pending size={}", address, handles_pending.size());

  return device_ready;
}

void VolumeControlDevice::GetExtAudioOutVolumeOffset(uint8_t ext_output_id, GATT_READ_OP_CB cb,
                                                     void* cb_data) {
  VolumeOffset* offset = audio_offsets.FindById(ext_output_id);
  if (!offset) {
    log::error("{}, no such offset={:#x}!", address, ext_output_id);
    return;
  }

  BtaGattQueue::ReadCharacteristic(connection_id, offset->state_handle, cb, cb_data);
}

void VolumeControlDevice::GetExtAudioOutLocation(uint8_t ext_output_id, GATT_READ_OP_CB cb,
                                                 void* cb_data) {
  VolumeOffset* offset = audio_offsets.FindById(ext_output_id);
  if (!offset) {
    log::error("{}, no such offset={:#x}!", address, ext_output_id);
    return;
  }

  BtaGattQueue::ReadCharacteristic(connection_id, offset->audio_location_handle, cb, cb_data);
}

void VolumeControlDevice::SetExtAudioOutLocation(uint8_t ext_output_id, uint32_t location) {
  VolumeOffset* offset = audio_offsets.FindById(ext_output_id);
  if (!offset) {
    log::error("{}, no such offset={:#x}!", address, ext_output_id);
    return;
  }

  if (!offset->audio_location_writable) {
    log::warn("not writable");
    return;
  }

  std::vector<uint8_t> value(4);
  uint8_t* ptr = value.data();
  UINT32_TO_STREAM(ptr, location);
  BtaGattQueue::WriteCharacteristic(connection_id, offset->audio_location_handle, value,
                                    GATT_WRITE_NO_RSP, nullptr, nullptr);
}

void VolumeControlDevice::GetExtAudioOutDescription(uint8_t ext_output_id, GATT_READ_OP_CB cb,
                                                    void* cb_data) {
  VolumeOffset* offset = audio_offsets.FindById(ext_output_id);
  if (!offset) {
    log::error("{}, no such offset={:#x}!", address, ext_output_id);
    return;
  }

  BtaGattQueue::ReadCharacteristic(connection_id, offset->audio_descr_handle, cb, cb_data);
}

void VolumeControlDevice::SetExtAudioOutDescription(uint8_t ext_output_id,
                                                    const std::string& descr) {
  VolumeOffset* offset = audio_offsets.FindById(ext_output_id);
  if (!offset) {
    log::error("{}, no such offset={:#x}!", address, ext_output_id);
    return;
  }

  if (!offset->audio_descr_writable) {
    log::warn("not writable");
    return;
  }

  std::vector<uint8_t> value(descr.begin(), descr.end());
  BtaGattQueue::WriteCharacteristic(connection_id, offset->audio_descr_handle, value,
                                    GATT_WRITE_NO_RSP, nullptr, nullptr);
}

void VolumeControlDevice::ExtAudioOutControlPointOperation(uint8_t ext_output_id, uint8_t opcode,
                                                           const std::vector<uint8_t>* arg,
                                                           GATT_WRITE_OP_CB cb, void* cb_data) {
  VolumeOffset* offset = audio_offsets.FindById(ext_output_id);
  if (!offset) {
    log::error("{}, no such offset={:#x}!", address, ext_output_id);
    return;
  }

  std::vector<uint8_t> set_value({opcode, offset->change_counter});
  if (arg != nullptr) {
    set_value.insert(set_value.end(), (*arg).begin(), (*arg).end());
  }

  BtaGattQueue::WriteCharacteristic(connection_id, offset->control_point_handle, set_value,
                                    GATT_WRITE, cb, cb_data);
}

void VolumeControlDevice::GetExtAudioInState(uint8_t ext_input_id, GATT_READ_OP_CB cb,
                                             void* cb_data) {
  VolumeAudioInput* input = audio_inputs.FindById(ext_input_id);
  if (!input) {
    log::error("{}, no such input={:#x}", address, ext_input_id);
    return;
  }

  BtaGattQueue::ReadCharacteristic(connection_id, input->state_handle, cb, cb_data);
}

void VolumeControlDevice::GetExtAudioInStatus(uint8_t ext_input_id, GATT_READ_OP_CB cb,
                                              void* cb_data) {
  VolumeAudioInput* input = audio_inputs.FindById(ext_input_id);
  if (!input) {
    log::error("{}, no such input={:#x}", address, ext_input_id);
    return;
  }

  BtaGattQueue::ReadCharacteristic(connection_id, input->status_handle, cb, cb_data);
}

void VolumeControlDevice::GetExtAudioInType(uint8_t ext_input_id, GATT_READ_OP_CB cb,
                                            void* cb_data) {
  VolumeAudioInput* input = audio_inputs.FindById(ext_input_id);
  if (!input) {
    log::error("{}, no such input={:#x}", address, ext_input_id);
    return;
  }

  BtaGattQueue::ReadCharacteristic(connection_id, input->type_handle, cb, cb_data);
}

void VolumeControlDevice::GetExtAudioInGainProps(uint8_t ext_input_id, GATT_READ_OP_CB cb,
                                                 void* cb_data) {
  VolumeAudioInput* input = audio_inputs.FindById(ext_input_id);
  if (!input) {
    log::error("{}, no such input={:#x}", address, ext_input_id);
    return;
  }

  BtaGattQueue::ReadCharacteristic(connection_id, input->gain_setting_handle, cb, cb_data);
}

void VolumeControlDevice::GetExtAudioInDescription(uint8_t ext_input_id, GATT_READ_OP_CB cb,
                                                   void* cb_data) {
  VolumeAudioInput* input = audio_inputs.FindById(ext_input_id);
  if (!input) {
    log::error("{}, no such input={:#x}", address, ext_input_id);
    return;
  }

  BtaGattQueue::ReadCharacteristic(connection_id, input->description_handle, cb, cb_data);
}

void VolumeControlDevice::SetExtAudioInDescription(uint8_t ext_input_id, const std::string& descr) {
  VolumeAudioInput* input = audio_inputs.FindById(ext_input_id);
  if (!input) {
    log::error("{} no such input={:#x}", address, ext_input_id);
    return;
  }

  if (!input->description_writable) {
    log::warn("{} input={:#x} input description is not writable", address, ext_input_id);
    return;
  }

  std::vector<uint8_t> value(descr.begin(), descr.end());
  BtaGattQueue::WriteCharacteristic(connection_id, input->description_handle, value,
                                    GATT_WRITE_NO_RSP, nullptr, nullptr);
}

bool VolumeControlDevice::ExtAudioInControlPointOperation(uint8_t ext_input_id, uint8_t opcode,
                                                          const std::vector<uint8_t>* arg,
                                                          GATT_WRITE_OP_CB cb, void* cb_data) {
  VolumeAudioInput* input = audio_inputs.FindById(ext_input_id);
  if (!input) {
    log::error("{}, no such input={:#x}", address, ext_input_id);
    return false;
  }

  std::vector<uint8_t> set_value({opcode, input->change_counter});
  if (arg != nullptr) {
    set_value.insert(set_value.end(), (*arg).begin(), (*arg).end());
  }

  BtaGattQueue::WriteCharacteristic(connection_id, input->control_point_handle, set_value,
                                    GATT_WRITE, cb, cb_data);
  return true;
}

bool VolumeControlDevice::IsEncryptionEnabled() {
  return BTM_IsEncrypted(address, BT_TRANSPORT_LE);
}

bool VolumeControlDevice::EnableEncryption() {
  tBTM_STATUS result =
          BTM_SetEncryption(address, BT_TRANSPORT_LE, nullptr, nullptr, BTM_BLE_SEC_ENCRYPT);
  log::info("{}: result=0x{:02x}", address, result);

  return result != tBTM_STATUS::BTM_ERR_KEY_MISSING;
}
