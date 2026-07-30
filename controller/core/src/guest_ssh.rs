#![forbid(unsafe_code)]

use std::fmt;
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom, Write};
use std::net::{IpAddr, Ipv4Addr};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

const MAX_HOST_BYTES: usize = 253;
const MAX_PATH_BYTES: usize = 4096;
const MAX_PRIVATE_KEY_BYTES: u64 = 64 * 1024;
const MAX_KNOWN_HOSTS_BYTES: u64 = 16 * 1024;
const MAX_AUTH_KEY_BYTES: u64 = 513;
const MAX_COMMAND_BYTES: usize = 4096;
const MAX_OUTPUT_BYTES: usize = 1024 * 1024;
const MAX_ERROR_BYTES: usize = 64 * 1024;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(12);
const COMMAND_TIMEOUT: Duration = Duration::from_secs(60);
const ENROLLMENT_TIMEOUT: Duration = Duration::from_secs(120);
const MAX_COMMAND_TIMEOUT: Duration = Duration::from_secs(300);

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct GuestSshTarget {
    host: String,
    port: u16,
}

impl GuestSshTarget {
    pub fn parse(host: &str, port: u16) -> Result<Self, GuestSshError> {
        if host.is_empty() || host.len() > MAX_HOST_BYTES || port == 0 || host.starts_with('-') {
            return Err(GuestSshError::InvalidTarget);
        }
        let valid = host.parse::<IpAddr>().is_ok() || valid_dns_name(host);
        if !valid {
            return Err(GuestSshError::InvalidTarget);
        }
        Ok(Self {
            host: host.to_ascii_lowercase(),
            port,
        })
    }

