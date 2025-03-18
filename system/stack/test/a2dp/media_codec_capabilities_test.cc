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
#include <gtest/gtest.h>

#include <cstdint>
#include <string>

#include "hardware/bt_av.h"
#include "stack/include/a2dp_codec_api.h"
#include "stack/include/a2dp_constants.h"
#include "stack/include/a2dp_vendor_aptx_constants.h"
#include "stack/include/a2dp_vendor_aptx_hd_constants.h"
#include "stack/include/a2dp_vendor_ldac_constants.h"
#include "stack/include/a2dp_vendor_opus_constants.h"
#include "stack/include/avdt_api.h"

namespace {
static constexpr uint8_t kCodecCapabilitiesWithTruncatedMediaCodecType[] = {0x0};
static constexpr uint8_t kCodecCapabilitiesWithTruncatedVendorCodecId[] = {0x5, 0x0, 0xff,
                                                                           0x1, 0x2, 0x3};
static constexpr uint8_t kCodecCapabilitiesWithInvalidMediaCodecType[] = {0x2, 0x0, 0xaa};
static constexpr uint8_t kCodecCapabilitiesWithInvalidVendorCodecId[] = {0x8, 0x0, 0xff, 0x1, 0x2,
                                                                         0x3, 0x4, 0x5,  0x6};

static constexpr uint8_t kSbcCodecCapabilities[] = {
        6,                   // Length (A2DP_SBC_INFO_LEN)
        0,                   // Media Type: AVDT_MEDIA_TYPE_AUDIO
        0,                   // Media Codec Type: A2DP_MEDIA_CT_SBC
        0x20 | 0x01,         // Sample Frequency: A2DP_SBC_IE_SAMP_FREQ_44 |
                             // Channel Mode: A2DP_SBC_IE_CH_MD_JOINT
        0x10 | 0x04 | 0x01,  // Block Length: A2DP_SBC_IE_BLOCKS_16 |
                             // Subbands: A2DP_SBC_IE_SUBBAND_8 |
                             // Allocation Method: A2DP_SBC_IE_ALLOC_MD_L
        2,                   // MinimumBitpool Value: A2DP_SBC_IE_MIN_BITPOOL
        53,                  // Maximum Bitpool Value: A2DP_SBC_MAX_BITPOOL
        7,                   // Fake
        8,                   // Fake
        9                    // Fake
};

static constexpr uint8_t kAacCodecCapabilities[] = {
        8,           // Length (A2DP_AAC_INFO_LEN)
        0,           // Media Type: AVDT_MEDIA_TYPE_AUDIO
        2,           // Media Codec Type: A2DP_MEDIA_CT_AAC
        0x80,        // Object Type: A2DP_AAC_OBJECT_TYPE_MPEG2_LC
        0x01,        // Sampling Frequency: A2DP_AAC_SAMPLING_FREQ_44100
        0x04,        // Channels: A2DP_AAC_CHANNEL_MODE_STEREO
        0x00 | 0x4,  // Variable Bit Rate:
                     // A2DP_AAC_VARIABLE_BIT_RATE_DISABLED
                     // Bit Rate: 320000 = 0x4e200
        0xe2,        // Bit Rate: 320000 = 0x4e200
        0x00,        // Bit Rate: 320000 = 0x4e200
        7,           // Unused
        8,           // Unused
        9            // Unused
};

static constexpr uint8_t kAptxCodecCapabilities[] = {
        A2DP_APTX_CODEC_LEN,
        AVDT_MEDIA_TYPE_AUDIO,
        A2DP_MEDIA_CT_NON_A2DP,
        0x4F,  // A2DP_APTX_VENDOR_ID
        0x00,  // A2DP_APTX_VENDOR_ID
        0x00,  // A2DP_APTX_VENDOR_ID
        0x00,  // A2DP_APTX_VENDOR_ID
        0x01,  // A2DP_APTX_CODEC_ID
        0x00,  // A2DP_APTX_CODEC_ID,
        A2DP_APTX_SAMPLERATE_44100 | A2DP_APTX_CHANNELS_STEREO,
};

static constexpr uint8_t kAptxHdCodecCapabilities[] = {
        A2DP_APTX_HD_CODEC_LEN,
        AVDT_MEDIA_TYPE_AUDIO,
        A2DP_MEDIA_CT_NON_A2DP,
        0xD7,  // A2DP_APTX_HD_VENDOR_ID
        0x00,  // A2DP_APTX_HD_VENDOR_ID
        0x00,  // A2DP_APTX_HD_VENDOR_ID
        0x00,  // A2DP_APTX_HD_VENDOR_ID
        0x24,  // A2DP_APTX_HD_CODEC_ID
        0x00,  // A2DP_APTX_HD_CODEC_ID,
        A2DP_APTX_HD_SAMPLERATE_44100 | A2DP_APTX_HD_CHANNELS_STEREO,
        A2DP_APTX_HD_ACL_SPRINT_RESERVED0,
        A2DP_APTX_HD_ACL_SPRINT_RESERVED1,
        A2DP_APTX_HD_ACL_SPRINT_RESERVED2,
        A2DP_APTX_HD_ACL_SPRINT_RESERVED3,
};

static constexpr uint8_t kLdacCodecCapabilities[] = {
        A2DP_LDAC_CODEC_LEN,
        AVDT_MEDIA_TYPE_AUDIO,
        A2DP_MEDIA_CT_NON_A2DP,
        0x2D,  // A2DP_LDAC_VENDOR_ID
        0x01,  // A2DP_LDAC_VENDOR_ID
        0x00,  // A2DP_LDAC_VENDOR_ID
        0x00,  // A2DP_LDAC_VENDOR_ID
        0xAA,  // A2DP_LDAC_CODEC_ID
        0x00,  // A2DP_LDAC_CODEC_ID,
        A2DP_LDAC_SAMPLING_FREQ_44100,
        A2DP_LDAC_CHANNEL_MODE_STEREO,
};

static constexpr uint8_t kOpusCodecCapabilities[] = {
        A2DP_OPUS_CODEC_LEN,         // Length
        AVDT_MEDIA_TYPE_AUDIO << 4,  // Media Type
        A2DP_MEDIA_CT_NON_A2DP,      // Media Codec Type Vendor
        (A2DP_OPUS_VENDOR_ID & 0x000000FF),
        (A2DP_OPUS_VENDOR_ID & 0x0000FF00) >> 8,
        (A2DP_OPUS_VENDOR_ID & 0x00FF0000) >> 16,
        (A2DP_OPUS_VENDOR_ID & 0xFF000000) >> 24,
        (A2DP_OPUS_CODEC_ID & 0x00FF),
        (A2DP_OPUS_CODEC_ID & 0xFF00) >> 8,
        A2DP_OPUS_CHANNEL_MODE_STEREO | A2DP_OPUS_20MS_FRAMESIZE | A2DP_OPUS_SAMPLING_FREQ_48000,
};
}  // namespace

