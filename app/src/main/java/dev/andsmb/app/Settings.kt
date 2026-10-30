package dev.andsmb.app

import android.content.Context
import android.os.Environment
import java.security.SecureRandom

data class Settings(
    val shareName: String,
    val path: String,
    val user: String,
    val password: String,
    val port: Int,
    val readOnly: Boolean,
) {
    companion object {
        private const val PREFS = "settings"

        /** 総当たりされにくくするための最低文字数 */
        const val MIN_PASSWORD = 8

        fun load(ctx: Context): Settings {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // 初回起動時はランダムなパスワードを作って保存しておく
            val password = p.getString("password", null) ?: randomPassword().also {
                p.edit().putString("password", it).apply()
            }
            return Settings(
                shareName = p.getString("shareName", "android")!!,
                path = p.getString("path", Environment.getExternalStorageDirectory().absolutePath)!!,
                user = p.getString("user", "android")!!,
                password = password,
                port = p.getInt("port", 4450),
                readOnly = p.getBoolean("readOnly", false),
            )
        }

        fun save(ctx: Context, s: Settings) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("shareName", s.shareName)
                .putString("path", s.path)
                .putString("user", s.user)
                .putString("password", s.password)
                .putInt("port", s.port)
                .putBoolean("readOnly", s.readOnly)
                .apply()
        }

        private fun randomPassword(): String {
            val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val r = SecureRandom()
            return (1..10).map { chars[r.nextInt(chars.length)] }.joinToString("")
        }
    }

    /** 設定の妥当性チェック。問題があれば (項目, 端末の言語のメッセージ) を返す。 */
    fun validate(ctx: Context): Pair<Field, String>? = when {
        shareName.isBlank() || shareName.any { it in "\\/:*?\"<>|" } -> Field.SHARE to ctx.getString(R.string.err_share_invalid)
        user.isBlank() -> Field.USER to ctx.getString(R.string.err_user_required)
        password.length < MIN_PASSWORD -> Field.PASSWORD to ctx.getString(R.string.err_password_short, MIN_PASSWORD)
        port !in 1024..65535 -> Field.PORT to ctx.getString(R.string.err_port_range)
        else -> null
    }

    enum class Field { SHARE, PATH, USER, PASSWORD, PORT }
}
