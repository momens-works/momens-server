#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION=0.24.2

# 버전 변경 시 공식 release archive의 SHA-256도 함께 갱신합니다.
case "$(uname -s)/$(uname -m)" in
  Darwin/arm64)
    TARGET=aarch64-apple-darwin
    SHA256=c9d3740ea2d891854d37116c9fba840f37b6e7c89d330e7db84ac333631c4977 ;;
  Darwin/x86_64)
    TARGET=x86_64-apple-darwin
    SHA256=887503a9cff667d322b8d0892b40bf49976eb9507af8483220a3706cdad55978 ;;
  Linux/x86_64)
    TARGET=x86_64-unknown-linux-musl
    SHA256=73657a111819a30c47c08352896796f23d64e4eb2b3ed39b6d32149241566fc5 ;;
  Linux/aarch64)
    TARGET=aarch64-unknown-linux-musl
    SHA256=5d0b0e3aeab240f41920c633a6eaf97599be6eedda034b36e858ede7dba5e535 ;;
  *) echo "Unsupported lychee platform: $(uname -s)/$(uname -m)" >&2; exit 1 ;;
esac

INSTALL_DIR="${SCRIPT_DIR}/../build/tools/lychee-${VERSION}-${TARGET}"
BINARY="${INSTALL_DIR}/lychee"
if [[ ! -x "$BINARY" ]]; then
  mkdir -p "$INSTALL_DIR"
  DOWNLOAD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/momens-lychee.XXXXXX")"
  trap 'rm -rf -- "$DOWNLOAD_DIR"' EXIT
  ARCHIVE="lychee-${TARGET}.tar.gz"
  curl --fail --silent --show-error --location \
    "https://github.com/lycheeverse/lychee/releases/download/lychee-v${VERSION}/${ARCHIVE}" \
    -o "${DOWNLOAD_DIR}/${ARCHIVE}"
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL_SHA256="$(sha256sum "${DOWNLOAD_DIR}/${ARCHIVE}")"
  else
    ACTUAL_SHA256="$(shasum -a 256 "${DOWNLOAD_DIR}/${ARCHIVE}")"
  fi
  if [[ "${ACTUAL_SHA256%% *}" != "$SHA256" ]]; then
    echo "lychee archive checksum mismatch" >&2
    exit 1
  fi
  tar -xzf "${DOWNLOAD_DIR}/${ARCHIVE}" -C "$DOWNLOAD_DIR"
  mv "${DOWNLOAD_DIR}/lychee-${TARGET}/lychee" "$BINARY"
fi

if [[ "$("$BINARY" --version)" != "lychee ${VERSION}" ]]; then
  echo "Unexpected lychee version; remove ${INSTALL_DIR} and retry" >&2
  exit 1
fi
printf '%s\n' "$BINARY"
