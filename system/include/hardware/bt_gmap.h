/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <hardware/bluetooth.h>

namespace bluetooth {
namespace gmap {

enum class RolesBitMask : uint8_t {
  UGG = 1 << 0,  // Unicast Game Gateway (UGG)
  UGT = 1 << 1,  // Unicast Game Terminal (UGT)
  BGS = 1 << 2,  // Broadcast Game Sender (BGS)
  BGR = 1 << 3,  // Broadcast Game Receiver (BGR)
};

enum class UGTFeatureBitMask : uint8_t {
  SourceFeatureSupport = 1 << 0,
  EightyKbpsSourceSupport = 1 << 1,
  SinkFeatureSupport = 1 << 2,
  SixtyFourSinkFeatureSupport = 1 << 3,
  MultiplexFeatureSupport = 1 << 4,
  MultisinkFeatureSupport = 1 << 5,
  MultisourceFeatureSupport = 1 << 6
};
enum class UGGFeatureBitMask : uint8_t {
  MultiplexFeatureSupport = 1 << 0,
  NinetySixKbpsSourceFeatureSupport = 1 << 1,
  MultisinkFeatureSupport = 1 << 2,
};
}  // namespace gmap
}  // namespace bluetooth

namespace std {
template <>
struct formatter<bluetooth::gmap::RolesBitMask> : enum_formatter<bluetooth::gmap::RolesBitMask> {};
template <>
struct formatter<bluetooth::gmap::UGTFeatureBitMask>
    : enum_formatter<bluetooth::gmap::UGTFeatureBitMask> {};
template <>
struct formatter<bluetooth::gmap::UGGFeatureBitMask>
    : enum_formatter<bluetooth::gmap::UGGFeatureBitMask> {};
}  // namespace std
