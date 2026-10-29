#!/usr/bin/env python3
"""Inspect an experimental ELF; never copy it into default native resources."""
import re
import subprocess
import sys


def output(*command):
    return subprocess.check_output(command, universal_newlines=True)


artifact = sys.argv[1]
header = output("file", artifact)
if "ELF 64-bit LSB shared object, x86-64" not in header:
    raise SystemExit("Expected Linux x86-64 shared library: " + header)
versions = output("readelf", "-W", "--version-info", artifact)
labels = set(re.findall(r"GLIBC_[A-Za-z0-9_.]+", versions))
if not labels:
    raise SystemExit("Missing GLIBC version requirements")
numeric = []
for label in labels:
    if not re.fullmatch(r"GLIBC_\d+(?:\.\d+)+", label):
        raise SystemExit("Unapproved nonnumeric GLIBC requirement: " + label)
    numeric.append(tuple(int(part) for part in label[6:].split(".")))
maximum = max(numeric)
if maximum > (2, 17):
    raise SystemExit("GLIBC requirement exceeds 2.17: " + ".".join(map(str, maximum)))
dependencies = subprocess.run(["ldd", artifact], stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, universal_newlines=True)
if dependencies.returncode or "not found" in dependencies.stdout:
    raise SystemExit("Native dependency resolution failed: " + dependencies.stdout)
symbols = output("nm", "-D", "--defined-only", artifact)
exports = sorted(line.split()[-1] for line in symbols.splitlines()
                 if line.split()[-1].startswith("reactor_"))
required = {
    "reactor_ffm_api_version", "reactor_ffm_capabilities", "reactor_glowroot_record_op_fast",
    "reactor_redis_client_status", "reactor_redis_get_into_slot", "reactor_slab_acquire",
    "reactor_slab_allocated_bytes", "reactor_slab_base_ptr", "reactor_slab_configure",
    "reactor_slab_copy_in", "reactor_slab_copy_out", "reactor_slab_layout_bytes",
    "reactor_slab_read_byte", "reactor_slab_release", "reactor_slab_total_bytes",
    "reactor_slab_write_byte",
}
missing = required.difference(exports)
if missing:
    raise SystemExit("Missing FFM C exports: " + ",".join(sorted(missing)))
print(header.strip())
print("MAX_GLIBC=" + ".".join(map(str, maximum)))
print("FFM_C_EXPORTS=" + str(len(required)))
print("\n".join(exports))
print(dependencies.stdout.strip())
