/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

#include "client_audio.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <chrono>
#include <future>

#include "audio_hal_interface/le_audio_software.h"
#include "base/bind_helpers.h"
#include "common/message_loop_thread.h"
#include "hardware/bluetooth.h"
#include "osi/include/wakelock.h"

using ::testing::_;
using ::testing::Assign;
using ::testing::AtLeast;
using ::testing::DoAll;
using ::testing::Invoke;
using ::testing::Mock;
using ::testing::Return;
using ::testing::ReturnPointee;
using ::testing::SaveArg;
using std::chrono_literals::operator""ms;

bluetooth::common::MessageLoopThread message_loop_thread("test message loop");
bluetooth::common::MessageLoopThread* get_main_thread() {
  return &message_loop_thread;
}
bt_status_t do_in_main_thread(const base::Location& from_here,
                              base::OnceClosure task) {
  if (!message_loop_thread.DoInThread(from_here, std::move(task))) {
    LOG(ERROR) << __func__ << ": failed from " << from_here.ToString();
    return BT_STATUS_FAIL;
  }
  return BT_STATUS_SUCCESS;
}

static base::MessageLoop* message_loop_;
base::MessageLoop* get_main_message_loop() { return message_loop_; }

static void init_message_loop_thread() {
  message_loop_thread.StartUp();
  if (!message_loop_thread.IsRunning()) {
    FAIL() << "unable to create message loop thread.";
  }

  if (!message_loop_thread.EnableRealTimeScheduling())
    LOG(ERROR) << "Unable to set real time scheduling";

  message_loop_ = message_loop_thread.message_loop();
  if (message_loop_ == nullptr) FAIL() << "unable to get message loop.";
}

static void cleanup_message_loop_thread() {
  message_loop_ = nullptr;
  message_loop_thread.ShutDown();
}

using bluetooth::audio::le_audio::LeAudioClientInterface;

class MockLeAudioClientInterfaceSink : public LeAudioClientInterface::Sink {
 public:
  MOCK_METHOD((void), Cleanup, (), (override));
  MOCK_METHOD((void), SetPcmParameters,
              (const LeAudioClientInterface::PcmParameters& params),
              (override));
  MOCK_METHOD((void), SetRemoteDelay, (uint16_t delay_report_ms), (override));
  MOCK_METHOD((void), StartSession, (), (override));
  MOCK_METHOD((void), StopSession, (), (override));
  MOCK_METHOD((void), ConfirmStreamingRequest, (), (override));
  MOCK_METHOD((void), CancelStreamingRequest, (), (override));
  MOCK_METHOD((size_t), Read, (uint8_t * p_buf, uint32_t len));
};

class MockLeAudioClientInterfaceSource : public LeAudioClientInterface::Source {
 public:
  MOCK_METHOD((void), Cleanup, (), (override));
  MOCK_METHOD((void), SetPcmParameters,
              (const LeAudioClientInterface::PcmParameters& params),
              (override));
  MOCK_METHOD((void), SetRemoteDelay, (uint16_t delay_report_ms), (override));
  MOCK_METHOD((void), StartSession, (), (override));
  MOCK_METHOD((void), StopSession, (), (override));
  MOCK_METHOD((void), ConfirmStreamingRequest, (), (override));
  MOCK_METHOD((void), CancelStreamingRequest, (), (override));
  MOCK_METHOD((size_t), Write, (const uint8_t* p_buf, uint32_t len));
};

class MockLeAudioClientInterface : public LeAudioClientInterface {
 public:
  MockLeAudioClientInterface() = default;
  ~MockLeAudioClientInterface() = default;

  MOCK_METHOD((Sink*), GetSink,
              (bluetooth::audio::le_audio::StreamCallbacks stream_cb,
               bluetooth::common::MessageLoopThread* message_loop));
  MOCK_METHOD((Source*), GetSource,
              (bluetooth::audio::le_audio::StreamCallbacks stream_cb,
               bluetooth::common::MessageLoopThread* message_loop));
};

LeAudioClientInterface* mockInterface;

