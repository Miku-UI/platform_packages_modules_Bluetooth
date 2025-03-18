/******************************************************************************
 *
 *  Copyright 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

/*
 * MGMT stub
 */

#include <bluetooth/log.h>

#include "os/mgmt.h"

namespace bluetooth {
namespace os {

uint16_t Management::getVendorSpecificCode(uint16_t vendor_specification) {
  log::debug("Using stub for vendor opcode 0x{:04x}", vendor_specification);
  return 0;
}

}  // namespace os
}  // namespace bluetooth
