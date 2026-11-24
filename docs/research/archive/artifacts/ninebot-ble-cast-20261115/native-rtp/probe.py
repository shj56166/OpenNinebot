"""Focused, read-only AArch64 ELF investigation of the frozen official JNI library."""
from pathlib import Path
import argparse
import bisect
import hashlib
import json
import re
import struct
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from elftools.elf.elffile import ELFFile
from elftools.dwarf.callframe import FDE

ROOT = Path(__file__).resolve().parent
WORKSPACE = ROOT.parents[2]
LIB = ROOT.parent / 'raw/native/librust_lib.so'

class Native:
    def __init__(self, path=LIB):
        self.path = Path(path)
        cache_path = ROOT / 'elf-index.json'
        cache = json.loads(cache_path.read_text(encoding='utf-8')) if cache_path.exists() else None
        with self.path.open('rb') as stream:
            elf = ELFFile(stream)
            self.sections = [(int(s['sh_addr']), s.data(), s.name)
                             for s in elf.iter_sections()
                             if int(s['sh_flags']) & 2 and s['sh_type'] != 'SHT_NOBITS']
            if cache and cache['sha256'] == hashlib.sha256(self.path.read_bytes()).hexdigest():
                self.fdes = cache['fdes']
                self.relocs = {int(k): v for k, v in cache['relocs'].items()}
                self.symbols = cache['symbols']
            else:
                self.fdes = sorted((int(e['initial_location']), int(e['address_range']))
                                   for e in elf.get_dwarf_info().EH_CFI_entries() if isinstance(e, FDE))
                self.relocs = {}
                for section in elf.iter_sections():
                    if section['sh_type'] == 'SHT_RELA':
                        for relocation in section.iter_relocations():
                            if relocation['r_info_type'] == 1027:
                                self.relocs[int(relocation['r_offset'])] = int(relocation['r_addend'])
                self.symbols = {s.name: int(s['st_value']) for s in elf.get_section_by_name('.dynsym').iter_symbols()}
                cache_path.write_text(json.dumps({'sha256': hashlib.sha256(self.path.read_bytes()).hexdigest(),
                                                  'fdes': self.fdes, 'relocs': self.relocs,
                                                  'symbols': self.symbols}), encoding='utf-8')
        self.starts = [a for a, _ in self.fdes]
        self.base, self.code, _ = next(s for s in self.sections if s[2] == '.text')
        self.md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)

    def read(self, address, size):
        for start, data, _ in self.sections:
            if start <= address and address + size <= start + len(data):
                return data[address-start:address-start+size]
        return b''

    def qword(self, address):
        if address in self.relocs:
            return self.relocs[address]
        data = self.read(address, 8)
        return struct.unpack('<Q', data)[0] if len(data) == 8 else None

    def owner(self, address):
        index = bisect.bisect_right(self.starts, address) - 1
        if index >= 0:
            start, size = self.fdes[index]
            if address < start + size:
                return start, size
        return address, 0x100

    def string(self, address, limit=160):
        data = self.read(address, limit).split(b'\0', 1)[0]
        if len(data) >= 3 and all(32 <= byte < 127 for byte in data):
            return data.decode('ascii')
        return ''

    def dump(self, address):
        start, size = self.owner(address)
        lines = []
        registers = {}
        calls = []
        for ins in self.md.disasm(self.read(start, size), start):
            annotation = ''
            m, op = ins.mnemonic, ins.op_str
            if m in ('adrp', 'adr'):
                match = re.fullmatch(r'(x\d+), #0x([0-9a-f]+)', op)
                if match:
                    registers[match[1]] = int(match[2], 16)
            elif m in ('add', 'sub'):
                match = re.fullmatch(r'(x\d+), (x\d+), #(0x[0-9a-f]+|\d+)', op)
                if match and match[2] in registers:
                    address = registers[match[2]] + int(match[3], 0) * (1 if m == 'add' else -1)
                    registers[match[1]] = address
                    annotation = f' ; address={address:#x}'
                    value = self.string(address)
                    if value:
                        annotation += ' ' + repr(value)
            elif m in ('ldr', 'ldp'):
                match = re.fullmatch(r'(x\d+)(?:, (x\d+))?, \[(x\d+)(?:, #(0x[0-9a-f]+|\d+))?\]', op)
                if match and match[3] in registers:
                    address = registers[match[3]] + int(match[4] or '0', 0)
                    value = self.qword(address)
                    if value is not None:
                        registers[match[1]] = value
                        annotation = f' ; [{address:#x}]={value:#x}'
                        if match[2]:
                            value2 = self.qword(address + 8)
                            if value2 is not None:
                                registers[match[2]] = value2
                                annotation += f', {value2:#x}'
            elif m == 'mov':
                match = re.fullmatch(r'([xw]\d+), #(0x[0-9a-f]+|-?\d+)', op)
                if match:
                    registers['x' + match[1][1:]] = int(match[2], 0)
                else:
                    match = re.fullmatch(r'([xw]\d+), ([xw]\d+)', op)
                    if match:
                        source = 'x' + match[2][1:]
                        destination = 'x' + match[1][1:]
                        if source in registers:
                            registers[destination] = registers[source]
                        else:
                            registers.pop(destination, None)
            elif m in ('bl', 'blr'):
                if m == 'bl':
                    calls.append({'instruction': ins.address, 'target': int(op[1:], 16)})
                elif op in registers:
                    annotation += f' ; resolved={registers[op]:#x}'
                for ptr, length in [('x0', 'x1'), ('x1', 'x2')]:
                    p, n = registers.get(ptr, 0), registers.get(length, 0)
                    if 1 <= n <= 200:
                        data = self.read(p, n)
                        if len(data) == n and all(32 <= c < 127 for c in data):
                            annotation += f' ; string_arg={data.decode("ascii")!r}'
                registers = {k: v for k, v in registers.items() if int(k[1:]) >= 19}
            lines.append(f'{ins.address:#x} {m} {op}{annotation}')
        path = ROOT / f'fn-{start:x}.asm.txt'
        path.write_text('\n'.join(lines) + '\n', encoding='utf-8')
        return {'function': hex(start), 'size': hex(size), 'path': str(path), 'calls': calls}

    def callers(self, target):
        callers = []
        for offset in range(0, len(self.code) - 3, 4):
            word = struct.unpack_from('<I', self.code, offset)[0]
            if word & 0xfc000000 in (0x94000000, 0x14000000):
                relative = word & 0x3ffffff
                if relative & 0x2000000:
                    relative -= 0x4000000
                address = self.base + offset
                if address + relative * 4 == target:
                    owner, size = self.owner(address)
                    callers.append({'instruction': hex(address), 'function': hex(owner),
                                    'kind': 'bl' if word & 0xfc000000 == 0x94000000 else 'b'})
        return callers

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['dump', 'callers', 'around', 'manifest', 'pointers'])
    parser.add_argument('addresses', nargs='*')
    args = parser.parse_args()
    native = Native()
    values = [int(a, 0) for a in args.addresses]
    if args.command == 'manifest':
        result = {'path': str(LIB), 'sha256': hashlib.sha256(LIB.read_bytes()).hexdigest(),
                  'size': LIB.stat().st_size, 'function_fdes': len(native.fdes)}
        (ROOT / 'binary-manifest.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    elif args.command == 'dump':
        result = [native.dump(address) for address in values]
        for row in result:
            row['call_count'] = len(row.pop('calls'))
    elif args.command == 'callers':
        result = {hex(address): native.callers(address) for address in values}
    elif args.command == 'pointers':
        result = {hex(address): [{'slot': hex(slot), 'owner': [hex(n) for n in native.owner(slot)]}
                                for slot, value in native.relocs.items() if value == address]
                  for address in values}
    elif args.command == 'around':
        result = []
        for address in values:
            index = bisect.bisect_right(native.starts, address) - 1
            result.extend(native.dump(start) for start, _ in native.fdes[max(0,index-3):index+5])
        for row in result:
            row['call_count'] = len(row.pop('calls'))
    print(json.dumps(result, indent=2))

if __name__ == '__main__':
    main()
