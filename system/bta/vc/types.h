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

#pragma once

#include <hardware/bt_vc.h>

#include <algorithm>
#include <queue>
#include <string>
#include <vector>

#include "bta/include/bta_groups.h"
#include "osi/include/alarm.h"
#include "types/bluetooth/uuid.h"
#include "types/raw_address.h"

namespace bluetooth {
namespace vc {
namespace internal {

/* clang-format off */
/* Volume control point opcodes */
static constexpr uint8_t kControlPointOpcodeVolumeDown         = 0x00;
static constexpr uint8_t kControlPointOpcodeVolumeUp           = 0x01;
static constexpr uint8_t kControlPointOpcodeUnmuteVolumeDown   = 0x02;
static constexpr uint8_t kControlPointOpcodeUnmuteVolumeUp     = 0x03;
static constexpr uint8_t kControlPointOpcodeSetAbsoluteVolume  = 0x04;
static constexpr uint8_t kControlPointOpcodeUnmute             = 0x05;
static constexpr uint8_t kControlPointOpcodeMute               = 0x06;

/* Volume offset control point opcodes */
static constexpr uint8_t kVolumeOffsetControlPointOpcodeSet                 = 0x01;

/* Volume input control point opcodes */
static constexpr uint8_t kVolumeInputControlPointOpcodeSetGain              = 0x01;
static constexpr uint8_t kVolumeInputControlPointOpcodeUnmute               = 0x02;
static constexpr uint8_t kVolumeInputControlPointOpcodeMute                 = 0x03;
static constexpr uint8_t kVolumeInputControlPointOpcodeSetManualGainMode    = 0x04;
static constexpr uint8_t kVolumeInputControlPointOpcodeSetAutoGainMode      = 0x05;

static const Uuid kVolumeControlUuid                  = Uuid::From16Bit(0x1844);
static const Uuid kVolumeControlStateUuid             = Uuid::From16Bit(0x2B7D);
static const Uuid kVolumeControlPointUuid             = Uuid::From16Bit(0x2B7E);
static const Uuid kVolumeFlagsUuid                    = Uuid::From16Bit(0x2B7F);

static const Uuid kVolumeOffsetUuid                   = Uuid::From16Bit(0x1845);
static const Uuid kVolumeOffsetStateUuid              = Uuid::From16Bit(0x2B80);
static const Uuid kVolumeOffsetLocationUuid           = Uuid::From16Bit(0x2B81);
static const Uuid kVolumeOffsetControlPointUuid       = Uuid::From16Bit(0x2B82);
static const Uuid kVolumeOffsetOutputDescriptionUuid  = Uuid::From16Bit(0x2B83);

static const Uuid kVolumeAudioInputUuid               = Uuid::From16Bit(0x1843);
static const Uuid kVolumeAudioInputStateUuid          = Uuid::From16Bit(0x2B77);
static const Uuid kVolumeAudioInputGainSettingUuid    = Uuid::From16Bit(0x2B78);
static const Uuid kVolumeAudioInputTypeUuid           = Uuid::From16Bit(0x2B79);
static const Uuid kVolumeAudioInputStatusUuid         = Uuid::From16Bit(0x2B7A);
static const Uuid kVolumeAudioInputControlPointUuid   = Uuid::From16Bit(0x2B7B);
static const Uuid kVolumeAudioInputDescriptionUuid    = Uuid::From16Bit(0x2B7C);

/* clang-format on */

struct VolumeOperation {
  int operation_id_;
  int group_id_;

  bool started_;
  bool is_autonomous_;

  uint8_t opcode_;
  std::vector<uint8_t> arguments_;

  std::vector<RawAddress> devices_;
  alarm_t* operation_timeout_;

