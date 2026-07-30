#![forbid(unsafe_code)]

//! Pure, transport-independent codec and mapping policy for host management v1.
//!
//! This module deliberately owns no socket, SSH process, credential, or phone connection. A future
//! authenticated transport may implement [`HostManagementExchange`]; the adapter then remains the
//! only mapping into [`crate::VmServiceBoundary`].

use crate::{
    BootStage, BoundaryError, ControllerSnapshot, HostConnection, HostId, HostStatus, VmBackend,
    VmLifecycle, VmServiceBoundary, VmStatus,
};
use std::fmt;
use std::io::{self, Read};
use std::time::Duration;

pub const COMMAND: &str = "podroid-management-v1";
pub const VERSION: u64 = 1;
pub const MAX_REQUEST_BYTES: usize = 4096;
pub const MAX_RESPONSE_BYTES: usize = 16_384;
pub const REQUEST_DEADLINE: Duration = Duration::from_secs(5);
const FRAME_HEADER_BYTES: usize = 4;
const MAX_JSON_DEPTH: usize = 3;
const MAX_GENERATION: u64 = i64::MAX as u64;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RequestId(String);

impl RequestId {
    pub fn parse(value: &str) -> Result<Self, CodecError> {
        let bytes = value.as_bytes();
        let canonical = bytes.len() == 36
            && bytes.iter().enumerate().all(|(index, byte)| match index {
                8 | 13 | 18 | 23 => *byte == b'-',
                _ => byte.is_ascii_digit() || (b'a'..=b'f').contains(byte),
            })
            && bytes[14] == b'4'
            && matches!(bytes[19], b'8' | b'9' | b'a' | b'b');
        if !canonical {
            return Err(CodecError::InvalidRequestId);
        }
        Ok(Self(value.to_owned()))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Operation {
    ProtocolDescribe,
    VmDefaultStatus,
    VmDefaultStart,
    VmDefaultStop,
}

impl Operation {
    pub const ALL: [Self; 4] = [
        Self::ProtocolDescribe,
        Self::VmDefaultStatus,
        Self::VmDefaultStart,
        Self::VmDefaultStop,
    ];

    pub const fn as_str(self) -> &'static str {
        match self {
            Self::ProtocolDescribe => "protocol.describe",
            Self::VmDefaultStatus => "vm.default.status",
            Self::VmDefaultStart => "vm.default.start",
            Self::VmDefaultStop => "vm.default.stop",
        }
    }

    fn parse(value: &str) -> Result<Self, CodecError> {
        Self::ALL
            .into_iter()
            .find(|operation| operation.as_str() == value)
            .ok_or(CodecError::UnknownOperation)
    }

    pub const fn is_mutation(self) -> bool {
        matches!(self, Self::VmDefaultStart | Self::VmDefaultStop)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Generation(u64);

impl Generation {
    pub fn new(value: u64) -> Result<Self, CodecError> {
        if value > MAX_GENERATION {
            return Err(CodecError::InvalidGeneration);
        }
        Ok(Self(value))
    }

    pub const fn value(self) -> u64 {
        self.0
    }

    fn next(self) -> Result<Self, CodecError> {
        Self::new(self.0.checked_add(1).ok_or(CodecError::InvalidGeneration)?)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Request {
    request_id: RequestId,
    operation: Operation,
    if_generation: Option<Generation>,
}

impl Request {
    pub fn describe(request_id: RequestId) -> Self {
        Self {
            request_id,
            operation: Operation::ProtocolDescribe,
            if_generation: None,
        }
    }

    pub fn status(request_id: RequestId) -> Self {
        Self {
            request_id,
            operation: Operation::VmDefaultStatus,
            if_generation: None,
        }
    }

    pub fn start(request_id: RequestId, if_generation: Generation) -> Self {
        Self {
            request_id,
            operation: Operation::VmDefaultStart,
            if_generation: Some(if_generation),
        }
    }

    pub fn stop(request_id: RequestId, if_generation: Generation) -> Self {
        Self {
            request_id,
            operation: Operation::VmDefaultStop,
            if_generation: Some(if_generation),
        }
    }

    pub fn request_id(&self) -> &RequestId {
        &self.request_id
    }

    pub fn operation(&self) -> Operation {
        self.operation
    }

    pub fn if_generation(&self) -> Option<Generation> {
        self.if_generation
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ErrorCode {
    InvalidRequest,
    UnsupportedVersion,
    UnknownOperation,
    Unauthenticated,
    Forbidden,
    GenerationMismatch,
    Conflict,
    Busy,
    Timeout,
    AuditUnavailable,
    CapacityExceeded,
    ProviderUnavailable,
    Interrupted,
    Indeterminate,
    InternalError,
}

impl ErrorCode {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::InvalidRequest => "invalid_request",
            Self::UnsupportedVersion => "unsupported_version",
            Self::UnknownOperation => "unknown_operation",
            Self::Unauthenticated => "unauthenticated",
            Self::Forbidden => "forbidden",
            Self::GenerationMismatch => "generation_mismatch",
            Self::Conflict => "conflict",
            Self::Busy => "busy",
            Self::Timeout => "timeout",
            Self::AuditUnavailable => "audit_unavailable",
            Self::CapacityExceeded => "capacity_exceeded",
            Self::ProviderUnavailable => "provider_unavailable",
            Self::Interrupted => "interrupted",
            Self::Indeterminate => "indeterminate",
            Self::InternalError => "internal_error",
        }
    }

    pub const fn retryable(self) -> bool {
        matches!(
            self,
            Self::Busy
                | Self::Timeout
                | Self::AuditUnavailable
                | Self::CapacityExceeded
                | Self::ProviderUnavailable
                | Self::Interrupted
                | Self::InternalError
        )
    }

    fn parse(value: &str) -> Result<Self, CodecError> {
        let code = match value {
            "invalid_request" => Self::InvalidRequest,
            "unsupported_version" => Self::UnsupportedVersion,
            "unknown_operation" => Self::UnknownOperation,
            "unauthenticated" => Self::Unauthenticated,
            "forbidden" => Self::Forbidden,
            "generation_mismatch" => Self::GenerationMismatch,
            "conflict" => Self::Conflict,
            "busy" => Self::Busy,
            "timeout" => Self::Timeout,
            "audit_unavailable" => Self::AuditUnavailable,
            "capacity_exceeded" => Self::CapacityExceeded,
            "provider_unavailable" => Self::ProviderUnavailable,
            "interrupted" => Self::Interrupted,
            "indeterminate" => Self::Indeterminate,
            "internal_error" => Self::InternalError,
            _ => return Err(CodecError::UnknownErrorCode),
        };
        Ok(code)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ProtocolFailure {
    code: ErrorCode,
}

impl ProtocolFailure {
    pub const fn new(code: ErrorCode) -> Self {
        Self { code }
    }

    pub const fn code(self) -> ErrorCode {
        self.code
    }

    pub const fn retryable(self) -> bool {
        self.code.retryable()
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ManagedVmStatus {
    generation: Generation,
    status: VmStatus,
}

impl ManagedVmStatus {
    pub fn new(generation: Generation, status: VmStatus) -> Self {
        Self { generation, status }
    }

    pub fn generation(&self) -> Generation {
        self.generation
    }

    pub fn status(&self) -> &VmStatus {
        &self.status
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Success {
    ProtocolDescription,
    VmStatus(ManagedVmStatus),
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Outcome {
    Success(Success),
    Failure(ProtocolFailure),
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Response {
    request_id: RequestId,
    outcome: Outcome,
}

impl Response {
    pub fn success(request_id: RequestId, success: Success) -> Self {
        Self {
            request_id,
            outcome: Outcome::Success(success),
        }
    }

    pub fn failure(request_id: RequestId, code: ErrorCode) -> Self {
        Self {
            request_id,
            outcome: Outcome::Failure(ProtocolFailure::new(code)),
        }
    }

    pub fn request_id(&self) -> &RequestId {
        &self.request_id
    }

    pub fn outcome(&self) -> &Outcome {
        &self.outcome
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CodecError {
    EmptyFrame,
    FrameTooLarge,
    TruncatedFrame,
    TrailingBytes,
    MalformedUtf8,
    MalformedJson,
    JsonNestingTooDeep,
    DuplicateField,
    UnknownField,
    MissingField,
    WrongFieldType,
    UnsupportedVersion,
    UnknownOperation,
    InvalidRequestId,
    InvalidGeneration,
    InvalidRetrySemantics,
    UnknownErrorCode,
    UnexpectedResult,
    ProtocolMismatch,
    InvalidVmStatus,
    Io,
}

impl fmt::Display for CodecError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "host-management codec rejected input: {self:?}")
    }
}

impl std::error::Error for CodecError {}

pub fn encode_request_payload(request: &Request) -> Vec<u8> {
    let mut json = format!(
        "{{\"version\":1,\"request_id\":\"{}\",\"operation\":\"{}\"",
        request.request_id.as_str(),
        request.operation.as_str()
    );
    json.push_str(",\"parameters\":{");
    if let Some(generation) = request.if_generation {
        json.push_str(&format!("\"if_generation\":{}", generation.value()));
    }
    json.push_str("}}");
    debug_assert!(json.len() <= MAX_REQUEST_BYTES);
    json.into_bytes()
}

pub fn encode_request_frame(request: &Request) -> Vec<u8> {
    frame_payload(&encode_request_payload(request))
}

pub fn decode_request_payload(payload: &[u8]) -> Result<Request, CodecError> {
    enforce_payload_bound(payload, MAX_REQUEST_BYTES)?;
    let root = parse_json(payload)?;
    let mut object = root.into_object()?;
    let version = take_required_u64(&mut object, "version")?;
    if version != VERSION {
        return Err(CodecError::UnsupportedVersion);
    }
    let request_id = RequestId::parse(&take_required_string(&mut object, "request_id")?)?;
    let operation = Operation::parse(&take_required_string(&mut object, "operation")?)?;
    let mut parameters = take_required(&mut object, "parameters")?.into_object()?;
    reject_unknown_fields(&object)?;
    let if_generation = if operation.is_mutation() {
        let generation = Generation::new(take_required_u64(&mut parameters, "if_generation")?)?;
        reject_unknown_fields(&parameters)?;
        Some(generation)
    } else {
        reject_unknown_fields(&parameters)?;
        None
    };
    Ok(Request {
        request_id,
        operation,
        if_generation,
    })
}

pub fn decode_request_frame(frame: &[u8]) -> Result<Request, CodecError> {
    decode_request_payload(unframe_bytes(frame, MAX_REQUEST_BYTES)?)
}

pub fn read_request_frame(reader: &mut impl Read) -> Result<Request, CodecError> {
    let payload = read_one_frame(reader, MAX_REQUEST_BYTES)?;
    decode_request_payload(&payload)
}

pub fn encode_response_payload(response: &Response) -> Result<Vec<u8>, CodecError> {
    let mut json = format!(
        "{{\"version\":1,\"request_id\":\"{}\",",
        response.request_id.as_str()
    );
    match &response.outcome {
        Outcome::Failure(failure) => {
            json.push_str(&format!(
                "\"error\":{{\"code\":\"{}\",\"retryable\":{}}}}}",
                failure.code.as_str(),
                failure.retryable()
            ));
        }
        Outcome::Success(Success::ProtocolDescription) => {
            json.push_str("\"result\":{\"protocol\":\"podroid-management-v1\",\"operations\":[\"protocol.describe\",\"vm.default.status\",\"vm.default.start\",\"vm.default.stop\"],\"request_max_bytes\":4096,\"response_max_bytes\":16384}}");
        }
        Outcome::Success(Success::VmStatus(managed)) => {
            let status = managed.status();
            json.push_str("\"result\":{");
            json.push_str("\"vm_id\":\"default\",");
            json.push_str(&format!("\"generation\":{},", managed.generation().value()));
            json.push_str(&format!(
                "\"lifecycle\":\"{}\",\"backend\":\"{}\",\"boot_stage\":\"{}\",",
                lifecycle_text(status.lifecycle()),
                backend_text(status.backend()),
                boot_stage_text(status.boot_stage())
            ));
            match status.uptime_seconds() {
                Some(seconds) => json.push_str(&format!("\"uptime_seconds\":{seconds},")),
                None => json.push_str("\"uptime_seconds\":null,"),
            }
            match status.error() {
                Some(message) => {
                    json.push_str("\"error\":\"");
                    push_json_string_contents(&mut json, message);
                    json.push_str("\"}}");
                }
                None => json.push_str("\"error\":null}}"),
            }
        }
    }
    if json.len() > MAX_RESPONSE_BYTES {
        return Err(CodecError::FrameTooLarge);
    }
    Ok(json.into_bytes())
}

pub fn encode_response_frame(response: &Response) -> Result<Vec<u8>, CodecError> {
    Ok(frame_payload(&encode_response_payload(response)?))
}

pub fn decode_response_payload(payload: &[u8], request: &Request) -> Result<Response, CodecError> {
    enforce_payload_bound(payload, MAX_RESPONSE_BYTES)?;
    let root = parse_json(payload)?;
    let mut object = root.into_object()?;
    let version = take_required_u64(&mut object, "version")?;
    if version != VERSION {
        return Err(CodecError::UnsupportedVersion);
    }
    let request_id = RequestId::parse(&take_required_string(&mut object, "request_id")?)?;
    if request_id != request.request_id {
        return Err(CodecError::ProtocolMismatch);
    }
    let result = take_optional(&mut object, "result")?;
    let error = take_optional(&mut object, "error")?;
    reject_unknown_fields(&object)?;
    let outcome = match (result, error) {
        (Some(_), Some(_)) | (None, None) => return Err(CodecError::MissingField),
        (None, Some(error)) => Outcome::Failure(parse_failure(error)?),
        (Some(result), None) => Outcome::Success(parse_success(result, request)?),
    };
    Ok(Response {
        request_id,
        outcome,
    })
}

pub fn decode_response_frame(frame: &[u8], request: &Request) -> Result<Response, CodecError> {
    decode_response_payload(unframe_bytes(frame, MAX_RESPONSE_BYTES)?, request)
}

pub fn read_response_frame(
    reader: &mut impl Read,
    request: &Request,
) -> Result<Response, CodecError> {
    let payload = read_one_frame(reader, MAX_RESPONSE_BYTES)?;
    decode_response_payload(&payload, request)
}

fn parse_success(value: JsonValue, request: &Request) -> Result<Success, CodecError> {
    match request.operation {
        Operation::ProtocolDescribe => parse_description(value),
        Operation::VmDefaultStatus | Operation::VmDefaultStart | Operation::VmDefaultStop => {
            let managed = parse_vm_status(value)?;
            let expected_generation = match request.operation {
                Operation::VmDefaultStart => {
                    request.if_generation.map(Generation::next).transpose()?
                }
                Operation::VmDefaultStop => request.if_generation,
                Operation::VmDefaultStatus => None,
                Operation::ProtocolDescribe => unreachable!("handled above"),
            };
            if expected_generation.is_some_and(|expected| managed.generation != expected) {
                return Err(CodecError::InvalidGeneration);
            }
            Ok(Success::VmStatus(managed))
        }
    }
}

fn parse_description(value: JsonValue) -> Result<Success, CodecError> {
    let mut object = value.into_object()?;
    if take_required_string(&mut object, "protocol")? != COMMAND {
        return Err(CodecError::ProtocolMismatch);
    }
    let operations = take_required(&mut object, "operations")?.into_array()?;
    if operations.len() != Operation::ALL.len()
        || operations
            .into_iter()
            .zip(Operation::ALL)
            .any(|(actual, expected)| {
                actual.into_string().ok().as_deref() != Some(expected.as_str())
            })
    {
        return Err(CodecError::ProtocolMismatch);
    }
    if take_required_u64(&mut object, "request_max_bytes")? != MAX_REQUEST_BYTES as u64
        || take_required_u64(&mut object, "response_max_bytes")? != MAX_RESPONSE_BYTES as u64
    {
        return Err(CodecError::ProtocolMismatch);
    }
    reject_unknown_fields(&object)?;
    Ok(Success::ProtocolDescription)
}

fn parse_failure(value: JsonValue) -> Result<ProtocolFailure, CodecError> {
    let mut object = value.into_object()?;
    let code = ErrorCode::parse(&take_required_string(&mut object, "code")?)?;
    let retryable = take_required_bool(&mut object, "retryable")?;
    reject_unknown_fields(&object)?;
    if retryable != code.retryable() {
        return Err(CodecError::InvalidRetrySemantics);
    }
    Ok(ProtocolFailure::new(code))
}

fn parse_vm_status(value: JsonValue) -> Result<ManagedVmStatus, CodecError> {
    let mut object = value.into_object()?;
    if take_required_string(&mut object, "vm_id")? != "default" {
        return Err(CodecError::ProtocolMismatch);
    }
    let generation = Generation::new(take_required_u64(&mut object, "generation")?)?;
    let lifecycle = match take_required_string(&mut object, "lifecycle")?.as_str() {
        "stopped" => VmLifecycle::Stopped,
        "starting" => VmLifecycle::Starting,
        "running" => VmLifecycle::Running,
        "stopping" => VmLifecycle::Stopping,
        "error" => VmLifecycle::Error,
        _ => return Err(CodecError::InvalidVmStatus),
    };
    let backend = match take_required_string(&mut object, "backend")?.as_str() {
        "qemu_tcg" => VmBackend::QemuTcg,
        "avf_pvm" => VmBackend::AvfPvm,
        "unknown" => VmBackend::Unknown,
        _ => return Err(CodecError::InvalidVmStatus),
    };
    let boot_stage = match take_required_string(&mut object, "boot_stage")?.as_str() {
        "idle" => BootStage::Idle,
        "installing" => BootStage::Installing,
        "starting_ssh" => BootStage::StartingSsh,
        "almost_ready" => BootStage::AlmostReady,
        "ready" => BootStage::Ready,
        "failed" => BootStage::Failed,
        _ => return Err(CodecError::InvalidVmStatus),
    };
    let uptime_seconds = take_required_nullable_u64(&mut object, "uptime_seconds")?;
    let error = take_required_nullable_string(&mut object, "error")?;
    reject_unknown_fields(&object)?;
    let status = VmStatus::new(
        lifecycle,
        backend,
        boot_stage,
        uptime_seconds,
        error.as_deref(),
    )
    .map_err(|_| CodecError::InvalidVmStatus)?;
    Ok(ManagedVmStatus::new(generation, status))
}

fn lifecycle_text(value: VmLifecycle) -> &'static str {
    match value {
        VmLifecycle::Stopped => "stopped",
        VmLifecycle::Starting => "starting",
        VmLifecycle::Running => "running",
        VmLifecycle::Stopping => "stopping",
        VmLifecycle::Error => "error",
    }
}

fn backend_text(value: VmBackend) -> &'static str {
    match value {
        VmBackend::QemuTcg => "qemu_tcg",
        VmBackend::AvfPvm => "avf_pvm",
        VmBackend::Unknown => "unknown",
    }
}

fn boot_stage_text(value: BootStage) -> &'static str {
    match value {
        BootStage::Idle => "idle",
        BootStage::Installing => "installing",
        BootStage::StartingSsh => "starting_ssh",
        BootStage::AlmostReady => "almost_ready",
        BootStage::Ready => "ready",
        BootStage::Failed => "failed",
    }
}

fn push_json_string_contents(output: &mut String, value: &str) {
    for character in value.chars() {
        match character {
            '"' => output.push_str("\\\""),
            '\\' => output.push_str("\\\\"),
            '\u{08}' => output.push_str("\\b"),
            '\u{0c}' => output.push_str("\\f"),
            '\n' => output.push_str("\\n"),
            '\r' => output.push_str("\\r"),
            '\t' => output.push_str("\\t"),
            character if character.is_control() => {
                output.push_str(&format!("\\u{:04x}", character as u32));
            }
            character => output.push(character),
        }
    }
}

fn frame_payload(payload: &[u8]) -> Vec<u8> {
    let length = u32::try_from(payload.len()).expect("bounded protocol frame fits u32");
    let mut frame = Vec::with_capacity(FRAME_HEADER_BYTES + payload.len());
    frame.extend_from_slice(&length.to_be_bytes());
    frame.extend_from_slice(payload);
    frame
}

fn unframe_bytes(frame: &[u8], max_payload: usize) -> Result<&[u8], CodecError> {
    if frame.len() < FRAME_HEADER_BYTES {
        return Err(CodecError::TruncatedFrame);
    }
    let announced = u32::from_be_bytes(frame[..FRAME_HEADER_BYTES].try_into().expect("four bytes"));
    let announced = usize::try_from(announced).map_err(|_| CodecError::FrameTooLarge)?;
    if announced == 0 {
        return Err(CodecError::EmptyFrame);
    }
    if announced > max_payload {
        return Err(CodecError::FrameTooLarge);
    }
    let expected = FRAME_HEADER_BYTES + announced;
    match frame.len().cmp(&expected) {
        std::cmp::Ordering::Less => Err(CodecError::TruncatedFrame),
        std::cmp::Ordering::Greater => Err(CodecError::TrailingBytes),
        std::cmp::Ordering::Equal => Ok(&frame[FRAME_HEADER_BYTES..]),
    }
}

fn read_one_frame(reader: &mut impl Read, max_payload: usize) -> Result<Vec<u8>, CodecError> {
    let mut header = [0u8; FRAME_HEADER_BYTES];
    reader.read_exact(&mut header).map_err(map_read_error)?;
    let announced =
        usize::try_from(u32::from_be_bytes(header)).map_err(|_| CodecError::FrameTooLarge)?;
    if announced == 0 {
        return Err(CodecError::EmptyFrame);
    }
    if announced > max_payload {
        return Err(CodecError::FrameTooLarge);
    }
    let mut payload = vec![0; announced];
    reader.read_exact(&mut payload).map_err(map_read_error)?;
    let mut trailing = [0u8; 1];
    match reader.read(&mut trailing) {
        Ok(0) => Ok(payload),
        Ok(_) => Err(CodecError::TrailingBytes),
        Err(_) => Err(CodecError::Io),
    }
}

fn map_read_error(error: io::Error) -> CodecError {
    if error.kind() == io::ErrorKind::UnexpectedEof {
        CodecError::TruncatedFrame
    } else {
        CodecError::Io
    }
}

fn enforce_payload_bound(payload: &[u8], max: usize) -> Result<(), CodecError> {
    if payload.is_empty() {
        Err(CodecError::EmptyFrame)
    } else if payload.len() > max {
        Err(CodecError::FrameTooLarge)
    } else {
        Ok(())
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
enum JsonValue {
    Null,
    Bool(bool),
    Number(u64),
    String(String),
    Array(Vec<JsonValue>),
    Object(Vec<(String, JsonValue)>),
}

impl JsonValue {
    fn into_object(self) -> Result<Vec<(String, JsonValue)>, CodecError> {
        match self {
            Self::Object(value) => Ok(value),
            _ => Err(CodecError::WrongFieldType),
        }
    }

    fn into_array(self) -> Result<Vec<JsonValue>, CodecError> {
        match self {
            Self::Array(value) => Ok(value),
            _ => Err(CodecError::WrongFieldType),
        }
    }

    fn into_string(self) -> Result<String, CodecError> {
        match self {
            Self::String(value) => Ok(value),
            _ => Err(CodecError::WrongFieldType),
        }
    }

    fn into_u64(self) -> Result<u64, CodecError> {
        match self {
            Self::Number(value) => Ok(value),
            _ => Err(CodecError::WrongFieldType),
        }
    }
}

fn parse_json(payload: &[u8]) -> Result<JsonValue, CodecError> {
    let text = std::str::from_utf8(payload).map_err(|_| CodecError::MalformedUtf8)?;
    let mut parser = JsonParser {
        bytes: text.as_bytes(),
        cursor: 0,
    };
    let value = parser.parse_value(0)?;
    if parser.cursor != parser.bytes.len() {
        return Err(CodecError::TrailingBytes);
    }
    Ok(value)
}

struct JsonParser<'a> {
    bytes: &'a [u8],
    cursor: usize,
}

impl JsonParser<'_> {
    fn parse_value(&mut self, depth: usize) -> Result<JsonValue, CodecError> {
        if depth > MAX_JSON_DEPTH {
            return Err(CodecError::JsonNestingTooDeep);
        }
        self.skip_whitespace();
        match self.peek() {
            Some(b'{') => self.parse_object(depth + 1),
            Some(b'[') => self.parse_array(depth + 1),
            Some(b'"') => self.parse_string().map(JsonValue::String),
            Some(b'0'..=b'9') => self.parse_number().map(JsonValue::Number),
            Some(b'n') => {
                self.literal(b"null")?;
                Ok(JsonValue::Null)
            }
            Some(b't') => {
                self.literal(b"true")?;
                Ok(JsonValue::Bool(true))
            }
            Some(b'f') => {
                self.literal(b"false")?;
                Ok(JsonValue::Bool(false))
            }
            _ => Err(CodecError::MalformedJson),
        }
    }

    fn parse_object(&mut self, depth: usize) -> Result<JsonValue, CodecError> {
        self.expect(b'{')?;
        let mut fields: Vec<(String, JsonValue)> = Vec::new();
        self.skip_whitespace();
        if self.consume(b'}') {
            return Ok(JsonValue::Object(fields));
        }
        loop {
            self.skip_whitespace();
            let key = self.parse_string()?;
            if fields.iter().any(|(existing, _)| existing == &key) {
                return Err(CodecError::DuplicateField);
            }
            self.skip_whitespace();
            self.expect(b':')?;
            let value = self.parse_value(depth)?;
            fields.push((key, value));
            self.skip_whitespace();
            if self.consume(b'}') {
                return Ok(JsonValue::Object(fields));
            }
            self.expect(b',')?;
        }
    }

    fn parse_array(&mut self, depth: usize) -> Result<JsonValue, CodecError> {
        self.expect(b'[')?;
        let mut values = Vec::new();
        self.skip_whitespace();
        if self.consume(b']') {
            return Ok(JsonValue::Array(values));
        }
        loop {
            values.push(self.parse_value(depth)?);
            self.skip_whitespace();
            if self.consume(b']') {
                return Ok(JsonValue::Array(values));
            }
            self.expect(b',')?;
        }
    }

    fn parse_number(&mut self) -> Result<u64, CodecError> {
        let start = self.cursor;
        while matches!(self.peek(), Some(b'0'..=b'9')) {
            self.cursor += 1;
        }
        let bytes = &self.bytes[start..self.cursor];
        if bytes.len() > 1 && bytes[0] == b'0' {
            return Err(CodecError::MalformedJson);
        }
        let text = std::str::from_utf8(bytes).map_err(|_| CodecError::MalformedJson)?;
        text.parse().map_err(|_| CodecError::MalformedJson)
    }

    fn parse_string(&mut self) -> Result<String, CodecError> {
        self.expect(b'"')?;
        let mut output = String::new();
        let mut segment_start = self.cursor;
        loop {
            let byte = self.peek().ok_or(CodecError::MalformedJson)?;
            match byte {
                b'"' => {
                    let segment = std::str::from_utf8(&self.bytes[segment_start..self.cursor])
                        .map_err(|_| CodecError::MalformedUtf8)?;
                    output.push_str(segment);
                    self.cursor += 1;
                    return Ok(output);
                }
                b'\\' => {
                    let segment = std::str::from_utf8(&self.bytes[segment_start..self.cursor])
                        .map_err(|_| CodecError::MalformedUtf8)?;
                    output.push_str(segment);
                    self.cursor += 1;
                    let escaped = self.peek().ok_or(CodecError::MalformedJson)?;
                    self.cursor += 1;
                    match escaped {
                        b'"' => output.push('"'),
                        b'\\' => output.push('\\'),
                        b'/' => output.push('/'),
                        b'b' => output.push('\u{08}'),
                        b'f' => output.push('\u{0c}'),
                        b'n' => output.push('\n'),
                        b'r' => output.push('\r'),
                        b't' => output.push('\t'),
                        // Surrogate pairs are intentionally excluded: protocol fields never need
                        // them and accepting only scalar escapes keeps canonical validation small.
                        b'u' => {
                            let digits = self.take(4)?;
                            let digits = std::str::from_utf8(digits)
                                .map_err(|_| CodecError::MalformedJson)?;
                            let scalar = u32::from_str_radix(digits, 16)
                                .map_err(|_| CodecError::MalformedJson)?;
                            let character = char::from_u32(scalar)
                                .filter(|value| !matches!(*value as u32, 0xd800..=0xdfff))
                                .ok_or(CodecError::MalformedJson)?;
                            output.push(character);
                        }
                        _ => return Err(CodecError::MalformedJson),
                    }
                    segment_start = self.cursor;
                }
                0x00..=0x1f => return Err(CodecError::MalformedJson),
                _ => self.cursor += 1,
            }
        }
    }

    fn literal(&mut self, literal: &[u8]) -> Result<(), CodecError> {
        if self.bytes.get(self.cursor..self.cursor + literal.len()) == Some(literal) {
            self.cursor += literal.len();
            Ok(())
        } else {
            Err(CodecError::MalformedJson)
        }
    }

    fn skip_whitespace(&mut self) {
        while matches!(self.peek(), Some(b' ' | b'\n' | b'\r' | b'\t')) {
            self.cursor += 1;
        }
    }

    fn peek(&self) -> Option<u8> {
        self.bytes.get(self.cursor).copied()
    }

    fn consume(&mut self, byte: u8) -> bool {
        if self.peek() == Some(byte) {
            self.cursor += 1;
            true
        } else {
            false
        }
    }

    fn expect(&mut self, byte: u8) -> Result<(), CodecError> {
        if self.consume(byte) {
            Ok(())
        } else {
            Err(CodecError::MalformedJson)
        }
    }

    fn take(&mut self, count: usize) -> Result<&[u8], CodecError> {
        let end = self
            .cursor
            .checked_add(count)
            .ok_or(CodecError::MalformedJson)?;
        let value = self
            .bytes
            .get(self.cursor..end)
            .ok_or(CodecError::MalformedJson)?;
        self.cursor = end;
        Ok(value)
    }
}

fn take_required(
    fields: &mut Vec<(String, JsonValue)>,
    name: &str,
) -> Result<JsonValue, CodecError> {
    take_optional(fields, name)?.ok_or(CodecError::MissingField)
}

fn take_optional(
    fields: &mut Vec<(String, JsonValue)>,
    name: &str,
) -> Result<Option<JsonValue>, CodecError> {
    Ok(fields
        .iter()
        .position(|(key, _)| key == name)
        .map(|index| fields.swap_remove(index).1))
}

fn take_required_string(
    fields: &mut Vec<(String, JsonValue)>,
    name: &str,
) -> Result<String, CodecError> {
    take_required(fields, name)?.into_string()
}

fn take_required_u64(fields: &mut Vec<(String, JsonValue)>, name: &str) -> Result<u64, CodecError> {
    take_required(fields, name)?.into_u64()
}

fn take_required_bool(
    fields: &mut Vec<(String, JsonValue)>,
    name: &str,
) -> Result<bool, CodecError> {
    match take_required(fields, name)? {
        JsonValue::Bool(value) => Ok(value),
        _ => Err(CodecError::WrongFieldType),
    }
}

fn take_required_nullable_u64(
    fields: &mut Vec<(String, JsonValue)>,
    name: &str,
) -> Result<Option<u64>, CodecError> {
    match take_required(fields, name)? {
        JsonValue::Null => Ok(None),
        JsonValue::Number(value) => Ok(Some(value)),
        _ => Err(CodecError::WrongFieldType),
    }
}

fn take_required_nullable_string(
    fields: &mut Vec<(String, JsonValue)>,
    name: &str,
) -> Result<Option<String>, CodecError> {
    match take_required(fields, name)? {
        JsonValue::Null => Ok(None),
        JsonValue::String(value) => Ok(Some(value)),
        _ => Err(CodecError::WrongFieldType),
    }
}

fn reject_unknown_fields(fields: &[(String, JsonValue)]) -> Result<(), CodecError> {
    if fields.is_empty() {
        Ok(())
    } else {
        Err(CodecError::UnknownField)
    }
}

/// Abstract authenticated call seam. No implementation is supplied by controller-core.
///
/// Implementations must use a dedicated Android-host identity and must not accept a guest SSH
/// credential. They must execute exactly [`COMMAND`], apply their own bounded deadline, and return
/// one complete framed response. The adapter rechecks the response bound and schema.
pub trait HostManagementExchange: Send + 'static {
    /// Executes only the fixed [`COMMAND`] subsystem; there is deliberately no command parameter.
    /// The implementation must fit the complete response frame in the caller-owned buffer and
    /// return the initialized byte count, so a length prefix can never trigger unbounded allocation.
    fn exchange_v1(
        &mut self,
        framed_request: &[u8],
        framed_response: &mut [u8],
        deadline: Duration,
    ) -> Result<usize, BoundaryError>;
}

/// Explicit source for operation IDs; randomness and persistence stay outside protocol policy.
pub trait RequestIdSource: Send + 'static {
    fn next_request_id(&mut self) -> Result<RequestId, BoundaryError>;
}

/// The sole host-management mapping seam into [`VmServiceBoundary`].
///
/// It stores only the last authoritative generation. Mutations cannot be emitted before a status
/// response. A successful start advances the runtime generation once; stop preserves it.
pub struct HostManagementVmService<E, I> {
    exchange: E,
    request_ids: I,
    host_id: HostId,
    generation: Option<Generation>,
}

impl<E, I> HostManagementVmService<E, I> {
    pub fn new(exchange: E, request_ids: I, host_id: HostId) -> Self {
        Self {
            exchange,
            request_ids,
            host_id,
            generation: None,
        }
    }
}

impl<E: HostManagementExchange, I: RequestIdSource> HostManagementVmService<E, I> {
    fn perform(&mut self, operation: Operation) -> Result<ControllerSnapshot, BoundaryError> {
        let request_id = self.request_ids.next_request_id()?;
        let request = match operation {
            Operation::VmDefaultStatus => Request::status(request_id),
            Operation::VmDefaultStart => Request::start(
                request_id,
                self.generation.ok_or_else(status_required_error)?,
            ),
            Operation::VmDefaultStop => Request::stop(
                request_id,
                self.generation.ok_or_else(status_required_error)?,
            ),
            Operation::ProtocolDescribe => return Err(protocol_boundary_error("invalid mapping")),
        };
        let frame = encode_request_frame(&request);
        let mut response_frame = [0u8; FRAME_HEADER_BYTES + MAX_RESPONSE_BYTES];
        let response_length =
            self.exchange
                .exchange_v1(&frame, &mut response_frame, REQUEST_DEADLINE)?;
        let response_bytes = response_frame
            .get(..response_length)
            .ok_or_else(|| protocol_boundary_error("invalid management response length"))?;
        let response = decode_response_frame(response_bytes, &request)
            .map_err(|_| protocol_boundary_error("invalid management response"))?;
        match response.outcome {
            Outcome::Success(Success::VmStatus(managed)) => {
                self.generation = Some(managed.generation);
                let host = HostStatus::new(
                    HostConnection::AuthenticatedManagement,
                    Some(self.host_id.clone()),
                )
                .map_err(|_| protocol_boundary_error("invalid management host"))?;
                Ok(ControllerSnapshot::new(host, managed.status))
            }
            Outcome::Failure(failure) => Err(protocol_boundary_error(failure.code.as_str())),
            Outcome::Success(Success::ProtocolDescription) => {
                Err(protocol_boundary_error("unexpected management response"))
            }
        }
    }
}

impl<E: HostManagementExchange, I: RequestIdSource> VmServiceBoundary
    for HostManagementVmService<E, I>
{
    fn refresh(&mut self) -> Result<ControllerSnapshot, BoundaryError> {
        self.perform(Operation::VmDefaultStatus)
    }

    fn start(&mut self) -> Result<ControllerSnapshot, BoundaryError> {
        self.perform(Operation::VmDefaultStart)
    }

    fn stop(&mut self) -> Result<ControllerSnapshot, BoundaryError> {
        self.perform(Operation::VmDefaultStop)
    }
}

fn status_required_error() -> BoundaryError {
    protocol_boundary_error("management status required")
}

fn protocol_boundary_error(message: &'static str) -> BoundaryError {
    BoundaryError::internal(message).expect("static protocol boundary message is valid")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    const ID: &str = "123e4567-e89b-42d3-a456-426614174000";

    fn id() -> RequestId {
        RequestId::parse(ID).unwrap()
    }

    fn stopped(generation: u64) -> ManagedVmStatus {
        ManagedVmStatus::new(
            Generation::new(generation).unwrap(),
            VmStatus::new(
                VmLifecycle::Stopped,
                VmBackend::QemuTcg,
                BootStage::Idle,
                None,
                None,
            )
            .unwrap(),
        )
    }

    #[test]
    fn request_encoding_is_canonical_and_mutations_require_generation() {
        let request = Request::start(id(), Generation::new(7).unwrap());
        assert_eq!(
            String::from_utf8(encode_request_payload(&request)).unwrap(),
            format!(
                "{{\"version\":1,\"request_id\":\"{ID}\",\"operation\":\"vm.default.start\",\"parameters\":{{\"if_generation\":7}}}}"
            )
        );
        assert_eq!(
            decode_request_payload(&encode_request_payload(&request)).unwrap(),
            request
        );
        let missing = format!(
            "{{\"version\":1,\"request_id\":\"{ID}\",\"operation\":\"vm.default.stop\",\"parameters\":{{}}}}"
        );
        assert_eq!(
            decode_request_payload(missing.as_bytes()),
            Err(CodecError::MissingField)
        );
        let status_with_generation = format!(
            "{{\"version\":1,\"request_id\":\"{ID}\",\"operation\":\"vm.default.status\",\"parameters\":{{\"if_generation\":0}}}}"
        );
        assert_eq!(
            decode_request_payload(status_with_generation.as_bytes()),
            Err(CodecError::UnknownField)
        );
        assert_eq!(
            Generation::new(MAX_GENERATION + 1),
            Err(CodecError::InvalidGeneration)
        );
    }

    #[test]
    fn ids_are_only_canonical_lowercase_uuid_v4() {
        assert!(RequestId::parse(ID).is_ok());
        for invalid in [
            "123E4567-e89b-42d3-a456-426614174000",
            "123e4567-e89b-32d3-a456-426614174000",
            "123e4567-e89b-42d3-c456-426614174000",
            "123e4567e89b42d3a456426614174000",
        ] {
            assert_eq!(RequestId::parse(invalid), Err(CodecError::InvalidRequestId));
        }
    }

    #[test]
    fn strict_decoder_rejects_utf8_duplicates_unknown_missing_floats_versions_and_ops() {
        assert_eq!(
            decode_request_payload(&[0xff]),
            Err(CodecError::MalformedUtf8)
        );
        let cases = [
            (
                format!("{{\"version\":1,\"version\":1,\"request_id\":\"{ID}\",\"operation\":\"protocol.describe\"}}"),
                CodecError::DuplicateField,
            ),
            (
                format!("{{\"version\":1,\"request_id\":\"{ID}\",\"operation\":\"protocol.describe\",\"parameters\":{{}},\"extra\":null}}"),
                CodecError::UnknownField,
            ),
            (
                "{\"version\":1,\"operation\":\"protocol.describe\"}".to_owned(),
                CodecError::MissingField,
            ),
            (
                format!("{{\"version\":1.0,\"request_id\":\"{ID}\",\"operation\":\"protocol.describe\"}}"),
                CodecError::MalformedJson,
            ),
            (
                format!("{{\"version\":2,\"request_id\":\"{ID}\",\"operation\":\"protocol.describe\"}}"),
                CodecError::UnsupportedVersion,
            ),
            (
                format!("{{\"version\":1,\"request_id\":\"{ID}\",\"operation\":\"host.exec\"}}"),
                CodecError::UnknownOperation,
            ),
        ];
        for (json, expected) in cases {
            assert_eq!(
                decode_request_payload(json.as_bytes()),
                Err(expected),
                "{json}"
            );
        }
    }

    #[test]
    fn frame_caps_are_checked_before_payload_allocation_and_trailing_bytes_fail() {
        struct HeaderOnly {
            header: Cursor<Vec<u8>>,
            payload_read: bool,
        }
        impl Read for HeaderOnly {
            fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
                let read = self.header.read(buffer)?;
                if read == 0 {
                    self.payload_read = true;
                    panic!("oversized payload was read");
                }
                Ok(read)
            }
        }
        let mut reader = HeaderOnly {
            header: Cursor::new(((MAX_REQUEST_BYTES + 1) as u32).to_be_bytes().to_vec()),
            payload_read: false,
        };
        assert_eq!(
            read_request_frame(&mut reader),
            Err(CodecError::FrameTooLarge)
        );
        assert!(!reader.payload_read);

        let request = Request::status(id());
        let mut frame = encode_request_frame(&request);
        frame.push(b'x');
        assert_eq!(decode_request_frame(&frame), Err(CodecError::TrailingBytes));
        assert_eq!(
            decode_request_payload(&vec![b'x'; MAX_REQUEST_BYTES + 1]),
            Err(CodecError::FrameTooLarge)
        );
        assert_eq!(
            decode_response_payload(&vec![b'x'; MAX_RESPONSE_BYTES + 1], &request),
            Err(CodecError::FrameTooLarge)
        );
    }

    #[test]
    fn response_round_trips_all_results_and_stable_error_retry_pairs() {
        let describe_request = Request::describe(id());
        let describe = Response::success(id(), Success::ProtocolDescription);
        let encoded = encode_response_payload(&describe).unwrap();
        assert_eq!(
            decode_response_payload(&encoded, &describe_request).unwrap(),
            describe
        );

        for code in [
            ErrorCode::InvalidRequest,
            ErrorCode::UnsupportedVersion,
            ErrorCode::UnknownOperation,
            ErrorCode::Unauthenticated,
            ErrorCode::Forbidden,
            ErrorCode::GenerationMismatch,
            ErrorCode::Conflict,
            ErrorCode::Busy,
            ErrorCode::Timeout,
            ErrorCode::AuditUnavailable,
            ErrorCode::CapacityExceeded,
            ErrorCode::ProviderUnavailable,
            ErrorCode::Interrupted,
            ErrorCode::Indeterminate,
            ErrorCode::InternalError,
        ] {
            let response = Response::failure(id(), code);
            let encoded = encode_response_payload(&response).unwrap();
            assert_eq!(
                decode_response_payload(&encoded, &describe_request).unwrap(),
                response
            );
        }
        let wrong_retry = format!(
            "{{\"version\":1,\"request_id\":\"{ID}\",\"error\":{{\"code\":\"generation_mismatch\",\"retryable\":true}}}}"
        );
        assert_eq!(
            decode_response_payload(wrong_retry.as_bytes(), &describe_request),
            Err(CodecError::InvalidRetrySemantics)
        );
    }

    #[test]
    fn status_response_enforces_invariants_request_match_and_generation_advance() {
        let request = Request::start(id(), Generation::new(9).unwrap());
        let response = Response::success(id(), Success::VmStatus(stopped(10)));
        let encoded = encode_response_payload(&response).unwrap();
        assert_eq!(
            decode_response_payload(&encoded, &request).unwrap(),
            response
        );

        let wrong_generation = Response::success(id(), Success::VmStatus(stopped(9)));
        assert_eq!(
            decode_response_payload(
                &encode_response_payload(&wrong_generation).unwrap(),
                &request
            ),
            Err(CodecError::InvalidGeneration)
        );

        let stop_request = Request::stop(id(), Generation::new(10).unwrap());
        let stopped_same_generation = Response::success(id(), Success::VmStatus(stopped(10)));
        assert!(decode_response_payload(
            &encode_response_payload(&stopped_same_generation).unwrap(),
            &stop_request
        )
        .is_ok());
        let stopped_wrong_generation = Response::success(id(), Success::VmStatus(stopped(11)));
        assert_eq!(
            decode_response_payload(
                &encode_response_payload(&stopped_wrong_generation).unwrap(),
                &stop_request
            ),
            Err(CodecError::InvalidGeneration)
        );

        let wrong_id = String::from_utf8(encoded.clone())
            .unwrap()
            .replace(ID, "223e4567-e89b-42d3-a456-426614174000");
        assert_eq!(
            decode_response_payload(wrong_id.as_bytes(), &request),
            Err(CodecError::ProtocolMismatch)
        );
    }

    #[test]
    fn response_schema_rejects_unknown_duplicate_missing_float_and_trailing_content() {
        let request = Request::status(id());
        let valid = String::from_utf8(
            encode_response_payload(&Response::success(id(), Success::VmStatus(stopped(4))))
                .unwrap(),
        )
        .unwrap();
        let unknown = valid.replacen("\"generation\":4", "\"generation\":4,\"extra\":0", 1);
        assert_eq!(
            decode_response_payload(unknown.as_bytes(), &request),
            Err(CodecError::UnknownField)
        );
        let duplicate = valid.replacen("\"generation\":4", "\"generation\":4,\"generation\":4", 1);
        assert_eq!(
            decode_response_payload(duplicate.as_bytes(), &request),
            Err(CodecError::DuplicateField)
        );
        let missing = valid.replace("\"generation\":4,", "");
        assert_eq!(
            decode_response_payload(missing.as_bytes(), &request),
            Err(CodecError::MissingField)
        );
        let float = valid.replace("\"generation\":4", "\"generation\":4.0");
        assert_eq!(
            decode_response_payload(float.as_bytes(), &request),
            Err(CodecError::MalformedJson)
        );
        let trailing = format!("{valid}\n");
        assert_eq!(
            decode_response_payload(trailing.as_bytes(), &request),
            Err(CodecError::TrailingBytes)
        );
    }

    #[test]
    fn no_protocol_type_can_carry_guest_credentials_or_arbitrary_commands() {
        let request = Request::status(id());
        let encoded = String::from_utf8(encode_request_payload(&request)).unwrap();
        assert_eq!(COMMAND, "podroid-management-v1");
        assert!(!encoded.contains("ssh"));
        assert!(!encoded.contains("credential"));
        assert!(!encoded.contains("command"));
        assert_eq!(Operation::ALL.len(), 4);
    }

    struct OneId(bool);

    impl RequestIdSource for OneId {
        fn next_request_id(&mut self) -> Result<RequestId, BoundaryError> {
            if self.0 {
                Err(protocol_boundary_error("no request ID"))
            } else {
                self.0 = true;
                Ok(id())
            }
        }
    }

    struct StatusExchange;

    impl HostManagementExchange for StatusExchange {
        fn exchange_v1(
            &mut self,
            framed_request: &[u8],
            framed_response: &mut [u8],
            deadline: Duration,
        ) -> Result<usize, BoundaryError> {
            assert_eq!(deadline, REQUEST_DEADLINE);
            assert_eq!(
                framed_response.len(),
                FRAME_HEADER_BYTES + MAX_RESPONSE_BYTES
            );
            let request = decode_request_frame(framed_request).unwrap();
            let response = encode_response_frame(&Response::success(
                request.request_id.clone(),
                Success::VmStatus(stopped(3)),
            ))
            .map_err(|_| protocol_boundary_error("encode failed"))?;
            framed_response[..response.len()].copy_from_slice(&response);
            Ok(response.len())
        }
    }

    #[test]
    fn vm_boundary_mapping_uses_only_the_narrow_exchange_seam() {
        let host = HostId::parse("phone.example").unwrap();
        let mut service = HostManagementVmService::new(StatusExchange, OneId(false), host);
        let snapshot = service.refresh().unwrap();
        assert_eq!(snapshot.vm().lifecycle(), VmLifecycle::Stopped);
        assert_eq!(
            snapshot.host().connection(),
            HostConnection::AuthenticatedManagement
        );
    }
}