    pub fn host(&self) -> &str {
        &self.host
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    fn known_hosts_token(&self) -> String {
        if self.port == 22 {
            self.host.clone()
        } else {
            format!("[{}]:{}", self.host, self.port)
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Sha256HostFingerprint(String);

impl Sha256HostFingerprint {
    pub fn parse(value: &str) -> Result<Self, GuestSshError> {
        let body = value
            .strip_prefix("SHA256:")
            .ok_or(GuestSshError::InvalidHostFingerprint)?;
        if body.len() != 43
            || !body
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'+' | b'/'))
        {
            return Err(GuestSshError::InvalidHostFingerprint);
        }
        Ok(Self(value.to_owned()))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug)]
pub struct GuestSshConfig {
    target: GuestSshTarget,
    identity_file: PathBuf,
    known_hosts_file: PathBuf,
    host_fingerprint: Sha256HostFingerprint,
}

impl GuestSshConfig {
    pub fn new(
        target: GuestSshTarget,
        identity_file: &Path,
        known_hosts_file: &Path,
        host_fingerprint: Sha256HostFingerprint,
    ) -> Result<Self, GuestSshError> {
        validate_path(identity_file)?;
        validate_path(known_hosts_file)?;
        Ok(Self {
            target,
            identity_file: identity_file.to_owned(),
            known_hosts_file: known_hosts_file.to_owned(),
            host_fingerprint,
        })
    }

    pub fn target(&self) -> &GuestSshTarget {
        &self.target
    }
}

struct CredentialSnapshot {
    directory: PathBuf,
    config: GuestSshConfig,
    cleaned: bool,
}

impl CredentialSnapshot {
    fn create(source: &GuestSshConfig) -> Result<Self, GuestSshError> {
        let snapshot_parent = source
            .identity_file
            .parent()
            .ok_or(GuestSshError::InvalidFile)?;
        let directory = create_private_snapshot_dir(snapshot_parent)?;
        let mut snapshot = Self {
            directory: directory.clone(),
            cleaned: false,
            config: GuestSshConfig {
                target: source.target.clone(),
                identity_file: directory.join("identity"),
                known_hosts_file: directory.join("known_hosts"),
                host_fingerprint: source.host_fingerprint.clone(),
            },
        };
        let result = (|| {
            copy_stable_file(
                &source.identity_file,
                &snapshot.config.identity_file,
                MAX_PRIVATE_KEY_BYTES,
                true,
            )?;
            copy_stable_file(
                &source.known_hosts_file,
                &snapshot.config.known_hosts_file,
                MAX_KNOWN_HOSTS_BYTES,
                false,
            )
        })();
        match result {
            Ok(()) => Ok(snapshot),
            Err(error) => {
                let cleanup = snapshot.cleanup_inner();
                snapshot.cleaned = true;
                if cleanup.is_err() {
                    Err(GuestSshError::CredentialCleanup)
                } else {
                    Err(error)
                }
            }
        }
    }

    fn cleanup(mut self) -> Result<(), ()> {
        let result = self.cleanup_inner();
        self.cleaned = true;
        result
    }

    fn cleanup_inner(&mut self) -> Result<(), ()> {
        let mut failed = false;
        failed |= cleanup_snapshot_file(&self.config.identity_file, true).is_err();
        failed |= cleanup_snapshot_file(&self.config.known_hosts_file, false).is_err();
        failed |= fs::remove_dir(&self.directory).is_err();
        failed |= sync_parent_directory(&self.directory).is_err();
        if failed {
            Err(())
        } else {
            Ok(())
        }
    }
}

impl Drop for CredentialSnapshot {
    fn drop(&mut self) {
        if !self.cleaned {
            let _ = self.cleanup_inner();
        }
    }
}

struct AcquiredEnrollmentKey {
    path: PathBuf,
    file: File,
    bytes: Vec<u8>,
    original_length: usize,
    cleaned: bool,
}

impl AcquiredEnrollmentKey {
    fn acquire(path: &Path) -> Result<Self, GuestSshError> {
        validate_secret_file(path, MAX_AUTH_KEY_BYTES)?;
        let before = fs::symlink_metadata(path).map_err(|_| GuestSshError::InvalidFile)?;
        let file = fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(path)
            .map_err(|_| GuestSshError::Io)?;
        let opened = file.metadata().map_err(|_| GuestSshError::InvalidFile)?;
        let after = fs::symlink_metadata(path).map_err(|_| GuestSshError::InvalidFile)?;
        if !same_file(&before, &opened) || !same_file(&opened, &after) {
            return Err(GuestSshError::InvalidFile);
        }
        let mut acquired = Self {
            path: path.to_owned(),
            file,
            original_length: opened.len() as usize,
            bytes: Vec::with_capacity(opened.len() as usize),
            cleaned: false,
        };
        let read_result = Read::by_ref(&mut acquired.file)
            .take(MAX_AUTH_KEY_BYTES + 1)
            .read_to_end(&mut acquired.bytes);
        let read_error = match read_result {
            Err(_) => Some(GuestSshError::Io),
            Ok(_)
                if acquired.bytes.len() as u64 != opened.len()
                    || acquired.bytes.len() as u64 > MAX_AUTH_KEY_BYTES =>
            {
                Some(GuestSshError::InvalidFile)
            }
            Ok(_) => None,
        };
        if let Some(error) = read_error {
            let cleanup = acquired.cleanup_inner();
            acquired.cleaned = true;
            return if cleanup.is_err() {
                Err(GuestSshError::SecretCleanup)
            } else {
                Err(error)
            };
        }
        Ok(acquired)
    }

    fn cleanup(mut self) -> Result<(), ()> {
        let result = self.cleanup_inner();
        self.cleaned = true;
        result
    }

    fn cleanup_inner(&mut self) -> Result<(), ()> {
        self.bytes.fill(0);
        let mut failed = false;
        failed |= self.file.seek(SeekFrom::Start(0)).is_err();
        failed |= self
            .file
            .write_all(&vec![0u8; self.original_length])
            .is_err();
        failed |= self.file.flush().is_err();
        failed |= self.file.sync_data().is_err();
        let same_path = match (self.file.metadata(), fs::symlink_metadata(&self.path)) {
            (Ok(opened), Ok(current)) => same_file(&opened, &current),
            _ => false,
        };
        if same_path {
            failed |= fs::remove_file(&self.path).is_err();
            failed |= sync_parent_directory(&self.path).is_err();
        } else {
            failed = true;
        }
        if failed {
            Err(())
        } else {
            Ok(())
        }
    }
}

impl Drop for AcquiredEnrollmentKey {
    fn drop(&mut self) {
        if !self.cleaned {
            let _ = self.cleanup_inner();
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct GuestCommandOutput {
    pub stdout: Vec<u8>,
    pub stderr: Vec<u8>,
    pub exit_status: i32,
}

#[derive(Clone, Debug, Default)]
pub struct CancellationToken(Arc<AtomicBool>);

impl CancellationToken {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn cancel(&self) {
        self.0.store(true, Ordering::SeqCst);
    }

    pub fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::SeqCst)
    }

    pub fn shared_flag(&self) -> Arc<AtomicBool> {
        self.0.clone()
    }
}

#[derive(Clone, Debug)]
pub struct ProcessLimits {
    pub deadline: Duration,
    pub stdin_bytes: usize,
    pub stdout_bytes: usize,
    pub stderr_bytes: usize,
    pub cancellation: CancellationToken,
}

pub trait ProcessRunner: Send + Sync {
    fn run(
        &self,
        program: &Path,
        args: &[String],
        stdin: &[u8],
        limits: ProcessLimits,
    ) -> Result<GuestCommandOutput, GuestSshError>;
}

#[derive(Default)]
pub struct BoundedProcessRunner;

impl ProcessRunner for BoundedProcessRunner {
    fn run(
        &self,
        program: &Path,
        args: &[String],
        stdin: &[u8],
        limits: ProcessLimits,
    ) -> Result<GuestCommandOutput, GuestSshError> {
        if stdin.len() > limits.stdin_bytes {
            return Err(GuestSshError::InputLimit);
        }
        if limits.cancellation.is_cancelled() {
            return Err(GuestSshError::Cancelled);
        }
        let started = Instant::now();
        let mut command = Command::new(program);
        command
            .args(args)
            .env_clear()
            .env("LANG", "C")
            .env("LC_ALL", "C")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(unix)]
        {
            use std::os::unix::process::CommandExt;
            command.process_group(0);
        }
        let mut child = command
            .spawn()
            .map_err(|_| GuestSshError::ToolUnavailable)?;

        let child_stdin = child.stdin.take().ok_or(GuestSshError::Io)?;
        let child_stdout = child.stdout.take().ok_or(GuestSshError::Io)?;
        let child_stderr = child.stderr.take().ok_or(GuestSshError::Io)?;
        let mut input = stdin.to_vec();
        let writer = thread::spawn(move || {
            let mut child_stdin = child_stdin;
            let result = child_stdin.write_all(&input).map_err(|_| GuestSshError::Io);
            input.fill(0);
            result
        });
        let overflow = Arc::new(AtomicBool::new(false));
        let stdout = spawn_bounded_reader(child_stdout, limits.stdout_bytes, overflow.clone());
        let stderr = spawn_bounded_reader(child_stderr, limits.stderr_bytes, overflow.clone());

        let status = loop {
            let abort = if overflow.load(Ordering::SeqCst) {
                Some(GuestSshError::OutputLimit)
            } else if limits.cancellation.is_cancelled() {
                Some(GuestSshError::Cancelled)
            } else if started.elapsed() >= limits.deadline {
                Some(GuestSshError::Timeout)
            } else {
                None
            };
            if let Some(error) = abort {
                terminate_process_group(&mut child);
                let _ = writer.join();
                let _ = stdout.join();
                let _ = stderr.join();
                return Err(error);
            }
            match child.try_wait() {
                Ok(Some(status)) => break status,
                Ok(None) => thread::sleep(Duration::from_millis(10)),
                Err(_) => {
                    terminate_process_group(&mut child);
                    let _ = writer.join();
                    let _ = stdout.join();
                    let _ = stderr.join();
                    return Err(GuestSshError::Io);
                }
            }
        };

        terminate_descendants(child.id());
        writer.join().map_err(|_| GuestSshError::Io)??;
        let stdout = stdout.join().map_err(|_| GuestSshError::Io)??;
        let stderr = stderr.join().map_err(|_| GuestSshError::Io)??;
        let exit_status = status.code().ok_or(GuestSshError::MissingExitStatus)?;
        Ok(GuestCommandOutput {
            stdout,
            stderr,
            exit_status,
        })
    }
}

fn terminate_process_group(child: &mut std::process::Child) {
    terminate_descendants(child.id());
    let _ = child.kill();
    let _ = child.wait();
}

fn terminate_descendants(group_id: u32) {
    #[cfg(unix)]
    {
        use nix::sys::signal::{killpg, Signal};
        use nix::unistd::Pid;
        if let Ok(group_id) = i32::try_from(group_id) {
            let _ = killpg(Pid::from_raw(group_id), Signal::SIGKILL);
        }
    }
    #[cfg(not(unix))]
    let _ = group_id;
}

fn spawn_bounded_reader<R: Read + Send + 'static>(
    mut reader: R,
    limit: usize,
    overflow: Arc<AtomicBool>,
) -> thread::JoinHandle<Result<Vec<u8>, GuestSshError>> {
    thread::spawn(move || {
        let mut output = Vec::with_capacity(limit.min(8192));
        let mut chunk = [0u8; 4096];
        loop {
            let count = reader.read(&mut chunk).map_err(|_| GuestSshError::Io)?;
            if count == 0 {
                return Ok(output);
            }
            if output.len().saturating_add(count) > limit {
                overflow.store(true, Ordering::SeqCst);
                return Err(GuestSshError::OutputLimit);
            }
            output.extend_from_slice(&chunk[..count]);
        }
    })
}

pub struct OpenSshGuestClient<R: ProcessRunner = BoundedProcessRunner> {
    runner: R,
    ssh: PathBuf,
    ssh_keygen: PathBuf,
}

impl Default for OpenSshGuestClient<BoundedProcessRunner> {
    fn default() -> Self {
        Self {
            runner: BoundedProcessRunner,
            ssh: PathBuf::from("/usr/bin/ssh"),
            ssh_keygen: PathBuf::from("/usr/bin/ssh-keygen"),
        }
    }
}

impl<R: ProcessRunner> OpenSshGuestClient<R> {
    pub fn with_runner(runner: R, ssh: &Path, ssh_keygen: &Path) -> Self {
        Self {
            runner,
            ssh: ssh.to_owned(),
            ssh_keygen: ssh_keygen.to_owned(),
        }
    }

