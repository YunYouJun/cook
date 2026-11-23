"""Check 16 KiB ELF load segment alignment in APK and AAB native libraries."""

import argparse
from pathlib import Path
import struct
import sys
import zipfile


PAGE_SIZE = 16 * 1024
ELF64_HEADER_SIZE = 64
ELF64_PROGRAM_HEADER = struct.Struct("<IIQQQQQQ")
ANDROID_64_BIT_ABIS = {"arm64-v8a", "x86_64"}
PT_LOAD = 1


def library_alignment_error(archive, library):
    with archive.open(library) as stream:
        header = stream.read(ELF64_HEADER_SIZE)
        if len(header) != ELF64_HEADER_SIZE or header[:6] != b"\x7fELF\x02\x01":
            return "invalid little-endian ELF64 header"
        table_offset, = struct.unpack_from("<Q", header, 32)
        entry_size, entry_count = struct.unpack_from("<HH", header, 54)
        table_size = entry_size * entry_count
        if (
            table_offset < ELF64_HEADER_SIZE
            or entry_size < ELF64_PROGRAM_HEADER.size
            or table_offset + table_size > library.file_size
        ):
            return "invalid ELF program header table"
        stream.seek(table_offset)
        table = stream.read(table_size)

    if len(table) != table_size:
        return "truncated ELF program header table"
    has_load_segment = False
    for index in range(entry_count):
        segment_type, _, offset, address, _, _, _, alignment = ELF64_PROGRAM_HEADER.unpack_from(
            table, index * entry_size,
        )
        if segment_type != PT_LOAD:
            continue
        has_load_segment = True
        if alignment < PAGE_SIZE or (address - offset) % PAGE_SIZE != 0:
            return f"LOAD {index} has alignment {alignment:#x}, offset {offset:#x}, address {address:#x}"
    if not has_load_segment:
        return "missing ELF LOAD segment"
    return None


def verify(artifact):
    with zipfile.ZipFile(artifact) as archive:
        libraries = []
        for entry in archive.infolist():
            parts = entry.filename.split("/")
            if len(parts) >= 3 and parts[-3] == "lib" and parts[-2] in ANDROID_64_BIT_ABIS and parts[-1].endswith(".so"):
                libraries.append(entry)
        if not libraries:
            raise ValueError("no 64-bit native libraries found")
        errors = [(entry.filename, library_alignment_error(archive, entry)) for entry in libraries]
    for filename, error in errors:
        if error:
            print(f"{artifact.name}: {filename}: {error}", file=sys.stderr)
    if any(error for _, error in errors):
        return False
    print(f"{artifact.name}: {len(libraries)} 64-bit native libraries aligned to 16 KiB")
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", type=Path, nargs="+", metavar="APK_OR_AAB")
    success = True
    for artifact in parser.parse_args().artifacts:
        try:
            success = verify(artifact) and success
        except (OSError, ValueError, zipfile.BadZipFile) as error:
            print(f"{artifact.name}: {error}", file=sys.stderr)
            success = False
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
