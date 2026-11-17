#!/bin/sh
# Installs spring-gen on Linux, macOS, or Windows (Git Bash / MSYS / WSL).
#
#   curl -fsSL https://raw.githubusercontent.com/teelab1329/spring-gen/main/scripts/install.sh | sh
#   curl -fsSL .../install.sh | sh -s -- --version v1.0.0 --dir /usr/local/bin
#
# Options:
#   --version, -v  release tag to install (default: latest)
#   --dir, -d      install directory (default: $HOME/.local/bin)
#   --help, -h     show this help
#
# Environment:
#   SPRING_GEN_VERSION      same as --version
#   SPRING_GEN_INSTALL_DIR  same as --dir
#   SPRING_GEN_BASE_URL     override the download base (tests or a mirror)
set -eu

REPO="teelab1329/spring-gen"
BINARY="spring-gen"

usage() {
	cat <<'EOF'
Install spring-gen.

  install.sh [--version <tag>] [--dir <path>]

  --version, -v  release tag (default: latest)
  --dir, -d      install directory (default: $HOME/.local/bin)
  --help, -h     show this help

The download is verified against the release checksums.txt before installing.
EOF
}

VERSION="${SPRING_GEN_VERSION:-}"
INSTALL_DIR="${SPRING_GEN_INSTALL_DIR:-}"

while [ $# -gt 0 ]; do
	case "$1" in
		--version|-v)
			[ $# -ge 2 ] || { echo "install: --version needs a value" >&2; exit 2; }
			VERSION="$2"
			shift 2
			;;
		--dir|-d)
			[ $# -ge 2 ] || { echo "install: --dir needs a value" >&2; exit 2; }
			INSTALL_DIR="$2"
			shift 2
			;;
		--help|-h)
			usage
			exit 0
			;;
		*)
			echo "install: unknown option '$1'" >&2
			usage >&2
			exit 2
			;;
	esac
done

if [ -n "${SPRING_GEN_BASE_URL:-}" ]; then
	BASE_URL="$SPRING_GEN_BASE_URL"
elif [ -n "$VERSION" ]; then
	BASE_URL="https://github.com/$REPO/releases/download/$VERSION"
else
	BASE_URL="https://github.com/$REPO/releases/latest/download"
fi

[ -n "$INSTALL_DIR" ] || INSTALL_DIR="$HOME/.local/bin"

detect_os() {
	case "$(uname -s)" in
		Linux) echo linux ;;
		Darwin) echo darwin ;;
		MINGW*|MSYS*|CYGWIN*) echo windows ;;
		*)
			echo "install: unsupported OS '$(uname -s)'" >&2
			exit 1
			;;
	esac
}

detect_arch() {
	case "$(uname -m)" in
		x86_64|amd64) echo amd64 ;;
		arm64|aarch64) echo arm64 ;;
		*)
			echo "install: unsupported architecture '$(uname -m)'" >&2
			exit 1
			;;
	esac
}

download() {
	if command -v curl >/dev/null 2>&1; then
		curl -fsSL "$1" -o "$2"
	elif command -v wget >/dev/null 2>&1; then
		wget -q "$1" -O "$2"
	else
		echo "install: curl or wget is required" >&2
		exit 1
	fi
}

sha256() {
	if command -v sha256sum >/dev/null 2>&1; then
		sha256sum "$1" | awk '{print $1}'
	elif command -v shasum >/dev/null 2>&1; then
		shasum -a 256 "$1" | awk '{print $1}'
	else
		echo "install: sha256sum or shasum is required to verify the download" >&2
		exit 1
	fi
}

OS="$(detect_os)"
ARCH="$(detect_arch)"
if [ "$OS" = windows ]; then
	EXT="zip"
	EXE=".exe"
else
	EXT="tar.gz"
	EXE=""
fi
ASSET="${BINARY}_${OS}_${ARCH}.${EXT}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

echo "install: downloading $ASSET from $BASE_URL"
if ! download "$BASE_URL/$ASSET" "$TMP/$ASSET"; then
	echo "install: download failed." >&2
	echo "install: if the repository is private, use 'gh release download' or make it public." >&2
	exit 1
fi
download "$BASE_URL/checksums.txt" "$TMP/checksums.txt"

EXPECTED="$(awk -v asset="$ASSET" '$2 == asset { print $1 }' "$TMP/checksums.txt" | head -n 1)"
[ -n "$EXPECTED" ] || {
	echo "install: checksums.txt has no entry for $ASSET" >&2
	exit 1
}
ACTUAL="$(sha256 "$TMP/$ASSET")"
if [ "$EXPECTED" != "$ACTUAL" ]; then
	echo "install: checksum mismatch for $ASSET" >&2
	echo "  expected $EXPECTED" >&2
	echo "  actual   $ACTUAL" >&2
	exit 1
fi

mkdir -p "$TMP/extract"
case "$EXT" in
	tar.gz)
		tar -xzf "$TMP/$ASSET" -C "$TMP/extract"
		;;
	zip)
		if ! command -v unzip >/dev/null 2>&1; then
			echo "install: unzip is required on Windows; use scripts/install.ps1 instead" >&2
			exit 1
		fi
		unzip -q "$TMP/$ASSET" -d "$TMP/extract"
		;;
esac

SRC="$TMP/extract/$BINARY$EXE"
if [ ! -f "$SRC" ]; then
	echo "install: archive did not contain $BINARY$EXE" >&2
	exit 1
fi

mkdir -p "$INSTALL_DIR"
DEST="$INSTALL_DIR/$BINARY$EXE"
cp "$SRC" "$DEST"
chmod 0755 "$DEST"

echo "install: installed $DEST"
"$DEST" version || true

case ":$PATH:" in
	*":$INSTALL_DIR:"*) ;;
	*)
		echo "install: add $INSTALL_DIR to PATH to run '$BINARY' directly:"
		echo "  export PATH=\"$INSTALL_DIR:\$PATH\""
		;;
esac