    pub fn status(&self, config: &GuestSshConfig) -> Result<GuestCommandOutput, GuestSshError> {
        self.status_cancellable(config, &CancellationToken::new())
    }

    pub fn status_cancellable(
        &self,
        config: &GuestSshConfig,
        cancellation: &CancellationToken,
    ) -> Result<GuestCommandOutput, GuestSshError> {
        self.exec_cancellable(
            config,
            "/usr/local/bin/podroid-tailscale-status",
            COMMAND_TIMEOUT,
            cancellation,
        )
    }

    pub fn exec(
        &self,
        config: &GuestSshConfig,
        command: &str,
        deadline: Duration,
    ) -> Result<GuestCommandOutput, GuestSshError> {
        self.exec_cancellable(config, command, deadline, &CancellationToken::new())
    }

    pub fn exec_cancellable(
        &self,
        config: &GuestSshConfig,
        command: &str,
        deadline: Duration,
        cancellation: &CancellationToken,
    ) -> Result<GuestCommandOutput, GuestSshError> {
        self.execute_with_input(config, command, &[], deadline, cancellation)
    }

    fn execute_with_input(
        &self,
        config: &GuestSshConfig,
        command: &str,
        stdin: &[u8],
        deadline: Duration,
        cancellation: &CancellationToken,
    ) -> Result<GuestCommandOutput, GuestSshError> {
        if command.is_empty()
            || command.len() > MAX_COMMAND_BYTES
            || command.as_bytes().contains(&0)
            || stdin.len() > MAX_AUTH_KEY_BYTES as usize
        {
            return Err(GuestSshError::InvalidCommand);
        }
        if deadline.is_zero() || deadline > MAX_COMMAND_TIMEOUT {
            return Err(GuestSshError::InvalidDeadline);
        }
        if cancellation.is_cancelled() {
            return Err(GuestSshError::Cancelled);
        }
        let started = Instant::now();
        let snapshot = CredentialSnapshot::create(config)?;
        let result = (|| {
            let remaining = deadline
                .checked_sub(started.elapsed())
                .ok_or(GuestSshError::Timeout)?;
            self.validate_files_and_host_key(&snapshot.config, cancellation, remaining)?;
            let remaining = deadline
                .checked_sub(started.elapsed())
                .ok_or(GuestSshError::Timeout)?;
            let args = ssh_args(&snapshot.config, command)?;
            let output = self.runner.run(
                &self.ssh,
                &args,
                stdin,
                ProcessLimits {
                    deadline: remaining,
                    stdin_bytes: MAX_AUTH_KEY_BYTES as usize,
                    stdout_bytes: MAX_OUTPUT_BYTES,
                    stderr_bytes: MAX_ERROR_BYTES,
                    cancellation: cancellation.clone(),
                },
            )?;
            if output.exit_status != 0 {
                return Err(GuestSshError::RemoteFailure(output.exit_status));
            }
            Ok(output)
        })();
        if snapshot.cleanup().is_err() {
            return Err(GuestSshError::CredentialCleanup);
        }
        result
    }

    pub fn enroll_tailscale(
        &self,
        config: &GuestSshConfig,
        login_server: &str,
        hostname: &str,
        auth_key_file: &Path,
        reauth: bool,
    ) -> Result<GuestCommandOutput, GuestSshError> {
        self.enroll_tailscale_cancellable(
            config,
            login_server,
            hostname,
            auth_key_file,
            reauth,
            &CancellationToken::new(),
        )
    }

