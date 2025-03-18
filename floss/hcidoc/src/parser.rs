//! Parsing of various Bluetooth packets.
use chrono::{DateTime, NaiveDateTime};
use num_derive::{FromPrimitive, ToPrimitive};
use num_traits::cast::FromPrimitive;
use std::convert::TryFrom;
use std::fs::File;
use std::io::{BufRead, BufReader, Error, ErrorKind, Read};

use hcidoc_packets::hci::{Acl, AclChild, Command, Event};
use hcidoc_packets::l2cap::{
    BasicFrame, BasicFrameChild, Control, ControlFrameChild, GroupFrameChild, LeControl,
    LeControlFrameChild,
};

/// Snoop file header format.
#[derive(Debug)]
pub struct SnoopHeader {
    _id: [u8; 8],
    _version: u32,
    datalink_type: SnoopDatalinkType,
}

/// Identifier for a snoop file. In ASCII, this is 'btsnoop\0'.
const SNOOP_MAGIC: [u8; 8] = [0x62, 0x74, 0x73, 0x6e, 0x6f, 0x6f, 0x70, 0x00];

/// Size of snoop header. 8 bytes for magic, 4 bytes for version, and 4 bytes for snoop type.
const SNOOP_HEADER_SIZE: usize = 16;

#[derive(Debug, FromPrimitive, ToPrimitive)]
#[repr(u32)]
enum SnoopDatalinkType {
    H4Uart = 1002,
    LinuxMonitor = 2001,
}

impl TryFrom<&[u8]> for SnoopHeader {
    type Error = String;

    fn try_from(item: &[u8]) -> Result<Self, Self::Error> {
        if item.len() != SNOOP_HEADER_SIZE {
            return Err(format!("Invalid size for snoop header: {}", item.len()));
        }

        let rest = item;
        let (id_bytes, rest) = rest.split_at(8);
        let (version_bytes, rest) = rest.split_at(std::mem::size_of::<u32>());
        let (data_type_bytes, _rest) = rest.split_at(std::mem::size_of::<u32>());

        let id = id_bytes.try_into().unwrap();
        let version = u32::from_be_bytes(version_bytes.try_into().unwrap());
        let data_type = u32::from_be_bytes(data_type_bytes.try_into().unwrap());

        if id != SNOOP_MAGIC {
            return Err(format!("Id is not 'btsnoop'."));
        }

        if version != 1 {
            return Err(format!("Version is not supported. Got {}.", version));
        }

        let datalink_type = match SnoopDatalinkType::from_u32(data_type) {
            Some(datalink_type) => datalink_type,
            None => return Err(format!("Unsupported datalink type {}", data_type)),
        };

        return Ok(SnoopHeader { _id: id, _version: version, datalink_type });
    }
}

/// Opcodes for snoop packets.
#[derive(Debug, FromPrimitive, ToPrimitive)]
#[repr(u16)]
pub enum SnoopOpcodes {
    NewIndex = 0,
    DeleteIndex,
    Command,
    Event,
    AclTxPacket,
    AclRxPacket,
    ScoTxPacket,
    ScoRxPacket,
    OpenIndex,
    CloseIndex,
    IndexInfo,
    VendorDiag,
    SystemNote,
    UserLogging,
    CtrlOpen,
    CtrlClose,
    CtrlCommand,
    CtrlEvent,
    IsoTx,
    IsoRx,

    Invalid = 0xffff,
}

/// Size of packet preamble (everything except the data).
const SNOOP_PACKET_PREAMBLE_SIZE: usize = 24;

/// Number of microseconds from btsnoop zero to Linux epoch.
const SNOOP_Y0_TO_Y1970_US: i64 = 62_168_256_000_000_000;

/// Snoop file packet format.
#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct SnoopPacketPreamble {
    /// The original length of the captured packet as received via a network.
    pub original_length: u32,

    /// The length of the included data (can be smaller than original_length if
    /// the received packet was truncated).
    pub included_length: u32,
    pub flags: u32,
    pub drops: u32,
    pub timestamp_us: u64,
}

