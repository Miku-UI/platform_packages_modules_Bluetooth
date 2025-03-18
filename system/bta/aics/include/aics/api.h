// Copyright (C) 2024 The Android Open Source Project
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

// http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma once

#ifndef TARGET_FLOSS
#include <bluetooth/constants/aics/GainMode.h>
#include <bluetooth/constants/aics/Mute.h>
#endif

#include <bluetooth/log.h>

#include <cstdint>

namespace bluetooth::aics {

#ifndef TARGET_FLOSS
using Mute = bluetooth::constants::aics::Mute;
using GainMode = bluetooth::constants::aics::GainMode;
#else
// TODO: b/376941621 Support the aidl generation in FLOSS
enum class Mute : uint8_t { NOT_MUTED = 0x00, MUTED = 0x01, DISABLED = 0x02 };
enum class GainMode : uint8_t {
  MANUAL_ONLY = 0x00,
  AUTOMATIC_ONLY = 0x01,
  MANUAL = 0x02,
  AUTOMATIC = 0x03
};
#endif

/** Check if the data is a correct Mute value */
bool isValidAudioInputMuteValue(uint8_t data);

/** Convert valid data into a Mute value. Abort if data is not valid */
Mute parseMuteField(uint8_t data);

/** Check if the data is a correct GainMode value */
bool isValidAudioInputGainModeValue(uint8_t data);

/** Convert valid data into a Mute value. Abort if data is not valid */
GainMode parseGainModeField(uint8_t data);
}  // namespace bluetooth::aics

namespace std {
template <>
struct formatter<bluetooth::aics::Mute> : enum_formatter<bluetooth::aics::Mute> {};
template <>
struct formatter<bluetooth::aics::GainMode> : enum_formatter<bluetooth::aics::GainMode> {};
}  // namespace std