    pub fn enroll_tailscale_cancellable(
        &self,
        config: &GuestSshConfig,
        login_server: &str,
        hostname: &str,
        auth_key_file: &Path,
        reauth: bool,
        cancellation: &CancellationToken,
    ) -> Result<GuestCommandOutput, GuestSshError> {
        let enrollment_started = Instant::now();
        let mut key = AcquiredEnrollmentKey::acquire(auth_key_file)?;
        let result = (|| {
            let login_server = validate_login_server(login_server)?;
            let hostname = validate_hostname(hostname)?;
            let has_optional_final_newline =
                !key.bytes.contains(&b'\n') || key.bytes.last() == Some(&b'\n');
            let character_count = key.bytes.len() - usize::from(key.bytes.last() == Some(&b'\n'));
            let key_valid = (8..=512).contains(&character_count)
                && has_optional_final_newline
                && key.bytes[..character_count]
                    .iter()
                    .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'));
            if !key_valid {
                return Err(GuestSshError::InvalidEnrollmentKey);
            }
            if !key.bytes.ends_with(b"\n") {
                key.bytes.push(b'\n');
            }
            let command = format!(
                "/usr/local/bin/podroid-tailscale-enroll --login-server {} --hostname {} --auth-key-stdin{}",
                shell_quote(&login_server),
                shell_quote(&hostname),
                if reauth { " --reauth" } else { "" },
            );
            let remaining = remaining_deadline(enrollment_started, ENROLLMENT_TIMEOUT)?;
            self.execute_with_input(config, &command, &key.bytes, remaining, cancellation)
        })();
        if key.cleanup().is_err() {
            return Err(GuestSshError::SecretCleanup);
        }
        result
    }

    fn validate_files_and_host_key(
        &self,
        config: &GuestSshConfig,
        cancellation: &CancellationToken,
        remaining: Duration,
    ) -> Result<(), GuestSshError> {
        validate_secret_file(&config.identity_file, MAX_PRIVATE_KEY_BYTES)?;
        validate_known_hosts_file(&config.known_hosts_file, &config.target)?;
        let args = vec![
            "-l".to_owned(),
            "-E".to_owned(),
            "sha256".to_owned(),
            config.known_hosts_file.to_string_lossy().into_owned(),
        ];
        let output = self.runner.run(
            &self.ssh_keygen,
            &args,
            &[],
            ProcessLimits {
                deadline: CONNECT_TIMEOUT.min(remaining),
                stdin_bytes: 0,
                stdout_bytes: 4096,
                stderr_bytes: 4096,
                cancellation: cancellation.clone(),
            },
        )?;
        if output.exit_status != 0 {
            return Err(GuestSshError::HostKeyVerification);
        }
        let text =
            std::str::from_utf8(&output.stdout).map_err(|_| GuestSshError::HostKeyVerification)?;
        let fingerprints: Vec<&str> = text
            .split_ascii_whitespace()
            .filter(|field| field.starts_with("SHA256:"))
            .collect();
        if fingerprints.len() != 1 || fingerprints[0] != config.host_fingerprint.as_str() {
            return Err(GuestSshError::HostKeyVerification);
        }
        Ok(())
    }
}

fn remaining_deadline(started: Instant, total: Duration) -> Result<Duration, GuestSshError> {
    remaining_after_elapsed(started.elapsed(), total)
}

fn remaining_after_elapsed(elapsed: Duration, total: Duration) -> Result<Duration, GuestSshError> {
    total
        .checked_sub(elapsed)
        .filter(|remaining| !remaining.is_zero())
        .ok_or(GuestSshError::Timeout)
}

fn ssh_args(config: &GuestSshConfig, command: &str) -> Result<Vec<String>, GuestSshError> {
    let identity = path_text(&config.identity_file)?;
    let known_hosts = path_text(&config.known_hosts_file)?;
    Ok(vec![
        "-F".into(),
        "/dev/null".into(),
        "-o".into(),
        "BatchMode=yes".into(),
        "-o".into(),
        "PasswordAuthentication=no".into(),
        "-o".into(),
        "KbdInteractiveAuthentication=no".into(),
        "-o".into(),
        "ChallengeResponseAuthentication=no".into(),
        "-o".into(),
        "PubkeyAuthentication=yes".into(),
        "-o".into(),
        "IdentitiesOnly=yes".into(),
        "-o".into(),
        "IdentityAgent=none".into(),
        "-o".into(),
        "StrictHostKeyChecking=yes".into(),
        "-o".into(),
        format!("UserKnownHostsFile={known_hosts}"),
        "-o".into(),
        "GlobalKnownHostsFile=/dev/null".into(),
        "-o".into(),
        "HostKeyAlgorithms=ssh-ed25519".into(),
        "-o".into(),
        "HostbasedAuthentication=no".into(),
        "-o".into(),
        "GSSAPIAuthentication=no".into(),
        "-o".into(),
        "UpdateHostKeys=no".into(),
        "-o".into(),
        "VerifyHostKeyDNS=no".into(),
        "-o".into(),
        "ClearAllForwardings=yes".into(),
        "-o".into(),
        "ForwardAgent=no".into(),
        "-o".into(),
        "ForwardX11=no".into(),
        "-o".into(),
        "PermitLocalCommand=no".into(),
        "-o".into(),
        "RequestTTY=no".into(),
        "-o".into(),
        "ControlMaster=no".into(),
        "-o".into(),
        "ControlPath=none".into(),
        "-o".into(),
        "ConnectTimeout=10".into(),
        "-p".into(),
        config.target.port.to_string(),
        "-i".into(),
        identity,
        "--".into(),
        format!("root@{}", config.target.host),
        command.to_owned(),
    ])
}

fn validate_known_hosts_file(path: &Path, target: &GuestSshTarget) -> Result<(), GuestSshError> {
    validate_regular_file(path, MAX_KNOWN_HOSTS_BYTES, false)?;
    let text = fs::read_to_string(path).map_err(|_| GuestSshError::HostKeyVerification)?;
    let lines: Vec<&str> = text
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .collect();
    if lines.len() != 1 {
        return Err(GuestSshError::HostKeyVerification);
    }
    let fields: Vec<&str> = lines[0].split_ascii_whitespace().collect();
    if fields.len() != 3
        || fields[0] != target.known_hosts_token()
        || fields[1] != "ssh-ed25519"
        || fields[2].len() > 1024
        || !fields[2]
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'+' | b'/' | b'='))
    {
        return Err(GuestSshError::HostKeyVerification);
    }
    Ok(())
}

