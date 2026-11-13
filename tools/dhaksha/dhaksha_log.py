#!/usr/bin/env python3
"""Dhaksha log: turns a DhakshaGroundControl crash into a readable call stack.

Give it what the crash left behind and the app build it came from:

    python dhaksha_log.py FlightLog_2026-11-13_10-15-02.txt --apk DhakshaGroundControl-arm64-v8a.apk
    python dhaksha_log.py FlightLog_....txt --symbols DhakshaGroundControl-arm64-v8a-symbols.so.xz
    python dhaksha_log.py tombstone_03 --apk DhakshaGroundControl-arm64-v8a.apk
    python dhaksha_log.py core --exe QGroundControl          (Linux desktop build, needs gdb)

What it reads:
  * the flight log the app writes to its Logs folder (its "=== CRASH" report),
  * an Android tombstone or the crash lines of adb logcat ("#00 pc 0001234  /.../libX.so"),
  * a Linux core dump of the desktop build.

Where the function names come from:
  --apk      the APK that was installed. Enough for most functions.
  --symbols  the symbols file of the same build (kept next to the APKs on the release page). Also names the
             functions the APK does not list. May be the .so or the .so.xz.

The APK or symbols file must be from the same build as the crash: the tool checks this against the names the
app itself wrote into the report and says so when they do not match.

Python standard library only; runs on Windows, Linux and macOS.
"""

import argparse
import bisect
import io
import lzma
import re
import shutil
import struct
import subprocess
import sys
import zipfile

CRASH_MARK = "=== CRASH"
SIGNALS = {
    "SIGSEGV": "the app read or wrote memory it does not own (bad or null pointer, object already deleted)",
    "SIGABRT": "the app stopped itself (failed check, uncaught exception, or memory corruption found by the system)",
    "SIGBUS": "bad memory access (misaligned address or a mapped file that went away)",
    "SIGFPE": "arithmetic error (integer divide by zero)",
    "SIGILL": "the processor met an instruction it cannot run (corrupted code or jump to a bad address)",
}
# "  0x7b1c2d3e4f  libQGroundControl_arm64-v8a.so + 0x1a2b3c  (symbol)"
FLIGHT_FRAME = re.compile(r"^(Crashed at\s+|\s+)0x([0-9a-fA-F]+)(?:\s+(\S+) \+ 0x([0-9a-fA-F]+))?(?:\s+\((.+)\))?\s*$")
# "    #03 pc 00000000001a2b3c  /data/app/.../lib/arm64/libQGroundControl_arm64-v8a.so (symbol+12) (BuildId: ..)"
TOMBSTONE_FRAME = re.compile(
    r"#(\d+)\s+pc\s+([0-9a-fA-F]+)\s+(\S+)(?:\s+\((?!BuildId)(.+?)(?:\+\d+)?\))?(?:\s+\(BuildId[^)]*\))?\s*$")


class Symbols:
    """Function addresses and names of one ELF library (.symtab when present, and .dynsym)."""

    def __init__(self, name, data):
        self.name = name
        self.functions = []      # sorted (address, size, name)
        self.full = False        # has .symtab: every function, not only the exported ones
        self._parse(data)
        self.functions.sort()
        self._starts = [f[0] for f in self.functions]
        self._by_name = {}
        for address, size, symbol in self.functions:
            self._by_name.setdefault(symbol, (address, size))

    def _parse(self, data):
        if data[:4] != b"\x7fELF":
            raise ValueError(f"{self.name} is not an ELF library")
        is64 = data[4] == 2
        end = "<" if data[5] == 1 else ">"
        machine = struct.unpack_from(end + "H", data, 18)[0]
        thumb = machine == 40    # ARM: bit 0 of a function address marks Thumb code
        if is64:
            shoff, = struct.unpack_from(end + "Q", data, 0x28)
            shentsize, shnum, _ = struct.unpack_from(end + "HHH", data, 0x3A)
        else:
            shoff, = struct.unpack_from(end + "I", data, 0x20)
            shentsize, shnum, _ = struct.unpack_from(end + "HHH", data, 0x2E)
        sections = []
        for i in range(shnum):
            at = shoff + i * shentsize
            if is64:
                _, kind, _, _, offset, size, link, _, _, entsize = struct.unpack_from(end + "IIQQQQIIQQ", data, at)
            else:
                _, kind, _, _, offset, size, link, _, _, entsize = struct.unpack_from(end + "IIIIIIIIII", data, at)
            sections.append((kind, offset, size, link, entsize))
        for kind, offset, size, link, entsize in sections:
            if kind not in (2, 11) or entsize == 0 or link >= len(sections):     # SHT_SYMTAB, SHT_DYNSYM
                continue
            if kind == 2:
                self.full = True
            _, str_offset, str_size, _, _ = sections[link]
            strings = data[str_offset:str_offset + str_size]
            for at in range(offset, offset + size, entsize):
                if is64:
                    name_at, info, _, shndx, value, sym_size = struct.unpack_from(end + "IBBHQQ", data, at)
                else:
                    name_at, value, sym_size, info, _, shndx = struct.unpack_from(end + "IIIBBH", data, at)
                if (info & 0xF) != 2 or shndx == 0 or value == 0:    # STT_FUNC, defined
                    continue
                if thumb:
                    value &= ~1
                stop = strings.find(b"\0", name_at)
                self.functions.append((value, sym_size, strings[name_at:stop].decode("utf-8", "replace")))

    def find(self, address):
        """(name, offset inside the function) for an address, or None."""
        i = bisect.bisect_right(self._starts, address) - 1
        if i < 0:
            return None
        start, size, name = self.functions[i]
        if size and address >= start + size:
            return None
        if not size and address - start > 0x10000:
            return None
        return name, address - start

    def address_of(self, name):
        return self._by_name.get(name)