impl SnoopPacketPreamble {
    fn from_fd<'a>(fd: &mut Box<dyn BufRead + 'a>) -> Option<SnoopPacketPreamble> {
        let mut buf = [0u8; SNOOP_PACKET_PREAMBLE_SIZE];
        match fd.read_exact(&mut buf) {
            Ok(()) => {}
            Err(e) => {
                // |UnexpectedEof| could be seen since we're trying to read more
                // data than is available (i.e. end of file).
                if e.kind() != ErrorKind::UnexpectedEof {
                    eprintln!("Error reading preamble: {:?}", e);
                }
                return None;
            }
        };

        match SnoopPacketPreamble::try_from(&buf[0..SNOOP_PACKET_PREAMBLE_SIZE]) {
            Ok(preamble) => Some(preamble),
            Err(e) => {
                eprintln!("Error reading preamble: {}", e);
                None
            }
        }
    }
}

impl TryFrom<&[u8]> for SnoopPacketPreamble {
    type Error = String;

    fn try_from(item: &[u8]) -> Result<Self, Self::Error> {
        if item.len() != SNOOP_PACKET_PREAMBLE_SIZE {
            return Err(format!("Wrong size for snoop packet preamble: {}", item.len()));
        }

        let rest = item;
        let (orig_len_bytes, rest) = rest.split_at(std::mem::size_of::<u32>());
        let (included_len_bytes, rest) = rest.split_at(std::mem::size_of::<u32>());
        let (flags_bytes, rest) = rest.split_at(std::mem::size_of::<u32>());
        let (drops_bytes, rest) = rest.split_at(std::mem::size_of::<u32>());
        let (ts_bytes, _rest) = rest.split_at(std::mem::size_of::<u64>());

        // Note that all bytes are in big-endian because they're network order.
        let preamble = SnoopPacketPreamble {
            original_length: u32::from_be_bytes(orig_len_bytes.try_into().unwrap()),
            included_length: u32::from_be_bytes(included_len_bytes.try_into().unwrap()),
            flags: u32::from_be_bytes(flags_bytes.try_into().unwrap()),
            drops: u32::from_be_bytes(drops_bytes.try_into().unwrap()),
            timestamp_us: u64::from_be_bytes(ts_bytes.try_into().unwrap()),
        };

        Ok(preamble)
    }
}

pub trait GeneralSnoopPacket {
    fn adapter_index(&self) -> u16;
    fn opcode(&self) -> SnoopOpcodes;
    fn preamble(&self) -> &SnoopPacketPreamble;
    fn data(&self) -> &Vec<u8>;

    fn get_timestamp(&self) -> Option<NaiveDateTime> {
        let preamble = self.preamble();
        let ts_i64 = i64::try_from(preamble.timestamp_us).unwrap_or(i64::MAX);

        // TODO: directly use Datetime::from_timestamp_micros() once chrono package is re-vendored
        //       to v0.4.35. Below is the actual implementation of that function.
        let from_timestamp_micros = |micros: i64| -> Option<DateTime<_>> {
            let secs = micros.div_euclid(1_000_000);
            let nsecs = micros.rem_euclid(1_000_000) as u32 * 1000;
            DateTime::from_timestamp(secs, nsecs)
        };
        from_timestamp_micros(ts_i64 - SNOOP_Y0_TO_Y1970_US).map(|date| date.naive_utc())
    }
}

pub struct LinuxSnoopPacket {
    pub preamble: SnoopPacketPreamble,
    pub data: Vec<u8>,
}

impl GeneralSnoopPacket for LinuxSnoopPacket {
    fn adapter_index(&self) -> u16 {
        (self.preamble.flags >> 16).try_into().unwrap_or(0u16)
    }
    fn opcode(&self) -> SnoopOpcodes {
        SnoopOpcodes::from_u32(self.preamble.flags & 0xffff).unwrap_or(SnoopOpcodes::Invalid)
    }
    fn preamble(&self) -> &SnoopPacketPreamble {
        &self.preamble
    }
    fn data(&self) -> &Vec<u8> {
        &self.data
    }
}