fn cleanup_snapshot_file(path: &Path, overwrite: bool) -> Result<(), ()> {
    let mut failed = false;
    if overwrite {
        match fs::OpenOptions::new().read(true).write(true).open(path) {
            Ok(mut file) => {
                let length = file.metadata().map(|metadata| metadata.len() as usize);
                match length {
                    Ok(length) => {
                        failed |= file.seek(SeekFrom::Start(0)).is_err();
                        failed |= file.write_all(&vec![0u8; length]).is_err();
                        failed |= file.flush().is_err();
                        failed |= file.sync_data().is_err();
                    }
                    Err(_) => failed = true,
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => failed = true,
        }
    }
    match fs::remove_file(path) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(_) => failed = true,
    }
    if failed {
        Err(())
    } else {
        Ok(())
    }
}

fn sync_parent_directory(path: &Path) -> Result<(), ()> {
    let parent = path.parent().ok_or(())?;
    File::open(parent)
        .and_then(|directory| directory.sync_all())
        .map_err(|_| ())
}

fn create_private_snapshot_dir(parent: &Path) -> Result<PathBuf, GuestSshError> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        validate_snapshot_ancestors(parent)?;
        let mut random = File::open("/dev/urandom").map_err(|_| GuestSshError::Io)?;
        for _ in 0..8 {
            let mut nonce = [0u8; 16];
            random
                .read_exact(&mut nonce)
                .map_err(|_| GuestSshError::Io)?;
            let name = nonce
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect::<String>();
            let path = parent.join(format!(".podroid-controller-{name}"));
            let mut builder = fs::DirBuilder::new();
            builder.mode(0o700);
            match builder.create(&path) {
                Ok(()) => return Ok(path),
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(_) => return Err(GuestSshError::Io),
            }
        }
        Err(GuestSshError::Io)
    }
    #[cfg(not(unix))]
    {
        let _ = parent;
        Err(GuestSshError::UnsupportedPlatform)
    }
}

#[cfg(unix)]
fn validate_snapshot_ancestors(parent: &Path) -> Result<(), GuestSshError> {
    use std::os::unix::fs::PermissionsExt;
    let mut current = Some(parent);
    while let Some(path) = current {
        let metadata =
            fs::symlink_metadata(path).map_err(|_| GuestSshError::UnsafeTemporaryDirectory)?;
        let mode = metadata.permissions().mode();
        if metadata.file_type().is_symlink() || !metadata.is_dir() || mode & 0o022 != 0 {
            return Err(GuestSshError::UnsafeTemporaryDirectory);
        }
        current = path.parent();
    }
    Ok(())
}

fn copy_stable_file(
    source: &Path,
    destination: &Path,
    max_bytes: u64,
    secret: bool,
) -> Result<(), GuestSshError> {
    validate_path(source)?;
    let before = fs::symlink_metadata(source).map_err(|_| GuestSshError::InvalidFile)?;
    validate_metadata(&before, max_bytes, secret)?;
    let input = File::open(source).map_err(|_| GuestSshError::InvalidFile)?;
    let opened = input.metadata().map_err(|_| GuestSshError::InvalidFile)?;
    let after = fs::symlink_metadata(source).map_err(|_| GuestSshError::InvalidFile)?;
    if !same_file(&before, &opened) || !same_file(&opened, &after) {
        return Err(GuestSshError::InvalidFile);
    }

    #[cfg(unix)]
    let mut output = {
        use std::os::unix::fs::OpenOptionsExt;
        fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(destination)
            .map_err(|_| GuestSshError::Io)?
    };
    #[cfg(not(unix))]
    let mut output = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(destination)
        .map_err(|_| GuestSshError::Io)?;

    let copied = std::io::copy(&mut input.take(max_bytes + 1), &mut output)
        .map_err(|_| GuestSshError::Io)?;
    if copied != opened.len() || copied == 0 || copied > max_bytes {
        return Err(GuestSshError::InvalidFile);
    }
    output.flush().map_err(|_| GuestSshError::Io)?;
    output.sync_all().map_err(|_| GuestSshError::Io)
}

fn same_file(left: &fs::Metadata, right: &fs::Metadata) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        left.dev() == right.dev() && left.ino() == right.ino()
    }
    #[cfg(not(unix))]
    {
        left.len() == right.len()
            && left.modified().ok() == right.modified().ok()
            && left.is_file() == right.is_file()
    }
}

fn validate_secret_file(path: &Path, max_bytes: u64) -> Result<(), GuestSshError> {
    validate_regular_file(path, max_bytes, true)
}

fn validate_regular_file(path: &Path, max_bytes: u64, secret: bool) -> Result<(), GuestSshError> {
    validate_path(path)?;
    let metadata = fs::symlink_metadata(path).map_err(|_| GuestSshError::InvalidFile)?;
    validate_metadata(&metadata, max_bytes, secret)
}

fn validate_metadata(
    metadata: &fs::Metadata,
    max_bytes: u64,
    secret: bool,
) -> Result<(), GuestSshError> {
    if metadata.file_type().is_symlink()
        || !metadata.is_file()
        || metadata.len() == 0
        || metadata.len() > max_bytes
    {
        return Err(GuestSshError::InvalidFile);
    }
    #[cfg(unix)]
    if secret {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o077 != 0 {
            return Err(GuestSshError::UnsafePermissions);
        }
    }
    Ok(())
}

fn validate_path(path: &Path) -> Result<(), GuestSshError> {
    let text = path.to_str().ok_or(GuestSshError::InvalidFile)?;
    if !path.is_absolute() || text.len() > MAX_PATH_BYTES || text.as_bytes().contains(&0) {
        return Err(GuestSshError::InvalidFile);
    }
    Ok(())
}

