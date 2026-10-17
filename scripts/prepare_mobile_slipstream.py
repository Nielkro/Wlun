"""Make slipstream-client listen on a Unix socket for qThroneMobile.

A TCP listener on loopback is open to every app on the phone, so a tunnel
whose backend needs no password would carry anyone's traffic. The mobile
build listens on a Unix socket named by --tcp-listen-host instead; it sits in
the app's private session directory, which other apps cannot open. The DNS
transport, QUIC and resolver handling are untouched.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1]) / "crates/slipstream-client/src"


def patch(name, replacements):
    path = root / name
    source = path.read_text(encoding="utf-8")
    for old, new in replacements:
        if source.count(old) != 1:
            raise SystemExit(f"slipstream-client {name} changed; update prepare_mobile_slipstream.py ({old!r})")
        source = source.replace(old, new)
    path.write_text(source, encoding="utf-8", newline="\n")


patch("runtime/setup.rs", [
    ("use tokio::net::{TcpListener as TokioTcpListener, UdpSocket as TokioUdpSocket};",
     "use tokio::net::{UnixListener as TokioTcpListener, UdpSocket as TokioUdpSocket};"),
    ("""    bind_first_resolved_with_ipv4_fallback(host, port, bind_tcp_listener_addr, "TCP listener").await
""",
     """    let _ = (port, bind_tcp_listener_addr);
    let _ = std::fs::remove_file(host);
    let listener = TokioTcpListener::bind(host)?;
    Ok((listener, host.to_owned()))
"""),
])
patch("streams/acceptor.rs", [
    ("use tokio::net::TcpListener as TokioTcpListener;", "use tokio::net::UnixListener as TokioTcpListener;"),
])
patch("streams/state.rs", [
    ("use tokio::net::TcpStream as TokioTcpStream;", "use tokio::net::UnixStream as TokioTcpStream;"),
])
patch("streams/io_tasks.rs", [
    ("tokio::net::tcp::OwnedReadHalf", "tokio::net::unix::OwnedReadHalf"),
    ("tokio::net::tcp::OwnedWriteHalf", "tokio::net::unix::OwnedWriteHalf"),
])
patch("streams/command_dispatch.rs", [
    ("            let _ = stream.set_nodelay(true);\n", ""),
])
