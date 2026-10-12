"""Isolated emulator VPN lifecycle and HTTP probe smoke test with a local SOCKS fixture."""
import os, re, socket, socketserver, subprocess, threading, time
from pathlib import Path
import xml.etree.ElementTree as ET

adb = os.path.join(os.environ['ANDROID_HOME'], 'platform-tools', 'adb')
out = Path('ui-screenshots'); out.mkdir(exist_ok=True)
hits = []
response_delay = 0.0
stalled_started = []
probe_gate = threading.Event()
probe_gate.set()
def shell(*args):
    return subprocess.check_output([adb, 'shell', *args], text=True)
def xml():
    shell('uiautomator', 'dump', '/sdcard/vpn-smoke.xml')
    return shell('cat', '/sdcard/vpn-smoke.xml')
def tap(label):
    for node in ET.fromstring(xml()).iter('node'):
        if label in (node.get('text'), node.get('content-desc')):
            a,b,c,d = map(int,re.findall(r'\d+',node.attrib['bounds']))
            shell('input','tap',str((a+c)//2),str((b+d)//2)); time.sleep(1)
            return
    raise AssertionError('Missing control: '+label)
def wait_for(value):
    for _ in range(30):
        if value in xml(): return
        time.sleep(1)
    (out/'vpn-failed.xml').write_text(xml())
    raise AssertionError('State not reached: '+value)
def profile_latency():
    tree=ET.fromstring(xml())
    parents={child:parent for parent in tree.iter() for child in parent}
    node=next(n for n in tree.iter('node') if n.get('text')=='VPN-smoke')
    row=parents[parents[node]]
    return next((n.get('text') for n in row.iter('node') if re.fullmatch(r'[0-9]+ ms',n.get('text',''))),None)

def exact(stream,n):
    data=b''
    while len(data)<n:
        chunk=stream.recv(n-len(data))
        if not chunk: raise EOFError()
        data+=chunk
    return data
class Socks(socketserver.BaseRequestHandler):
    def handle(self):
        s=self.request; s.settimeout(20)
        try:
            version,count=exact(s,2); methods=exact(s,count)
            if version!=5 or 0 not in methods: return
            s.sendall(b'\x05\x00')
            version,command,_,kind=exact(s,4)
            if version!=5 or command!=1: return
            size={1:4,4:16}.get(kind)
            if kind==3: size=exact(s,1)[0]
            if size is None: return
            exact(s,size); exact(s,2)
            s.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x50')
            request=b''
            while b'\r\n\r\n' not in request and len(request)<16384:
                chunk=s.recv(4096)
                if not chunk: return
                request+=chunk
            if b'generate_204' not in request: return
            hits.append(request.split(b'\r\n',1)[0].decode('ascii','replace'))
            if not probe_gate.is_set(): stalled_started.append(time.monotonic())
            probe_gate.wait(20)
            time.sleep(response_delay)
            s.sendall(b'HTTP/1.1 204 No Content\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
        except (OSError,EOFError): pass
class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address=True
    daemon_threads=True
server=Server(('127.0.0.1',8868),Socks)
threading.Thread(target=server.serve_forever,daemon=True).start()
subprocess.check_call([adb,'reverse','tcp:8868','tcp:8868'])
packages=[v.split(':',1)[1].strip() for v in shell('pm','list','packages').splitlines() if v.startswith('package:app.qthrone.mobile')]
assert len(packages)==1
package=packages[0]; activity=package+'/app.qthrone.mobile.ui.MainActivity'
(out/'tested-build-run.txt').write_text(os.environ.get('TESTED_BUILD_RUN',''))
(out/'tested-package.txt').write_text(shell('dumpsys','package',package))
# Only this disposable CI emulator's application data is reset.
shell('pm','clear',package)
shell('pm','grant',package,'android.permission.POST_NOTIFICATIONS')
# The power button continuously pulses while connected. UIAutomator's idle
# detector otherwise waits forever for its bounds to stop changing.
shell('settings','put','global','animator_duration_scale','0')
shell('am','start','-n',activity,'-a','android.intent.action.VIEW','-d','socks5://127.0.0.1:8868#VPN-smoke')
time.sleep(5); tap('Yes')
# A clean installation also asks about background battery restrictions.
if 'Keep Throne running' in xml(): tap('Not now')
wait_for('VPN-smoke')
tap('VPN-smoke')
for cycle in range(2):
    tap('Connect or disconnect VPN, Not connected')
    time.sleep(2)
    if 'Connection request' in xml(): tap('OK')
    wait_for('Connect or disconnect VPN, Connected')
    state=shell('dumpsys','connectivity')
    assert 'TRANSPORT_VPN' in state or 'Transports: VPN' in state, 'No Android VPN network'
    # A different Android UID must not obtain an unauthenticated local SOCKS path.
    subprocess.check_call([adb,'forward','tcp:18880','tcp:2080'])
    reply=b''
    try:
        with socket.create_connection(('127.0.0.1',18880),timeout=2) as probe:
            probe.settimeout(2); probe.sendall(b'\x05\x01\x00'); reply=probe.recv(2)
    except OSError:
        pass
    assert reply!=b'\x05\x00', 'VPN exposes an unauthenticated local SOCKS listener'
    (out/f'10-vpn-connected-{cycle}.png').write_bytes(subprocess.check_output([adb,'exec-out','screencap','-p']))
    previous=len(hits)
    for node in ET.fromstring(xml()).iter('node'):
        if node.get('text')=='VPN-smoke':
            a,b,c,d=map(int,re.findall(r'\d+',node.attrib['bounds'])); y=(b+d)//2
            shell('input','swipe',str((a+c)//2),str(y),'1000',str(y),'350'); break
    time.sleep(1); tap('Testing')
    for _ in range(30):
        screen=xml()
        assert 'text="Stop"' not in screen and 'text="Latency test"' not in screen, 'Ping opened a testing dialog'
        if len(hits)>previous and profile_latency(): break
        time.sleep(1)
    else: raise AssertionError('Inline ping result was not displayed')
    assert len(hits)>previous, 'Latency test did not send HTTP through the SOCKS fixture'
    assert re.search(r'\d+ ms',xml()), 'Successful latency was not saved in the profile row'
    if cycle==0:
        saved_ping=profile_latency()
        previous=len(hits)
        response_delay=.3
        tap('Check current connection')
        for _ in range(30):
            readings=[n.get('text') for n in ET.fromstring(xml()).iter('node') if re.fullmatch(r'[0-9]+ ms',n.get('text',''))]
            if len(hits)>previous and len(readings)>=2: break
            time.sleep(1)
        else: raise AssertionError('Current connection result was not shown separately')
        assert profile_latency()==saved_ping, 'Current connection test overwrote profile latency'
        assert any(int(value.split()[0])>=250 for value in readings), 'Current probe did not measure the controlled delay'
        response_delay=0.0
        (out/'26-current-connection-ping.png').write_bytes(subprocess.check_output([adb,'exec-out','screencap','-p']))
    tap('Connect or disconnect VPN, Connected')
    wait_for('Connect or disconnect VPN, Not connected')
# A stalled probe must yield to a connection start without a separate cancellation UI.
connect_node=next(n for n in ET.fromstring(xml()).iter('node') if n.get('content-desc')=='Connect or disconnect VPN, Not connected')
a,b,c,d=map(int,re.findall(r'\d+',connect_node.attrib['bounds']))
connect_x,connect_y=(a+c)//2,(b+d)//2
probe_gate.clear()
previous = len(hits)
for node in ET.fromstring(xml()).iter('node'):
    if node.get('text') == 'VPN-smoke':
        a,b,c,d = map(int,re.findall(r'\d+',node.attrib['bounds']))
        y=(b+d)//2
        shell('input','swipe',str((a+c)//2),str(y),'1000',str(y),'350')
        break
time.sleep(1)
tap('Testing')
for _ in range(10):
    if len(hits)>previous: break
    time.sleep(.2)
else: raise AssertionError('Stalled probe did not start')
assert stalled_started and time.monotonic()-stalled_started[-1] < 2.5, 'Probe ended before the connection action'
started = time.monotonic()
shell('input','tap',str(connect_x),str(connect_y))
wait_for('Connect or disconnect VPN, Connected')
assert time.monotonic()-started < 15, 'Connection waited for the stalled probe timeout'
probe_gate.set()
tap('Connect or disconnect VPN, Connected')
wait_for('Connect or disconnect VPN, Not connected')
(out/'vpn-smoke-result.txt').write_text('PASS: Android VPN connected and stopped twice; profile and current-connection HTTP generate_204 probes traversed the local SOCKS fixture; current connection ping stays separate from profile latency; default local SOCKS port rejects unauthenticated access; a connection start preempts a stalled probe.\n')
server.shutdown()
