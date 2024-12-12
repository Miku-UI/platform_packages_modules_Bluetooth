/*
 * Copyright 2022 The Android Open Source Project
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

#include "distance_measurement_manager.h"

#include "bta/include/bta_ras_api.h"
#include "btif/include/btif_common.h"
#include "hci/distance_measurement_manager.h"
#include "main/shim/entry.h"
#include "main/shim/helpers.h"
#include "stack/include/acl_api.h"
#include "stack/include/main_thread.h"

using bluetooth::hci::DistanceMeasurementErrorCode;
using bluetooth::hci::DistanceMeasurementMethod;
using namespace bluetooth;

extern tBTM_SEC_DEV_REC* btm_find_dev(const RawAddress& bd_addr);

class DistanceMeasurementInterfaceImpl : public DistanceMeasurementInterface,
                                         public bluetooth::hci::DistanceMeasurementCallbacks,
                                         public bluetooth::ras::RasServerCallbacks,
                                         public bluetooth::ras::RasClientCallbacks {
public:
  ~DistanceMeasurementInterfaceImpl() override {}

  void Init() {
    // Register callback
    bluetooth::shim::GetDistanceMeasurementManager()->RegisterDistanceMeasurementCallbacks(this);
    bluetooth::ras::GetRasServer()->RegisterCallbacks(this);
    bluetooth::ras::GetRasClient()->RegisterCallbacks(this);
  }

  /**
   * Gets the BLE connection handle, must be called from main_thread.
   * @param bd_addr could be random, rpa or identity address.
   * @return BLE ACL handle
   */
  uint16_t GetConnectionHandle(const RawAddress& bd_addr) {
    tBTM_SEC_DEV_REC* p_sec_dev_rec = btm_find_dev(bd_addr);
    if (p_sec_dev_rec != nullptr) {
      return p_sec_dev_rec->get_ble_hci_handle();
    }
    return kIllegalConnectionHandle;
  }

  void RegisterDistanceMeasurementCallbacks(::DistanceMeasurementCallbacks* callbacks) {
    distance_measurement_callbacks_ = callbacks;
  }

  void StartDistanceMeasurement(RawAddress identity_addr, uint16_t interval, uint8_t method) {
    do_in_main_thread(base::BindOnce(&DistanceMeasurementInterfaceImpl::DoStartDistanceMeasurement,
                                     base::Unretained(this), identity_addr, interval, method));
  }

  void DoStartDistanceMeasurement(RawAddress identity_addr, uint16_t interval, uint8_t method) {
    DistanceMeasurementMethod distance_measurement_method =
            static_cast<DistanceMeasurementMethod>(method);
    bluetooth::shim::GetDistanceMeasurementManager()->StartDistanceMeasurement(
            bluetooth::ToGdAddress(identity_addr), GetConnectionHandle(identity_addr), interval,
            distance_measurement_method);
    if (distance_measurement_method == DistanceMeasurementMethod::METHOD_CS) {
      bluetooth::ras::GetRasClient()->Connect(identity_addr);
    }
  }

  void StopDistanceMeasurement(RawAddress identity_addr, uint8_t method) {
    do_in_main_thread(base::BindOnce(&DistanceMeasurementInterfaceImpl::DoStopDistanceMeasurement,
                                     base::Unretained(this), identity_addr, method));
  }

  void DoStopDistanceMeasurement(RawAddress identity_addr, uint8_t method) {
    bluetooth::shim::GetDistanceMeasurementManager()->StopDistanceMeasurement(
            bluetooth::ToGdAddress(identity_addr), GetConnectionHandle(identity_addr),
            static_cast<DistanceMeasurementMethod>(method));
  }

  // Callbacks of bluetooth::hci::DistanceMeasurementCallbacks
  void OnDistanceMeasurementStarted(bluetooth::hci::Address address,
                                    DistanceMeasurementMethod method) override {
    do_in_jni_thread(base::BindOnce(&::DistanceMeasurementCallbacks::OnDistanceMeasurementStarted,
                                    base::Unretained(distance_measurement_callbacks_),
                                    bluetooth::ToRawAddress(address),
                                    static_cast<uint8_t>(method)));
  }

  void OnDistanceMeasurementStartFail(bluetooth::hci::Address address,
                                      DistanceMeasurementErrorCode reason,
                                      DistanceMeasurementMethod method) override {
    do_in_jni_thread(base::BindOnce(&::DistanceMeasurementCallbacks::OnDistanceMeasurementStartFail,
                                    base::Unretained(distance_measurement_callbacks_),
                                    bluetooth::ToRawAddress(address), static_cast<uint8_t>(reason),
                                    static_cast<uint8_t>(method)));
  }

  void OnDistanceMeasurementStopped(bluetooth::hci::Address address,
                                    DistanceMeasurementErrorCode reason,
                                    DistanceMeasurementMethod method) override {
    do_in_jni_thread(base::BindOnce(&::DistanceMeasurementCallbacks::OnDistanceMeasurementStopped,
                                    base::Unretained(distance_measurement_callbacks_),
                                    bluetooth::ToRawAddress(address), static_cast<uint8_t>(reason),
                                    static_cast<uint8_t>(method)));
  }

  void OnDistanceMeasurementResult(bluetooth::hci::Address address, uint32_t centimeter,
                                   uint32_t error_centimeter, int azimuth_angle,
                                   int error_azimuth_angle, int altitude_angle,
                                   int error_altitude_angle,
                                   DistanceMeasurementMethod method) override {
    do_in_jni_thread(base::BindOnce(&::DistanceMeasurementCallbacks::OnDistanceMeasurementResult,
                                    base::Unretained(distance_measurement_callbacks_),
                                    bluetooth::ToRawAddress(address), centimeter, error_centimeter,
                                    azimuth_angle, error_azimuth_angle, altitude_angle,
                                    error_altitude_angle, static_cast<uint8_t>(method)));
  }

  void OnRasFragmentReady(bluetooth::hci::Address address, uint16_t procedure_counter, bool is_last,
                          std::vector<uint8_t> raw_data) {
    bluetooth::ras::GetRasServer()->PushProcedureData(bluetooth::ToRawAddress(address),
                                                      procedure_counter, is_last, raw_data);
  }

  void OnVendorSpecificCharacteristics(std::vector<bluetooth::hal::VendorSpecificCharacteristic>
                                               vendor_specific_characteristics) {
    std::vector<bluetooth::ras::VendorSpecificCharacteristic> ras_vendor_specific_characteristics;
    for (auto& characteristic : vendor_specific_characteristics) {
      bluetooth::ras::VendorSpecificCharacteristic vendor_specific_characteristic;
      vendor_specific_characteristic.characteristicUuid_ =
              bluetooth::Uuid::From128BitBE(characteristic.characteristicUuid_);
      vendor_specific_characteristic.value_ = characteristic.value_;
      ras_vendor_specific_characteristics.emplace_back(vendor_specific_characteristic);
    }
    bluetooth::ras::GetRasServer()->SetVendorSpecificCharacteristic(
            ras_vendor_specific_characteristics);
  }

  void OnVendorSpecificReply(bluetooth::hci::Address address,
                             std::vector<bluetooth::hal::VendorSpecificCharacteristic>
                                     vendor_specific_characteristics) {
    std::vector<bluetooth::ras::VendorSpecificCharacteristic> ras_vendor_specific_characteristics;
    for (auto& characteristic : vendor_specific_characteristics) {
      bluetooth::ras::VendorSpecificCharacteristic vendor_specific_characteristic;
      vendor_specific_characteristic.characteristicUuid_ =
              bluetooth::Uuid::From128BitBE(characteristic.characteristicUuid_);
      vendor_specific_characteristic.value_ = characteristic.value_;
      ras_vendor_specific_characteristics.emplace_back(vendor_specific_characteristic);
    }
    bluetooth::ras::GetRasClient()->SendVendorSpecificReply(bluetooth::ToRawAddress(address),
                                                            ras_vendor_specific_characteristics);
  }

  void OnHandleVendorSpecificReplyComplete(bluetooth::hci::Address address, bool success) {
    bluetooth::ras::GetRasServer()->HandleVendorSpecificReplyComplete(
            bluetooth::ToRawAddress(address), success);
  }

  // Must be called from main_thread
  // Callbacks of bluetooth::ras::RasServerCallbacks
  void OnVendorSpecificReply(
          const RawAddress& address,
          const std::vector<bluetooth::ras::VendorSpecificCharacteristic>& vendor_specific_reply) {
    std::vector<bluetooth::hal::VendorSpecificCharacteristic> hal_vendor_specific_characteristics;
    for (auto& characteristic : vendor_specific_reply) {
      bluetooth::hal::VendorSpecificCharacteristic vendor_specific_characteristic;
      vendor_specific_characteristic.characteristicUuid_ =
              characteristic.characteristicUuid_.To128BitBE();
      vendor_specific_characteristic.value_ = characteristic.reply_value_;
      hal_vendor_specific_characteristics.emplace_back(vendor_specific_characteristic);
    }
    bluetooth::shim::GetDistanceMeasurementManager()->HandleVendorSpecificReply(
            bluetooth::ToGdAddress(address), GetConnectionHandle(address),
            hal_vendor_specific_characteristics);
  }

  // Must be called from main_thread
  // Callbacks of bluetooth::ras::RasClientCallbacks
  void OnConnected(const RawAddress& address, uint16_t att_handle,
                   const std::vector<bluetooth::ras::VendorSpecificCharacteristic>&
                           vendor_specific_characteristics) {
    std::vector<bluetooth::hal::VendorSpecificCharacteristic> hal_vendor_specific_characteristics;
    for (auto& characteristic : vendor_specific_characteristics) {
      bluetooth::hal::VendorSpecificCharacteristic vendor_specific_characteristic;
      vendor_specific_characteristic.characteristicUuid_ =
              characteristic.characteristicUuid_.To128BitBE();
      vendor_specific_characteristic.value_ = characteristic.value_;
      hal_vendor_specific_characteristics.emplace_back(vendor_specific_characteristic);
    }

    bluetooth::shim::GetDistanceMeasurementManager()->HandleRasConnectedEvent(
            bluetooth::ToGdAddress(address), GetConnectionHandle(address), att_handle,
            hal_vendor_specific_characteristics);
  }

  void OnDisconnected(const RawAddress& address) {
    bluetooth::shim::GetDistanceMeasurementManager()->HandleRasDisconnectedEvent(
            bluetooth::ToGdAddress(address));
  }

  // Must be called from main_thread
  void OnWriteVendorSpecificReplyComplete(const RawAddress& address, bool success) {
    bluetooth::shim::GetDistanceMeasurementManager()->HandleVendorSpecificReplyComplete(
            bluetooth::ToGdAddress(address), GetConnectionHandle(address), success);
  }

  // Must be called from main_thread
  void OnRemoteData(const RawAddress& address, const std::vector<uint8_t>& data) {
    bluetooth::shim::GetDistanceMeasurementManager()->HandleRemoteData(
            bluetooth::ToGdAddress(address), GetConnectionHandle(address), data);
  }

private:
  ::DistanceMeasurementCallbacks* distance_measurement_callbacks_;
  static constexpr uint16_t kIllegalConnectionHandle = 0xffff;
};

DistanceMeasurementInterfaceImpl* distance_measurement_instance = nullptr;

void bluetooth::shim::init_distance_measurement_manager() {
  static_cast<DistanceMeasurementInterfaceImpl*>(
          bluetooth::shim::get_distance_measurement_instance())
          ->Init();
}

DistanceMeasurementInterface* bluetooth::shim::get_distance_measurement_instance() {
  if (distance_measurement_instance == nullptr) {
    distance_measurement_instance = new DistanceMeasurementInterfaceImpl();
  }
  return distance_measurement_instance;
}
