package de.klackwerk.pneumatik.credentials

import groovy.transform.CompileStatic

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Streaming AES-256-GCM for backup archives.
 *
 * {@link AesGcmCipher} encrypts a credential in one shot, which is wrong for a
 * dump: GCM only authenticates at the very end, so a single-pass archive could
 * not be verified without holding all of it, and a truncated file would decrypt
 * happily right up to the point where it stops. Archives are therefore split
 * into independently sealed chunks — the STREAM construction — so decryption
 * only ever emits bytes that have already been authenticated, and a file that
 * loses its tail fails instead of yielding a short dump that looks fine.
 *
 * <pre>
 * header  "PNEUMATIK-ARC1" (14) || salt (16) || nonce prefix (7)
 * chunk   ciphertext (<= 1 MiB) || tag (16)          repeated to EOF
 * </pre>
 *
 * The per-chunk nonce is {@code prefix || counter || final-flag}.
 *
 * The key is not the data-encryption key itself but HKDF-SHA256 derived from
 * it, with a random per-archive salt. Archives and credentials never
 * share key material, no single AES-GCM key covers more than one archive.
 */
@CompileStatic
class ArchiveCipher {

    static final byte[] MAGIC = 'PNEUMATIK-ARC1'.getBytes('US-ASCII')

    /** Plaintext bytes per sealed chunk. */
    static final int CHUNK_SIZE = 1024 * 1024

    private static final int SALT_LENGTH = 16
    private static final int PREFIX_LENGTH = 7
    private static final int TAG_LENGTH_BITS = 128
    private static final int TAG_LENGTH_BYTES = 16
    private static final String HKDF_INFO = 'pneumatik-archive-v1'

    private static final SecureRandom RANDOM = new SecureRandom()

    static int headerLength() {
        return MAGIC.length + SALT_LENGTH + PREFIX_LENGTH
    }

    /**
     * Encrypts everything readable from {@code plain} into {@code encrypted}.
     */
    static void encrypt(InputStream plain, OutputStream encrypted, byte[] masterKey) {
        byte[] salt = new byte[SALT_LENGTH]
        byte[] prefix = new byte[PREFIX_LENGTH]
        RANDOM.nextBytes(salt)
        RANDOM.nextBytes(prefix)

        encrypted.write(MAGIC)
        encrypted.write(salt)
        encrypted.write(prefix)

        SecretKeySpec key = deriveKey(masterKey, salt)
        long counter = 0L

        byte[] current = new byte[CHUNK_SIZE]
        byte[] held = new byte[CHUNK_SIZE]
        int heldLength = -1

        int read
        while ((read = readFully(plain, current)) > 0) {
            if (heldLength >= 0) {
                encrypted.write(seal(key, prefix, counter++, false, held, heldLength))
            }
            byte[] swap = held
            held = current
            current = swap
            heldLength = read
            if (read < CHUNK_SIZE) {
                break
            }
        }

        // an empty input still gets its final chunk, so every archive is
        // well-formed and an empty one is distinguishable from a truncated one
        encrypted.write(seal(key, prefix, counter, true, held, Math.max(heldLength, 0)))
    }

