#![forbid(unsafe_code)]

use controller_core::guest_ssh::{
    CancellationToken, GuestSshConfig, GuestSshTarget, OpenSshGuestClient, Sha256HostFingerprint,
};
use signal_hook::consts::signal::{SIGINT, SIGTERM};
use std::collections::HashSet;
use std::env;
use std::path::PathBuf;
use std::process::ExitCode;
use std::time::Duration;

const EXEC_TIMEOUT: Duration = Duration::from_secs(60);

fn main() -> ExitCode {
    match run(env::args().skip(1).collect()) {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("phonectl: {message}");
            ExitCode::from(1)
        }
    }
}

fn run(args: Vec<String>) -> Result<(), String> {
    let request = parse_arguments(&args)?;
    let config = GuestSshConfig::new(
        GuestSshTarget::parse(&request.host, request.port).map_err(|error| error.to_string())?,
        &request.identity,
        &request.known_hosts,
        Sha256HostFingerprint::parse(&request.fingerprint).map_err(|error| error.to_string())?,
    )
    .map_err(|error| error.to_string())?;
    let client = OpenSshGuestClient::default();
    let cancellation = CancellationToken::new();
    signal_hook::flag::register(SIGINT, cancellation.shared_flag())
        .map_err(|_| "cannot install SIGINT cancellation handler".to_owned())?;
    signal_hook::flag::register(SIGTERM, cancellation.shared_flag())
        .map_err(|_| "cannot install SIGTERM cancellation handler".to_owned())?;

    let output = match request.operation {
        GuestOperation::Status => client.status_cancellable(&config, &cancellation),
        GuestOperation::Exec(command) => {
            client.exec_cancellable(&config, &command, EXEC_TIMEOUT, &cancellation)
        }
        GuestOperation::Enroll {
            login_server,
            hostname,
            auth_key_file,
            reauth,
        } => client.enroll_tailscale_cancellable(
            &config,
            &login_server,
            &hostname,
            &auth_key_file,
            reauth,
            &cancellation,
        ),
    }
    .map_err(|error| error.to_string())?;

    std::io::Write::write_all(&mut std::io::stdout(), &output.stdout)
        .map_err(|_| "cannot write command output".to_owned())?;
    if !output.stderr.is_empty() {
        std::io::Write::write_all(&mut std::io::stderr(), &output.stderr)
            .map_err(|_| "cannot write command diagnostics".to_owned())?;
    }
    Ok(())
}

struct ParsedRequest {
    host: String,
    port: u16,
    identity: PathBuf,
    known_hosts: PathBuf,
    fingerprint: String,
    operation: GuestOperation,
}

enum GuestOperation {
    Status,
    Exec(String),
    Enroll {
        login_server: String,
        hostname: String,
        auth_key_file: PathBuf,
        reauth: bool,
    },
}

fn parse_arguments(args: &[String]) -> Result<ParsedRequest, String> {
    if args.len() < 2 || args[0] != "guest" {
        return Err(usage());
    }
    let operation = args[1].as_str();
    if !matches!(operation, "status" | "exec" | "enroll") {
        return Err(usage());
    }

    let mut cursor = 2usize;
    let mut seen = HashSet::new();
    let mut host = None;
    let mut port = 22u16;
    let mut identity = None;
    let mut known_hosts = None;
    let mut fingerprint = None;
    let mut login_server = None;
    let mut hostname = None;
    let mut auth_key_file = None;
    let mut reauth = false;
    let mut command = None;

    while cursor < args.len() {
        let option = args[cursor].as_str();
        if option == "--" {
            if operation != "exec" || command.is_some() || cursor + 1 >= args.len() {
                return Err(usage());
            }
            command = Some(args[cursor + 1..].join(" "));
            cursor = args.len();
            continue;
        }
        let allowed = matches!(
            option,
            "--host" | "--port" | "--identity" | "--known-hosts" | "--host-key-sha256"
        ) || (operation == "enroll"
            && matches!(
                option,
                "--login-server" | "--hostname" | "--auth-key-file" | "--reauth"
            ));
        if !allowed {
            return Err(format!(
                "option {option} is not valid for guest {operation}"
            ));
        }
        if !seen.insert(option.to_owned()) {
            return Err(format!("duplicate option {option}"));
        }
        match option {
            "--host" => host = Some(take_value(args, &mut cursor, option)?),
            "--port" => {
                let value = take_value(args, &mut cursor, option)?;
                port = value.parse().map_err(|_| "invalid --port".to_owned())?;
            }
            "--identity" => {
                identity = Some(PathBuf::from(take_value(args, &mut cursor, option)?));
            }
            "--known-hosts" => {
                known_hosts = Some(PathBuf::from(take_value(args, &mut cursor, option)?));
            }
            "--host-key-sha256" => {
                fingerprint = Some(take_value(args, &mut cursor, option)?);
            }
            "--login-server" => {
                login_server = Some(take_value(args, &mut cursor, option)?);
            }
            "--hostname" => hostname = Some(take_value(args, &mut cursor, option)?),
            "--auth-key-file" => {
                auth_key_file = Some(PathBuf::from(take_value(args, &mut cursor, option)?));
            }
            "--reauth" => {
                reauth = true;
                cursor += 1;
            }
            _ => return Err(usage()),
        }
    }

    let operation = match operation {
        "status" => GuestOperation::Status,
        "exec" => GuestOperation::Exec(
            command.ok_or_else(|| "guest exec requires a command after --".to_owned())?,
        ),
        "enroll" => GuestOperation::Enroll {
            login_server: login_server.ok_or_else(usage)?,
            hostname: hostname.ok_or_else(usage)?,
            auth_key_file: auth_key_file.ok_or_else(usage)?,
            reauth,
        },
        _ => return Err(usage()),
    };

    Ok(ParsedRequest {
        host: host.ok_or_else(usage)?,
        port,
        identity: identity.ok_or_else(usage)?,
        known_hosts: known_hosts.ok_or_else(usage)?,
        fingerprint: fingerprint.ok_or_else(usage)?,
        operation,
    })
}