namespace bluetooth {
namespace audio {
namespace le_audio {
MockLeAudioClientInterface* interface_mock;
MockLeAudioClientInterfaceSink* sink_mock;
MockLeAudioClientInterfaceSource* source_mock;

LeAudioClientInterface* LeAudioClientInterface::Get() { return interface_mock; }

LeAudioClientInterface::Sink* LeAudioClientInterface::GetSink(
    StreamCallbacks stream_cb,
    bluetooth::common::MessageLoopThread* message_loop) {
  return interface_mock->GetSink(stream_cb, message_loop);
}

LeAudioClientInterface::Source* LeAudioClientInterface::GetSource(
    StreamCallbacks stream_cb,
    bluetooth::common::MessageLoopThread* message_loop) {
  return interface_mock->GetSource(stream_cb, message_loop);
}

bool LeAudioClientInterface::ReleaseSink(LeAudioClientInterface::Sink* sink) {
  return true;
}
bool LeAudioClientInterface::ReleaseSource(
    LeAudioClientInterface::Source* source) {
  return true;
}

void LeAudioClientInterface::Sink::Cleanup() {}
void LeAudioClientInterface::Sink::SetPcmParameters(
    const PcmParameters& params) {}
void LeAudioClientInterface::Sink::SetRemoteDelay(uint16_t delay_report_ms) {}
void LeAudioClientInterface::Sink::StartSession() {}
void LeAudioClientInterface::Sink::StopSession() {}
void LeAudioClientInterface::Sink::ConfirmStreamingRequest(){};
void LeAudioClientInterface::Sink::CancelStreamingRequest(){};

void LeAudioClientInterface::Source::Cleanup() {}
void LeAudioClientInterface::Source::SetPcmParameters(
    const PcmParameters& params) {}
void LeAudioClientInterface::Source::SetRemoteDelay(uint16_t delay_report_ms) {}
void LeAudioClientInterface::Source::StartSession() {}
void LeAudioClientInterface::Source::StopSession() {}
void LeAudioClientInterface::Source::ConfirmStreamingRequest(){};
void LeAudioClientInterface::Source::CancelStreamingRequest(){};

size_t LeAudioClientInterface::Source::Write(const uint8_t* p_buf,
                                             uint32_t len) {
  return source_mock->Write(p_buf, len);
}

size_t LeAudioClientInterface::Sink::Read(uint8_t* p_buf, uint32_t len) {
  return sink_mock->Read(p_buf, len);
}
}  // namespace le_audio
}  // namespace audio
}  // namespace bluetooth

class MockLeAudioClientAudioSinkEventReceiver
    : public LeAudioClientAudioSinkReceiver {
 public:
  MOCK_METHOD((void), OnAudioDataReady, (const std::vector<uint8_t>& data),
              (override));
  MOCK_METHOD((void), OnAudioSuspend, (std::promise<void> do_suspend_promise),
              (override));
  MOCK_METHOD((void), OnAudioResume, (std::promise<void> do_resume_promise),
              (override));
  MOCK_METHOD((void), OnAudioMetadataUpdate,
              (std::promise<void> do_update_metadata_promise,
               audio_usage_t usage, audio_content_type_t content_type),
              (override));
};

class MockLeAudioClientAudioSourceEventReceiver
    : public LeAudioClientAudioSourceReceiver {
 public:
  MOCK_METHOD((void), OnAudioSuspend, (std::promise<void> do_suspend_promise),
              (override));
  MOCK_METHOD((void), OnAudioResume, (std::promise<void> do_resume_promise),
              (override));
};

class LeAudioClientAudioTest : public ::testing::Test {
 public:
  void SetUp(void) override {
    init_message_loop_thread();
    bluetooth::audio::le_audio::interface_mock = &mock_client_interface_;
    bluetooth::audio::le_audio::sink_mock = &mock_client_interface_sink_;
    bluetooth::audio::le_audio::source_mock = &mock_client_interface_source_;

    // Init sink Audio HAL mock
    is_sink_acquired = false;
    hal_sink_stream_cb = {.on_suspend_ = nullptr, .on_resume_ = nullptr};

    ON_CALL(mock_client_interface_, GetSink(_, _))
        .WillByDefault(DoAll(SaveArg<0>(&hal_sink_stream_cb),
                             Assign(&is_sink_acquired, true),
                             Return(bluetooth::audio::le_audio::sink_mock)));
    ON_CALL(mock_client_interface_sink_, Cleanup())
        .WillByDefault(Assign(&is_sink_acquired, false));

    // Init source Audio HAL mock
    is_source_acquired = false;
    hal_source_stream_cb = {.on_suspend_ = nullptr, .on_resume_ = nullptr};

    ON_CALL(mock_client_interface_, GetSource(_, _))
        .WillByDefault(DoAll(SaveArg<0>(&hal_source_stream_cb),
                             Assign(&is_source_acquired, true),
                             Return(bluetooth::audio::le_audio::source_mock)));
    ON_CALL(mock_client_interface_source_, Cleanup())
        .WillByDefault(Assign(&is_source_acquired, false));
  }