  VolumeOperation(int operation_id, int group_id, bool is_autonomous, uint8_t opcode,
                  std::vector<uint8_t> arguments, std::vector<RawAddress> devices)
      : operation_id_(operation_id),
        group_id_(group_id),
        is_autonomous_(is_autonomous),
        opcode_(opcode),
        arguments_(arguments),
        devices_(devices) {
    auto name = "operation_timeout_" + std::to_string(operation_id);
    operation_timeout_ = alarm_new(name.c_str());
    started_ = false;
  }

  // Disallow copying due to owning operation_timeout_t which is freed in the
  // destructor - prevents double free.
  VolumeOperation(const VolumeOperation&) = delete;
  VolumeOperation& operator=(const VolumeOperation&) = delete;

  ~VolumeOperation() {
    if (operation_timeout_ == nullptr) {
      log::warn("operation_timeout_ should not be null, id {}, device cnt {}", operation_id_,
                devices_.size());
      return;
    }

    if (alarm_is_scheduled(operation_timeout_)) {
      alarm_cancel(operation_timeout_);
    }

    alarm_free(operation_timeout_);
    operation_timeout_ = nullptr;
  }

  bool IsGroupOperation(void) { return group_id_ != bluetooth::groups::kGroupUnknown; }

  bool IsStarted(void) { return started_; }
  void Start(void) { started_ = true; }
};

struct GainSettings {
  uint8_t unit;
  int8_t min;
  int8_t max;

  GainSettings() : unit(0), min(0), max(0) {}
};

struct VolumeAudioInput {
  uint8_t id;
  bool mute;
  int8_t gain_value;
  VolumeInputStatus status;
  VolumeInputType type;
  uint8_t change_counter;
  uint8_t mode;
  std::string description;
  uint16_t service_handle;
  uint16_t state_handle;
  uint16_t state_ccc_handle;
  uint16_t gain_setting_handle;
  uint16_t type_handle;
  uint16_t status_handle;
  uint16_t status_ccc_handle;
  uint16_t control_point_handle;
  uint16_t description_handle;
  uint16_t description_ccc_handle;
  bool description_writable;
  struct GainSettings gain_settings;

  explicit VolumeAudioInput(uint16_t service_handle)
      : id(0),
        mute(false),
        gain_value(0),
        status(VolumeInputStatus::Inactive),
        type(VolumeInputType::Unspecified),
        change_counter(0),
        mode(0),
        description(""),
        service_handle(service_handle),
        state_handle(0),
        state_ccc_handle(0),
        gain_setting_handle(0),
        type_handle(0),
        status_handle(0),
        status_ccc_handle(0),
        control_point_handle(0),
        description_handle(0),
        description_ccc_handle(0),
        description_writable(false),
        gain_settings(GainSettings()) {}
};

class VolumeAudioInputs {
public:
  void Add(VolumeAudioInput input) {
    input.id = (uint8_t)Size() + 1;
    volume_audio_inputs.push_back(input);
  }

  VolumeAudioInput* FindByType(VolumeInputType type) {
    auto iter = std::find_if(volume_audio_inputs.begin(), volume_audio_inputs.end(),
                             [&type](const VolumeAudioInput& item) { return item.type == type; });

    return (iter == volume_audio_inputs.end()) ? nullptr : &(*iter);
  }

  VolumeAudioInput* FindByServiceHandle(uint16_t service_handle) {
    auto iter = std::find_if(volume_audio_inputs.begin(), volume_audio_inputs.end(),
                             [&service_handle](const VolumeAudioInput& item) {
                               return item.service_handle == service_handle;
                             });

    return (iter == volume_audio_inputs.end()) ? nullptr : &(*iter);
  }

  VolumeAudioInput* FindById(uint8_t id) {
    auto iter = std::find_if(volume_audio_inputs.begin(), volume_audio_inputs.end(),
                             [&id](const VolumeAudioInput& item) { return item.id == id; });

    return (iter == volume_audio_inputs.end()) ? nullptr : &(*iter);
  }

  void Clear() { volume_audio_inputs.clear(); }

