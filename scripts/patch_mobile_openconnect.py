"""Restore CSTP full-tunnel defaults before the endpoint builds preferred routes."""
import json
import shutil
import subprocess
import sys
from pathlib import Path


def patch(core: Path):
    module = "github.com/sagernet/sing-openconnect"
    version = "v0.1.1-0.20260929151226-29757281a247"
    info = json.loads(subprocess.check_output(["go", "mod", "download", "-json", module + "@" + version], cwd=core))
    if info.get("Sum") != "h1:Ui4XtxNgQiEquK+ch4dYhthKsVlGQpdCdKIpOuJVyog=" or info.get("GoModSum") != "h1:4AKZLVcvY3r54UaK2Gbnm7aN8pOwdLz+y4EP0QFZ5Eg=":
        raise ValueError("OpenConnect source checksum mismatch")
    target = core / ".qthrone" / "openconnect"
    shutil.copytree(info["Dir"], target, dirs_exist_ok=True)
    client = target / "cstp_client.go"
    before = '\tresult.Configuration.ExcludedRoutes, err = parseCSTPRoutes('
    content = client.read_text(encoding="utf-8")
    if content.count(before) != 1:
        raise ValueError("CSTP route parsing changed; review full-tunnel defaults")
    client.chmod(0o644)
    client.write_text(content.replace(before, '\tresult.Configuration.Routes = cstpDefaultRoutes(result.Configuration.Addresses, result.Configuration.Routes)\n' + before), encoding="utf-8", newline="\n")
    for source in (Path(__file__).resolve().parents[1] / "core-patches" / "openconnect").glob("*.go"):
        shutil.copyfile(source, target / source.name)
    subprocess.run(["go", "mod", "edit", "-replace=" + module + "=./.qthrone/openconnect"], cwd=core, check=True)


if __name__ == "__main__":
    patch(Path(sys.argv[1]).resolve())
