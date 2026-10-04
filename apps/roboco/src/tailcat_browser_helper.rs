//! Loopback HTTP helper so a browser tab can pair with `roboco-tailcat:…` without
//! pasting a `http://127.0.0.1:…/pair#token=…` URL. Run beside the browser:
//! `roboco engine tailcat browser-helper`

use std::{
    io::{Read, Write},
    net::{SocketAddr, TcpListener},
    sync::{Arc, Mutex},
    thread,
    time::Duration,
};

use anyhow::Context as _;
use roboco_engine::tailcat::{TailcatClient, TailcatInvite};
use serde::Deserialize;

const DEFAULT_ADDR: &str = "127.0.0.1:7333";

struct HeldClient(TailcatClient);

pub fn run(data_dir: &std::path::Path, listen: Option<&str>) -> anyhow::Result<()> {
    let addr: SocketAddr = listen
        .unwrap_or(DEFAULT_ADDR)
        .parse()
        .context("invalid browser-helper listen address")?;
    let listener = TcpListener::bind(addr).with_context(|| format!("cannot bind {addr}"))?;
    eprintln!(
        "Tailcat browser helper listening on http://{addr} — paste roboco-tailcat:… in Settings → Devices"
    );
    let clients: Arc<Mutex<Vec<HeldClient>>> = Arc::new(Mutex::new(Vec::new()));
    for stream in listener.incoming() {
        let Ok(mut stream) = stream else {
            continue;
        };
        let data_dir = data_dir.to_path_buf();
        let clients = Arc::clone(&clients);
        thread::spawn(move || {
            if let Err(error) = handle(&mut stream, &data_dir, &clients) {
                let body = serde_json::json!({ "error": error.to_string() }).to_string();
                let _ = write_response(&mut stream, 400, &body);
            }
        });
    }
    Ok(())
}

fn handle(
    stream: &mut std::net::TcpStream,
    data_dir: &std::path::Path,
    clients: &Arc<Mutex<Vec<HeldClient>>>,
) -> anyhow::Result<()> {
    stream
        .set_read_timeout(Some(Duration::from_secs(30)))
        .ok();
    let mut buffer = [0u8; 65536];
    let read = stream.read(&mut buffer)?;
    let request = std::str::from_utf8(&buffer[..read]).context("request is not UTF-8")?;
    let mut lines = request.lines();
    let request_line = lines.next().context("empty request")?;
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("");
    let path = parts.next().unwrap_or("");
    if method == "OPTIONS" {
        write_cors(stream, 204, "")?;
        return Ok(());
    }
    if method != "POST" || path != "/pair" {
        anyhow::bail!("expected POST /pair");
    }
    let mut content_length = 0usize;
    for line in lines {
        if line.is_empty() {
            break;
        }
        if let Some(value) = line.strip_prefix("Content-Length:") {
            content_length = value.trim().parse().unwrap_or(0);
        }
    }
    let body_start = request.find("\r\n\r\n").map(|i| i + 4).unwrap_or(read);
    let body = if body_start + content_length <= read {
        &request[body_start..body_start + content_length]
    } else {
        &request[body_start..]
    };
    #[derive(Deserialize)]
    struct PairBody {
        invite: String,
        label: String,
    }
    let PairBody { invite, label } =
        serde_json::from_str(body).context("POST /pair body must be JSON { invite, label }")?;
    let invite = TailcatInvite::decode(&invite)?;
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)?
        .as_millis() as i64;
    anyhow::ensure!(
        !invite.expired(now),
        "that tailcat invite has expired; mint a new one"
    );
    let client = TailcatClient::start(data_dir, &invite.address, None)?;
    let base = client.url().trim_end_matches('/').to_string();
    let http = reqwest::blocking::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(15))
        .build()?;
    let response = http
        .post(format!("{base}/pairing/redeem"))
        .bearer_auth(&invite.token)
        .json(&serde_json::json!({ "label": label }))
        .send()
        .context("could not reach the engine through Tailcat")?;
    anyhow::ensure!(
        response.status().is_success(),
        "pairing was refused or has expired"
    );
    let mut grant: serde_json::Value = response.json()?;
    if let Some(object) = grant.as_object_mut() {
        object.insert("baseUrl".into(), base.clone().into());
    }
    clients.lock().unwrap().push(HeldClient(client));
    write_cors(stream, 200, &grant.to_string())?;
    Ok(())
}

fn write_cors(stream: &mut std::net::TcpStream, status: u16, body: &str) -> anyhow::Result<()> {
    write_response(stream, status, body)
}

fn write_response(stream: &mut std::net::TcpStream, status: u16, body: &str) -> anyhow::Result<()> {
    let reason = match status {
        200 => "OK",
        204 => "No Content",
        400 => "Bad Request",
        _ => "Error",
    };
    let response = format!(
        "HTTP/1.1 {status} {reason}\r\n\
         Content-Type: application/json\r\n\
         Content-Length: {}\r\n\
         Access-Control-Allow-Origin: *\r\n\
         Access-Control-Allow-Methods: POST, OPTIONS\r\n\
         Access-Control-Allow-Headers: content-type\r\n\
         Connection: close\r\n\r\n\
         {body}",
        body.len()
    );
    stream.write_all(response.as_bytes())?;
    Ok(())
}
