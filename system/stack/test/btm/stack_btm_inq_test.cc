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

#include <bluetooth/log.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <future>

#include "common/contextual_callback.h"
#include "hci/address.h"
#include "hci/class_of_device.h"
#include "hci/hci_layer_fake.h"
#include "hci/hci_packets.h"
#include "stack/btm/btm_int_types.h"
#include "stack/include/btm_inq.h"
#include "stack/include/btm_status.h"
#include "stack/include/hci_error_code.h"
#include "stack/include/inq_hci_link_interface.h"
#include "stack/include/main_thread.h"
#include "stack/test/btm/btm_test_fixtures.h"
#include "test/common/mock_functions.h"
#include "test/mock/mock_main_shim_entry.h"
#include "types/raw_address.h"

// TODO(b/369381361) Enfore -Wmissing-prototypes
#pragma GCC diagnostic ignored "-Wmissing-prototypes"

extern tBTM_CB btm_cb;

using bluetooth::common::ContextualCallback;
using bluetooth::common::ContextualOnceCallback;
using bluetooth::hci::Address;
using bluetooth::hci::CommandCompleteView;
using bluetooth::hci::CommandStatusView;
using bluetooth::hci::EventView;
using bluetooth::hci::ExtendedInquiryResultBuilder;
using bluetooth::hci::ExtendedInquiryResultView;
using bluetooth::hci::InquiryResponse;
using bluetooth::hci::InquiryResultBuilder;
using bluetooth::hci::InquiryResultView;
using bluetooth::hci::InquiryResultWithRssiBuilder;
using bluetooth::hci::InquiryResultWithRssiView;
using bluetooth::hci::InquiryStatusBuilder;
using bluetooth::hci::InquiryView;
using bluetooth::hci::OpCode;
using bluetooth::hci::EventCode::EXTENDED_INQUIRY_RESULT;
using bluetooth::hci::EventCode::INQUIRY_COMPLETE;
using bluetooth::hci::EventCode::INQUIRY_RESULT;
using bluetooth::hci::EventCode::INQUIRY_RESULT_WITH_RSSI;
using testing::_;
using testing::A;
using testing::Matcher;
using testing::NiceMock;
using testing::Return;
using testing::SaveArg;