    /**
     * Decrypts {@code encrypted} into {@code plain}. Bytes reach the output
     * only after the chunk they belong to has been authenticated.
     *
     * @throws IllegalStateException when the header is not ours
     * @throws javax.crypto.AEADBadTagException when the key is wrong or the
     *         archive was altered or truncated
     */
    static void decrypt(InputStream encrypted, OutputStream plain, byte[] masterKey) {
        PushbackInputStream input = new PushbackInputStream(encrypted, 1)

        byte[] magic = new byte[MAGIC.length]
        if (readFully(input, magic) != MAGIC.length || !Arrays.equals(magic, MAGIC)) {
            throw new IllegalStateException('Not a Pneumatik encrypted archive')
        }
        byte[] salt = new byte[SALT_LENGTH]
        byte[] prefix = new byte[PREFIX_LENGTH]
        if (readFully(input, salt) != SALT_LENGTH || readFully(input, prefix) != PREFIX_LENGTH) {
            throw new IllegalStateException('Encrypted archive header is truncated')
        }

        SecretKeySpec key = deriveKey(masterKey, salt)
        byte[] frame = new byte[CHUNK_SIZE + TAG_LENGTH_BYTES]
        long counter = 0L

        while (true) {
            int read = readFully(input, frame)
            if (read < TAG_LENGTH_BYTES) {
                throw new IllegalStateException('Encrypted archive is truncated')
            }

            // a short frame ends the archive; a full one only ends it when
            // nothing follows, so peek one byte and put it back
            boolean last = read < frame.length
            if (!last) {
                int peek = input.read()
                if (peek == -1) {
                    last = true
                } else {
                    input.unread(peek)
                }
            }

            plain.write(open(key, prefix, counter++, last, frame, read))
            if (last) {
                return
            }
        }
    }

    /** Whether the file starts with the archive magic. */
    static boolean isEncrypted(File file) {
        if (!file?.exists() || file.length() < MAGIC.length) {
            return false
        }
        return new FileInputStream(file).withCloseable { InputStream input ->
            byte[] magic = new byte[MAGIC.length]
            return readFully(input, magic) == MAGIC.length && Arrays.equals(magic, MAGIC)
        } as boolean
    }

    private static byte[] seal(SecretKeySpec key, byte[] prefix, long counter, boolean last, byte[] chunk, int length) {
        Cipher cipher = Cipher.getInstance('AES/GCM/NoPadding')
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce(prefix, counter, last)))
        return cipher.doFinal(chunk, 0, length)
    }

    private static byte[] open(SecretKeySpec key, byte[] prefix, long counter, boolean last, byte[] frame, int length) {
        Cipher cipher = Cipher.getInstance('AES/GCM/NoPadding')
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce(prefix, counter, last)))
        return cipher.doFinal(frame, 0, length)
    }

    /** prefix (7) || counter big-endian (4) || final flag (1) */
    private static byte[] nonce(byte[] prefix, long counter, boolean last) {
        if (counter > 0xFFFFFFFFL) {
            throw new IllegalStateException('Archive exceeds the maximum number of chunks')
        }
        byte[] nonce = new byte[12]
        System.arraycopy(prefix, 0, nonce, 0, PREFIX_LENGTH)
        nonce[7] = (byte) ((counter >>> 24) & 0xFF)
        nonce[8] = (byte) ((counter >>> 16) & 0xFF)
        nonce[9] = (byte) ((counter >>> 8) & 0xFF)
        nonce[10] = (byte) (counter & 0xFF)
        nonce[11] = (byte) (last ? 1 : 0)
        return nonce
    }

    /**
     * HKDF-SHA256. The JDK has no HKDF before 24, and this is the whole of
     * RFC 5869 for a single 32-byte output.
     */
    private static SecretKeySpec deriveKey(byte[] masterKey, byte[] salt) {
        Mac mac = Mac.getInstance('HmacSHA256')

        mac.init(new SecretKeySpec(salt, 'HmacSHA256'))
        byte[] pseudoRandomKey = mac.doFinal(masterKey)

        mac.init(new SecretKeySpec(pseudoRandomKey, 'HmacSHA256'))
        mac.update(HKDF_INFO.getBytes('US-ASCII'))
        mac.update((byte) 1)
        return new SecretKeySpec(mac.doFinal(), 'AES')
    }

    /** Reads until the buffer is full or the stream ends. */
    private static int readFully(InputStream input, byte[] buffer) {
        int total = 0
        while (total < buffer.length) {
            int read = input.read(buffer, total, buffer.length - total)
            if (read == -1) {
                break
            }
            total += read
        }
        return total
    }
}