  void AcquireAudioSink(void) {
    audio_sink_instance_ = LeAudioClientAudioSink::Acquire();
  }

  void ReleaseAudioSink(void) {
    LeAudioClientAudioSink::Release(audio_sink_instance_);
    audio_sink_instance_ = nullptr;
  }

  void AcquireAudioSource(void) {
    audio_source_instance_ = LeAudioClientAudioSource::Acquire();
  }

  void ReleaseAudioSource(void) {
    LeAudioClientAudioSource::Release(audio_source_instance_);
    audio_source_instance_ = nullptr;
  }

  void TearDown(void) override {
    /* We have to call Cleanup to tidy up some static variables.
     * If on the HAL end Source is running it means we are running the Sink
     * on our end, and vice versa.
     */
    if (is_source_acquired == true) ReleaseAudioSink();
    if (is_sink_acquired == true) ReleaseAudioSource();

    cleanup_message_loop_thread();

    bluetooth::audio::le_audio::sink_mock = nullptr;
    bluetooth::audio::le_audio::source_mock = nullptr;
  }

  MockLeAudioClientInterface mock_client_interface_;
  MockLeAudioClientInterfaceSink mock_client_interface_sink_;
  MockLeAudioClientInterfaceSource mock_client_interface_source_;

  MockLeAudioClientAudioSinkEventReceiver mock_hal_sink_event_receiver_;
  MockLeAudioClientAudioSourceEventReceiver mock_hal_source_event_receiver_;

  bool is_source_acquired = false;
  bool is_sink_acquired = false;
  const void* audio_sink_instance_ = nullptr;
  const void* audio_source_instance_ = nullptr;

  bluetooth::audio::le_audio::StreamCallbacks hal_source_stream_cb;
  bluetooth::audio::le_audio::StreamCallbacks hal_sink_stream_cb;

  const LeAudioCodecConfiguration default_codec_conf{
      .num_channels = LeAudioCodecConfiguration::kChannelNumberMono,
      .sample_rate = LeAudioCodecConfiguration::kSampleRate44100,
      .bits_per_sample = LeAudioCodecConfiguration::kBitsPerSample24,
      .data_interval_us = LeAudioCodecConfiguration::kInterval10000Us,
  };
};

TEST_F(LeAudioClientAudioTest, testLeAudioClientAudioSinkInitializeCleanup) {
  EXPECT_CALL(mock_client_interface_, GetSource(_, _))
      .WillOnce(DoAll(Assign(&is_source_acquired, true),
                      Return(bluetooth::audio::le_audio::source_mock)));
  AcquireAudioSink();

  EXPECT_CALL(mock_client_interface_source_, Cleanup())
      .WillOnce(Assign(&is_source_acquired, false));
  ReleaseAudioSink();
}

TEST_F(LeAudioClientAudioTest, testLeAudioClientAudioSourceInitializeCleanup) {
  EXPECT_CALL(mock_client_interface_, GetSink(_, _))
      .WillOnce(DoAll(Assign(&is_sink_acquired, true),
                      Return(bluetooth::audio::le_audio::sink_mock)));
  AcquireAudioSource();

  EXPECT_CALL(mock_client_interface_sink_, Cleanup())
      .WillOnce(Assign(&is_sink_acquired, false));
  ReleaseAudioSource();
}

TEST_F(LeAudioClientAudioTest, testLeAudioClientAudioSinkStartStop) {
  LeAudioClientInterface::PcmParameters params;
  EXPECT_CALL(mock_client_interface_source_, SetPcmParameters(_))
      .Times(1)
      .WillOnce(SaveArg<0>(&params));
  EXPECT_CALL(mock_client_interface_source_, StartSession()).Times(1);

  AcquireAudioSink();
  ASSERT_TRUE(LeAudioClientAudioSink::Start(default_codec_conf,
                                            &mock_hal_source_event_receiver_));

  ASSERT_EQ(params.channels_count,
            bluetooth::audio::le_audio::kChannelNumberMono);
  ASSERT_EQ(params.sample_rate, bluetooth::audio::le_audio::kSampleRate44100);
  ASSERT_EQ(params.bits_per_sample,
            bluetooth::audio::le_audio::kBitsPerSample24);
  ASSERT_EQ(params.data_interval_us, 10000u);

  EXPECT_CALL(mock_client_interface_source_, StopSession()).Times(1);

  LeAudioClientAudioSink::Stop();
}