  size_t Size() { return volume_audio_inputs.size(); }

  void Dump(int fd) {
    std::stringstream stream;
    int n = Size();
    stream << "     == number of inputs: " << n << " == \n";

    for (auto const v : volume_audio_inputs) {
      stream << "   id: " << +v.id << "\n"
             << "    description: " << v.description << "\n"
             << "    type: " << static_cast<int>(v.type) << "\n"
             << "    status: " << static_cast<int>(v.status) << "\n"
             << "    changeCnt: " << +v.change_counter << "\n"
             << "    service_handle: " << +v.service_handle << "\n"
             << "    description_writable" << v.description_writable << "\n";
    }
    dprintf(fd, "%s", stream.str().c_str());
  }

  std::vector<VolumeAudioInput> volume_audio_inputs;
};

struct VolumeOffset {
  uint8_t id;
  uint8_t change_counter;
  int16_t offset;
  uint32_t location;
  std::string description;
  uint16_t service_handle;
  uint16_t state_handle;
  uint16_t state_ccc_handle;
  uint16_t audio_location_handle;
  uint16_t audio_location_ccc_handle;
  uint16_t audio_descr_handle;
  uint16_t audio_descr_ccc_handle;
  uint16_t control_point_handle;
  bool audio_location_writable;
  bool audio_descr_writable;

  explicit VolumeOffset(uint16_t service_handle)
      : id(0),
        change_counter(0),
        offset(0),
        location(0),
        description(""),
        service_handle(service_handle),
        state_handle(0),
        state_ccc_handle(0),
        audio_location_handle(0),
        audio_location_ccc_handle(0),
        audio_descr_handle(0),
        audio_descr_ccc_handle(0),
        control_point_handle(0),
        audio_location_writable(false),
        audio_descr_writable(false) {}
};

class VolumeOffsets {
public:
  void Add(VolumeOffset offset) {
    offset.id = static_cast<uint8_t>(Size()) + 1;
    volume_offsets.push_back(offset);
  }

  VolumeOffset* FindByLocation(uint8_t location) {
    auto iter = std::find_if(
            volume_offsets.begin(), volume_offsets.end(),
            [&location](const VolumeOffset& item) { return item.location == location; });

    return (iter == volume_offsets.end()) ? nullptr : &(*iter);
  }

  VolumeOffset* FindByServiceHandle(uint16_t service_handle) {
    auto iter = std::find_if(volume_offsets.begin(), volume_offsets.end(),
                             [&service_handle](const VolumeOffset& item) {
                               return item.service_handle == service_handle;
                             });

    return (iter == volume_offsets.end()) ? nullptr : &(*iter);
  }

  VolumeOffset* FindById(uint16_t id) {
    auto iter = std::find_if(volume_offsets.begin(), volume_offsets.end(),
                             [&id](const VolumeOffset& item) { return item.id == id; });

    return (iter == volume_offsets.end()) ? nullptr : &(*iter);
  }

  void Clear() { volume_offsets.clear(); }

  size_t Size() { return volume_offsets.size(); }

  void Dump(int fd) {
    std::stringstream stream;
    int n = Size();
    stream << "     == number of offsets: " << n << " == \n";

    for (auto const v : volume_offsets) {
      stream << "   id: " << +v.id << "\n"
             << "    offset: " << +v.offset << "\n"
             << "    changeCnt: " << +v.change_counter << "\n"
             << "    location: " << +v.location << "\n"
             << "    description: " << v.description << "\n"
             << "    service_handle: " << +v.service_handle << "\n"
             << "    audio_location_writable " << v.audio_location_writable << "\n"
             << "    audio_descr_writable: " << v.audio_descr_writable << "\n";
    }
    dprintf(fd, "%s", stream.str().c_str());
  }

  std::vector<VolumeOffset> volume_offsets;
};

}  // namespace internal
}  // namespace vc
}  // namespace bluetooth
