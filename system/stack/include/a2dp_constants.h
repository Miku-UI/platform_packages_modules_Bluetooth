/******************************************************************************
 *
 *  Copyright 2000-2012 Broadcom Corporation
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

#pragma once
#include <bluetooth/log.h>

#include <cstdint>

/* Profile supported features */
#define A2DP_SUPF_PLAYER 0x0001
#define A2DP_SUPF_MIC 0x0002
#define A2DP_SUPF_TUNER 0x0004
#define A2DP_SUPF_MIXER 0x0008

#define A2DP_SUPF_HEADPHONE 0x0001
#define A2DP_SUPF_SPEAKER 0x0002
#define A2DP_SUPF_RECORDER 0x0004
#define A2DP_SUPF_AMP 0x0008

// AV Media Codec Types (Audio Codec ID).
// cf. Assigned Numbers § 6.5.1 Audio Codec ID
enum tA2DP_CODEC_TYPE : uint8_t {
  A2DP_MEDIA_CT_SBC = 0x00,
  A2DP_MEDIA_CT_MPEG_AUDIO = 0x01,
  A2DP_MEDIA_CT_AAC = 0x02,
  A2DP_MEDIA_CT_MPEG_USAC = 0x03,
  A2DP_MEDIA_CT_ATRAC = 0x04,
  A2DP_MEDIA_CT_NON_A2DP = 0xff,
};

// Standardized codec identifiers.
// The codec identifier is 40 bits,
//  - Bits 0-7: Audio Codec ID, as defined by Assigned Numbers § 6.5.1 Audio Codec ID
//          0x00: SBC
//          0x02: AAC
//          0xFF: Vendor
//  - Bits 8-23: Company ID,
//          set to 0, if octet 0 is not 0xFF.
//  - Bits 24-39: Vendor-defined codec ID,
//          set to 0, if octet 0 is not 0xFF.
enum tA2DP_CODEC_ID : uint64_t {
  A2DP_CODEC_ID_SBC = 0x0000000000,
  A2DP_CODEC_ID_AAC = 0x0000000002,
  A2DP_CODEC_ID_APTX = 0x0001004fff,
  A2DP_CODEC_ID_APTX_HD = 0x002400d7ff,
  A2DP_CODEC_ID_LDAC = 0x00aa012dff,
  A2DP_CODEC_ID_OPUS = 0x000100e0ff,
};

// Error codes returned in AVDTP reject signalling messages.
// The codes are specified from multiple sources as documented in the enum.
enum tA2DP_STATUS : uint8_t {
  A2DP_SUCCESS = 0,

  // Custom error codes.
  A2DP_FAIL = 0x0A,
  A2DP_BUSY = 0x0B,

  // [AVDTP_1.3] 8.20.6.2 ERROR_CODE tables.
  AVDTP_BAD_HEADER_FORMAT = 0x01,
  AVDTP_BAD_LENGTH = 0x11,
  AVDTP_BAD_ACP_SEID = 0x12,
  AVDTP_SEP_IN_USE = 0x13,
  AVDTP_SEP_NOT_IN_USE = 0x14,
  AVDTP_BAD_SERV_CATEGORY = 0x17,
  AVDTP_BAD_PAYLOAD_FORMAT = 0x18,
  AVDTP_NOT_SUPPORTED_COMMAND = 0x19,
  AVDTP_INVALID_CAPABILITIES = 0x1A,
  AVDTP_BAD_RECOVERY_TYPE = 0x22,
  AVDTP_BAD_MEDIA_TRANSPORT_FORMAT = 0x23,
  AVDTP_BAD_RECOVERY_FORMAT = 0x25,
  AVDTP_BAD_ROHC_FORMAT = 0x26,
  AVDTP_BAD_CP_FORMAT = 0x27,
  AVDTP_BAD_MULTIPLEXING_FORMAT = 0x28,
  AVDTP_UNSUPPORTED_CONFIGURATION = 0x29,
  AVDTP_BAD_STATE = 0x31,

  // [GAVDTP_1.3] 3.3 Error codes.
  GAVDTP_BAD_SERVICE = 0x80,
  GAVDTP_INSUFFICIENT_RESOURCES = 0x81,

  // [A2DP_1.3.2] 5.1.3 Error Codes.
  A2DP_INVALID_CODEC_TYPE = 0xC1,
  A2DP_NOT_SUPPORTED_CODEC_TYPE = 0xC2,
  A2DP_INVALID_SAMPLING_FREQUENCY = 0xC3,
  A2DP_NOT_SUPPORTED_SAMPLING_FREQUENCY = 0xC4,
  A2DP_INVALID_CHANNEL_MODE = 0xC5,
  A2DP_NOT_SUPPORTED_CHANNEL_MODE = 0xC6,
  A2DP_INVALID_SUBBANDS = 0xC7,
  A2DP_NOT_SUPPORTED_SUBBANDS = 0xC8,
  A2DP_INVALID_ALLOCATION_METHOD = 0xC9,
  A2DP_NOT_SUPPORTED_ALLOCATION_METHOD = 0xCA,
  A2DP_INVALID_MINIMUM_BITPOOL_VALUE = 0xCB,
  A2DP_NOT_SUPPORTED_MINIMUM_BITPOOL_VALUE = 0xCC,
  A2DP_INVALID_MAXIMUM_BITPOOL_VALUE = 0xCD,
  A2DP_NOT_SUPPORTED_MAXIMUM_BITPOOL_VALUE = 0xCE,
  A2DP_INVALID_LAYER = 0xCF,
  A2DP_NOT_SUPPORTED_LAYER = 0xD0,
  A2DP_NOT_SUPPORTED_CRC = 0xD1,
  A2DP_NOT_SUPPORTED_MPF = 0xD2,
  A2DP_NOT_SUPPORTED_VBR = 0xD3,
  A2DP_INVALID_BIT_RATE = 0xD4,
  A2DP_NOT_SUPPORTED_BIT_RATE = 0xD5,
  A2DP_INVALID_OBJECT_TYPE = 0xD6,
  A2DP_NOT_SUPPORTED_OBJECT_TYPE = 0xD7,
  A2DP_INVALID_CHANNELS = 0xD8,
  A2DP_NOT_SUPPORTED_CHANNELS = 0xD9,
  A2DP_INVALID_BLOCK_LENGTH = 0xDD,
  A2DP_INVALID_CP_TYPE = 0xE0,
  A2DP_INVALID_CP_FORMAT = 0xE1,
  A2DP_INVALID_CODEC_PARAMETER = 0xE2,
  A2DP_NOT_SUPPORTED_CODEC_PARAMETER = 0xE3,
};

namespace fmt {
template <>
struct formatter<tA2DP_CODEC_TYPE> : enum_formatter<tA2DP_CODEC_TYPE> {};
template <>
struct formatter<tA2DP_STATUS> : enum_formatter<tA2DP_STATUS> {};
}  // namespace fmt
