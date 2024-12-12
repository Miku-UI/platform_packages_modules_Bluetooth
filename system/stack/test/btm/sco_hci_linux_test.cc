/*
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
 */

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "stack/btm/btm_sco.h"
#include "stack/test/btm/btm_test_fixtures.h"
#include "test/common/mock_functions.h"
#include "udrv/include/uipc.h"

extern std::unique_ptr<tUIPC_STATE> mock_uipc_init_ret;
extern uint32_t mock_uipc_read_ret;
extern bool mock_uipc_send_ret;

namespace {

class ScoHciTest : public BtmWithMocksTest {
public:
protected:
  void SetUp() override {
    BtmWithMocksTest::SetUp();
    mock_uipc_init_ret = nullptr;
    mock_uipc_read_ret = 0;
    mock_uipc_send_ret = true;
  }
  void TearDown() override { BtmWithMocksTest::TearDown(); }
};

class ScoHciWithOpenCleanTest : public ScoHciTest {
public:
protected:
  void SetUp() override {
    ScoHciTest::SetUp();
    mock_uipc_init_ret = std::make_unique<tUIPC_STATE>();
    bluetooth::audio::sco::open();
  }
  void TearDown() override { bluetooth::audio::sco::cleanup(); }
};

TEST_F(ScoHciTest, ScoOverHciOpenFail) {
  bluetooth::audio::sco::open();
  ASSERT_EQ(get_func_call_count("UIPC_Init"), 1);
  ASSERT_EQ(get_func_call_count("UIPC_Open"), 0);
  bluetooth::audio::sco::cleanup();

  // UIPC is nullptr and shouldn't require an actual call of UIPC_Close;
  ASSERT_EQ(get_func_call_count("UIPC_Close"), 0);
}

TEST_F(ScoHciWithOpenCleanTest, ScoOverHciOpenClean) {
  ASSERT_EQ(get_func_call_count("UIPC_Init"), 1);
  ASSERT_EQ(get_func_call_count("UIPC_Open"), 1);
  ASSERT_EQ(mock_uipc_init_ret, nullptr);

  mock_uipc_init_ret = std::make_unique<tUIPC_STATE>();
  // Double open will override uipc
  bluetooth::audio::sco::open();
  ASSERT_EQ(get_func_call_count("UIPC_Init"), 2);
  ASSERT_EQ(get_func_call_count("UIPC_Open"), 2);
  ASSERT_EQ(mock_uipc_init_ret, nullptr);

  bluetooth::audio::sco::cleanup();
  ASSERT_EQ(get_func_call_count("UIPC_Close"), 1);

  // Double clean shouldn't fail
  bluetooth::audio::sco::cleanup();
  ASSERT_EQ(get_func_call_count("UIPC_Close"), 1);
}

TEST_F(ScoHciTest, ScoOverHciReadNoOpen) {
  uint8_t buf[100];
  ASSERT_EQ(bluetooth::audio::sco::read(buf, sizeof(buf)), size_t(0));
  ASSERT_EQ(get_func_call_count("UIPC_Read"), 0);
}

TEST_F(ScoHciWithOpenCleanTest, ScoOverHciRead) {
  uint8_t buf[100];
  // The UPIC should be ready
  ASSERT_EQ(get_func_call_count("UIPC_Init"), 1);
  ASSERT_EQ(get_func_call_count("UIPC_Open"), 1);
  ASSERT_EQ(mock_uipc_init_ret, nullptr);

  mock_uipc_read_ret = sizeof(buf);
  ASSERT_EQ(bluetooth::audio::sco::read(buf, sizeof(buf)), mock_uipc_read_ret);
  ASSERT_EQ(get_func_call_count("UIPC_Read"), 1);
}

TEST_F(ScoHciTest, ScoOverHciWriteNoOpen) {
  uint8_t buf[100];
  bluetooth::audio::sco::write(buf, sizeof(buf));
  ASSERT_EQ(get_func_call_count("UIPC_Send"), 0);
}

TEST_F(ScoHciWithOpenCleanTest, ScoOverHciWrite) {
  uint8_t buf[100];
  // The UPIC should be ready
  ASSERT_EQ(get_func_call_count("UIPC_Init"), 1);
  ASSERT_EQ(get_func_call_count("UIPC_Open"), 1);
  ASSERT_EQ(mock_uipc_init_ret, nullptr);

  ASSERT_EQ(bluetooth::audio::sco::write(buf, sizeof(buf)), sizeof(buf));
  ASSERT_EQ(get_func_call_count("UIPC_Send"), 1);

  // Send fails
  mock_uipc_send_ret = false;
  ASSERT_EQ(bluetooth::audio::sco::write(buf, sizeof(buf)), size_t(0));
  ASSERT_EQ(get_func_call_count("UIPC_Send"), 2);
}
}  // namespace