namespace bluetooth::a2dp {

class MediaCodecCapabilitiesTest : public ::testing::Test {};

// Test the correctness of the method `bluetooth::a2dp::ParseCodecId`.
TEST_F(MediaCodecCapabilitiesTest, ParseCodecId) {
  ASSERT_EQ(ParseCodecId(kCodecCapabilitiesWithTruncatedMediaCodecType), std::nullopt);
  ASSERT_EQ(ParseCodecId(kCodecCapabilitiesWithTruncatedVendorCodecId), std::nullopt);
  ASSERT_EQ(ParseCodecId(kCodecCapabilitiesWithInvalidMediaCodecType), std::nullopt);
  ASSERT_EQ(ParseCodecId(kCodecCapabilitiesWithInvalidVendorCodecId), std::nullopt);

  ASSERT_EQ(ParseCodecId(kSbcCodecCapabilities), CodecId::SBC);
  ASSERT_EQ(ParseCodecId(kAacCodecCapabilities), CodecId::AAC);
  ASSERT_EQ(ParseCodecId(kAptxCodecCapabilities), CodecId::APTX);
  ASSERT_EQ(ParseCodecId(kAptxHdCodecCapabilities), CodecId::APTX_HD);
  ASSERT_EQ(ParseCodecId(kLdacCodecCapabilities), CodecId::LDAC);
  ASSERT_EQ(ParseCodecId(kOpusCodecCapabilities), CodecId::OPUS);
}

// Test provided to validate the Media Codec Capabilities, the legacy APIs `A2DP_xxx`
// are considered the source of truth here.
TEST_F(MediaCodecCapabilitiesTest, A2DP_SourceCodecIndex) {
  ASSERT_EQ(A2DP_SourceCodecIndex(kSbcCodecCapabilities), BTAV_A2DP_CODEC_INDEX_SOURCE_SBC);
  ASSERT_EQ(A2DP_SourceCodecIndex(kAacCodecCapabilities), BTAV_A2DP_CODEC_INDEX_SOURCE_AAC);
  ASSERT_EQ(A2DP_SourceCodecIndex(kAptxCodecCapabilities), BTAV_A2DP_CODEC_INDEX_SOURCE_APTX);
  ASSERT_EQ(A2DP_SourceCodecIndex(kAptxHdCodecCapabilities), BTAV_A2DP_CODEC_INDEX_SOURCE_APTX_HD);
  ASSERT_EQ(A2DP_SourceCodecIndex(kLdacCodecCapabilities), BTAV_A2DP_CODEC_INDEX_SOURCE_LDAC);
  ASSERT_EQ(A2DP_SourceCodecIndex(kOpusCodecCapabilities), BTAV_A2DP_CODEC_INDEX_SOURCE_OPUS);
}

}  // namespace bluetooth::a2dp
