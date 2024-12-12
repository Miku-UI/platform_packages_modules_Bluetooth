use crate::{gatt::server::att_database::AttDatabase, packets::att};
use pdl_runtime::EncodeError;

pub async fn handle_write_request<T: AttDatabase>(
    request: att::AttWriteRequest,
    db: &T,
) -> Result<att::Att, EncodeError> {
    let handle = request.handle.into();
    let value = request.value;
    match db.write_attribute(handle, &value).await {
        Ok(()) => att::AttWriteResponse {}.try_into(),
        Err(error_code) => att::AttErrorResponse {
            opcode_in_error: att::AttOpcode::WriteRequest,
            handle_in_error: handle.into(),
            error_code,
        }
        .try_into(),
    }
}

#[cfg(test)]
mod test {
    use super::*;

    use tokio_test::block_on;

    use crate::{
        core::uuid::Uuid,
        gatt::{
            ids::AttHandle,
            server::{
                att_database::{AttAttribute, AttDatabase},
                gatt_database::AttPermissions,
                test::test_att_db::TestAttDatabase,
            },
        },
        packets::att,
    };

    #[test]
    fn test_successful_write() {
        // arrange: db with one writable attribute
        let db = TestAttDatabase::new(vec![(
            AttAttribute {
                handle: AttHandle(1),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE | AttPermissions::WRITABLE_WITH_RESPONSE,
            },
            vec![],
        )]);
        let data = vec![1, 2];

        // act: write to the attribute
        let att_view = att::AttWriteRequest { handle: AttHandle(1).into(), value: data.clone() };
        let resp = block_on(handle_write_request(att_view, &db));

        // assert: that the write succeeded
        assert_eq!(resp, att::AttWriteResponse {}.try_into());
        assert_eq!(block_on(db.read_attribute(AttHandle(1))).unwrap(), data);
    }

    #[test]
    fn test_failed_write() {
        // arrange: db with no writable attributes
        let db = TestAttDatabase::new(vec![(
            AttAttribute {
                handle: AttHandle(1),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE,
            },
            vec![],
        )]);
        // act: write to the attribute
        let att_view = att::AttWriteRequest { handle: AttHandle(1).into(), value: vec![1, 2] };
        let resp = block_on(handle_write_request(att_view, &db));

        // assert: that the write failed
        assert_eq!(
            resp,
            att::AttErrorResponse {
                opcode_in_error: att::AttOpcode::WriteRequest,
                handle_in_error: AttHandle(1).into(),
                error_code: att::AttErrorCode::WriteNotPermitted
            }
            .try_into()
        );
    }
}
