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

#include <bluetooth/log.h>
#include <hardware/bluetooth.h>

#include <bitset>
#include <sstream>

namespace bluetooth::le_audio {

class GmapClient {
public:
  void AddFromStorage(const RawAddress& addr, const uint8_t role, const uint16_t role_handle,
                      const uint8_t UGT_feature, const uint16_t UGT_feature_handle);

  void DebugDump(std::stringstream& stream);

  static bool IsGmapClientEnabled();

  static void UpdateGmapOffloaderSupport(bool value);

  GmapClient(const RawAddress& addr) : role_handle_(0), UGT_feature_handle_(0), addr_(addr) {}

  bool parseAndSaveGmapRole(uint16_t len, const uint8_t* value);

  bool parseAndSaveUGTFeature(uint16_t len, const uint8_t* value);

  std::bitset<8> getRole();

  uint16_t getRoleHandle();

  void setRoleHandle(uint16_t handle);

  std::bitset<8> getUGTFeature();

  uint16_t getUGTFeatureHandle();

  void setUGTFeatureHandle(uint16_t handle);

  constexpr static uint16_t kGmapRoleLen = 1;
  constexpr static uint16_t kGmapUGTFeatureLen = 1;

private:
  static bool is_offloader_support_gmap_;
  std::bitset<8> role_;
  uint16_t role_handle_;
  std::bitset<8> UGT_feature_;
  uint16_t UGT_feature_handle_;
  RawAddress addr_;
};
}  // namespace bluetooth::le_audio