fn path_text(path: &Path) -> Result<String, GuestSshError> {
    validate_path(path)?;
    Ok(path.to_string_lossy().into_owned())
}

fn valid_dns_name(value: &str) -> bool {
    if value.starts_with('.') || value.ends_with('.') || value.contains("..") {
        return false;
    }
    value.split('.').all(|label| {
        !label.is_empty()
            && label.len() <= 63
            && !label.starts_with('-')
            && !label.ends_with('-')
            && label
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
    })
}

fn validate_hostname(value: &str) -> Result<String, GuestSshError> {
    if value.len() > MAX_HOST_BYTES || !valid_dns_name(value) {
        return Err(GuestSshError::InvalidEnrollment);
    }
    Ok(value.to_ascii_lowercase())
}

fn validate_login_server(value: &str) -> Result<String, GuestSshError> {
    if value.is_empty() || value.len() > 2048 || !value.starts_with("https://") {
        return Err(GuestSshError::InvalidEnrollment);
    }
    let authority = value
        .strip_prefix("https://")
        .ok_or(GuestSshError::InvalidEnrollment)?
        .strip_suffix('/')
        .unwrap_or_else(|| value.strip_prefix("https://").expect("prefix checked"));
    if authority.is_empty()
        || authority.contains(['/', '?', '#', '@', '[', ']'])
        || authority.chars().any(char::is_whitespace)
    {
        return Err(GuestSshError::InvalidEnrollment);
    }
    let (host, port) = match authority.rsplit_once(':') {
        Some((host, port)) if !host.contains(':') => {
            let port = port
                .parse::<u16>()
                .map_err(|_| GuestSshError::InvalidEnrollment)?;
            if port == 0 {
                return Err(GuestSshError::InvalidEnrollment);
            }
            (host, Some(port))
        }
        _ => (authority, None),
    };
    if host.parse::<Ipv4Addr>().is_err() && !valid_dns_name(host) {
        return Err(GuestSshError::InvalidEnrollment);
    }
    Ok(match port {
        Some(port) => format!("https://{}:{port}", host.to_ascii_lowercase()),
        None => format!("https://{}", host.to_ascii_lowercase()),
    })
}

fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum GuestSshError {
    InvalidTarget,
    InvalidHostFingerprint,
    InvalidFile,
    UnsafePermissions,
    UnsafeTemporaryDirectory,
    HostKeyVerification,
    InvalidCommand,
    InvalidDeadline,
    InvalidEnrollment,
    InvalidEnrollmentKey,
    SecretCleanup,
    CredentialCleanup,
    ToolUnavailable,
    UnsupportedPlatform,
    Cancelled,
    Timeout,
    InputLimit,
    OutputLimit,
    MissingExitStatus,
    RemoteFailure(i32),
    Io,
}

impl fmt::Display for GuestSshError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        let message = match self {
            Self::InvalidTarget => "invalid guest SSH target",
            Self::InvalidHostFingerprint => "invalid host-key fingerprint",
            Self::InvalidFile => "invalid controller credential file",
            Self::UnsafePermissions => "credential file permissions are too broad",
            Self::UnsafeTemporaryDirectory => "controller temporary directory is unsafe",
            Self::HostKeyVerification => "guest host-key verification failed",
            Self::InvalidCommand => "invalid or oversized guest command",
            Self::InvalidDeadline => "invalid guest SSH deadline",
            Self::InvalidEnrollment => "invalid guest enrollment parameters",
            Self::InvalidEnrollmentKey => "invalid one-use enrollment key",
            Self::SecretCleanup => "one-use enrollment key cleanup failed",
            Self::CredentialCleanup => "guest SSH credential snapshot cleanup failed",
            Self::ToolUnavailable => "required OpenSSH tool is unavailable",
            Self::UnsupportedPlatform => "guest SSH is unsupported on this controller platform",
            Self::Cancelled => "guest SSH operation was cancelled",
            Self::Timeout => "guest SSH operation timed out",
            Self::InputLimit => "guest SSH input exceeded its limit",
            Self::OutputLimit => "guest SSH output exceeded its limit",
            Self::MissingExitStatus => "guest SSH command returned no exit status",
            Self::RemoteFailure(_) => "guest SSH command failed",
            Self::Io => "guest SSH I/O failed",
        };
        formatter.write_str(message)
    }
}