TEST_F(LeAudioClientAudioTest, testLeAudioClientAudioSourceStartStop) {
  LeAudioClientInterface::PcmParameters params;
  EXPECT_CALL(mock_client_interface_sink_, SetPcmParameters(_))
      .Times(1)
      .WillOnce(SaveArg<0>(&params));
  EXPECT_CALL(mock_client_interface_sink_, StartSession()).Times(1);

  AcquireAudioSource();
  ASSERT_TRUE(LeAudioClientAudioSource::Start(default_codec_conf,
                                              &mock_hal_sink_event_receiver_));

  ASSERT_EQ(params.channels_count,
            bluetooth::audio::le_audio::kChannelNumberMono);
  ASSERT_EQ(params.sample_rate, bluetooth::audio::le_audio::kSampleRate44100);
  ASSERT_EQ(params.bits_per_sample,
            bluetooth::audio::le_audio::kBitsPerSample24);
  ASSERT_EQ(params.data_interval_us, 10000u);

  EXPECT_CALL(mock_client_interface_sink_, StopSession()).Times(1);

  LeAudioClientAudioSource::Stop();
}

TEST_F(LeAudioClientAudioTest, testLeAudioClientAudioSinkSendData) {
  AcquireAudioSink();
  ASSERT_TRUE(LeAudioClientAudioSink::Start(default_codec_conf,
                                            &mock_hal_source_event_receiver_));

  const uint8_t* exp_p = nullptr;
  uint32_t exp_len = 0;
  uint8_t input_buf[] = {
      0x02,
      0x03,
      0x05,
      0x19,
  };
  ON_CALL(mock_client_interface_source_, Write(_, _))
      .WillByDefault(DoAll(SaveArg<0>(&exp_p), SaveArg<1>(&exp_len),
                           ReturnPointee(&exp_len)));

  ASSERT_EQ(LeAudioClientAudioSink::SendData(input_buf, sizeof(input_buf)),
            sizeof(input_buf));
  ASSERT_EQ(exp_len, sizeof(input_buf));
  ASSERT_EQ(exp_p, input_buf);

  LeAudioClientAudioSource::Stop();
}

TEST_F(LeAudioClientAudioTest, testLeAudioClientAudioSinkSuspend) {
  AcquireAudioSink();
  ASSERT_TRUE(LeAudioClientAudioSink::Start(default_codec_conf,
                                            &mock_hal_source_event_receiver_));

  ASSERT_NE(hal_source_stream_cb.on_suspend_, nullptr);

  /* Expect LeAudio registered event listener to get called when HAL calls the
   * client_audio's internal suspend callback.
   */
  EXPECT_CALL(mock_hal_source_event_receiver_, OnAudioSuspend(_)).Times(1);
  ASSERT_TRUE(hal_source_stream_cb.on_suspend_());
}

TEST_F(LeAudioClientAudioTest, testLeAudioClientAudioSourceSuspend) {
  AcquireAudioSource();
  ASSERT_TRUE(LeAudioClientAudioSource::Start(default_codec_conf,
                                              &mock_hal_sink_event_receiver_));

  ASSERT_NE(hal_sink_stream_cb.on_suspend_, nullptr);

  /* Expect LeAudio registered event listener to get called when HAL calls the
   * client_audio's internal suspend callback.
   */
  EXPECT_CALL(mock_hal_sink_event_receiver_, OnAudioSuspend(_)).Times(1);
  ASSERT_TRUE(hal_sink_stream_cb.on_suspend_());
}

TEST_F(LeAudioClientAudioTest, testLeAudioClientAudioSinkResume) {
  AcquireAudioSink();
  ASSERT_TRUE(LeAudioClientAudioSink::Start(default_codec_conf,
                                            &mock_hal_source_event_receiver_));

  ASSERT_NE(hal_source_stream_cb.on_resume_, nullptr);

  /* Expect LeAudio registered event listener to get called when HAL calls the
   * client_audio's internal resume callback.
   */
  EXPECT_CALL(mock_hal_source_event_receiver_, OnAudioResume(_)).Times(1);
  bool start_media_task = false;
  ASSERT_TRUE(hal_source_stream_cb.on_resume_(start_media_task));
}

