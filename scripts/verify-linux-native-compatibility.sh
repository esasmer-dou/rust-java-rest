#!/usr/bin/env bash
set -euo pipefail

artifact="${1:?usage: verify-linux-native-compatibility.sh <artifact> [maximum-glibc-version]}"
maximum_glibc="${2:-2.17}"

if [[ ! -f "$artifact" ]]; then
  echo "Native artifact not found: $artifact" >&2
  exit 1
fi

versions="$({
  objdump -T "$artifact" 2>/dev/null || true
  readelf --version-info "$artifact" 2>/dev/null || true
} | grep -oE 'GLIBC_[0-9]+\.[0-9]+' | sed 's/GLIBC_//' | sort -Vu)"

if [[ -z "$versions" ]]; then
  echo "No GLIBC symbol versions were found in $artifact" >&2
  exit 1
fi

highest_required="$(printf '%s\n' "$versions" | tail -n 1)"
highest_allowed="$(printf '%s\n%s\n' "$highest_required" "$maximum_glibc" | sort -Vu | tail -n 1)"
if [[ "$highest_allowed" != "$maximum_glibc" ]]; then
  echo "GLIBC compatibility violation: $artifact requires $highest_required; maximum allowed is $maximum_glibc" >&2
  exit 1
fi

echo "Native compatibility OK: $artifact requires GLIBC <= $highest_required (policy <= $maximum_glibc)."
