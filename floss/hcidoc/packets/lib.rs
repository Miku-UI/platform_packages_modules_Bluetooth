#![allow(clippy::all)]
#![allow(unused)]
#![allow(missing_docs)]

pub mod l2cap {
    include!(concat!(env!("OUT_DIR"), "/l2cap_packets.rs"));
}

pub mod hci {
    include!(concat!(env!("OUT_DIR"), "/hci_packets.rs"));

    pub const EMPTY_ADDRESS: Address = Address(0x000000000000);

    impl fmt::Display for Address {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            let bytes = u64::to_le_bytes(self.0);
            write!(
                f,
                "{:02X}:{:02X}:{:02X}:{:02X}:{:02X}:{:02X}",
                bytes[5], bytes[4], bytes[3], bytes[2], bytes[1], bytes[0],
            )
        }
    }

    impl From<&[u8; 6]> for Address {
        fn from(bytes: &[u8; 6]) -> Self {
            Self(u64::from_le_bytes([
                bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], 0, 0,
            ]))
        }
    }

    impl From<Address> for [u8; 6] {
        fn from(Address(addr): Address) -> Self {
            let bytes = u64::to_le_bytes(addr);
            bytes[0..6].try_into().unwrap()
        }
    }

    pub struct GapData {
        pub data_type: GapDataType,
        pub data: Vec<u8>,
    }

    impl GapData {
        pub fn parse(bytes: &[u8]) -> std::result::Result<Self, String> {
            // In case of parsing EIR, we can get normal data, or all zeroes. Normal data always
            // have at least 2 bytes: one for the length, and another for the type. Therefore we
            // can terminate early if the data has less than 2 bytes.
            if (bytes.len() == 0) {
                return Err("no data to parse".to_string());
            } else if (bytes.len() == 1) {
                if (bytes[0] != 0) {
                    return Err(format!("can't parse 1 byte of data: {}", bytes[0]));
                }
                return Ok(GapData { data_type: GapDataType::Invalid, data: vec![] });
            }

            let mut data_size = bytes[0] as usize;
            if (data_size == 0) {
                // Data size already include the data_type, so size = 0 is possible only when
                // parsing EIR, where all data are zeroes. Here we just assume that assumption is
                // correct, and don't really check all the elements.
                return Ok(GapData { data_type: GapDataType::Invalid, data: bytes[2..].to_vec() });
            }

            if (data_size > bytes.len() - 1) {
                return Err(format!(
                    "size {} is bigger than remaining length {}",
                    data_size,
                    bytes.len() - 1
                ));
            }
            let data_type = match GapDataType::try_from(bytes[1]) {
                Ok(data_type) => Ok(data_type),
                Err(_) => Err(format!("can't parse data type {}", bytes[1])),
            }?;
            return Ok(GapData { data_type, data: bytes[2..(data_size + 1)].to_vec() });
        }
    }
}
