/*
 * Copyright 2024 The Android Open Source Project
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

#include <cstdint>

class LeConnectionParameters {
public:
  static constexpr uint16_t kAggressiveConnThreshold = 2;
  static constexpr uint16_t kMinConnIntervalAggressive = 0x0008;  // 8, *1.25 becomes 10ms
  static constexpr uint16_t kMaxConnIntervalAggressive = 0x0010;  // 16, *1.25 becomes 20ms
  static constexpr uint16_t kMinConnIntervalRelaxed = 0x0018;     // 24, *1.25 becomes 30ms
  static constexpr uint16_t kMaxConnIntervalRelaxed = 0x0028;     // 40, *1.25 becomes 50ms

  static const std::string kPropertyAggressiveConnThreshold;
  static const std::string kPropertyMinConnIntervalAggressive;
  static const std::string kPropertyMaxConnIntervalAggressive;
  static const std::string kPropertyMinConnIntervalRelaxed;
  static const std::string kPropertyMaxConnIntervalRelaxed;

  static void InitConnParamsWithSystemProperties();
  static uint32_t GetAggressiveConnThreshold();
  static uint32_t GetMinConnIntervalAggressive();
  static uint32_t GetMaxConnIntervalAggressive();
  static uint32_t GetMinConnIntervalRelaxed();
  static uint32_t GetMaxConnIntervalRelaxed();

private:
  static bool initialized;
  static uint32_t aggressive_conn_threshold;
  static uint32_t min_conn_interval_aggressive;
  static uint32_t max_conn_interval_aggressive;
  static uint32_t min_conn_interval_relaxed;
  static uint32_t max_conn_interval_relaxed;
};
