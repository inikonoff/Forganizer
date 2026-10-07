#!/usr/bin/env bash
# Generates a release keystore with random passwords and an encrypted backup.
# Usage: generate-keystore.sh <output-dir>
# Env:   BACKUP_PASSPHRASE (required), KEY_ALIAS_INPUT, CERT_CN, VALIDITY_DAYS
# Writes into <output-dir>: release.jks, alias.txt, store_password.txt (all outside the repo),
# and backup/forganizer-keystore-backup.enc (AES-256, safe to publish as an artifact).
# Nothing secret is ever printed.
set -euo pipefail

out="${1:?output dir required}"
: "${BACKUP_PASSPHRASE:?BACKUP_PASSPHRASE is required}"
alias="${KEY_ALIAS_INPUT:-forganizer}"
cn="${CERT_CN:-Forganizer}"
days="${VALIDITY_DAYS:-10000}"

[[ "$alias" =~ ^[A-Za-z0-9_-]{1,32}$ ]] || { echo "::error::Alias: 1-32 chars, letters, digits, _ or -"; exit 1; }
[[ "$cn" =~ ^[A-Za-z0-9\ ._-]{1,50}$ ]] || { echo "::error::Name: 1-50 chars, letters, digits, space, . _ -"; exit 1; }
[[ "$days" =~ ^[0-9]{3,6}$ ]] || { echo "::error::Validity must be a number of days (at least 100)"; exit 1; }

mkdir -p "$out/backup"
chmod 700 "$out"

STOREPASS="$(openssl rand -hex 20)"
echo "::add-mask::$STOREPASS"
export STOREPASS

# PKCS12: the key password equals the store password.
keytool -genkeypair -keystore "$out/release.jks" -storetype PKCS12 \
  -alias "$alias" -keyalg RSA -keysize 2048 -validity "$days" -dname "CN=$cn" \
  -storepass:env STOREPASS -keypass:env STOREPASS >/dev/null 2>&1

# Verify that the key is really there and can be opened with the password.
keytool -list -keystore "$out/release.jks" -storepass:env STOREPASS -alias "$alias" >/dev/null 2>&1 \
  || { echo "::error::Generated keystore failed verification"; exit 1; }

printf '%s' "$alias" > "$out/alias.txt"
printf '%s' "$STOREPASS" > "$out/store_password.txt"
chmod 600 "$out"/release.jks "$out"/alias.txt "$out"/store_password.txt

# Encrypted backup: keystore + credentials in one tar, protected by BACKUP_PASSPHRASE.
bundle="$(mktemp -d)"
cp "$out/release.jks" "$bundle/release.jks"
{
  echo "KEY_ALIAS=$alias"
  echo "KEYSTORE_PASSWORD=$STOREPASS"
  echo "KEY_PASSWORD=$STOREPASS"
} > "$bundle/credentials.txt"
tar -C "$bundle" -cf "$bundle/bundle.tar" release.jks credentials.txt
openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt \
  -in "$bundle/bundle.tar" -out "$out/backup/forganizer-keystore-backup.enc" -pass env:BACKUP_PASSPHRASE
rm -rf "$bundle"

# Public information only: certificate fingerprint (useful for store consoles).
keytool -list -v -keystore "$out/release.jks" -storepass:env STOREPASS -alias "$alias" 2>/dev/null \
  | grep -E 'SHA256:|Valid from' | sed 's/^ *//' > "$out/fingerprint.txt" || true
echo "Keystore generated for alias '$alias'."
