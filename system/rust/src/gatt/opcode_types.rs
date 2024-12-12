//! This module lets us classify AttOpcodes to determine how to handle them

use crate::packets::att::AttOpcode;

/// The type of ATT operation performed by the packet
/// (see Core Spec 5.3 Vol 3F 3.3 Attribute PDU for details)
pub enum OperationType {
    /// Client -> server, no response expected
    Command,
    /// Client -> server, response expected
    Request,
    /// Server -> client, response to a request
    Response,
    /// Server -> client, no response expected
    Notification,
    /// Server -> client, response expected
    Indication,
    /// Client -> server, response to an indication
    Confirmation,
}

/// Classify an opcode by its operation type. Note that this could be done using
/// bitmasking, but is done explicitly for clarity.
pub fn classify_opcode(opcode: AttOpcode) -> OperationType {
    match opcode {
        AttOpcode::ErrorResponse => OperationType::Response,
        AttOpcode::ExchangeMtuResponse => OperationType::Response,
        AttOpcode::FindInformationResponse => OperationType::Response,
        AttOpcode::FindByTypeValueResponse => OperationType::Response,
        AttOpcode::ReadByTypeResponse => OperationType::Response,
        AttOpcode::ReadResponse => OperationType::Response,
        AttOpcode::ReadBlobResponse => OperationType::Response,
        AttOpcode::ReadMultipleResponse => OperationType::Response,
        AttOpcode::ReadByGroupTypeResponse => OperationType::Response,
        AttOpcode::WriteResponse => OperationType::Response,
        AttOpcode::PrepareWriteResponse => OperationType::Response,
        AttOpcode::ExecuteWriteResponse => OperationType::Response,
        AttOpcode::ReadMultipleVariableResponse => OperationType::Response,

        AttOpcode::ExchangeMtuRequest => OperationType::Request,
        AttOpcode::FindInformationRequest => OperationType::Request,
        AttOpcode::FindByTypeValueRequest => OperationType::Request,
        AttOpcode::ReadByTypeRequest => OperationType::Request,
        AttOpcode::ReadRequest => OperationType::Request,
        AttOpcode::ReadBlobRequest => OperationType::Request,
        AttOpcode::ReadMultipleRequest => OperationType::Request,
        AttOpcode::ReadByGroupTypeRequest => OperationType::Request,
        AttOpcode::WriteRequest => OperationType::Request,
        AttOpcode::PrepareWriteRequest => OperationType::Request,
        AttOpcode::ExecuteWriteRequest => OperationType::Request,
        AttOpcode::ReadMultipleVariableRequest => OperationType::Request,

        AttOpcode::WriteCommand => OperationType::Command,
        AttOpcode::SignedWriteCommand => OperationType::Command,

        AttOpcode::HandleValueNotification => OperationType::Notification,

        AttOpcode::HandleValueIndication => OperationType::Indication,

        AttOpcode::HandleValueConfirmation => OperationType::Confirmation,
    }
}
