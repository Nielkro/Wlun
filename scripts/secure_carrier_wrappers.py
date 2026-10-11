"""Require authenticated loopback bridges in mobile wrappers; leave desktop sources intact."""
from pathlib import Path
import sys
kind, root = sys.argv[1], Path(sys.argv[2])
def replace(path, old, new):
    source = path.read_text(encoding="utf-8")
    assert source.count(old) == 1, f"unexpected {path.name} layout"
    path.write_text(source.replace(old,new),encoding="utf-8")
if kind == "olcrtc":
    main=root/"cmd/qthrone-olcrtc/main.go"
    replace(main,'SOCKS     string                     `json:"socks"`',
        'SOCKS     string                     `json:"socks"`\n\tSOCKSUser string `json:"socks_user"`\n\tSOCKSPass string `json:"socks_pass"`')
    replace(main,'key, err := hex.DecodeString(c.Key)',
        'if len(c.SOCKSUser)<1 || len(c.SOCKSUser)>255 || len(c.SOCKSPass)<1 || len(c.SOCKSPass)>255 { return client.Config{}, errors.New("authenticated bridge required") }\n\tkey, err := hex.DecodeString(c.Key)')
    replace(main,'LocalAddr: c.SOCKS, DeviceID:', 'LocalAddr: c.SOCKS, SOCKSUser: c.SOCKSUser, SOCKSPass: c.SOCKSPass, DeviceID:')
    assert 'SOCKSUser' in (root/'pkg/olcrtc/client/client.go').read_text()
elif kind == "openflux":
    main=root/'qthrone_config.go'
    replace(main,'func init() {','var qThroneSOCKSUser, qThroneSOCKSPass string\n\nfunc init() {')
    replace(main,'conf, key, err := qThroneConfig(data, filepath.Dir(os.Args[2]))',
        """var auth struct { User string `json:"socks_user"`; Pass string `json:"socks_pass"` }
    if json.Unmarshal(data,&auth)!=nil || len(auth.User)<1 || len(auth.User)>255 || len(auth.Pass)<1 || len(auth.Pass)>255 {
        log.Fatal("OpenFlux: authenticated bridge required")
    }
    qThroneSOCKSUser,qThroneSOCKSPass=auth.User,auth.Pass
    conf, key, err := qThroneConfig(data, filepath.Dir(os.Args[2]))""")
    replace(root/'main.go','socks5Server := socks5.NewSOCKS5Server(socksAddr, tun)',
        'socks5Server := socks5.NewSOCKS5Server(socksAddr, tun)\n\t\tsocks5Server.SetAuth(qThroneSOCKSUser, qThroneSOCKSPass)')
    assert 'func (s *SOCKS5Server) SetAuth' in (root/'socks5/socks5.go').read_text()
else:
    raise SystemExit("unknown carrier")
