use log::warn;
use pdl_runtime::EncodeError;

use crate::{
    core::uuid::Uuid,
    gatt::{
        ids::AttHandle,
        server::att_database::{AttAttribute, StableAttDatabase},
    },
    packets::att::{self, AttErrorCode},
};

use super::helpers::{
    att_grouping::find_group_end, att_range_filter::filter_to_range,
    payload_accumulator::PayloadAccumulator,
};

pub async fn handle_find_by_type_value_request(
    request: att::AttFindByTypeValueRequest,
    mtu: usize,
    db: &impl StableAttDatabase,
) -> Result<att::Att, EncodeError> {
    let Some(attrs) = filter_to_range(
        request.starting_handle.clone().into(),
        request.ending_handle.into(),
        db.list_attributes().into_iter(),
    ) else {
        return att::AttErrorResponse {
            opcode_in_error: att::AttOpcode::FindByTypeValueRequest,
            handle_in_error: AttHandle::from(request.starting_handle).into(),
            error_code: AttErrorCode::InvalidHandle,
        }
        .try_into();
    };

    // ATT_MTU-1 limit comes from Spec 5.3 Vol 3F Sec 3.4.3.4
    let mut matches = PayloadAccumulator::new(mtu - 1);

    for attr @ AttAttribute { handle, type_, .. } in attrs {
        if Uuid::from(request.attribute_type.clone()) != type_ {
            continue;
        }
        if let Ok(value) = db.read_attribute(handle).await {
            if value == request.attribute_value {
                // match found
                if !matches.push(att::AttributeHandleRange {
                    found_attribute_handle: handle.into(),
                    group_end_handle: find_group_end(db, attr)
                        .map(|attr| attr.handle)
                        .unwrap_or(handle)
                        .into(),
                }) {
                    break;
                }
            }
        } else {
            warn!("skipping {handle:?} in FindByTypeRequest since read failed")
        }
    }

    if matches.is_empty() {
        att::AttErrorResponse {
            opcode_in_error: att::AttOpcode::FindByTypeValueRequest,
            handle_in_error: request.starting_handle,
            error_code: AttErrorCode::AttributeNotFound,
        }
        .try_into()
    } else {
        att::AttFindByTypeValueResponse { handles_info: matches.into_vec() }.try_into()
    }
}

#[cfg(test)]
mod test {
    use crate::{
        gatt::{
            ffi::Uuid,
            server::{
                gatt_database::{
                    AttPermissions, CHARACTERISTIC_UUID, PRIMARY_SERVICE_DECLARATION_UUID,
                },
                test::test_att_db::TestAttDatabase,
            },
        },
        packets::att,
    };

    use super::*;

    const UUID: Uuid = Uuid::new(0);
    const ANOTHER_UUID: Uuid = Uuid::new(1);

    const VALUE: [u8; 2] = [1, 2];
    const ANOTHER_VALUE: [u8; 2] = [3, 4];