namespace {
const Address kAddress = Address({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
const Address kAddress2 = Address({0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc});
const RawAddress kRawAddress = RawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
const RawAddress kRawAddress2 = RawAddress({0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc});
const BD_NAME kBdName = {'A', ' ', 'B', 'd', ' ', 'N', 'a', 'm', 'e', '\0'};
const BD_NAME kEmptyName = "";

tBTM_REMOTE_DEV_NAME gBTM_REMOTE_DEV_NAME{};
bool gBTM_REMOTE_DEV_NAME_sent{false};

static constexpr uint8_t kNumCommandPackets = 1;

}  // namespace

class BtmInqTest : public BtmWithMocksTest {
protected:
  void SetUp() override {
    BtmWithMocksTest::SetUp();
    btm_cb = {};
  }

  void TearDown() override { BtmWithMocksTest::TearDown(); }
};

class BtmInqActiveTest : public BtmInqTest {
protected:
  void SetUp() override {
    BtmInqTest::SetUp();
    gBTM_REMOTE_DEV_NAME = {};
    gBTM_REMOTE_DEV_NAME_sent = false;

    btm_cb.rnr.remname_active = true;
    btm_cb.rnr.remname_bda = kRawAddress;
    btm_cb.rnr.remname_dev_type = BT_DEVICE_TYPE_BREDR;
    btm_cb.rnr.p_remname_cmpl_cb = [](const tBTM_REMOTE_DEV_NAME* name) {
      gBTM_REMOTE_DEV_NAME = *name;
      gBTM_REMOTE_DEV_NAME_sent = true;
    };
  }

  void TearDown() override { BtmInqTest::TearDown(); }
};

class BtmInquiryCallbacks {
public:
  virtual ~BtmInquiryCallbacks() = default;
  virtual void btm_inq_results_cb(tBTM_INQ_RESULTS*, const uint8_t*, uint16_t) = 0;
  virtual void btm_inq_cmpl_cb(void*) = 0;
};

class MockBtmInquiryCallbacks : public BtmInquiryCallbacks {
public:
  MOCK_METHOD(void, btm_inq_results_cb,
              (tBTM_INQ_RESULTS * p_inq_results, const uint8_t* p_eir, uint16_t eir_len),
              (override));
  MOCK_METHOD(void, btm_inq_cmpl_cb, (void*), (override));
};

MockBtmInquiryCallbacks* inquiry_callback_ptr = nullptr;

void btm_inq_results_cb(tBTM_INQ_RESULTS* p_inq_results, const uint8_t* p_eir, uint16_t eir_len) {
  inquiry_callback_ptr->btm_inq_results_cb(p_inq_results, p_eir, eir_len);
}

void btm_inq_cmpl_cb(void* p1) { inquiry_callback_ptr->btm_inq_cmpl_cb(p1); }

class BtmDeviceInquiryTest : public BtmInqTest {
protected:
  void SetUp() override {
    BtmInqTest::SetUp();
    main_thread_start_up();
    inquiry_callback_ptr = &callbacks_;
    bluetooth::hci::testing::mock_controller_ = &controller_;
    ON_CALL(controller_, SupportsBle()).WillByDefault(Return(true));
    bluetooth::hci::testing::mock_hci_layer_ = &hci_layer_;

    // Start Inquiry
    EXPECT_EQ(tBTM_STATUS::BTM_CMD_STARTED, BTM_StartInquiry(btm_inq_results_cb, btm_inq_cmpl_cb));
    auto view = hci_layer_.GetCommand(OpCode::INQUIRY);
    hci_layer_.IncomingEvent(
            InquiryStatusBuilder::Create(bluetooth::hci::ErrorCode::SUCCESS, kNumCommandPackets));

    // Send one response to synchronize
    std::promise<void> first_result_promise;
    auto first_result = first_result_promise.get_future();
    EXPECT_CALL(*inquiry_callback_ptr, btm_inq_results_cb(_, _, _))
            .WillOnce([&first_result_promise]() { first_result_promise.set_value(); })
            .RetiresOnSaturation();

    InquiryResponse one_device(kAddress, bluetooth::hci::PageScanRepetitionMode::R0,
                               bluetooth::hci::ClassOfDevice(), 0x1234);
    hci_layer_.IncomingEvent(InquiryResultBuilder::Create({one_device}));

    EXPECT_EQ(std::future_status::ready, first_result.wait_for(std::chrono::seconds(1)));
  }

  void TearDown() override {
    BTM_CancelInquiry();
    inquiry_callback_ptr = nullptr;
    main_thread_shut_down();
    BtmInqTest::TearDown();
  }

  NiceMock<bluetooth::hci::testing::MockControllerInterface> controller_;
  bluetooth::hci::HciLayerFake hci_layer_;
  ContextualCallback<void(EventView)> on_exteneded_inq_result_;
  ContextualCallback<void(EventView)> on_inq_complete_;
  ContextualCallback<void(EventView)> on_inq_result_;
  ContextualCallback<void(EventView)> on_inq_result_with_rssi_;
  MockBtmInquiryCallbacks callbacks_;
};

TEST_F(BtmDeviceInquiryTest, bta_dm_disc_device_discovery_single_result) {
  std::promise<void> one_result_promise;
  auto one_result = one_result_promise.get_future();
  EXPECT_CALL(*inquiry_callback_ptr, btm_inq_results_cb(_, _, _))
          .WillOnce([&one_result_promise]() { one_result_promise.set_value(); })
          .RetiresOnSaturation();

  InquiryResponse one_device(kAddress2, bluetooth::hci::PageScanRepetitionMode::R0,
                             bluetooth::hci::ClassOfDevice(), 0x2345);
  hci_layer_.IncomingEvent(InquiryResultBuilder::Create({one_device}));

  EXPECT_EQ(std::future_status::ready, one_result.wait_for(std::chrono::seconds(1)));
}
