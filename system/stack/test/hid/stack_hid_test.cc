/*
 *  Copyright 2021 The Android Open Source Project
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
 */

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "common/message_loop_thread.h"
#include "stack/hid/hidh_int.h"
#include "stack/include/hci_error_code.h"
#include "test/common/mock_functions.h"
#include "test/mock/mock_stack_l2cap_interface.h"

// TODO(b/369381361) Enfore -Wmissing-prototypes
#pragma GCC diagnostic ignored "-Wmissing-prototypes"

bluetooth::common::MessageLoopThread* get_main_thread() { return nullptr; }
tHCI_REASON btm_get_acl_disc_reason_code(void) { return HCI_SUCCESS; }

using ::testing::_;
using ::testing::DoAll;
using ::testing::NotNull;
using ::testing::Pointee;
using ::testing::Return;
using ::testing::ReturnArg;
using ::testing::SaveArg;
using ::testing::SaveArgPointee;
using ::testing::StrEq;
using ::testing::StrictMock;
using ::testing::Test;

namespace {

class StackHidTest : public Test {
public:
protected:
  void SetUp() override {
    reset_mock_function_count_map();
    bluetooth::testing::stack::l2cap::set_interface(&l2cap_interface_);
  }
  void TearDown() override { bluetooth::testing::stack::l2cap::reset_interface(); }

  bluetooth::testing::stack::l2cap::Mock l2cap_interface_;
  const tL2CAP_APPL_INFO* p_cb_info_;
};

TEST_F(StackHidTest, disconnect_bad_cid) {
  tL2CAP_APPL_INFO l2cap_callbacks{};
  EXPECT_CALL(l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
          .WillRepeatedly(DoAll(SaveArg<1>(&l2cap_callbacks), ::testing::ReturnArg<0>()));

  tHID_STATUS status = hidh_conn_reg();
  ASSERT_EQ(HID_SUCCESS, status);

  l2cap_callbacks.pL2CA_Error_Cb(123, static_cast<uint16_t>(tL2CAP_CONN::L2CAP_CONN_NO_RESOURCES));
}

}  // namespace