def demangle(names):
    """C++ names in readable form: with c++filt when installed, else a simple reading of plain nested names."""
    tool = shutil.which("c++filt") or shutil.which("llvm-cxxfilt")
    if tool and names:
        try:
            out = subprocess.run([tool], input="\n".join(names), capture_output=True, text=True, timeout=30)
            lines = out.stdout.split("\n")
            if out.returncode == 0 and len(lines) >= len(names):
                return dict(zip(names, lines))
        except (OSError, subprocess.SubprocessError):
            pass
    return {name: simple_demangle(name) for name in names}


def simple_demangle(name):
    match = re.match(r"^_ZN(K?)", name)
    if not match:
        return name
    rest, parts = name[match.end():], []
    while rest and rest[0].isdigit():
        digits = re.match(r"\d+", rest).group()
        length = int(digits)
        parts.append(rest[len(digits):len(digits) + length])
        rest = rest[len(digits) + length:]
    if not parts:
        return name
    if rest[:2] in ("C1", "C2"):
        parts.append(parts[-1])
    elif rest[:2] in ("D0", "D1", "D2"):
        parts.append("~" + parts[-1])
    return "::".join(parts) + "()"


def load_symbol_files(apk, symbol_paths):
    """{library file name: Symbols}; a symbols file replaces the APK's copy of the same library."""
    libraries = {}
    if apk:
        with zipfile.ZipFile(apk) as archive:
            for entry in archive.namelist():
                if entry.startswith("lib/") and entry.endswith(".so"):
                    base = entry.rsplit("/", 1)[1]
                    try:
                        libraries[base] = Symbols(base, archive.read(entry))
                    except (ValueError, struct.error):
                        pass
    for path in symbol_paths or []:
        with open(path, "rb") as handle:
            data = handle.read()
        if path.endswith(".xz"):
            data = lzma.decompress(data)
        symbols = Symbols(path, data)
        base = re.sub(r"\.xz$", "", path.replace("\\", "/").rsplit("/", 1)[-1])
        # DhakshaGroundControl-arm64-v8a-symbols.so -> libQGroundControl_arm64-v8a.so
        match = re.match(r"DhakshaGroundControl-(.+)-symbols\.so$", base)
        if match:
            base = f"libQGroundControl_{match.group(1)}.so"
        symbols.name = base
        libraries[base] = symbols
    return libraries


def parse_flight_log(text):
    """[(lines before the crash, header line, frames)]; a frame is (label, library, offset, symbol the app wrote)."""
    lines = text.splitlines()
    crashes = []
    for i, line in enumerate(lines):
        if not line.startswith(CRASH_MARK):
            continue
        frames = []
        crash_pc = None
        for follow in lines[i + 1:]:
            if follow.startswith("=== End of crash report"):
                break
            match = FLIGHT_FRAME.match(follow)
            if not match:
                continue
            pc = int(match.group(2), 16)
            frame = (pc, match.group(3), int(match.group(4), 16) if match.group(4) else None, match.group(5))
            if match.group(1).startswith("Crashed"):
                crash_pc = frame
            else:
                frames.append(frame)
        # The stack starts inside the crash handler: keep from the crashing address on
        if crash_pc:
            at = next((n for n, frame in enumerate(frames) if frame[0] == crash_pc[0]), None)
            frames = [crash_pc] + (frames[at + 1:] if at is not None else frames)
        before = [text for text in lines[:i] if text.strip()][-15:]
        crashes.append((before, line, [(lib, offset, symbol) for _, lib, offset, symbol in frames]))
    return lines, crashes


def parse_tombstone(text):
    frames = []
    seen_zero = False
    for line in text.splitlines():
        match = TOMBSTONE_FRAME.search(line)
        if not match:
            continue
        if int(match.group(1)) == 0:
            if seen_zero:
                break            # only the crashing thread, which comes first
            seen_zero = True
        frames.append((match.group(3).rsplit("/", 1)[-1], int(match.group(2), 16), match.group(4)))
    return frames


