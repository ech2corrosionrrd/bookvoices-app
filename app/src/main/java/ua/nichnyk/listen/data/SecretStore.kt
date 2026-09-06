package ua.nichnyk.listen.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.nichnyk.listen.AppLog

/**
 * Пароль WebDAV: AES-256/GCM, ключ живе в AndroidKeyStore і не залишає пристрій.
 *
 * Раніше тут була androidx.security-crypto (EncryptedSharedPreferences + MasterKeys).
 * Google депрекейтнув усю бібліотеку — і `MasterKeys`, і `MasterKey.Builder` у 1.1.0, —
 * тож замість переїзду на такий самий застарілий API схема реалізована напряму.
 * Формат той самий за суттю: шифротекст лежить у звичайних SharedPreferences,
 * а без ключа з Keystore він марний. Перенос зі старого сховища жив тут із v1.1.0
 * і прибраний разом із самою залежністю — чотири релізи він уже нічого не робить.
 *
 * Один екземпляр на процес (створюється в AppContainer). Усі методи suspend і працюють
 * на IO: раніше розшифрування траплялося в мапінгу потоку налаштувань, тобто на головному потоці.
 */
class SecretStore(context: Context) {

    /**
     * false означає, що Keystore недоступний і пароль лежить відкритим текстом.
     * Раніше це відбувалося мовчки — тепер стан видно в налаштуваннях.
     */
    var isEncrypted: Boolean = true
        private set

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    suspend fun getWebDavPassword(): String = withContext(Dispatchers.IO) {
        val stored = prefs.getString(KEY_WEBDAV, null) ?: return@withContext ""
        decrypt(stored)
    }

    suspend fun setWebDavPassword(value: String) = withContext(Dispatchers.IO) {
        prefs.edit { putString(KEY_WEBDAV, encrypt(value)) }
    }

    /** Порожній рядок стирає пароль — раніше `if (pass.isNotBlank())` робив це неможливим. */
    suspend fun clearWebDavPassword() = withContext(Dispatchers.IO) {
        prefs.edit { remove(KEY_WEBDAV) }
    }

    suspend fun isProCached(): Boolean = withContext(Dispatchers.IO) {
        val stored = prefs.getString(KEY_PRO_LICENSE, null) ?: return@withContext false
        decrypt(stored).isNotBlank()
    }

    suspend fun setProCached(purchaseToken: String) = withContext(Dispatchers.IO) {
        prefs.edit { putString(KEY_PRO_LICENSE, encrypt(purchaseToken)) }
    }

    suspend fun clearProCached() = withContext(Dispatchers.IO) {
        prefs.edit { remove(KEY_PRO_LICENSE) }
    }

    /**
     * Прогріває ключ поза головним потоком і повертає стан шифрування.
     *
     * Саме прогрів, а не ліниве створення на місці: генерація ключа в Keystore
     * помітно довга, і без цього вона траплялася б у момент першого читання
     * пароля — тобто на екрані налаштувань, під пальцем.
     */
    suspend fun warmUp(): Boolean = withContext(Dispatchers.IO) {
        runCatching { secretKey() }.onFailure {
            isEncrypted = false
            AppLog.w("SecretStore: Keystore недоступний, пароль ляже відкритим текстом", it)
        }
        isEncrypted
    }

    // --- шифрування -------------------------------------------------------

    private fun encrypt(plain: String): String {
        val key = runCatching { secretKey() }.getOrNull()
        if (key == null) {
            isEncrypted = false
            return PLAIN_PREFIX + plain
        }
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            // iv (12 байтів для GCM) кладемо попереду шифротексту.
            val packed = cipher.iv + body
            Base64.encodeToString(packed, Base64.NO_WRAP)
        }.getOrElse {
            isEncrypted = false
            AppLog.w("SecretStore.encrypt", it)
            PLAIN_PREFIX + plain
        }
    }

    private fun decrypt(stored: String): String {
        if (stored.startsWith(PLAIN_PREFIX)) {
            isEncrypted = false
            return stored.removePrefix(PLAIN_PREFIX)
        }
        return runCatching {
            val packed = Base64.decode(stored, Base64.NO_WRAP)
            if (packed.size <= IV_BYTES) return@runCatching ""
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES),
            )
            String(cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES), Charsets.UTF_8)
        }.getOrDefault("")
        // Порожній рядок замість винятку: ключ міг зникнути (скидання блокування екрана,
        // відновлення на іншому пристрої). Користувач просто вводить пароль ще раз.
    }

    /**
     * `synchronized`: warmUp() і encrypt() можуть зустрітися на різних потоках,
     * обидва не знайти alias і обидва згенерувати ключ. Другий перезаписав би
     * перший, і вже збережений шифротекст став би нерозшифровним — а декрипт
     * повертає на це порожній рядок, тобто пароль зникав би без жодного сліду.
     */
    @Synchronized
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Без setUserAuthenticationRequired: плеєр має синхронізуватися у фоні,
                // коли екран заблокований.
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE = "bookvoices_secrets_v2"
        const val KEY_WEBDAV = "webdav_password"
        const val KEY_PRO_LICENSE = "pro_license_token"

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "bookvoices_secret_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128

        /** Маркер значення, збереженого без шифрування (Keystore недоступний). */
        const val PLAIN_PREFIX = "plain:"
    }
}