    #[test]
    fn test_uuid_match() {
        // arrange: db all with same value, but some with different UUID
        let db = TestAttDatabase::new(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                VALUE.into(),
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: ANOTHER_UUID,
                    permissions: AttPermissions::READABLE,
                },
                VALUE.into(),
            ),
            (
                AttAttribute {
                    handle: AttHandle(5),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                VALUE.into(),
            ),
        ]);

        // act
        let att_view = att::AttFindByTypeValueRequest {
            starting_handle: AttHandle(3).into(),
            ending_handle: AttHandle(5).into(),
            attribute_type: UUID.try_into().unwrap(),
            attribute_value: VALUE.to_vec(),
        };
        let response = tokio_test::block_on(handle_find_by_type_value_request(att_view, 128, &db));

        // assert: we only matched the ones with the correct UUID
        assert_eq!(
            response,
            att::AttFindByTypeValueResponse {
                handles_info: vec![
                    att::AttributeHandleRange {
                        found_attribute_handle: AttHandle(3).into(),
                        group_end_handle: AttHandle(3).into(),
                    },
                    att::AttributeHandleRange {
                        found_attribute_handle: AttHandle(5).into(),
                        group_end_handle: AttHandle(5).into(),
                    },
                ]
            }
            .try_into()
        );
    }

    #[test]
    fn test_value_match() {
        // arrange: db all with same type, but some with different value
        let db = TestAttDatabase::new(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                VALUE.into(),
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                ANOTHER_VALUE.into(),
            ),
            (
                AttAttribute {
                    handle: AttHandle(5),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                VALUE.into(),
            ),
        ]);

        // act
        let att_view = att::AttFindByTypeValueRequest {
            starting_handle: AttHandle(3).into(),
            ending_handle: AttHandle(5).into(),
            attribute_type: UUID.try_into().unwrap(),
            attribute_value: VALUE.to_vec(),
        };
        let response = tokio_test::block_on(handle_find_by_type_value_request(att_view, 128, &db));

        // assert
        assert_eq!(
            response,
            att::AttFindByTypeValueResponse {
                handles_info: vec![
                    att::AttributeHandleRange {
                        found_attribute_handle: AttHandle(3).into(),
                        group_end_handle: AttHandle(3).into(),
                    },
                    att::AttributeHandleRange {
                        found_attribute_handle: AttHandle(5).into(),
                        group_end_handle: AttHandle(5).into(),
                    },
                ]
            }
            .try_into()
        );
    }

    #[test]
    fn test_range_check() {
        // arrange: empty db
        let db = TestAttDatabase::new(vec![]);

        // act: provide an invalid handle range
        let att_view = att::AttFindByTypeValueRequest {
            starting_handle: AttHandle(3).into(),
            ending_handle: AttHandle(1).into(),
            attribute_type: UUID.try_into().unwrap(),
            attribute_value: VALUE.to_vec(),
        };
        let response = tokio_test::block_on(handle_find_by_type_value_request(att_view, 128, &db));

        // assert
        assert_eq!(
            response,
            att::AttErrorResponse {
                opcode_in_error: att::AttOpcode::FindByTypeValueRequest,
                handle_in_error: AttHandle(3).into(),
                error_code: AttErrorCode::InvalidHandle,
            }
            .try_into()
        );
    }

    #[test]
    fn test_empty_response() {
        // arrange
        let db = TestAttDatabase::new(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: UUID,
                permissions: AttPermissions::READABLE,
            },
            VALUE.into(),
        )]);

        // act: query using a range that does not overlap with matching attributes
        let att_view = att::AttFindByTypeValueRequest {
            starting_handle: AttHandle(4).into(),
            ending_handle: AttHandle(5).into(),
            attribute_type: UUID.try_into().unwrap(),
            attribute_value: VALUE.to_vec(),
        };
        let response = tokio_test::block_on(handle_find_by_type_value_request(att_view, 128, &db));

        // assert: got ATTRIBUTE_NOT_FOUND error
        assert_eq!(
            response,
            att::AttErrorResponse {
                opcode_in_error: att::AttOpcode::FindByTypeValueRequest,
                handle_in_error: AttHandle(4).into(),
                error_code: AttErrorCode::AttributeNotFound,
            }
            .try_into()
        );
    }

    #[test]
    fn test_grouping_uuid() {
        // arrange
        let db = TestAttDatabase::new(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: CHARACTERISTIC_UUID,
                    permissions: AttPermissions::READABLE,
                },
                VALUE.into(),
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                VALUE.into(),
            ),
            (
                AttAttribute {
                    handle: AttHandle(5),
                    type_: PRIMARY_SERVICE_DECLARATION_UUID,
                    permissions: AttPermissions::READABLE,
                },
                VALUE.into(),
            ),
        ]);

        // act: look for a particular characteristic declaration
        let att_view = att::AttFindByTypeValueRequest {
            starting_handle: AttHandle(3).into(),
            ending_handle: AttHandle(4).into(),
            attribute_type: CHARACTERISTIC_UUID.try_into().unwrap(),
            attribute_value: VALUE.to_vec(),
        };
        let response = tokio_test::block_on(handle_find_by_type_value_request(att_view, 128, &db));

        // assert
        assert_eq!(
            response,
            att::AttFindByTypeValueResponse {
                handles_info: vec![att::AttributeHandleRange {
                    found_attribute_handle: AttHandle(3).into(),
                    group_end_handle: AttHandle(4).into(),
                },]
            }
            .try_into()
        );
    }

    #[test]
    fn test_limit_total_size() {
        // arrange
        let db = TestAttDatabase::new(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                VALUE.into(),
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                VALUE.into(),
            ),
        ]);

        // act: use MTU = 5, so we can only fit one element in the output
        let att_view = att::AttFindByTypeValueRequest {
            starting_handle: AttHandle(3).into(),
            ending_handle: AttHandle(4).into(),
            attribute_type: UUID.try_into().unwrap(),
            attribute_value: VALUE.to_vec(),
        };
        let response = tokio_test::block_on(handle_find_by_type_value_request(att_view, 5, &db));

        // assert: only one of the two matches produced
        assert_eq!(
            response,
            att::AttFindByTypeValueResponse {
                handles_info: vec![att::AttributeHandleRange {
                    found_attribute_handle: AttHandle(3).into(),
                    group_end_handle: AttHandle(3).into(),
                },]
            }
            .try_into()
        );
    }
}
