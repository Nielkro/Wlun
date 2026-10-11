"""Capture an isolated emulator; sample profiles never initiate a connection."""
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

adb = os.path.join(os.environ['ANDROID_HOME'], 'platform-tools', 'adb')
out = Path('ui-screenshots')
out.mkdir(exist_ok=True)

def shell(*args):
    return subprocess.check_output([adb, 'shell', *args], text=True)

def capture(name):
    time.sleep(2)
    (out / f'{name}.png').write_bytes(subprocess.check_output([adb, 'exec-out', 'screencap', '-p']))

def screen_xml():
    shell('uiautomator', 'dump', '/sdcard/qthrone-preview.xml')
    return shell('cat', '/sdcard/qthrone-preview.xml')

def tap_text(text):
    xml = screen_xml()
    (out / 'last-screen.xml').write_text(xml)
    for node in ET.fromstring(xml).iter('node'):
        if node.get('text') == text or node.get('content-desc') == text:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
            shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            time.sleep(2)
            return
    raise RuntimeError(f'UI control missing: {text}')

packages = [line.removeprefix('package:').strip() for line in shell('pm', 'list', 'packages').splitlines()
            if line.startswith('package:app.qthrone.mobile')]
assert len(packages) == 1, 'Expected one isolated qThrone installation'
activity = packages[0] + '/app.qthrone.mobile.ui.MainActivity'
shell('pm', 'grant', packages[0], 'android.permission.POST_NOTIFICATIONS')
shell('dumpsys', 'deviceidle', 'whitelist', '+' + packages[0])
shell('am', 'force-stop', packages[0])
shell('logcat', '-c')
time.sleep(3)
shell('am', 'start', '-n', activity)
time.sleep(8)
capture('01-home-empty')
tap_text('Settings')
capture('03-settings')
tap_text('Split routing')
capture('02-routing')
shell('input', 'keyevent', '4')
time.sleep(2)
for label in ('Finland-demo', 'Sweden-demo', 'Germany-demo'):
    # Documentation-only TEST-NET address, never a real account or server.
    shell('am', 'start', '-n', activity, '-a', 'android.intent.action.VIEW', '-d',
          f'socks5://192.0.2.1:1080#{label}')
    time.sleep(2)
    # Existing import flow asks for confirmation; retain a diagnostic screenshot if it differs.
    try:
        tap_text('Yes')
        for attempt in range(12):
            if label in screen_xml() and 'Confirm you want' not in screen_xml():
                break
            time.sleep(1)
        shell('input', 'keyevent', '4')
        time.sleep(2)
    except RuntimeError:
        capture('04-import-dialog')
        break
capture('05-profiles')
profile_xml = screen_xml()
(out / 'profiles-screen.xml').write_text(profile_xml)
# This is a fresh CI emulator with documentation-only fixtures, never the user's device.
(out / 'emulator-logcat.txt').write_text(shell('logcat', '-d'))
for name in ('core.log', 'core.log.prev'):
    try:
        (out / name).write_text(shell('run-as', packages[0], 'cat', 'files/' + name))
    except subprocess.CalledProcessError:
        pass
assert 'Finland-demo' in profile_xml, 'Imported demo profile is missing from the home screen'

# Controlled local subscription, including metadata. No external account or server is contacted.
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import base64
class Fixture(BaseHTTPRequestHandler):
    def do_GET(self):
        body=b"socks5://192.0.2.2:1080#Subscription-Finland\nsocks5://192.0.2.3:1080#Subscription-Sweden"
        self.send_response(200)
        self.send_header('Content-Type','text/plain')
        self.send_header('Subscription-Userinfo','upload=524288; download=1048576; total=10485760; expire=2000000000')
        self.end_headers();self.wfile.write(body)
    def log_message(self,*args): pass
server=ThreadingHTTPServer(('127.0.0.1',8866),Fixture)
threading.Thread(target=server.serve_forever,daemon=True).start()
subprocess.check_call([adb,'reverse','tcp:8866','tcp:8866'])
payload=base64.b64encode(b'http://127.0.0.1:8866/demo#Demo subscription').decode()
shell('am','start','-n',activity,'-a','android.intent.action.VIEW','-d','throne://addsub/'+payload)
time.sleep(2);tap_text('OK')
for attempt in range(15):
    xml=screen_xml()
    if 'Subscription-Finland' in xml: break
    time.sleep(1)
assert 'Subscription-Finland' in xml,'Subscription was not added to the dashboard'
capture('06-subscription-expanded')
tap_text('Demo subscription');capture('07-subscription-collapsed')
assert 'Subscription-Finland' not in screen_xml(),'Collapse did not hide subscription profiles'
tap_text('Demo subscription')

def profile_bounds(label):
    for node in ET.fromstring(screen_xml()).iter('node'):
        if node.get('text')==label:
            return list(map(int,re.findall(r'\d+',node.attrib['bounds'])))
    raise RuntimeError('Swipe fixture profile missing')
bounds=profile_bounds('Finland-demo');x1,y1,x2,y2=bounds;y=(y1+y2)//2
shell('input','swipe',str((x1+x2)//2),str(y),str(x2+140),str(y),'350')
capture('08-profile-swipe-actions')
shell('input','tap',str(x2),str(y))
shell('input','swipe',str(x2-40),str(y),str(x1+20),str(y),'350')
capture('09-profile-swipe-delete')
server.shutdown()