impl std::error::Error for GuestSshError {}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Mutex;

    static NEXT_TEMP: AtomicUsize = AtomicUsize::new(0);

    type RecordedCall = (PathBuf, Vec<String>, Vec<u8>, ProcessLimits);

    #[derive(Default)]
    struct FakeRunner {
        calls: Mutex<Vec<RecordedCall>>,
        count: AtomicUsize,
        fingerprint: String,
        ssh_result: Mutex<Option<Result<GuestCommandOutput, GuestSshError>>>,
        mutate_sources: Mutex<Option<(PathBuf, PathBuf)>>,
    }

    impl FakeRunner {
        fn success(fingerprint: &str) -> Self {
            Self {
                fingerprint: fingerprint.to_owned(),
                ssh_result: Mutex::new(Some(Ok(GuestCommandOutput {
                    stdout: b"ok".to_vec(),
                    stderr: Vec::new(),
                    exit_status: 0,
                }))),
                ..Self::default()
            }
        }
    }

    impl ProcessRunner for FakeRunner {
        fn run(
            &self,
            program: &Path,
            args: &[String],
            stdin: &[u8],
            limits: ProcessLimits,
        ) -> Result<GuestCommandOutput, GuestSshError> {
            if limits.cancellation.is_cancelled() {
                return Err(GuestSshError::Cancelled);
            }
            self.calls.lock().unwrap().push((
                program.to_owned(),
                args.to_vec(),
                stdin.to_vec(),
                limits,
            ));
            if self.count.fetch_add(1, Ordering::SeqCst) == 0 {
                if let Some((identity, known_hosts)) = self.mutate_sources.lock().unwrap().take() {
                    fs::write(identity, b"replacement-private-key").unwrap();
                    fs::write(known_hosts, "100.64.0.10 ssh-ed25519 AAAAReplacement\n").unwrap();
                }
                return Ok(GuestCommandOutput {
                    stdout: format!("256 {} guest (ED25519)\n", self.fingerprint).into_bytes(),
                    stderr: Vec::new(),
                    exit_status: 0,
                });
            }
            self.ssh_result.lock().unwrap().take().unwrap_or_else(|| {
                Ok(GuestCommandOutput {
                    stdout: Vec::new(),
                    stderr: Vec::new(),
                    exit_status: 0,
                })
            })
        }
    }

    fn temp_dir() -> PathBuf {
        let workspace = Path::new(env!("CARGO_MANIFEST_DIR")).parent().unwrap();
        let path = workspace.join("target/podroid-test-fixtures").join(format!(
            "podroid-guest-ssh-{}-{}",
            std::process::id(),
            NEXT_TEMP.fetch_add(1, Ordering::SeqCst)
        ));
        fs::create_dir_all(&path).unwrap();
        path
    }

    #[cfg(unix)]
    fn write_secret(path: &Path, bytes: &[u8]) {
        use std::os::unix::fs::PermissionsExt;
        fs::write(path, bytes).unwrap();
        fs::set_permissions(path, fs::Permissions::from_mode(0o600)).unwrap();
    }

    #[cfg(not(unix))]
    fn write_secret(path: &Path, bytes: &[u8]) {
        fs::write(path, bytes).unwrap();
    }

    fn fixture() -> (PathBuf, GuestSshConfig, &'static str) {
        let root = temp_dir();
        let identity = root.join("guest-key");
        let known = root.join("known_hosts");
        write_secret(&identity, b"private-key");
        fs::write(
            &known,
            "100.64.0.10 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKzFp6nOEBlVqIxtOCwLR/JoOPgZYTYlcgDqnWhYmRIN\n",
        )
        .unwrap();
        let fingerprint = "SHA256:/1Izi0wnioNRmc3IVU/bcjQ4PyEmPNqwO89aNAOSGXI";
        let config = GuestSshConfig::new(
            GuestSshTarget::parse("100.64.0.10", 22).unwrap(),
            &identity,
            &known,
            Sha256HostFingerprint::parse(fingerprint).unwrap(),
        )
        .unwrap();
        (root, config, fingerprint)
    }

    #[test]
    fn strict_options_disable_password_pty_agent_x11_and_all_forwarding() {
        let (root, config, fingerprint) = fixture();
        let runner = FakeRunner::success(fingerprint);
        let client = OpenSshGuestClient::with_runner(
            runner,
            Path::new("/fake/ssh"),
            Path::new("/fake/ssh-keygen"),
        );
        client
            .exec(&config, "true", Duration::from_secs(1))
            .unwrap();
        let calls = client.runner.calls.lock().unwrap();
        let ssh_args = &calls[1].1;
        let joined = ssh_args.join(" ");
        for required in [
            "BatchMode=yes",
            "PasswordAuthentication=no",
            "KbdInteractiveAuthentication=no",
            "IdentityAgent=none",
            "StrictHostKeyChecking=yes",
            "ClearAllForwardings=yes",
            "ForwardAgent=no",
            "ForwardX11=no",
            "RequestTTY=no",
            "PermitLocalCommand=no",
        ] {
            assert!(joined.contains(required), "missing {required}");
        }
        assert!(!joined.contains("-L "));
        assert!(!joined.contains("-R "));
        assert!(!joined.contains("-D "));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn credentials_are_snapshotted_before_host_key_verification_and_ssh() {
        let (root, config, fingerprint) = fixture();
        let runner = FakeRunner::success(fingerprint);
        *runner.mutate_sources.lock().unwrap() =
            Some((root.join("guest-key"), root.join("known_hosts")));
        let client = OpenSshGuestClient::with_runner(
            runner,
            Path::new("/fake/ssh"),
            Path::new("/fake/ssh-keygen"),
        );
        client.status(&config).unwrap();
        let calls = client.runner.calls.lock().unwrap();
        let known_snapshot = calls[0].1.last().unwrap();
        assert_ne!(Path::new(known_snapshot), root.join("known_hosts"));
        let identity_index = calls[1].1.iter().position(|value| value == "-i").unwrap();
        let identity_snapshot = &calls[1].1[identity_index + 1];
        assert_ne!(Path::new(identity_snapshot), root.join("guest-key"));
        assert!(!Path::new(known_snapshot).exists());
        assert!(!Path::new(identity_snapshot).exists());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn public_operation_cancellation_fails_before_spawning_a_tool() {
        let (root, config, fingerprint) = fixture();
        let client = OpenSshGuestClient::with_runner(
            FakeRunner::success(fingerprint),
            Path::new("/fake/ssh"),
            Path::new("/fake/ssh-keygen"),
        );
        let cancellation = CancellationToken::new();
        cancellation.cancel();
        assert_eq!(
            client.status_cancellable(&config, &cancellation),
            Err(GuestSshError::Cancelled)
        );
        assert!(client.runner.calls.lock().unwrap().is_empty());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn wrong_or_changed_host_fingerprint_fails_before_ssh() {
        let (root, config, _) = fixture();
        let runner = FakeRunner::success("SHA256:differentDifferentDifferentDifferent123");
        let client = OpenSshGuestClient::with_runner(
            runner,
            Path::new("/fake/ssh"),
            Path::new("/fake/ssh-keygen"),
        );
        assert_eq!(
            client.status(&config),
            Err(GuestSshError::HostKeyVerification)
        );
        assert_eq!(client.runner.calls.lock().unwrap().len(), 1);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn enrollment_streams_key_only_on_stdin_and_always_deletes_it() {
        let (root, config, fingerprint) = fixture();
        let key = root.join("headscale.key");
        write_secret(&key, b"synthetic-enrollment-value\n");
        let runner = FakeRunner::success(fingerprint);
        let client = OpenSshGuestClient::with_runner(
            runner,
            Path::new("/fake/ssh"),
            Path::new("/fake/ssh-keygen"),
        );
        client
            .enroll_tailscale(
                &config,
                "https://headscale.example.test",
                "guest-one",
                &key,
                false,
            )
            .unwrap();
        assert!(!key.exists());
        let calls = client.runner.calls.lock().unwrap();
        let args = calls[1].1.join(" ");
        assert!(!args.contains("synthetic-enrollment-value"));
        assert!(args.contains("--auth-key-stdin"));
        assert_eq!(calls[1].2, b"synthetic-enrollment-value\n");
        assert!(calls[1].3.deadline > Duration::from_secs(100));
        assert!(calls[1].3.deadline <= ENROLLMENT_TIMEOUT);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn malformed_enrollment_key_is_erased_and_removed() {
        let (root, config, fingerprint) = fixture();
        let key = root.join("headscale.key");
        write_secret(&key, b"valid-part\ntrailing-part");
        let client = OpenSshGuestClient::with_runner(
            FakeRunner::success(fingerprint),
            Path::new("/fake/ssh"),
            Path::new("/fake/ssh-keygen"),
        );
        assert_eq!(
            client.enroll_tailscale(
                &config,
                "https://headscale.example.test",
                "guest-one",
                &key,
                false,
            ),
            Err(GuestSshError::InvalidEnrollmentKey)
        );
        assert!(!key.exists());
        assert!(client.runner.calls.lock().unwrap().is_empty());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn acquired_enrollment_key_is_removed_when_parameters_are_invalid() {
        let (root, config, fingerprint) = fixture();
        let key = root.join("headscale.key");
        write_secret(&key, b"synthetic-enrollment-value\n");
        let client = OpenSshGuestClient::with_runner(
            FakeRunner::success(fingerprint),
            Path::new("/fake/ssh"),
            Path::new("/fake/ssh-keygen"),
        );
        assert_eq!(
            client.enroll_tailscale(&config, "http://insecure.test", "guest-one", &key, false),
            Err(GuestSshError::InvalidEnrollment)
        );
        assert!(!key.exists());
        assert!(client.runner.calls.lock().unwrap().is_empty());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn target_command_and_credential_inputs_are_bounded() {
        assert!(GuestSshTarget::parse("-oProxyCommand=x", 22).is_err());
        assert!(GuestSshTarget::parse("bad host", 22).is_err());
        assert!(Sha256HostFingerprint::parse("MD5:bad").is_err());
        let (root, config, fingerprint) = fixture();
        let client = OpenSshGuestClient::with_runner(
            FakeRunner::success(fingerprint),
            Path::new("/fake/ssh"),
            Path::new("/fake/keygen"),
        );
        assert_eq!(
            client.exec(
                &config,
                &"x".repeat(MAX_COMMAND_BYTES + 1),
                Duration::from_secs(1)
            ),
            Err(GuestSshError::InvalidCommand)
        );
        assert_eq!(
            client.exec(&config, "true", Duration::ZERO),
            Err(GuestSshError::InvalidDeadline)
        );
        assert_eq!(
            client.exec(
                &config,
                "true",
                MAX_COMMAND_TIMEOUT + Duration::from_secs(1)
            ),
            Err(GuestSshError::InvalidDeadline)
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn enrollment_preflight_consumes_the_overall_deadline() {
        assert_eq!(
            remaining_after_elapsed(Duration::from_secs(30), ENROLLMENT_TIMEOUT),
            Ok(Duration::from_secs(90))
        );
        assert_eq!(
            remaining_after_elapsed(ENROLLMENT_TIMEOUT, ENROLLMENT_TIMEOUT),
            Err(GuestSshError::Timeout)
        );
        assert_eq!(
            remaining_after_elapsed(Duration::from_secs(121), ENROLLMENT_TIMEOUT),
            Err(GuestSshError::Timeout)
        );
    }

    fn process_limits(deadline: Duration, stdout_bytes: usize) -> ProcessLimits {
        ProcessLimits {
            deadline,
            stdin_bytes: MAX_AUTH_KEY_BYTES as usize,
            stdout_bytes,
            stderr_bytes: 32,
            cancellation: CancellationToken::new(),
        }
    }

    #[test]
    fn process_runner_enforces_cancellation_timeout_input_output_and_exit_status() {
        let runner = BoundedProcessRunner;
        let cancelled = CancellationToken::new();
        cancelled.cancel();
        let mut cancelled_limits = process_limits(Duration::from_secs(1), 32);
        cancelled_limits.cancellation = cancelled;
        assert_eq!(
            runner.run(Path::new("/bin/true"), &[], &[], cancelled_limits),
            Err(GuestSshError::Cancelled)
        );
        let mut input_limits = process_limits(Duration::from_secs(1), 32);
        input_limits.stdin_bytes = 2;
        assert_eq!(
            runner.run(Path::new("/bin/true"), &[], b"123", input_limits),
            Err(GuestSshError::InputLimit)
        );
        let timeout = runner.run(
            Path::new("/bin/sh"),
            &["-c".into(), "sleep 2".into()],
            &[],
            process_limits(Duration::from_millis(50), 32),
        );
        assert_eq!(timeout, Err(GuestSshError::Timeout));
        let overflow = runner.run(
            Path::new("/bin/sh"),
            &["-c".into(), "printf 123456789".into()],
            &[],
            process_limits(Duration::from_secs(1), 4),
        );
        assert_eq!(overflow, Err(GuestSshError::OutputLimit));
        let no_status = runner.run(
            Path::new("/bin/sh"),
            &["-c".into(), "kill -9 $$".into()],
            &[],
            process_limits(Duration::from_secs(1), 32),
        );
        assert_eq!(no_status, Err(GuestSshError::MissingExitStatus));
        let started = Instant::now();
        let descendant = runner.run(
            Path::new("/bin/sh"),
            &["-c".into(), "sleep 30 &".into()],
            &[],
            process_limits(Duration::from_secs(1), 32),
        );
        assert_eq!(descendant.unwrap().exit_status, 0);
        assert!(started.elapsed() < Duration::from_secs(2));
    }
}
