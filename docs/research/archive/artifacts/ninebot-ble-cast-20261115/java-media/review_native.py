"""Independently re-run already counted native probes without editing parent outputs."""
from pathlib import Path
import hashlib
import importlib.util
import json
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from elftools.elf.elffile import ELFFile

ROOT=Path(__file__).resolve().parent
source=ROOT.parent/'control-config/emulate_rtp_forward.py'
spec=importlib.util.spec_from_file_location('parent_native_forward',source)
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
raw=(ROOT.parent/'raw/native/librust_lib.so').read_bytes()
assert hashlib.sha256(raw).hexdigest()==module.EXPECTED
forward=[module.validate(raw,bytes((i*37+0x90)&255 for i in range(n))) for n in [0,1,19,20,21,64,128,220,240,244,512,1024,4096]]
forward.append(module.validate(raw,bytes.fromhex('9080123401020304112233440000000100010000000001b3'),True))
initializers=[module.validate_handler_defaults(raw,n) for n in [0,244,247,512,65535,65536,65537]]
with (ROOT.parent/'raw/native/librust_lib.so').open('rb') as stream:
    elf=ELFFile(stream)
    md=Cs(CS_ARCH_ARM64,CS_MODE_ARM)
    sections=[(int(section['sh_addr']),section.data()) for section in elf.iter_sections() if int(section['sh_flags'])&2 and section['sh_type']!='SHT_NOBITS']
    ranges=[(0x2579B8,0x257A2C),(0x27E518,0x27E568),(0x26C05C,0x26C094),
            (0x26C094,0x26C134),(0x2ECD18,0x2ECE00),(0x328B70,0x328BA8)]
    evidence=[]
    for begin,end in ranges:
        data=next(data[begin-start:end-start] for start,data in sections if start<=begin and end<=start+len(data))
        evidence.append({'start':hex(begin),'end_exclusive':hex(end),'sha256':hashlib.sha256(data).hexdigest(),
             'instructions':[f'{ins.address:#x}: {ins.mnemonic} {ins.op_str}' for ins in md.disasm(data,begin)]})
report={'status':'passed','library_sha256':module.EXPECTED,'reviewed_script_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
        'forward_rechecks':len(forward),'initializer_rechecks':len(initializers),
        'counts_are_repeated_existing_cases_not_new_coverage':True,
        'scope':'Re-run unchanged original native function and initializer basic block through parent hooks; stop before writer; no JNI/GATT/authentication/radio/vehicle execution',
        'static_ranges':evidence}
(ROOT/'cross-review-native-validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k!='static_ranges'}))
