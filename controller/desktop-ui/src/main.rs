#![deny(unsafe_code)]

use controller_core::{
    ActionPolicy, BootStage, ControllerSnapshot, HostConnection, PreviewVmService, VmBackend,
    VmLifecycle, VmServiceBoundary,
};
use slint::{ComponentHandle, SharedString};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, SyncSender, TrySendError};
use std::sync::Arc;

slint::include_modules!();

#[derive(Clone, Copy, Debug)]
enum ServiceCommand {
    Refresh,
    Start,
    Stop,
}

impl ServiceCommand {
    fn progress_message(self) -> &'static str {
        match self {
            Self::Refresh => "Refreshing preview state…",
            Self::Start => "Starting the in-memory preview VM…",
            Self::Stop => "Stopping the in-memory preview VM…",
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct PresentationState {
    host_connection: String,
    host_identity: String,
    vm_identity: String,
    vm_lifecycle: String,
    vm_backend: String,
    vm_boot_stage: String,
    vm_uptime: String,
    vm_error: String,
    policy: ActionPolicy,
}

impl PresentationState {
    fn from_snapshot(snapshot: &ControllerSnapshot) -> Self {
        let host_connection = match snapshot.host().connection() {
            HostConnection::Disconnected => "Disconnected",
            HostConnection::PreviewOnly => "Preview only (not live)",
            HostConnection::AuthenticatedManagement => "Authenticated management",
        };
        let vm = snapshot.vm();
        Self {
            host_connection: host_connection.to_owned(),
            host_identity: snapshot
                .host()
                .identity()
                .map_or_else(|| "—".to_owned(), |identity| identity.as_str().to_owned()),
            vm_identity: vm.id().as_str().to_owned(),
            vm_lifecycle: match vm.lifecycle() {
                VmLifecycle::Stopped => "Stopped",
                VmLifecycle::Starting => "Starting",
                VmLifecycle::Running => "Running",
                VmLifecycle::Stopping => "Stopping",
                VmLifecycle::Error => "Error",
            }
            .to_owned(),
            vm_backend: match vm.backend() {
                VmBackend::QemuTcg => "QEMU / TCG",
                VmBackend::AvfPvm => "AVF / pKVM",
                VmBackend::Unknown => "Unknown",
            }
            .to_owned(),
            vm_boot_stage: match vm.boot_stage() {
                BootStage::Idle => "Idle",
                BootStage::Installing => "Installing",
                BootStage::StartingSsh => "Starting SSH",
                BootStage::AlmostReady => "Almost ready",
                BootStage::Ready => "Ready",
                BootStage::Failed => "Failed",
            }
            .to_owned(),
            vm_uptime: format_uptime(vm.uptime_seconds()),
            vm_error: vm.error().unwrap_or("None").to_owned(),
            policy: ActionPolicy::for_snapshot(snapshot, None),
        }
    }
}

fn format_uptime(uptime_seconds: Option<u64>) -> String {
    let Some(total_seconds) = uptime_seconds else {
        return "—".to_owned();
    };
    let hours = total_seconds / 3_600;
    let minutes = (total_seconds % 3_600) / 60;
    let seconds = total_seconds % 60;
    format!("{hours:02}:{minutes:02}:{seconds:02}")
}

fn apply_presentation(ui: &AppWindow, state: &PresentationState, feedback: &str) {
    ui.set_host_connection(SharedString::from(&state.host_connection));
    ui.set_host_identity(SharedString::from(&state.host_identity));
    ui.set_vm_identity(SharedString::from(&state.vm_identity));
    ui.set_vm_lifecycle(SharedString::from(&state.vm_lifecycle));
    ui.set_vm_backend(SharedString::from(&state.vm_backend));
    ui.set_vm_boot_stage(SharedString::from(&state.vm_boot_stage));
    ui.set_vm_uptime(SharedString::from(&state.vm_uptime));
    ui.set_vm_error(SharedString::from(&state.vm_error));
    ui.set_busy(false);
    ui.set_feedback(SharedString::from(feedback));
    ui.set_can_refresh(state.policy.can_refresh);
    ui.set_can_start(state.policy.can_start);
    ui.set_can_stop(state.policy.can_stop);
}

fn set_pending(ui: &AppWindow, command: ServiceCommand) {
    ui.set_busy(true);
    ui.set_feedback(SharedString::from(command.progress_message()));
    ui.set_can_refresh(false);
    ui.set_can_start(false);
    ui.set_can_stop(false);
}

#[derive(Clone)]
struct ServiceClient {
    sender: SyncSender<ServiceCommand>,
    in_flight: Arc<AtomicBool>,
}

fn submit(ui: &AppWindow, client: &ServiceClient, command: ServiceCommand) {
    if client
        .in_flight
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .is_err()
    {
        // UI policy normally prevents this. Keep the active request's disabled state if a stale or
        // synthetic callback still arrives.
        return;
    }

    set_pending(ui, command);
    match client.sender.try_send(command) {
        Ok(()) => {}
        Err(TrySendError::Full(_)) => {
            client.in_flight.store(false, Ordering::Release);
            ui.set_busy(false);
            ui.set_feedback(SharedString::from(
                "Preview request queue is full; refresh to recover.",
            ));
            ui.set_can_refresh(true);
        }
        Err(TrySendError::Disconnected(_)) => {
            client.in_flight.store(false, Ordering::Release);
            ui.set_busy(false);
            ui.set_feedback(SharedString::from(
                "Preview service is unavailable; restart the controller.",
            ));
        }
    }
}

fn spawn_service_worker(
    ui_weak: slint::Weak<AppWindow>,
    mut boundary: Box<dyn VmServiceBoundary>,
) -> ServiceClient {
    // One worker and one queued request bound both concurrency and retained caller-controlled work.
    let (sender, receiver) = mpsc::sync_channel::<ServiceCommand>(1);
    let in_flight = Arc::new(AtomicBool::new(false));
    let worker_in_flight = Arc::clone(&in_flight);
    std::thread::Builder::new()
        .name("controller-preview-service".to_owned())
        .spawn(move || {
            let mut latest_state: Option<PresentationState> = None;
            while let Ok(command) = receiver.recv() {
                let result = match command {
                    ServiceCommand::Refresh => boundary.refresh(),
                    ServiceCommand::Start => boundary.start(),
                    ServiceCommand::Stop => boundary.stop(),
                };
                let update = match result {
                    Ok(snapshot) => {
                        let state = PresentationState::from_snapshot(&snapshot);
                        latest_state = Some(state.clone());
                        WorkerUpdate::Success {
                            feedback: match command {
                                ServiceCommand::Refresh => "Preview state refreshed.",
                                ServiceCommand::Start => "Preview VM started.",
                                ServiceCommand::Stop => "Preview VM stopped.",
                            },
                            state,
                        }
                    }
                    Err(error) => WorkerUpdate::Failure {
                        message: format!("Preview action failed: {error}"),
                        previous: latest_state.clone(),
                    },
                };
                let ui_weak = ui_weak.clone();
                let completion_in_flight = Arc::clone(&worker_in_flight);
                if slint::invoke_from_event_loop(move || {
                    let Some(ui) = ui_weak.upgrade() else {
                        completion_in_flight.store(false, Ordering::Release);
                        return;
                    };
                    match update {
                        WorkerUpdate::Success { state, feedback } => {
                            apply_presentation(&ui, &state, feedback);
                        }
                        WorkerUpdate::Failure { message, previous } => {
                            ui.set_busy(false);
                            ui.set_feedback(SharedString::from(message));
                            if let Some(state) = previous {
                                ui.set_can_refresh(state.policy.can_refresh);
                                ui.set_can_start(state.policy.can_start);
                                ui.set_can_stop(state.policy.can_stop);
                            } else {
                                ui.set_can_refresh(true);
                            }
                        }
                    }
                    completion_in_flight.store(false, Ordering::Release);
                })
                .is_err()
                {
                    worker_in_flight.store(false, Ordering::Release);
                    // The event loop has closed. Ending the worker never calls stop on the service.
                    break;
                }
            }
        })
        .expect("failed to start bounded preview service worker");
    ServiceClient { sender, in_flight }
}

enum WorkerUpdate {
    Success {
        state: PresentationState,
        feedback: &'static str,
    },
    Failure {
        message: String,
        previous: Option<PresentationState>,
    },
}

fn run() -> Result<(), slint::PlatformError> {
    let ui = AppWindow::new()?;
    let client = spawn_service_worker(ui.as_weak(), Box::new(PreviewVmService::new()));

    let refresh_client = client.clone();
    let refresh_weak = ui.as_weak();
    ui.on_refresh_requested(move || {
        if let Some(ui) = refresh_weak.upgrade() {
            submit(&ui, &refresh_client, ServiceCommand::Refresh);
        }
    });

    let start_client = client.clone();
    let start_weak = ui.as_weak();
    ui.on_start_requested(move || {
        if let Some(ui) = start_weak.upgrade() {
            submit(&ui, &start_client, ServiceCommand::Start);
        }
    });

    let stop_client = client.clone();
    let stop_weak = ui.as_weak();
    ui.on_stop_requested(move || {
        if let Some(ui) = stop_weak.upgrade() {
            submit(&ui, &stop_client, ServiceCommand::Stop);
        }
    });

    submit(&ui, &client, ServiceCommand::Refresh);
    ui.run()
}

fn main() {
    if let Err(error) = run() {
        eprintln!("desktop controller failed: {error}");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use controller_core::{HostId, HostStatus, VmStatus};

    struct DropObservingBoundary {
        dropped: mpsc::Sender<()>,
        stop_calls: Arc<std::sync::atomic::AtomicUsize>,
    }

    impl Drop for DropObservingBoundary {
        fn drop(&mut self) {
            self.dropped
                .send(())
                .expect("test observer must remain available");
        }
    }

    impl VmServiceBoundary for DropObservingBoundary {
        fn refresh(&mut self) -> Result<ControllerSnapshot, controller_core::BoundaryError> {
            Ok(running_snapshot())
        }

        fn start(&mut self) -> Result<ControllerSnapshot, controller_core::BoundaryError> {
            Ok(running_snapshot())
        }

        fn stop(&mut self) -> Result<ControllerSnapshot, controller_core::BoundaryError> {
            self.stop_calls.fetch_add(1, Ordering::Relaxed);
            Ok(running_snapshot())
        }
    }

    fn running_snapshot() -> ControllerSnapshot {
        ControllerSnapshot::new(
            HostStatus::new(
                HostConnection::PreviewOnly,
                Some(HostId::parse("preview-test").unwrap()),
            )
            .unwrap(),
            VmStatus::new(
                VmLifecycle::Running,
                VmBackend::QemuTcg,
                BootStage::Ready,
                Some(3_661),
                None,
            )
            .unwrap(),
        )
    }

    #[test]
    fn presentation_maps_complete_host_and_vm_state() {
        let state = PresentationState::from_snapshot(&running_snapshot());
        assert_eq!(state.host_connection, "Preview only (not live)");
        assert_eq!(state.host_identity, "preview-test");
        assert_eq!(state.vm_identity, "default");
        assert_eq!(state.vm_lifecycle, "Running");
        assert_eq!(state.vm_backend, "QEMU / TCG");
        assert_eq!(state.vm_boot_stage, "Ready");
        assert_eq!(state.vm_uptime, "01:01:01");
        assert_eq!(state.vm_error, "None");
        assert!(state.policy.can_stop);
        assert!(!state.policy.can_start);
    }

    #[test]
    fn headless_slint_component_accepts_presentation_state() {
        i_slint_backend_testing::init_no_event_loop();
        let ui = AppWindow::new().expect("headless Slint component should instantiate");
        let state = PresentationState::from_snapshot(&running_snapshot());
        apply_presentation(&ui, &state, "smoke test");

        assert_eq!(ui.get_host_identity().as_str(), "preview-test");
        assert_eq!(ui.get_vm_lifecycle().as_str(), "Running");
        assert!(ui.get_can_stop());
        assert!(!ui.get_busy());

        let (sender, receiver) = mpsc::sync_channel(1);
        let client = ServiceClient {
            sender,
            in_flight: Arc::new(AtomicBool::new(false)),
        };
        submit(&ui, &client, ServiceCommand::Refresh);
        submit(&ui, &client, ServiceCommand::Start);
        assert!(matches!(receiver.try_recv(), Ok(ServiceCommand::Refresh)));
        assert!(receiver.try_recv().is_err());
        assert!(ui.get_busy());
        assert!(!ui.get_can_start());

        let stop_calls = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let (dropped_tx, dropped_rx) = mpsc::channel();
        let worker_client = spawn_service_worker(
            ui.as_weak(),
            Box::new(DropObservingBoundary {
                dropped: dropped_tx,
                stop_calls: Arc::clone(&stop_calls),
            }),
        );
        drop(worker_client);
        dropped_rx
            .recv_timeout(std::time::Duration::from_secs(1))
            .expect("dropping the UI-side client should release the worker");
        assert_eq!(stop_calls.load(Ordering::Relaxed), 0);
    }
}