fn take_value(args: &[String], cursor: &mut usize, option: &str) -> Result<String, String> {
    let value = args
        .get(*cursor + 1)
        .ok_or_else(|| format!("missing value for {option}"))?
        .clone();
    if value.starts_with("--") {
        return Err(format!("missing value for {option}"));
    }
    *cursor += 2;
    Ok(value)
}

fn usage() -> String {
    concat!(
        "usage: phonectl guest <status|exec|enroll> --host HOST [--port PORT] ",
        "--identity PRIVATE_KEY --known-hosts FILE --host-key-sha256 SHA256:... ",
        "[--login-server HTTPS_URL --hostname NAME --auth-key-file FILE [--reauth]] ",
        "[-- COMMAND ...]"
    )
    .to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn common(operation: &str) -> Vec<String> {
        [
            "guest",
            operation,
            "--host",
            "100.64.0.10",
            "--identity",
            "/home/operator/.ssh/guest",
            "--known-hosts",
            "/home/operator/.config/podroid/known_hosts",
            "--host-key-sha256",
            "SHA256:/1Izi0wnioNRmc3IVU/bcjQ4PyEmPNqwO89aNAOSGXI",
        ]
        .into_iter()
        .map(str::to_owned)
        .collect()
    }

    #[test]
    fn routes_status_exec_and_enroll_strictly() {
        let status = parse_arguments(&common("status")).unwrap();
        assert!(matches!(status.operation, GuestOperation::Status));

        let mut exec = common("exec");
        exec.extend(["--".to_owned(), "uname".to_owned(), "-a".to_owned()]);
        let exec = parse_arguments(&exec).unwrap();
        assert!(matches!(exec.operation, GuestOperation::Exec(ref value) if value == "uname -a"));

        let mut enroll = common("enroll");
        enroll.extend(
            [
                "--login-server",
                "https://headscale.example.test",
                "--hostname",
                "guest-one",
                "--auth-key-file",
                "/home/operator/.config/podroid/one-use.key",
                "--reauth",
            ]
            .into_iter()
            .map(str::to_owned),
        );
        let enroll = parse_arguments(&enroll).unwrap();
        assert!(matches!(
            enroll.operation,
            GuestOperation::Enroll { reauth: true, .. }
        ));
    }

    #[test]
    fn rejects_duplicate_irrelevant_missing_and_unknown_options() {
        let mut duplicate = common("status");
        duplicate.extend(["--host".to_owned(), "other".to_owned()]);
        assert!(parse_arguments(&duplicate)
            .err()
            .unwrap()
            .contains("duplicate"));

        let mut irrelevant = common("status");
        irrelevant.push("--reauth".to_owned());
        assert!(parse_arguments(&irrelevant)
            .err()
            .unwrap()
            .contains("not valid"));

        let mut missing_exec = common("exec");
        assert!(parse_arguments(&missing_exec).is_err());
        missing_exec.push("--".to_owned());
        assert!(parse_arguments(&missing_exec).is_err());

        let mut unknown = common("status");
        unknown.push("--proxy-command".to_owned());
        assert!(parse_arguments(&unknown).is_err());
    }
}