TEST_F(LeAudioClientAudioTest,
       testLeAudioClientAudioSourceResumeStartSourceTask) {
  const LeAudioCodecConfiguration codec_conf{
      .num_channels = LeAudioCodecConfiguration::kChannelNumberStereo,
      .sample_rate = LeAudioCodecConfiguration::kSampleRate16000,
      .bits_per_sample = LeAudioCodecConfiguration::kBitsPerSample24,
      .data_interval_us = LeAudioCodecConfiguration::kInterval10000Us,
  };
  AcquireAudioSource();
  ASSERT_TRUE(LeAudioClientAudioSource::Start(codec_conf,
                                              &mock_hal_sink_event_receiver_));

  std::chrono::time_point<std::chrono::system_clock> resumed_ts;
  std::chrono::time_point<std::chrono::system_clock> executed_ts;
  std::promise<void> promise;
  auto future = promise.get_future();

  uint32_t calculated_bytes_per_tick = 0;
  EXPECT_CALL(mock_client_interface_sink_, Read(_, _))
      .Times(AtLeast(1))
      .WillOnce(Invoke([&](uint8_t* p_buf, uint32_t len) -> uint32_t {
        executed_ts = std::chrono::system_clock::now();
        calculated_bytes_per_tick = len;

        // fake some data from audio framework
        for (uint32_t i = 0u; i < len; ++i) {
          p_buf[i] = i;
        }

        // Return exactly as much data as requested
        promise.set_value();
        return len;
      }));

  std::promise<void> data_promise;
  auto data_future = data_promise.get_future();

  /* Expect this callback to be called to Client by the HAL glue layer */
  std::vector<uint8_t> media_data_to_send;
  EXPECT_CALL(mock_hal_sink_event_receiver_, OnAudioDataReady(_))
      .WillOnce(Invoke([&](const std::vector<uint8_t>& data) -> void {
        media_data_to_send = std::move(data);
        data_promise.set_value();
      }));

  /* Expect LeAudio registered event listener to get called when HAL calls the
   * client_audio's internal resume callback.
   */
  ASSERT_NE(hal_sink_stream_cb.on_resume_, nullptr);
  EXPECT_CALL(mock_hal_sink_event_receiver_, OnAudioResume(_)).Times(1);
  resumed_ts = std::chrono::system_clock::now();
  bool start_media_task = true;
  ASSERT_TRUE(hal_sink_stream_cb.on_resume_(start_media_task));
  LeAudioClientAudioSource::ConfirmStreamingRequest();

  ASSERT_EQ(future.wait_for(std::chrono::seconds(1)),
            std::future_status::ready);

  ASSERT_EQ(data_future.wait_for(std::chrono::seconds(1)),
            std::future_status::ready);

  // Check agains expected payload size
  const uint32_t channel_bytes_per_sample = (24 /*bps*/ / 8);
  const uint32_t channel_bytes_per_10ms_at_16000Hz =
      ((10ms).count() * channel_bytes_per_sample * 16000 /*Hz*/) /
      (1000ms).count();

  // Expect 2 channel (stereo) data
  ASSERT_EQ(calculated_bytes_per_tick, 2 * channel_bytes_per_10ms_at_16000Hz);

  // Verify callback call interval for the requested 10ms (+2ms error margin)
  auto delta = std::chrono::duration_cast<std::chrono::milliseconds>(
      executed_ts - resumed_ts);
  EXPECT_TRUE((delta >= 10ms) && (delta <= 12ms));

  // Verify if we got just right amount of data in the callback call
  ASSERT_EQ(media_data_to_send.size(), calculated_bytes_per_tick);
}

TEST_F(LeAudioClientAudioTest, testLeAudioClientAudioSourceResume) {
  AcquireAudioSource();
  ASSERT_TRUE(LeAudioClientAudioSource::Start(default_codec_conf,
                                              &mock_hal_sink_event_receiver_));

  ASSERT_NE(hal_sink_stream_cb.on_resume_, nullptr);

  /* Expect LeAudio registered event listener to get called when HAL calls the
   * client_audio's internal resume callback.
   */
  EXPECT_CALL(mock_hal_sink_event_receiver_, OnAudioResume(_)).Times(1);
  bool start_media_task = false;
  ASSERT_TRUE(hal_sink_stream_cb.on_resume_(start_media_task));
}