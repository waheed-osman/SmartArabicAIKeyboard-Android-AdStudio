package com.qalamai.keyboard.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.qalamai.keyboard.ui.theme.KeyboardThemeId

class SecurePrefsManager(private val context: Context) {

    companion object {
        private const val PREFS_FILE = "qalam_secure_prefs"
        private const val KEY_GEMINI_API = "gemini_api_key"
        private const val KEY_THEME = "selected_theme"
        private const val KEY_HAPTIC = "haptic_enabled"
        private const val KEY_AUTO_PC_LANDSCAPE = "auto_pc_landscape"
        private const val KEY_CLIPBOARD_HISTORY = "clipboard_history_enabled"
    }

    private val prefs: SharedPreferences = createPrefs()

    private fun createPrefs(): SharedPreferences {
        return try {
            openEncrypted()
        } catch (e: Exception) {
            // يحدث أحياناً بعد استعادة جهاز أو تغيّر مخزن المفاتيح (AEADBadTagException).
            // نحذف الملف التالف ونبدأ من جديد بدلاً من أن تتعطل لوحة المفاتيح.
            context.deleteSharedPreferences(PREFS_FILE)
            openEncrypted()
        }
    }

    private fun openEncrypted(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveGeminiApiKey(apiKey: String) {
        prefs.edit().putString(KEY_GEMINI_API, apiKey.trim()).apply()
    }

    fun getGeminiApiKey(): String = prefs.getString(KEY_GEMINI_API, "") ?: ""

    fun saveSelectedTheme(themeId: KeyboardThemeId) {
        prefs.edit().putString(KEY_THEME, themeId.name).apply()
    }

    fun getSelectedTheme(): KeyboardThemeId {
        val raw = prefs.getString(KEY_THEME, null) ?: return KeyboardThemeId.OBSIDIAN
        return runCatching { KeyboardThemeId.valueOf(raw) }
            .getOrDefault(KeyboardThemeId.OBSIDIAN)
    }

    fun setHapticEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HAPTIC, enabled).apply()
    }

    fun isHapticEnabled(): Boolean = prefs.getBoolean(KEY_HAPTIC, true)

    fun setAutoPcInLandscape(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_PC_LANDSCAPE, enabled).apply()
    }

    fun isAutoPcInLandscape(): Boolean = prefs.getBoolean(KEY_AUTO_PC_LANDSCAPE, true)

    fun setClipboardHistoryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLIPBOARD_HISTORY, enabled).apply()
    }

    fun isClipboardHistoryEnabled(): Boolean = prefs.getBoolean(KEY_CLIPBOARD_HISTORY, true)
}
