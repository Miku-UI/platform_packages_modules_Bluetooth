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

#define LOG_TAG "aics"

#include <bluetooth/log.h>

#include "aics/api.h"

namespace bluetooth::aics {

bool isValidAudioInputMuteValue(uint8_t data) {
  return data >= static_cast<uint8_t>(Mute::NOT_MUTED) &&
         data <= static_cast<uint8_t>(Mute::DISABLED);
}

Mute parseMuteField(uint8_t data) {
  log::assert_that(isValidAudioInputMuteValue(data), "Not a valid Mute Value");

  return static_cast<Mute>(data);
}

bool isValidAudioInputGainModeValue(uint8_t data) {
  return data >= static_cast<uint8_t>(GainMode::MANUAL_ONLY) &&
         data <= static_cast<uint8_t>(GainMode::AUTOMATIC);
}

GainMode parseGainModeField(uint8_t data) {
  log::assert_that(isValidAudioInputGainModeValue(data), "Not a valid GainMode Value");

  return static_cast<GainMode>(data);
}

}  // namespace bluetooth::aics