def check_same_build(frames, libraries):
    """Compares the names the app wrote with the symbols given; returns (checked, wrong)."""
    checked = wrong = 0
    for library, offset, written in frames:
        symbols = libraries.get(library)
        if not symbols or offset is None or not written:
            continue
        where = symbols.address_of(written)
        if where is None:
            continue
        checked += 1
        start, size = where
        if not (start <= offset <= start + max(size, 1)):
            wrong += 1
    return checked, wrong


def print_stack(frames, libraries):
    resolved = []
    for n, (library, offset, written) in enumerate(frames):
        name = None
        symbols = libraries.get(library) if library else None
        if symbols and offset is not None:
            # Every frame after the first holds a return address: the call is the instruction before it
            found = symbols.find(offset - (1 if n else 0))
            if found:
                name = found[0]
        resolved.append(name or written)
    readable = demangle(sorted({name for name in resolved if name}))
    for n, ((library, offset, _), name) in enumerate(zip(frames, resolved)):
        where = f"{library} + 0x{offset:x}" if library and offset is not None else (library or "unknown")
        print(f"  #{n:<2} {readable.get(name, name) if name else '(no name)':<70}  [{where}]")
    ours = [(n, name) for n, ((library, _, _), name) in enumerate(zip(frames, resolved))
            if name and library and "QGroundControl" in library]
    if ours:
        n, name = ours[0]
        print(f"\nFirst DhakshaGroundControl function on the stack: #{n} {readable.get(name, name)}")
    missing = sorted({library for (library, offset, _), name in zip(frames, resolved)
                      if library and not name and library not in libraries})
    if missing:
        print("No names for: " + ", ".join(missing) + " (system or Qt libraries not in the APK / symbols given)")


def handle_core(core, exe):
    gdb = shutil.which("gdb")
    if not gdb:
        print("A core dump needs gdb: install it (sudo apt install gdb) and run again.")
        return 1
    if not exe:
        print("A core dump needs the program it came from: add  --exe path/to/QGroundControl")
        return 1
    result = subprocess.run([gdb, "-batch", "-ex", "set pagination off", "-ex", "info signal", "-ex", "bt",
                             "-ex", "echo \\n=== All threads ===\\n", "-ex", "thread apply all bt 25", exe, core],
                            capture_output=True, text=True)
    print(result.stdout)
    if result.returncode != 0:
        print(result.stderr, file=sys.stderr)
    return result.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("crash", help="flight log, tombstone / logcat text, or core dump")
    parser.add_argument("--apk", help="the APK the crash came from")
    parser.add_argument("--symbols", action="append", help="symbols file of the same build (.so or .so.xz)")
    parser.add_argument("--exe", help="for a core dump: the program it came from")
    args = parser.parse_args()

    with open(args.crash, "rb") as handle:
        raw = handle.read()
    if raw[:4] == b"\x7fELF":
        return handle_core(args.crash, args.exe)
    text = raw.decode("utf-8", "replace")
    libraries = load_symbol_files(args.apk, args.symbols)
    if not libraries:
        print("No --apk or --symbols given: showing only the names the app wrote itself.\n")

    lines, crashes = parse_flight_log(text)
    if not crashes:
        frames = parse_tombstone(text)
        if not frames:
            started = [line for line in lines if "started on" in line or "previous run" in line.lower()]
            print("No crash report in this file.")
            for line in started[:4]:
                print("  " + line)
            return 0
        crashes = [([], "Android crash (tombstone / logcat)", frames)]

    for before, header, frames in crashes:
        print("=" * 100)
        started = next((line for line in lines if "started on" in line), None)
        if started:
            print(started)
        print(header)
        for signal, meaning in SIGNALS.items():
            if signal in header:
                print(f"  {signal}: {meaning}")
        if "fault address 0x0," in header or re.search(r"fault address 0x[0-9a-f]{1,3},", header):
            print("  The fault address is (close to) zero: a null pointer was used.")
        checked, wrong = check_same_build(frames, libraries)
        if wrong:
            print(f"\n*** WARNING: {wrong} of {checked} names the app wrote do not match this APK / symbols file. ***")
            print("*** It is from a DIFFERENT BUILD than the crash: the names below are not reliable.       ***")
        elif checked:
            print(f"\nSame build confirmed ({checked} names the app wrote match the APK / symbols file).")
        print("\nCall stack, crashing function first:")
        print_stack(frames, libraries)
        if before:
            print("\nLast lines before the crash:")
            for line in before:
                print("  " + line)
    if libraries and not any(symbols.full for symbols in libraries.values()):
        print("\nNames come from the APK (exported functions). For the remaining ones use --symbols with the\n"
              "symbols file of the same build.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
