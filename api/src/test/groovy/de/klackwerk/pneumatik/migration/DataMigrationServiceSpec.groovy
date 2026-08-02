package de.klackwerk.pneumatik.migration

import de.klackwerk.pneumatik.credentials.AesGcmCipher
import de.klackwerk.pneumatik.credentials.CredentialService
import de.klackwerk.pneumatik.credentials.FileKeyProvider
import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.inventory.Host
import de.klackwerk.pneumatik.security.ApiKey
import de.klackwerk.pneumatik.security.ApiKeyService
import de.klackwerk.pneumatik.security.User
import de.klackwerk.pneumatik.backup.Trigger
import de.klackwerk.pneumatik.storage.StorageProvider
import grails.config.Config
import grails.core.GrailsApplication
import grails.testing.gorm.DataTest
import spock.lang.Specification

class DataMigrationServiceSpec extends Specification implements DataTest {

    static final String KEY_B64 = Base64.encoder.encodeToString('0123456789abcdef0123456789abcdef'.bytes)
    static final String LEGACY_SECRET = 'old-secret'
    static final String LEGACY_SALT = 'old-salt'

    DataMigrationService service
    CredentialService credentialService

    Class[] getDomainClassesToMock() {
        [Database, Host, ApiKey, User] as Class[]
    }

    void setup() {
        credentialService = new CredentialService()
        credentialService.keyProvider = new FileKeyProvider(null, KEY_B64)
        credentialService.grailsApplication = Stub(GrailsApplication) {
            getConfig() >> Stub(Config) {
                getProperty('pneumatik.credentials.legacy.secret') >> LEGACY_SECRET
                getProperty('pneumatik.credentials.legacy.salt') >> LEGACY_SALT
            }
        }
        service = new DataMigrationService(credentialService: credentialService)
    }

    void 'legacy-encrypted credentials are re-encrypted to v1, new ones untouched'() {
        given:
        Host host = new Host(hostname: 'h', port: 3306, useSSL: false,
                sshKey: legacyEncrypt('SSH-KEY')).save(failOnError: true, validate: false)
        Database legacyDb = new Database(databaseName: 'a', host: host, storageProvider: StorageProvider.DIRECT,
                trigger: Trigger.TRIGGER_DAILY, password: legacyEncrypt('pass-1')).save(failOnError: true, validate: false)
        String alreadyMigrated = credentialService.encryptString('pass-2')
        Database newDb = new Database(databaseName: 'b', host: host, storageProvider: StorageProvider.DIRECT,
                trigger: Trigger.TRIGGER_DAILY, password: alreadyMigrated).save(failOnError: true, validate: false)

        when:
        service.migrate()

        then:
        AesGcmCipher.isEncrypted(legacyDb.password)
        credentialService.decryptString(legacyDb.password) == 'pass-1'
        AesGcmCipher.isEncrypted(host.sshKey)
        credentialService.decryptString(host.sshKey) == 'SSH-KEY'
        newDb.password == alreadyMigrated
    }

    void 'plaintext api keys are hashed with a hint, hashed ones untouched'() {
        given:
        User user = new User(username: 'u', password: 'x', email: 'u@e.x').save(failOnError: true, validate: false)
        String plainKey = 'k' * 64
        ApiKey legacyKey = new ApiKey(key: plainKey, createdBy: user).save(failOnError: true, validate: false)
        String hashed = ApiKeyService.hashKey('other')
        ApiKey newKey = new ApiKey(key: hashed, keyHint: 'other'.take(8), createdBy: user)
                .save(failOnError: true, validate: false)

        when:
        service.migrate()

        then:
        legacyKey.key == ApiKeyService.hashKey(plainKey)
        legacyKey.keyHint == 'kkkkkkkk'
        newKey.key == hashed
    }

    void 'migration is idempotent'() {
        given:
        Host host = new Host(hostname: 'h', port: 3306, useSSL: false).save(failOnError: true, validate: false)
        Database db = new Database(databaseName: 'a', host: host, storageProvider: StorageProvider.DIRECT,
                trigger: Trigger.TRIGGER_DAILY, password: legacyEncrypt('pass')).save(failOnError: true, validate: false)

        when:
        service.migrate()
        String afterFirst = db.password
        service.migrate()

        then:
        db.password == afterFirst
    }

    private static String legacyEncrypt(String plaintext) {
        def factory = javax.crypto.SecretKeyFactory.getInstance('PBKDF2WithHmacSHA256')
        def spec = new javax.crypto.spec.PBEKeySpec(LEGACY_SECRET.toCharArray(), LEGACY_SALT.bytes, 65536, 256)
        def keySpec = new javax.crypto.spec.SecretKeySpec(factory.generateSecret(spec).encoded, 'AES')
        def cipher = javax.crypto.Cipher.getInstance('AES/CBC/PKCS5Padding')
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, new javax.crypto.spec.IvParameterSpec(new byte[16]))
        return Base64.encoder.encodeToString(cipher.doFinal(plaintext.getBytes('UTF-8')))
    }
}
