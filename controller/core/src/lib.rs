#![forbid(unsafe_code)]

use std::fmt;
use std::time::Instant;

pub const MAX_HOST_ID_BYTES: usize = 64;
pub const MAX_ERROR_BYTES: usize = 256;
pub const MAX_UPTIME_SECONDS: u64 = 10 * 365 * 24 * 60 * 60;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct HostId(String);

impl HostId {
    pub fn parse(raw: &str) -> Result<Self, ValidationError> {
        if raw.is_empty() || raw.len() > MAX_HOST_ID_BYTES {
            return Err(ValidationError::InvalidHostId);
        }
        if !raw
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
        {
            return Err(ValidationError::InvalidHostId);
        }
        Ok(Self(raw.to_owned()))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum VmId {
    Default,
}

impl VmId {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Default => "default",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum HostConnection {
    Disconnected,
    PreviewOnly,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct HostStatus {
    connection: HostConnection,
    identity: Option<HostId>,
}

impl HostStatus {
    pub fn new(
        connection: HostConnection,
        identity: Option<HostId>,
    ) -> Result<Self, ValidationError> {
        match (connection, identity.is_some()) {
            (HostConnection::PreviewOnly, false) => {
                return Err(ValidationError::PreviewIdentityRequired);
            }
            (HostConnection::Disconnected, true) => {
                return Err(ValidationError::DisconnectedIdentityNotAllowed);
            }
            _ => {}
        }
        Ok(Self {
            connection,
            identity,
        })
    }

    pub fn connection(&self) -> HostConnection {
        self.connection
    }

    pub fn identity(&self) -> Option<&HostId> {
        self.identity.as_ref()
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum VmLifecycle {
    Stopped,
    Starting,
    Running,
    Stopping,
    Error,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum VmBackend {
    QemuTcg,
    AvfPvm,
    Unknown,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BootStage {
    Idle,
    Installing,
    StartingSsh,
    AlmostReady,
    Ready,
    Failed,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct VmStatus {
    id: VmId,
    lifecycle: VmLifecycle,
    backend: VmBackend,
    boot_stage: BootStage,
    uptime_seconds: Option<u64>,
    error: Option<String>,
}

impl VmStatus {
    pub fn new(
        lifecycle: VmLifecycle,
        backend: VmBackend,
        boot_stage: BootStage,
        uptime_seconds: Option<u64>,
        error: Option<&str>,
    ) -> Result<Self, ValidationError> {
        if uptime_seconds.is_some_and(|seconds| seconds > MAX_UPTIME_SECONDS) {
            return Err(ValidationError::UptimeOutOfRange);
        }
        if uptime_seconds.is_some() && lifecycle != VmLifecycle::Running {
            return Err(ValidationError::UptimeRequiresRunningVm);
        }
        if lifecycle == VmLifecycle::Error {
            let message = error.ok_or(ValidationError::ErrorMessageRequired)?;
            validate_error(message)?;
        } else if error.is_some() {
            return Err(ValidationError::UnexpectedErrorMessage);
        }
        let valid_stage = match lifecycle {
            VmLifecycle::Stopped => boot_stage == BootStage::Idle,
            VmLifecycle::Starting => matches!(
                boot_stage,
                BootStage::Installing | BootStage::StartingSsh | BootStage::AlmostReady
            ),
            VmLifecycle::Running => boot_stage == BootStage::Ready,
            VmLifecycle::Stopping => boot_stage == BootStage::Ready,
            VmLifecycle::Error => boot_stage == BootStage::Failed,
        };
        if !valid_stage {
            return Err(ValidationError::InvalidBootStage);
        }

        Ok(Self {
            id: VmId::Default,
            lifecycle,
            backend,
            boot_stage,
            uptime_seconds,
            error: error.map(str::to_owned),
        })
    }

    pub fn id(&self) -> VmId {
        self.id
    }

    pub fn lifecycle(&self) -> VmLifecycle {
        self.lifecycle
    }

    pub fn backend(&self) -> VmBackend {
        self.backend
    }

    pub fn boot_stage(&self) -> BootStage {
        self.boot_stage
    }

    pub fn uptime_seconds(&self) -> Option<u64> {
        self.uptime_seconds
    }

    pub fn error(&self) -> Option<&str> {
        self.error.as_deref()
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ControllerSnapshot {
    host: HostStatus,
    vm: VmStatus,
}

impl ControllerSnapshot {
    pub fn new(host: HostStatus, vm: VmStatus) -> Self {
        Self { host, vm }
    }

    pub fn host(&self) -> &HostStatus {
        &self.host
    }

    pub fn vm(&self) -> &VmStatus {
        &self.vm
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PendingAction {
    Refresh,
    Start,
    Stop,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ActionPolicy {
    pub can_refresh: bool,
    pub can_start: bool,
    pub can_stop: bool,
}

impl ActionPolicy {
    pub fn for_snapshot(snapshot: &ControllerSnapshot, pending: Option<PendingAction>) -> Self {
        if pending.is_some() {
            return Self {
                can_refresh: false,
                can_start: false,
                can_stop: false,
            };
        }

        // PreviewOnly grants capability solely to the local preview implementation. A future live
        // status must receive its own authenticated connection variant and explicit policy branch.
        let connected = snapshot.host.connection != HostConnection::Disconnected;
        Self {
            can_refresh: true,
            can_start: connected && snapshot.vm.lifecycle == VmLifecycle::Stopped,
            can_stop: connected
                && matches!(
                    snapshot.vm.lifecycle,
                    VmLifecycle::Starting | VmLifecycle::Running
                ),
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct BoundaryMessage(String);

impl BoundaryMessage {
    pub fn parse(message: &str) -> Result<Self, ValidationError> {
        validate_error(message)?;
        Ok(Self(message.to_owned()))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum BoundaryError {
    ActionNotAllowed { action: PendingAction },
    Internal(BoundaryMessage),
}

impl BoundaryError {
    pub fn internal(message: &str) -> Result<Self, ValidationError> {
        Ok(Self::Internal(BoundaryMessage::parse(message)?))
    }
}

impl fmt::Display for BoundaryError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::ActionNotAllowed { action } => {
                write!(formatter, "{action:?} is not allowed in the current state")
            }
            Self::Internal(message) => formatter.write_str(message.as_str()),
        }
    }
}

impl std::error::Error for BoundaryError {}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ValidationError {
    InvalidHostId,
    PreviewIdentityRequired,
    DisconnectedIdentityNotAllowed,
    UptimeOutOfRange,
    UptimeRequiresRunningVm,
    ErrorMessageRequired,
    UnexpectedErrorMessage,
    InvalidErrorMessage,
    InvalidBootStage,
}

impl fmt::Display for ValidationError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "invalid controller boundary value: {self:?}")
    }
}

impl std::error::Error for ValidationError {}

fn validate_error(message: &str) -> Result<(), ValidationError> {
    if message.is_empty()
        || message.len() > MAX_ERROR_BYTES
        || message.chars().any(char::is_control)
    {
        return Err(ValidationError::InvalidErrorMessage);
    }
    Ok(())
}

/// The controller's complete lifecycle authority.
///
/// Implementations must return a fresh authoritative snapshot after each operation. This ticket
/// intentionally supplies only [`PreviewVmService`]; a live authenticated implementation belongs
/// to ticket #16.
pub trait VmServiceBoundary: Send + 'static {
    fn refresh(&mut self) -> Result<ControllerSnapshot, BoundaryError>;
    fn start(&mut self) -> Result<ControllerSnapshot, BoundaryError>;
    fn stop(&mut self) -> Result<ControllerSnapshot, BoundaryError>;
}

/// A bounded, process-local demonstration boundary. It never connects to or controls a phone.
pub struct PreviewVmService {
    lifecycle: VmLifecycle,
    started_at: Option<Instant>,
}

impl PreviewVmService {
    pub fn new() -> Self {
        Self {
            lifecycle: VmLifecycle::Stopped,
            started_at: None,
        }
    }

    fn snapshot(&self) -> ControllerSnapshot {
        let host = HostStatus::new(
            HostConnection::PreviewOnly,
            Some(HostId::parse("preview-host").expect("static preview host ID is valid")),
        )
        .expect("static preview host state is valid");
        let (boot_stage, uptime_seconds) = match self.lifecycle {
            VmLifecycle::Stopped => (BootStage::Idle, None),
            VmLifecycle::Running => (
                BootStage::Ready,
                self.started_at
                    .map(|started_at| started_at.elapsed().as_secs().min(MAX_UPTIME_SECONDS)),
            ),
            _ => unreachable!("preview boundary stores only stable lifecycle states"),
        };
        let vm = VmStatus::new(
            self.lifecycle,
            VmBackend::QemuTcg,
            boot_stage,
            uptime_seconds,
            None,
        )
        .expect("preview boundary creates valid VM state");
        ControllerSnapshot::new(host, vm)
    }
}

impl Default for PreviewVmService {
    fn default() -> Self {
        Self::new()
    }
}

impl VmServiceBoundary for PreviewVmService {
    fn refresh(&mut self) -> Result<ControllerSnapshot, BoundaryError> {
        Ok(self.snapshot())
    }

    fn start(&mut self) -> Result<ControllerSnapshot, BoundaryError> {
        if self.lifecycle != VmLifecycle::Stopped {
            return Err(BoundaryError::ActionNotAllowed {
                action: PendingAction::Start,
            });
        }
        self.started_at = Some(Instant::now());
        self.lifecycle = VmLifecycle::Running;
        Ok(self.snapshot())
    }

    fn stop(&mut self) -> Result<ControllerSnapshot, BoundaryError> {
        if self.lifecycle != VmLifecycle::Running {
            return Err(BoundaryError::ActionNotAllowed {
                action: PendingAction::Stop,
            });
        }
        self.lifecycle = VmLifecycle::Stopped;
        self.started_at = None;
        Ok(self.snapshot())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn snapshot(lifecycle: VmLifecycle) -> ControllerSnapshot {
        let host = HostStatus::new(
            HostConnection::PreviewOnly,
            Some(HostId::parse("test-host").unwrap()),
        )
        .unwrap();
        let (stage, uptime, error) = match lifecycle {
            VmLifecycle::Stopped => (BootStage::Idle, None, None),
            VmLifecycle::Starting => (BootStage::Installing, None, None),
            VmLifecycle::Running => (BootStage::Ready, Some(4), None),
            VmLifecycle::Stopping => (BootStage::Ready, None, None),
            VmLifecycle::Error => (BootStage::Failed, None, Some("preview failure")),
        };
        ControllerSnapshot::new(
            host,
            VmStatus::new(lifecycle, VmBackend::QemuTcg, stage, uptime, error).unwrap(),
        )
    }

    #[test]
    fn host_id_is_bounded_and_rejects_control_characters() {
        assert!(HostId::parse("phone-01.example").is_ok());
        assert_eq!(HostId::parse(""), Err(ValidationError::InvalidHostId));
        assert_eq!(
            HostId::parse(&"x".repeat(MAX_HOST_ID_BYTES + 1)),
            Err(ValidationError::InvalidHostId)
        );
        assert_eq!(
            HostId::parse("phone\nspoof"),
            Err(ValidationError::InvalidHostId)
        );
        assert_eq!(
            HostId::parse("phone-é"),
            Err(ValidationError::InvalidHostId)
        );
        assert_eq!(
            HostStatus::new(
                HostConnection::Disconnected,
                Some(HostId::parse("stale-host").unwrap())
            ),
            Err(ValidationError::DisconnectedIdentityNotAllowed)
        );
    }

    #[test]
    fn text_dto_apis_borrow_and_reject_oversized_input_before_retaining_it() {
        let _: fn(&str) -> Result<HostId, ValidationError> = HostId::parse;
        let _: fn(&str) -> Result<BoundaryMessage, ValidationError> = BoundaryMessage::parse;
        let _: fn(&str) -> Result<BoundaryError, ValidationError> = BoundaryError::internal;
        let _: fn(
            VmLifecycle,
            VmBackend,
            BootStage,
            Option<u64>,
            Option<&str>,
        ) -> Result<VmStatus, ValidationError> = VmStatus::new;

        // The only large allocation belongs to this test caller. Each DTO receives a borrow and
        // rejects it, so no constructor can consume and retain the caller's unbounded allocation.
        let very_large = "x".repeat(4 * 1024 * 1024);
        assert_eq!(
            HostId::parse(&very_large),
            Err(ValidationError::InvalidHostId)
        );
        assert_eq!(
            BoundaryMessage::parse(&very_large),
            Err(ValidationError::InvalidErrorMessage)
        );
        assert_eq!(
            BoundaryError::internal(&very_large),
            Err(ValidationError::InvalidErrorMessage)
        );
        assert_eq!(
            VmStatus::new(
                VmLifecycle::Error,
                VmBackend::Unknown,
                BootStage::Failed,
                None,
                Some(&very_large),
            ),
            Err(ValidationError::InvalidErrorMessage)
        );

        let maximum_host_id = "h".repeat(MAX_HOST_ID_BYTES);
        assert_eq!(
            HostId::parse(&maximum_host_id).unwrap().as_str().len(),
            MAX_HOST_ID_BYTES
        );
        let maximum_message = "m".repeat(MAX_ERROR_BYTES);
        assert_eq!(
            BoundaryMessage::parse(&maximum_message)
                .unwrap()
                .as_str()
                .len(),
            MAX_ERROR_BYTES
        );
        assert_eq!(
            BoundaryMessage::parse("line one\nline two"),
            Err(ValidationError::InvalidErrorMessage)
        );
        let maximum_multibyte_message = "é".repeat(MAX_ERROR_BYTES / "é".len());
        assert_eq!(
            BoundaryMessage::parse(&maximum_multibyte_message)
                .unwrap()
                .as_str()
                .len(),
            MAX_ERROR_BYTES
        );
        let oversized_multibyte_message = format!("{maximum_multibyte_message}é");
        assert_eq!(
            BoundaryMessage::parse(&oversized_multibyte_message),
            Err(ValidationError::InvalidErrorMessage)
        );
        assert_eq!(
            VmStatus::new(
                VmLifecycle::Error,
                VmBackend::Unknown,
                BootStage::Failed,
                None,
                Some(&maximum_message),
            )
            .unwrap()
            .error()
            .unwrap()
            .len(),
            MAX_ERROR_BYTES
        );
    }

    #[test]
    fn vm_status_enforces_cross_field_invariants() {
        assert_eq!(
            VmStatus::new(
                VmLifecycle::Stopped,
                VmBackend::QemuTcg,
                BootStage::Ready,
                None,
                None
            ),
            Err(ValidationError::InvalidBootStage)
        );
        assert_eq!(
            VmStatus::new(
                VmLifecycle::Running,
                VmBackend::QemuTcg,
                BootStage::Ready,
                Some(MAX_UPTIME_SECONDS + 1),
                None
            ),
            Err(ValidationError::UptimeOutOfRange)
        );
        assert_eq!(
            VmStatus::new(
                VmLifecycle::Error,
                VmBackend::Unknown,
                BootStage::Failed,
                None,
                None
            ),
            Err(ValidationError::ErrorMessageRequired)
        );
        assert_eq!(
            VmStatus::new(
                VmLifecycle::Starting,
                VmBackend::AvfPvm,
                BootStage::Ready,
                None,
                None
            ),
            Err(ValidationError::InvalidBootStage)
        );
        assert_eq!(
            BoundaryError::internal(&"x".repeat(MAX_ERROR_BYTES + 1)),
            Err(ValidationError::InvalidErrorMessage)
        );
    }

    #[test]
    fn action_policy_disables_invalid_and_concurrent_actions() {
        let stopped = ActionPolicy::for_snapshot(&snapshot(VmLifecycle::Stopped), None);
        assert_eq!(
            stopped,
            ActionPolicy {
                can_refresh: true,
                can_start: true,
                can_stop: false
            }
        );
        let running = ActionPolicy::for_snapshot(&snapshot(VmLifecycle::Running), None);
        assert!(running.can_stop);
        assert!(!running.can_start);

        for lifecycle in [
            VmLifecycle::Starting,
            VmLifecycle::Stopping,
            VmLifecycle::Error,
        ] {
            let policy = ActionPolicy::for_snapshot(&snapshot(lifecycle), None);
            assert!(!policy.can_start);
            assert_eq!(policy.can_stop, lifecycle == VmLifecycle::Starting);
        }

        let pending = ActionPolicy::for_snapshot(
            &snapshot(VmLifecycle::Stopped),
            Some(PendingAction::Refresh),
        );
        assert_eq!(
            pending,
            ActionPolicy {
                can_refresh: false,
                can_start: false,
                can_stop: false
            }
        );
    }

    #[test]
    fn preview_boundary_is_stateful_but_rejects_duplicate_mutations() {
        let mut boundary = PreviewVmService::new();
        assert_eq!(
            boundary.refresh().unwrap().vm().lifecycle(),
            VmLifecycle::Stopped
        );
        assert_eq!(
            boundary.start().unwrap().vm().lifecycle(),
            VmLifecycle::Running
        );
        assert_eq!(
            boundary.start(),
            Err(BoundaryError::ActionNotAllowed {
                action: PendingAction::Start
            })
        );
        assert_eq!(
            boundary.stop().unwrap().vm().lifecycle(),
            VmLifecycle::Stopped
        );
        assert_eq!(
            boundary.stop(),
            Err(BoundaryError::ActionNotAllowed {
                action: PendingAction::Stop
            })
        );
    }
}
