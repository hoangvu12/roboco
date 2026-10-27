//! A child the preview discovery tests spawn
//! (`env!("CARGO_BIN_EXE_preview-child-server")`): binds the socket address
//! given as the first argument, writes the port it received to the file
//! named by the second, then answers HTTP until killed. std-only on
//! purpose, so the Windows suite needs no interpreter from the runner
//! image.

use std::{
    io::{Read, Write},
    net::TcpListener,
};

fn main() {
    let mut args = std::env::args().skip(1);
    let (Some(bind), Some(ready)) = (args.next(), args.next()) else {
        std::process::exit(2);
    };
    let listener = match TcpListener::bind(bind.as_str()) {
        Ok(listener) => listener,
        Err(error) => {
            eprintln!("preview-child-server: could not bind {bind}: {error}");
            std::process::exit(3);
        }
    };
    let port = listener
        .local_addr()
        .expect("bound socket has a local address")
        .port();
    std::fs::write(&ready, port.to_string())
        .unwrap_or_else(|error| panic!("could not report the port: {error}"));
    // Discovery probes with one HEAD and reads the status line; draining a
    // single request is enough. The server serves until the test kills it.
    for stream in listener.incoming() {
        let Ok(mut stream) = stream else {
            continue;
        };
        let mut request = [0; 1024];
        let _ = stream.read(&mut request);
        let _ = stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    }
}
