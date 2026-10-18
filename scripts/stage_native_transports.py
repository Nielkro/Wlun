"""Install the pinned native binaries; Actions retention does not affect builds."""
import hashlib
import io
import json
import tarfile
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parent.parent
lock = json.loads((root / 'native-transports.lock.json').read_text())
properties = dict(line.split('=', 1) for line in (root / 'nb4a.properties').read_text().splitlines() if '=' in line)
assert properties['THRONE_CORE_REF'] == lock['core_commit'], 'Update the transport lock for the new core pin'
for name, expected in lock['inputs'].items():
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == expected, 'Rebuild transports after changing ' + name
for item in lock['archives']:
    with urllib.request.urlopen(item['url'], timeout=60) as response:
        archive = response.read()
    assert hashlib.sha256(archive).hexdigest() == item['sha256'], 'Native archive checksum mismatch'
    with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as tar:
        files = {}
        for member in tar.getmembers():
            assert member.isfile() and '/' not in member.name and '\\' not in member.name
            files[member.name] = tar.extractfile(member).read()
    assert files['SOURCES.txt'].decode() == item['sources'], 'Transport source pins mismatch'
    for line in files['SHA256SUMS'].decode().splitlines():
        digest, name = line.split()
        assert hashlib.sha256(files[name]).hexdigest() == digest
    assert set(name for name in files if name.endswith('.so')) == {
        'libqwdtt.so', 'libolcrtc.so', 'libopenflux.so', 'libcsqtt_transport.so',
        'libdnstun.so', 'libmasterdns.so', 'libs3bypass.so', 'libslipstream.so', 'libwdttplus.so'}
    for name, data in files.items():
        if name.endswith('.so'):
            output = root / 'deployment/carriers' / item['abi'] / name
        elif name.endswith('-LICENSE') or name == 'SOURCES.txt':
            output = root / 'app/src/main/assets/carrier-licenses' / item['abi'] / name
        else:
            continue
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(data)
    print('Verified native transports:', item['abi'])
