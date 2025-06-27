// Copyright 2024, The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use crate::reader::{unpack, Reader};
use crate::writer::{pack, Write, Writer};

/// 5.4.5 ISO Data Packets

/// Exchange of Isochronous Data between the Host and Controller
#[derive(Debug)]
pub struct IsoData<'a> {
    /// Identify the connection
    pub connection_handle: u16,
    /// Fragmentation of the packet
    pub sdu_fragment: IsoSduFragment,
    /// Payload
    pub payload: &'a [u8],
}

/// Fragmentation indication of the SDU
#[derive(Debug)]
pub enum IsoSduFragment {
    /// First SDU Fragment
    First {
        /// SDU Header
        hdr: IsoSduHeader,
        /// Last SDU fragment indication
        is_last: bool,
    },
    /// Continuous fragment
    Continue {
        /// Last SDU fragment indication
        is_last: bool,
    },
}

/// SDU Header information, when ISO Data in a first SDU fragment
#[derive(Debug, Default)]
pub struct IsoSduHeader {
    /// Optional timestamp in microseconds
    pub timestamp: Option<u32>,
    /// Sequence number of the SDU
    pub sequence_number: u16,
    /// Total length of the SDU (sum of all fragments)
    pub sdu_length: u16,
    /// Only valid from Controller, indicate valid SDU data when 0
    pub status: u16,
}

impl<'a> IsoData<'a> {
    /// Read an HCI ISO Data packet
    pub fn from_bytes(data: &'a [u8]) -> Option<Self> {
        Self::parse(&mut Reader::new(data))
    }

    /// Output the HCI ISO Data packet
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut w = Writer::new(Vec::with_capacity(12 + self.payload.len()));
        w.write(self);
        w.into_vec()
    }

    /// New ISO Data packet, including a complete SDU
    pub fn new(connection_handle: u16, sequence_number: u16, data: &'a [u8]) -> Self {
        Self {
            connection_handle,
            sdu_fragment: IsoSduFragment::First {
                hdr: IsoSduHeader {
                    sequence_number,
                    sdu_length: data.len().try_into().unwrap(),
                    ..Default::default()
                },
                is_last: true,
            },
            payload: data,
        }
    }

    fn parse(r: &mut Reader<'a>) -> Option<Self> {
        let (connection_handle, pb_flag, ts_present) = unpack!(r.read_u16()?, (12, 2, 1));
        let data_len = unpack!(r.read_u16()?, 14) as usize;

        let sdu_fragment = match pb_flag {
            0b00 => IsoSduFragment::First {
                hdr: IsoSduHeader::parse(r, ts_present != 0)?,
                is_last: false,
            },
            0b10 => IsoSduFragment::First {
                hdr: IsoSduHeader::parse(r, ts_present != 0)?,
                is_last: true,
            },
            0b01 => IsoSduFragment::Continue { is_last: false },
            0b11 => IsoSduFragment::Continue { is_last: true },
            _ => unreachable!(),
        };
        let sdu_header_len = Self::sdu_header_len(&sdu_fragment);
        if data_len < sdu_header_len {
            return None;
        }

        Some(Self { connection_handle, sdu_fragment, payload: r.get(data_len - sdu_header_len)? })
    }

    fn sdu_header_len(sdu_fragment: &IsoSduFragment) -> usize {
        match sdu_fragment {
            IsoSduFragment::First { ref hdr, .. } => 4 * (1 + hdr.timestamp.is_some() as usize),
            IsoSduFragment::Continue { .. } => 0,
        }
    }
}

impl Write for IsoData<'_> {
    fn write(&self, w: &mut Writer) {
        let (pb_flag, hdr) = match self.sdu_fragment {
            IsoSduFragment::First { ref hdr, is_last: false } => (0b00, Some(hdr)),
            IsoSduFragment::First { ref hdr, is_last: true } => (0b10, Some(hdr)),
            IsoSduFragment::Continue { is_last: false } => (0b01, None),
            IsoSduFragment::Continue { is_last: true } => (0b11, None),
        };

        let ts_present = hdr.is_some() && hdr.unwrap().timestamp.is_some();
        w.write_u16(pack!((self.connection_handle, 12), (pb_flag, 2), ((ts_present as u16), 1)));

        let packet_len = Self::sdu_header_len(&self.sdu_fragment) + self.payload.len();
        w.write_u16(pack!(u16::try_from(packet_len).unwrap(), 14));

        if let Some(hdr) = hdr {
            w.write(hdr);
        }
        w.put(self.payload);
    }
}

impl IsoSduHeader {
    fn parse(r: &mut Reader, ts_present: bool) -> Option<Self> {
        let timestamp = match ts_present {
            true => Some(r.read_u32::<4>()?),
            false => None,
        };
        let sequence_number = r.read_u16()?;
        let (sdu_length, _, status) = unpack!(r.read_u16()?, (12, 2, 2));
        Some(Self { timestamp, sequence_number, sdu_length, status })
    }
}

impl Write for IsoSduHeader {
    fn write(&self, w: &mut Writer) {
        if let Some(timestamp) = self.timestamp {
            w.write_u32::<4>(timestamp);
        };
        w.write_u16(self.sequence_number);
        w.write_u16(pack!((self.sdu_length, 12), (0, 2), (self.status, 2)));
    }
}

#[test]
fn test_iso_data() {
    let dump = [
        0x60, 0x60, 0x80, 0x00, 0x4d, 0xc8, 0xd0, 0x2f, 0x19, 0x03, 0x78, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0xe0, 0x93, 0xe5, 0x28, 0x34, 0x00, 0x00, 0x04,
    ];
    let Some(pkt) = IsoData::from_bytes(&dump) else { panic!() };
    assert_eq!(pkt.connection_handle, 0x060);

    let IsoSduFragment::First { ref hdr, is_last } = pkt.sdu_fragment else { panic!() };
    assert_eq!(hdr.timestamp, Some(802_211_917));
    assert_eq!(hdr.sequence_number, 793);
    assert_eq!(hdr.sdu_length, 120);
    assert!(is_last);

    assert_eq!(
        pkt.payload,
        &[
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0xe0, 0x93, 0xe5, 0x28, 0x34, 0x00, 0x00, 0x04
        ]
    );
    assert_eq!(pkt.to_bytes(), &dump[..]);
}
