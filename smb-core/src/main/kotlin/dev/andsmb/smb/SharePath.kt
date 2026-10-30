package dev.andsmb.smb

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** SMB のパス (共有内の相対パス) を実ファイルに変換し、共有の外へ出ないことを保証する */
internal object SharePath {
    /**
     * 共有内の相対パスを実ファイルに変換する。共有外へ出るパスは拒否する。
     * `..` だけでなく、共有内に置かれたシンボリックリンクが共有外を指す場合も拒否する。
     */
    fun resolve(root: File, name: String): Pair<File, String> {
        val parts = name.replace('/', '\\').split('\\').filter { it.isNotEmpty() && it != "." }
        for (p in parts) {
            if (p == ".." || p.any { it < ' ' || it in "<>:\"|?*" }) throw SmbError(Status.OBJECT_NAME_INVALID)
        }
        var f = root
        for (p in parts) f = File(f, p)
        if (parts.isNotEmpty() && !isInside(root, f)) throw SmbError(Status.ACCESS_DENIED)
        return f to parts.joinToString("\\")
    }

    /** ディレクトリの中身 (名前順)。共有外を指すリンクは一覧に出さない。 */
    fun listChildren(root: File, dir: File): List<File> {
        val rootReal = realRoot(root) ?: return emptyList()
        return (dir.listFiles() ?: return emptyList())
            .filter { !Files.isSymbolicLink(it.toPath()) || isInside(rootReal, it.toPath()) }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * f がリンクをすべて解決したうえで共有ルートの内側にあるか。
     * まだ存在しない末尾 (新規作成先) は、存在する一番深い祖先で判定する。
     * 宙ぶらりんのリンクは、作成時にたどられて共有外にファイルができうるので外側とみなす。
     */
    fun isInside(root: File, f: File): Boolean {
        val rootReal = realRoot(root) ?: return false
        return isInside(rootReal, f.toPath())
    }

    private fun isInside(rootReal: Path, path: Path): Boolean {
        var p: Path = path.toAbsolutePath().normalize()
        while (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) p = p.parent ?: return false
        val real = try { p.toRealPath() } catch (e: IOException) { return false }
        return real.startsWith(rootReal)
    }

    private fun realRoot(root: File): Path? = try { root.toPath().toRealPath() } catch (e: IOException) { null }
}
