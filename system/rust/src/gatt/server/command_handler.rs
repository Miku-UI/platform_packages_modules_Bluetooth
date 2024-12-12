use log::warn;

use crate::packets::att;

use super::att_database::AttDatabase;

/// This struct handles all ATT commands.
pub struct AttCommandHandler<Db: AttDatabase> {
    db: Db,
}

impl<Db: AttDatabase> AttCommandHandler<Db> {
    pub fn new(db: Db) -> Self {
        Self { db }
    }

    pub fn process_packet(&self, packet: att::Att) {
        let snapshotted_db = self.db.snapshot();
        match packet.opcode {
            att::AttOpcode::WriteCommand => {
                let Ok(packet) = att::AttWriteCommand::try_from(packet) else {
                    warn!("failed to parse WRITE_COMMAND packet");
                    return;
                };
                snapshotted_db.write_no_response_attribute(packet.handle.into(), &packet.value);
            }
            _ => {
                warn!("Dropping unsupported opcode {:?}", packet.opcode);
            }
        }
    }
}

#[cfg(test)]
mod test {
    use crate::{
        core::uuid::Uuid,
        gatt::{
            ids::AttHandle,
            server::{
                att_database::{AttAttribute, AttDatabase},
                command_handler::AttCommandHandler,
                gatt_database::AttPermissions,
                test::test_att_db::TestAttDatabase,
            },
        },
        packets::att,
        utils::task::block_on_locally,
    };

    #[test]
    fn test_write_command() {
        // arrange
        let db = TestAttDatabase::new(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE | AttPermissions::WRITABLE_WITHOUT_RESPONSE,
            },
            vec![1, 2, 3],
        )]);
        let handler = AttCommandHandler { db: db.clone() };
        let data = [1, 2];

        // act: send write command
        let att_view = att::AttWriteCommand { handle: AttHandle(3).into(), value: data.to_vec() }
            .try_into()
            .unwrap();
        handler.process_packet(att_view);

        // assert: the db has been updated
        assert_eq!(block_on_locally(db.read_attribute(AttHandle(3))).unwrap(), data);
    }

    #[test]
    fn test_unsupported_command() {
        // arrange
        let db = TestAttDatabase::new(vec![]);
        let handler = AttCommandHandler { db };

        // act: send a packet that should not be handled here
        let att_view = att::AttErrorResponse {
            opcode_in_error: att::AttOpcode::ExchangeMtuRequest,
            handle_in_error: AttHandle(1).into(),
            error_code: att::AttErrorCode::UnlikelyError,
        }
        .try_into()
        .unwrap();
        handler.process_packet(att_view);

        // assert: nothing happens (we crash if anything is unhandled within a mock)
    }
}
