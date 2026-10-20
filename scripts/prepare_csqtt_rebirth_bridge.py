"""Update the pinned Go SOCKS/packet adapter for CSQTT 2.5.0 Legacy options."""
from pathlib import Path
import sys

p = Path(sys.argv[1]) / "csqtt_bridge.go"
s = p.read_text(encoding="utf-8")

def replace(before, after):
    global s
    assert s.count(before) == 1, before
    s = s.replace(before, after, 1)

replace('if len(c.Hashes) < 1 || len(c.Hashes) > 6 {', '''var extra struct {
        HashSource string `json:"hash_source"`
        Token string `json:"vk_access_token"`
    }
    if json.Unmarshal(bytes, &extra) != nil { return nil, errors.New("invalid CSQTT configuration") }
    automaticJS := extra.HashSource == "auto_js" && len(extra.Token) > 0 && len(extra.Token) <= 4096
    if (!automaticJS && len(c.Hashes) < 1) || len(c.Hashes) > 6 {''')
replace('c.Workers = csqtt.NormalizeWorkers(c.Workers, len(c.Hashes), false)',
        'if c.Workers <= 5 { c.Workers = 5 } else { c.Workers = max(5, min(c.Workers, 100)/10*10) }')
replace('if c.Obfs != "audio" && c.Obfs != "video" {', 'if c.Obfs != "audio" && c.Obfs != "video" && c.Obfs != "unified" {')
replace('if c.VKAnonPath != "vkcalls" && c.VKAnonPath != "legacy" {',
        'if c.VKAnonPath != "vkcalls" && c.VKAnonPath != "legacy" && !(automaticJS && c.VKAnonPath == "auto_js") {')
replace('"--tun-fd-uds", ipc.path', '"--tun-uds", ipc.path')
p.write_text(s, encoding="utf-8", newline="\n")

p = Path(sys.argv[1]) / "internal/csqtt/config.go"
s = p.read_text(encoding="utf-8")
replace('parts := strings.Split(raw, ":")', '''parts := strings.Split(raw, ":")
    // 2.5.0 negotiates workers in an optional sixth field; the IP packet bridge
    // still consumes the same first five fields as previous servers.
    if len(parts) == 6 && strings.HasPrefix(parts[5], "workers=") {
        workers, err := strconv.Atoi(strings.TrimPrefix(parts[5], "workers="))
        if err != nil || workers < 0 || workers > 100 { return c, invalid }
        parts = parts[:5]
    }''')
p.write_text(s, encoding="utf-8", newline="\n")
