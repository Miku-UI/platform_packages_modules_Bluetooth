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

#ifndef BTIF_HCI_VS_H_
#define BTIF_HCI_VS_H_

#include <bluetooth/log.h>

#include "include/hardware/bt_hci_vs.h"

namespace bluetooth {
namespace hci_vs {

BluetoothHciVendorSpecificInterface* getBluetoothHciVendorSpecificInterface();

}  // namespace hci_vs
}  // namespace bluetooth

#endif  // BTIF_HCI_VS_H_
