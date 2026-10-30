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

    /** 設定の妥当性チェック。問題があれば (項目, メッセージ) を返す。 */
    fun validate(): Pair<Field, String>? = when {
        shareName.isBlank() || shareName.any { it in "\\/:*?\"<>|" } -> Field.SHARE to "共有名に使えない文字が含まれています"
        user.isBlank() -> Field.USER to "ユーザー名を入力してください"
        password.length < 4 -> Field.PASSWORD to "パスワードは4文字以上にしてください"
        port !in 1024..65535 -> Field.PORT to "1024〜65535 の番号にしてください"
        else -> null
    }

    enum class Field { SHARE, PATH, USER, PASSWORD, PORT }
}