pub struct H4SnoopPacket {
    pub preamble: SnoopPacketPreamble,
    pub data: Vec<u8>,
    pub pkt_type: u8,
}

impl GeneralSnoopPacket for H4SnoopPacket {
    fn adapter_index(&self) -> u16 {
        0
    }
    fn opcode(&self) -> SnoopOpcodes {
        match self.pkt_type {
            0x01 => SnoopOpcodes::Command,
            0x02 => match self.preamble.flags & 0x01 {
                0x00 => SnoopOpcodes::AclTxPacket,
                _ => SnoopOpcodes::AclRxPacket,
            },
            0x03 => match self.preamble.flags & 0x01 {
                0x00 => SnoopOpcodes::ScoTxPacket,
                _ => SnoopOpcodes::ScoRxPacket,
            },
            0x04 => SnoopOpcodes::Event,
            0x05 => match self.preamble.flags & 0x01 {
                0x00 => SnoopOpcodes::IsoTx,
                _ => SnoopOpcodes::IsoRx,
            },
            _ => SnoopOpcodes::Invalid,
        }
    }
    fn preamble(&self) -> &SnoopPacketPreamble {
        &self.preamble
    }
    fn data(&self) -> &Vec<u8> {
        &self.data
    }
}

/// Maximum packet size for snoop is the max ACL size + 4 bytes.
const SNOOP_MAX_PACKET_SIZE: usize = 1486 + 4;

/// Reader for Linux snoop files.
pub struct LinuxSnoopReader<'a> {
    fd: Box<dyn BufRead + 'a>,
}

impl<'a> LinuxSnoopReader<'a> {
    fn new(fd: Box<dyn BufRead + 'a>) -> Self {
        LinuxSnoopReader { fd }
    }
}

impl<'a> Iterator for LinuxSnoopReader<'a> {
    type Item = Box<dyn GeneralSnoopPacket>;

    fn next(&mut self) -> Option<Self::Item> {
        let preamble = match SnoopPacketPreamble::from_fd(&mut self.fd) {
            Some(preamble) => preamble,
            None => {
                return None;
            }
        };

        if preamble.included_length > 0 {
            let size: usize = (preamble.included_length).try_into().unwrap();
            let mut rem_data = [0u8; SNOOP_MAX_PACKET_SIZE];

            match self.fd.read_exact(&mut rem_data[0..size]) {
                Ok(()) => {
                    Some(Box::new(LinuxSnoopPacket { preamble, data: rem_data[0..size].to_vec() }))
                }
                Err(e) => {
                    eprintln!("Couldn't read any packet data: {}", e);
                    None
                }
            }
        } else {
            Some(Box::new(LinuxSnoopPacket { preamble, data: vec![] }))
        }
    }
}

/// Reader for H4/UART/Android snoop files.
pub struct H4SnoopReader<'a> {
    fd: Box<dyn BufRead + 'a>,
}

impl<'a> H4SnoopReader<'a> {
    fn new(fd: Box<dyn BufRead + 'a>) -> Self {
        H4SnoopReader { fd }
    }
}

impl<'a> Iterator for H4SnoopReader<'a> {
    type Item = Box<dyn GeneralSnoopPacket>;

    fn next(&mut self) -> Option<Self::Item> {
        let preamble = match SnoopPacketPreamble::from_fd(&mut self.fd) {
            Some(preamble) => preamble,
            None => {
                return None;
            }
        };

        if preamble.included_length > 0 {
            let size: usize = (preamble.included_length - 1).try_into().unwrap();
            let mut type_buf = [0u8; 1];
            match self.fd.read_exact(&mut type_buf) {
                Ok(()) => {}
                Err(e) => {
                    eprintln!("Couldn't read any packet data: {}", e);
                    return None;
                }
            };

            let mut rem_data = [0u8; SNOOP_MAX_PACKET_SIZE];
            match self.fd.read_exact(&mut rem_data[0..size]) {
                Ok(()) => Some(Box::new(H4SnoopPacket {
                    preamble,
                    data: rem_data[0..size].to_vec(),
                    pkt_type: type_buf[0],
                })),
                Err(e) => {
                    eprintln!("Couldn't read any packet data: {}", e);
                    None
                }
            }
        } else {
            eprintln!("Non-positive packet size: {}", preamble.included_length);
            None
        }
    }
}

