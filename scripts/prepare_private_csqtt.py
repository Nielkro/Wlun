"""Use private filesystem/FD IPC for the pinned mobile CSQTT client."""
from pathlib import Path
import sys

# A loopback UDP dispatcher accepts traffic from every Android app. The Go
# adapter passes a connected socketpair FD through a private filesystem socket.
tun = Path(sys.argv[1]) / "rust-client/tun.rs"
source = tun.read_text(encoding="utf-8")
before = '''        bind(
            listener.as_raw_fd(),
            &UnixAddr::new_abstract(name.as_bytes())?,
        )?;'''
after = '''        let address = if name.starts_with('/') {
            UnixAddr::new(name)?
        } else {
            UnixAddr::new_abstract(name.as_bytes())?
        };
        bind(listener.as_raw_fd(), &address)?;'''
if 'let addr = UnixAddr::new_abstract(name.as_bytes())?;' in source:
    before = 'let addr = UnixAddr::new_abstract(name.as_bytes())?;'
    after = '''let addr = if name.starts_with('/') {
            UnixAddr::new(name)?
        } else {
            UnixAddr::new_abstract(name.as_bytes())?
        };'''
if source.count(before) != 1:
    raise SystemExit("CSQTT FD receiver bind changed; review private IPC patch")
source = source.replace(before, after, 1)
before = '''                            let descriptor = unsafe { OwnedFd::from_raw_fd(descriptor) };
                            configure_nonblocking(descriptor.as_raw_fd())?;'''
after = '''                            let descriptor = unsafe { OwnedFd::from_raw_fd(descriptor) };
                            let credentials = nix::sys::socket::getsockopt(
                                &descriptor, nix::sys::socket::sockopt::PeerCredentials,
                            )?;
                            if credentials.uid() != unsafe { libc::geteuid() } {
                                continue;
                            }
                            configure_nonblocking(descriptor.as_raw_fd())?;'''
if 'if let Err(error) = configure_nonblocking(descriptor.as_fd()) {' in source:
    before = '''                                let descriptor = unsafe { OwnedFd::from_raw_fd(descriptor) };
                                if let Err(error) = configure_nonblocking(descriptor.as_fd()) {'''
    after = '''                                let descriptor = unsafe { OwnedFd::from_raw_fd(descriptor) };
                                let credentials = nix::sys::socket::getsockopt(
                                    &descriptor, nix::sys::socket::sockopt::PeerCredentials,
                                )?;
                                if credentials.uid() != unsafe { libc::geteuid() } {
                                    continue;
                                }
                                if let Err(error) = configure_nonblocking(descriptor.as_fd()) {'''
if source.count(before) != 1:
    raise SystemExit("CSQTT FD receiver accept changed; review private IPC patch")
source = source.replace(before, after, 1)
source += r'''

#[cfg(all(test, any(target_os = "linux", target_os = "android")))]
mod mobile_private_ipc_tests {
    use super::FdReceiver;
    use std::{io::{IoSlice, Read, Write}, os::{fd::AsRawFd, unix::{fs::{DirBuilderExt, PermissionsExt}, net::{UnixDatagram, UnixStream}}}};
    use nix::sys::socket::{sendmsg, ControlMessage, MsgFlags, UnixAddr};
    use tokio_util::sync::CancellationToken;

    #[tokio::test]
    async fn filesystem_receiver_preserves_private_packet_fd() {
        let dir = std::env::temp_dir().join(format!("csqtt-ipc-{}-{}", std::process::id(), rand::random::<u64>()));
        std::fs::DirBuilder::new().mode(0o700).create(&dir).unwrap();
        let path = dir.join("fd");
        let receiver = FdReceiver::bind(path.to_str().unwrap()).unwrap();
        assert_eq!(std::fs::metadata(&dir).unwrap().permissions().mode() & 0o777, 0o700);
        let (local, remote) = UnixDatagram::pair().unwrap();
        let mut connection = UnixStream::connect(&path).unwrap();
        let payload = [1u8];
        let slices = [IoSlice::new(&payload)];
        let descriptors = [remote.as_raw_fd()];
        let controls = [ControlMessage::ScmRights(&descriptors)];
        sendmsg::<UnixAddr>(connection.as_raw_fd(), &slices, &controls, MsgFlags::empty(), None).unwrap();
        let mut received = receiver.receive(&CancellationToken::new()).await.unwrap();
        let mut ack = [0u8;1];
        connection.read_exact(&mut ack).unwrap();
        assert_eq!(ack, [1]);
        local.send(&[0x45, 0, 0, 20]).unwrap();
        let mut packet = [0u8;64];
        let length = received.read(&mut packet).unwrap();
        assert_eq!(&packet[..length], &[0x45, 0, 0, 20]);
        received.write_all(&[0x45, 1, 0, 20]).unwrap();
        assert_eq!(local.recv(&mut packet).unwrap(), 4);
        assert_eq!(&packet[..4], &[0x45, 1, 0, 20]);
        let cancel = CancellationToken::new();
        cancel.cancel();
        assert!(receiver.receive(&cancel).await.is_err());
        drop(receiver);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
'''
tun.write_text(source, encoding="utf-8", newline="\n")
