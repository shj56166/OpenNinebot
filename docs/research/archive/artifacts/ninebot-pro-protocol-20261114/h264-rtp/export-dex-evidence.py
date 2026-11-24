"""Export exact original DEX instruction identity and selected method disassembly."""
import hashlib
import json
import os
from pathlib import Path
import zipfile
from loguru import logger
from androguard.core.dex import DEX

logger.remove()
root = Path(__file__).resolve().parent
apk = Path(os.environ['TEMP']) / 'ninebot-pro-v0.6.1.apk'
raw_apk = apk.read_bytes()
raw_dex = zipfile.ZipFile(apk).read('classes.dex')
dex = DEX(raw_dex)
keep = {'Lsd;': {'h', 'i', 'k', 'l'}, 'Lv01;': {'l', 'L', 'd0'}, 'Lo31;': {'a'}}
summary = {'apk_sha256': hashlib.sha256(raw_apk).hexdigest(),
           'classes_dex_sha256': hashlib.sha256(raw_dex).hexdigest(), 'methods': []}
lines = []
for cls in dex.get_classes():
    if cls.get_name() not in keep:
        continue
    for method in cls.get_methods():
        if method.get_name() not in keep[cls.get_name()]:
            continue
        code = method.get_code().get_bc().get_raw()
        name = f'{cls.get_name()}->{method.get_name()}{method.get_descriptor()}'
        summary['methods'].append({'method': name, 'instruction_sha256': hashlib.sha256(code).hexdigest(),
                                   'instruction_bytes': len(code)})
        if cls.get_name() == 'Lo31;':
            continue
        lines.append(f'\n{name}\n')
        lines.extend(f'{offset:04x} {ins.get_name():24} {ins.get_output()}'
                     for offset, ins in method.get_instructions_idx())
(root / 'dex-evidence.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(root / 'dex-instructions.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')
print(json.dumps(summary, ensure_ascii=False))