pub struct LogParser {
    fd: Box<dyn BufRead>,
    log_type: SnoopDatalinkType,
}

impl<'a> LogParser {
    pub fn new(filepath: &str) -> std::io::Result<Self> {
        let mut fd: Box<dyn BufRead>;
        if filepath.len() == 0 {
            fd = Box::new(BufReader::new(std::io::stdin()));
        } else {
            fd = Box::new(BufReader::new(File::open(filepath)?));
        }

        let mut buf = [0; SNOOP_HEADER_SIZE];
        fd.read_exact(&mut buf)?;

        match SnoopHeader::try_from(&buf[0..SNOOP_HEADER_SIZE]) {
            Ok(header) => Ok(Self { fd, log_type: header.datalink_type }),
            Err(e) => Err(Error::new(ErrorKind::Other, e)),
        }
    }

    pub fn get_snoop_iterator(self) -> Box<dyn Iterator<Item = Box<dyn GeneralSnoopPacket>>> {
        let reader = Box::new(BufReader::new(self.fd));
        match self.log_type {
            SnoopDatalinkType::H4Uart => Box::new(H4SnoopReader::new(reader)),
            SnoopDatalinkType::LinuxMonitor => Box::new(LinuxSnoopReader::new(reader)),
        }
    }
}

/// Data owned by a packet.
#[derive(Debug, Clone)]
pub enum PacketChild {
    HciCommand(Command),
    HciEvent(Event),
    AclTx(Acl),
    AclRx(Acl),
    NewIndex(NewIndex),
    SystemNote(String),
}

impl<'a> TryFrom<&'a dyn GeneralSnoopPacket> for PacketChild {
    type Error = String;

    fn try_from(item: &'a dyn GeneralSnoopPacket) -> Result<Self, Self::Error> {
        match item.opcode() {
            SnoopOpcodes::Command => match Command::parse(item.data().as_slice()) {
                Ok(command) => Ok(PacketChild::HciCommand(command)),
                Err(e) => Err(format!("Couldn't parse command: {:?}", e)),
            },

            SnoopOpcodes::Event => match Event::parse(item.data().as_slice()) {
                Ok(event) => Ok(PacketChild::HciEvent(event)),
                Err(e) => Err(format!("Couldn't parse event: {:?}", e)),
            },

            SnoopOpcodes::AclTxPacket => match Acl::parse(item.data().as_slice()) {
                Ok(data) => Ok(PacketChild::AclTx(data)),
                Err(e) => Err(format!("Couldn't parse acl tx: {:?}", e)),
            },

            SnoopOpcodes::AclRxPacket => match Acl::parse(item.data().as_slice()) {
                Ok(data) => Ok(PacketChild::AclRx(data)),
                Err(e) => Err(format!("Couldn't parse acl rx: {:?}", e)),
            },

            SnoopOpcodes::NewIndex => match NewIndex::parse(item.data().as_slice()) {
                Ok(data) => Ok(PacketChild::NewIndex(data)),
                Err(e) => Err(format!("Couldn't parse new index: {:?}", e)),
            },

            SnoopOpcodes::SystemNote => match String::from_utf8(item.data().to_vec()) {
                Ok(data) => Ok(PacketChild::SystemNote(data)),
                Err(e) => Err(format!("Couldn't parse system note: {:?}", e)),
            },

            // TODO(b/262928525) - Add packet handlers for more packet types.
            _ => Err(format!("Unhandled packet opcode: {:?}", item.opcode())),
        }
    }
}

