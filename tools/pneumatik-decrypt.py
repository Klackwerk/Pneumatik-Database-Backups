#!/usr/bin/env python3
"""Decrypt a Pneumatik archive without Pneumatik.

Pneumatik's stored archives are ordinary zip files sealed with AES-256-GCM,
keyed from the same keyfile that protects stored credentials. This script
reverses that, so a restore never depends on the application still running —
or still existing.

    ./pneumatik-decrypt.py -k pneumatik.key orders_prod_20260801.sql.zip.enc
    unzip -p orders_prod_20260801.sql.zip | mysql -u root -p orders_prod

Requires Python 3.8+ and the `cryptography` package (pip install cryptography).

Format
------
    header  b"PNEUMATIK-ARC1" (14) || salt (16) || nonce prefix (7)
    chunk   ciphertext (<= 1 MiB) || tag (16)          repeated to EOF

The chunk key is HKDF-SHA256(keyfile bytes, salt, "pneumatik-archive-v1").
Each chunk's nonce is prefix || 4-byte big-endian counter || final-flag, so a
reordered or truncated archive fails to authenticate rather than decrypting to
a plausible-looking prefix of the dump.
"""

import argparse
import base64
import hashlib
import hmac
import sys

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:  # pragma: no cover
    sys.exit("This script needs the 'cryptography' package: pip install cryptography")

MAGIC = b"PNEUMATIK-ARC1"
SALT_LENGTH = 16
PREFIX_LENGTH = 7
TAG_LENGTH = 16
CHUNK_SIZE = 1024 * 1024
HKDF_INFO = b"pneumatik-archive-v1"


def derive_key(master_key: bytes, salt: bytes) -> bytes:
    """HKDF-SHA256, single 32-byte output (RFC 5869)."""
    prk = hmac.new(salt, master_key, hashlib.sha256).digest()
    return hmac.new(prk, HKDF_INFO + b"\x01", hashlib.sha256).digest()


def nonce(prefix: bytes, counter: int, last: bool) -> bytes:
    return prefix + counter.to_bytes(4, "big") + (b"\x01" if last else b"\x00")


def read_key(path: str) -> bytes:
    with open(path, "r", encoding="ascii") as handle:
        key = base64.b64decode(handle.read().strip())
    if len(key) != 32:
        sys.exit(f"Key must be 32 bytes (256 bit), got {len(key)}")
    return key


def decrypt(source, target, master_key: bytes) -> None:
    header = source.read(len(MAGIC) + SALT_LENGTH + PREFIX_LENGTH)
    if not header.startswith(MAGIC):
        sys.exit("Not a Pneumatik encrypted archive (wrong magic)")
    if len(header) < len(MAGIC) + SALT_LENGTH + PREFIX_LENGTH:
        sys.exit("Encrypted archive header is truncated")

    salt = header[len(MAGIC):len(MAGIC) + SALT_LENGTH]
    prefix = header[len(MAGIC) + SALT_LENGTH:]

    aead = AESGCM(derive_key(master_key, salt))
    frame_size = CHUNK_SIZE + TAG_LENGTH
    counter = 0
    frame = source.read(frame_size)

    while True:
        if len(frame) < TAG_LENGTH:
            sys.exit("Encrypted archive is truncated")

        # a short frame ends the archive; a full one only ends it when nothing
        # follows, so always read ahead by one frame
        following = source.read(frame_size) if len(frame) == frame_size else b""
        last = not following

        try:
            target.write(aead.decrypt(nonce(prefix, counter, last), frame, None))
        except Exception:
            sys.exit(
                f"Chunk {counter} failed to authenticate. The key is wrong, or the "
                f"archive is truncated or was modified."
            )

        if last:
            return
        counter += 1
        frame = following


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Decrypt a Pneumatik .enc archive to a plain .zip.",
    )
    parser.add_argument("archive", help="the .enc file, or - for stdin")
    parser.add_argument(
        "-k", "--key", required=True,
        help="the keyfile (PNEUMATIK_KEY_FILE, base64-encoded 32 bytes)",
    )
    parser.add_argument(
        "-o", "--output",
        help="where to write the zip; defaults to the archive without .enc, "
             "or - for stdout",
    )
    args = parser.parse_args()

    master_key = read_key(args.key)

    if args.output:
        out_path = args.output
    elif args.archive.endswith(".enc"):
        out_path = args.archive[:-len(".enc")]
    else:
        sys.exit("Cannot infer the output name; pass --output")

    source = sys.stdin.buffer if args.archive == "-" else open(args.archive, "rb")
    target = sys.stdout.buffer if out_path == "-" else open(out_path, "wb")
    try:
        decrypt(source, target, master_key)
    finally:
        if source is not sys.stdin.buffer:
            source.close()
        if target is not sys.stdout.buffer:
            target.close()

    if out_path != "-":
        print(f"Wrote {out_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
