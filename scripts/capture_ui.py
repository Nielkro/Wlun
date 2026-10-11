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
activity = 'app.qthrone.mobile/app.qthrone.mobile.ui.MainActivity'

def shell(*args):
    return subprocess.check_output([adb, 'shell', *args], text=True)

def capture(name):
    time.sleep(2)
    (out / f'{name}.png').write_bytes(subprocess.check_output([adb, 'exec-out', 'screencap', '-p']))

def tap_text(text):
    shell('uiautomator', 'dump', '/sdcard/qthrone-preview.xml')
    xml = shell('cat', '/sdcard/qthrone-preview.xml')
    (out / 'last-screen.xml').write_text(xml)
    for node in ET.fromstring(xml).iter('node'):
        if node.get('text') == text or node.get('content-desc') == text:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
            shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            time.sleep(2)
            return
    raise RuntimeError(f'UI control missing: {text}')

shell('am', 'start', '-n', activity)
time.sleep(8)
capture('01-home-empty')
tap_text('Split routing')
capture('02-routing')
shell('input', 'keyevent', '4')
time.sleep(2)
tap_text('⚙')
capture('03-settings')
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
        time.sleep(3)
        shell('input', 'keyevent', '4')
        time.sleep(2)
    except RuntimeError:
        capture('04-import-dialog')
        break
capture('05-profiles')