/// A single processable packet of data.
#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct Packet {
    /// Timestamp of this packet
    pub ts: NaiveDateTime,

    /// Which adapter this packet is for. Unassociated packets should use 0xFFFE.
    pub adapter_index: u16,

    /// Packet number in current stream.
    pub index: usize,

    /// Inner data for this packet.
    pub inner: PacketChild,
}

impl<'a> TryFrom<(usize, &'a dyn GeneralSnoopPacket)> for Packet {
    type Error = String;

    fn try_from(item: (usize, &'a dyn GeneralSnoopPacket)) -> Result<Self, Self::Error> {
        let (index, packet) = item;
        match PacketChild::try_from(packet) {
            Ok(inner) => {
                let ts = packet.get_timestamp().ok_or(format!(
                    "timestamp conversion error: {}",
                    packet.preamble().timestamp_us
                ))?;
                let adapter_index = packet.adapter_index();
                Ok(Packet { ts, adapter_index, index, inner })
            }

            Err(e) => Err(e),
        }
    }
}

#[allow(dead_code)]
pub enum AclContent {
    Control(Control),
    LeControl(LeControl),
    ConnectionlessData(u16, Vec<u8>),
    StandardData(Vec<u8>),
    None,
}

pub fn get_acl_content(acl: &Acl) -> AclContent {
    match acl.specialize() {
        AclChild::Payload(bytes) => match BasicFrame::parse(bytes.as_ref()) {
            Ok(bf) => match bf.specialize() {
                BasicFrameChild::ControlFrame(cf) => match cf.specialize() {
                    ControlFrameChild::Payload(p) => match Control::parse(p.as_ref()) {
                        Ok(control) => AclContent::Control(control),
                        Err(_) => AclContent::None,
                    },
                    _ => AclContent::None,
                },
                BasicFrameChild::LeControlFrame(lcf) => match lcf.specialize() {
                    LeControlFrameChild::Payload(p) => match LeControl::parse(p.as_ref()) {
                        Ok(le_control) => AclContent::LeControl(le_control),
                        Err(_) => AclContent::None,
                    },
                    _ => AclContent::None,
                },
                BasicFrameChild::GroupFrame(gf) => match gf.specialize() {
                    GroupFrameChild::Payload(p) => {
                        AclContent::ConnectionlessData(gf.get_psm(), p.to_vec())
                    }
                    _ => AclContent::None,
                },
                BasicFrameChild::Payload(p) => AclContent::StandardData(p.to_vec()),
                _ => AclContent::None,
            },
            Err(_) => AclContent::None,
        },
        _ => AclContent::None,
    }
}

#[derive(Clone, Debug)]
pub struct NewIndex {
    _hci_type: u8,
    _bus: u8,
    bdaddr: [u8; 6],
    _name: [u8; 8],
}

impl NewIndex {
    fn parse(data: &[u8]) -> Result<NewIndex, std::string::String> {
        if data.len() != std::mem::size_of::<NewIndex>() {
            return Err(format!("Invalid size for New Index packet: {}", data.len()));
        }

        let rest = data;
        let (hci_type, rest) = rest.split_at(std::mem::size_of::<u8>());
        let (bus, rest) = rest.split_at(std::mem::size_of::<u8>());
        let (bdaddr, rest) = rest.split_at(6 * std::mem::size_of::<u8>());
        let (name, _rest) = rest.split_at(8 * std::mem::size_of::<u8>());

        Ok(NewIndex {
            _hci_type: hci_type[0],
            _bus: bus[0],
            bdaddr: bdaddr.try_into().unwrap(),
            _name: name.try_into().unwrap(),
        })
    }

    pub fn get_addr_str(&self) -> String {
        String::from(format!(
            "[{:02X}:{:02X}:{:02X}:{:02X}:{:02X}:{:02X}]",
            self.bdaddr[0],
            self.bdaddr[1],
            self.bdaddr[2],
            self.bdaddr[3],
            self.bdaddr[4],
            self.bdaddr[5]
        ))
    }
}
