"""Independently replay sibling cases without overwriting its report."""
from pathlib import Path
import importlib.util, json, hashlib
ROOT=Path(__file__).resolve().parent
p=ROOT.parent/'control-config/emulate_rtp_forward.py'
spec=importlib.util.spec_from_file_location('root_forward_review',p)
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
data=m.LIB.read_bytes();assert hashlib.sha256(data).hexdigest()==m.EXPECTED
original=json.loads((p.parent/'native-forward-validation.json').read_text(encoding='utf-8'))
lengths=[0,1,19,20,21,64,128,220,240,244,512,1024,4096]
cases=[m.validate(data,bytes((i*37+0x90)&255 for i in range(n))) for n in lengths]
cases.append(m.validate(data,bytes.fromhex('9080123401020304112233440000000100010000000001b3'),True))
init=[m.validate_handler_defaults(data,v) for v in (0,244,247,512,65535,65536,65537)]
assert cases==original['cases']
assert init==original['handler_initializer']['cases']
report={'status':'passed','kind':'independent rerun; do not add duplicate cases to aggregate','cases_replayed':len(cases),'initializer_cases_replayed':len(init),'same_as_original_report':True,'script_sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'ELF_sha256':m.EXPECTED}
(ROOT/'root-forward-rerun.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report))
