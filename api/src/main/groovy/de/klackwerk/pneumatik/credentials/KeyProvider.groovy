package de.klackwerk.pneumatik.credentials

/**
 * Source of the data-encryption key. The application only ever asks for the
 * key through this interface so the backing store (keyfile today, Vault or a
 * cloud KMS later) can be swapped without touching encrypted data or callers.
 */
interface KeyProvider {

    /** @return the 256-bit AES data-encryption key */
    byte[] getKey()
}
