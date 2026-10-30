package dev.andsmb.smb

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

internal class Stat(
    val isDir: Boolean,
    val size: Long,
    val created: Long,
    val accessed: Long,
    val modified: Long,
    val attributes: Int,
    val id: Long,
) {
    val allocation: Long get() = if (isDir) 0 else (size + 4095) / 4096 * 4096
}

internal object FileInfo {

    fun stat(f: File, id: Long, readOnlyShare: Boolean): Stat? {
        val hidden = f.name.startsWith(".")
        return try {
            val a = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val dir = a.isDirectory || (a.isSymbolicLink && f.isDirectory)
            Stat(
                dir, if (dir) 0 else (if (a.isSymbolicLink) f.length() else a.size()),
                a.creationTime().toMillis(), a.lastAccessTime().toMillis(), a.lastModifiedTime().toMillis(),
                attrs(dir, hidden, readOnlyShare), id,
            )
        } catch (e: Exception) {
            if (!f.exists()) return null
            val m = f.lastModified()
            Stat(f.isDirectory, if (f.isDirectory) 0 else f.length(), m, m, m, attrs(f.isDirectory, hidden, readOnlyShare), id)
        }
    }

    private fun attrs(dir: Boolean, hidden: Boolean, ro: Boolean): Int {
        var a = if (dir) Attr.DIRECTORY else Attr.ARCHIVE
        if (hidden) a = a or Attr.HIDDEN
        if (ro && !dir) a = a or Attr.READONLY
        return a
    }

    /** QUERY_DIRECTORY の各エントリ。NextEntryOffset は呼び出し側で埋める。null は未対応クラス。 */
    fun dirEntry(cls: Int, name: String, s: Stat): ByteArray? {
        val nm = name.utf16()
        val b = Buf(128 + nm.size)
        if (cls == 12) { // FileNamesInformation
            return b.u32(0).u32(0).u32(nm.size).bytes(nm).toByteArray()
        }
        if (cls !in intArrayOf(1, 2, 3, 37, 38, 60)) return null
        b.u32(0).u32(0)
        b.u64(fileTime(s.created)).u64(fileTime(s.accessed)).u64(fileTime(s.modified)).u64(fileTime(s.modified))
        b.u64(s.size).u64(s.allocation).u32(s.attributes).u32(nm.size)
        when (cls) {
            2 -> b.u32(0)
            38 -> b.u32(0).u32(0).u64(s.id)
            3 -> b.u32(0).u8(0).u8(0).zeros(24)
            37 -> b.u32(0).u8(0).u8(0).zeros(24).u16(0).u64(s.id)
            60 -> b.u32(0).u32(0).u64(s.id).u64(0)
        }
        return b.bytes(nm).toByteArray()
    }

    private fun basic(b: Buf, s: Stat) {
        b.u64(fileTime(s.created)).u64(fileTime(s.accessed)).u64(fileTime(s.modified)).u64(fileTime(s.modified))
        b.u32(s.attributes).u32(0)
    }

    private fun standard(b: Buf, s: Stat, deletePending: Boolean) {
        b.u64(s.allocation).u64(s.size).u32(1).u8(if (deletePending) 1 else 0).u8(if (s.isDir) 1 else 0).u16(0)
    }

    /**
     * QUERY_INFO(FILE) の応答データ。null は未対応クラス。
     * [path] は共有ルートからの相対パス (区切りは \, ルートは空文字)。
     */
    fun fileInfo(cls: Int, s: Stat, path: String, access: Int, deletePending: Boolean): ByteArray? {
        val b = Buf(128)
        val fullName = ("\\" + path).utf16()
        when (cls) {
            4 -> basic(b, s)
            5 -> standard(b, s, deletePending)
            6 -> b.u64(s.id)
            7 -> b.u32(0)
            8 -> b.u32(access)
            9 -> b.u32(fullName.size).bytes(fullName)
            14 -> b.u64(0)
            16 -> b.u32(0)
            17 -> b.u32(0)
            18 -> {
                basic(b, s); standard(b, s, deletePending)
                b.u64(s.id).u32(0).u32(access).u64(0).u32(0).u32(0)
                b.u32(fullName.size).bytes(fullName)
            }
            22 -> if (!s.isDir) {
                val n = "::\$DATA".utf16()
                b.u32(0).u32(n.size).u64(s.size).u64(s.allocation).bytes(n)
            }
            28 -> b.u64(s.size).u16(0).u8(0).u8(0).zeros(4)
            34 -> {
                b.u64(fileTime(s.created)).u64(fileTime(s.accessed)).u64(fileTime(s.modified)).u64(fileTime(s.modified))
                b.u64(s.allocation).u64(s.size).u32(s.attributes).u32(0)
            }
            35 -> b.u32(s.attributes).u32(0)
            48 -> { val n = path.utf16(); b.u32(n.size).bytes(n) }
            59 -> b.u64(0).u64(s.id).u64(0)
            else -> return null
        }
        return b.toByteArray()
    }

    /** QUERY_INFO(FILESYSTEM) の応答データ。 */
    fun fsInfo(cls: Int, root: File, label: String, readOnly: Boolean): ByteArray? {
        val b = Buf(64)
        val unit = 4096L
        val total = root.totalSpace / unit
        val free = root.usableSpace / unit
        when (cls) {
            1 -> {
                val l = label.utf16()
                b.u64(0).u32(label.hashCode()).u32(l.size).u8(0).u8(0).bytes(l)
            }
            3 -> b.u64(total).u64(free).u32(8).u32(512)
            4 -> b.u32(7).u32(0x20)
            5 -> {
                val n = "NTFS".utf16()
                var attrs = 0x2 or 0x4 // CASE_PRESERVED_NAMES | UNICODE_ON_DISK
                if (readOnly) attrs = attrs or 0x80000
                b.u32(attrs).u32(255).u32(n.size).bytes(n)
            }
            7 -> b.u64(total).u64(free).u64(free).u32(8).u32(512)
            8 -> b.zeros(64)
            11 -> b.u32(512).u32(512).u32(512).u32(512).u32(0x3).u32(0).u32(0)
            else -> return null
        }
        return b.toByteArray()
    }

    /** 全員フルアクセスの最小限のセキュリティ記述子 (自己相対形式) */
    fun securityDescriptor(additional: Int): ByteArray {
        val everyone = byteArrayOf(1, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0)
        val wantOwner = additional and 1 != 0
        val wantGroup = additional and 2 != 0
        val wantDacl = additional and 4 != 0
        val b = Buf(96)
        b.u8(1).u8(0).u16(0x8000 or (if (wantDacl) 0x4 else 0)).u32(0).u32(0).u32(0).u32(0)
        if (wantOwner) { b.set32(4, b.size); b.bytes(everyone) }
        if (wantGroup) { b.set32(8, b.size); b.bytes(everyone) }
        if (wantDacl) {
            b.set32(16, b.size)
            val aceSize = 8 + everyone.size
            b.u8(2).u8(0).u16(8 + aceSize).u16(1).u16(0)
            b.u8(0).u8(0x3).u16(aceSize).u32(ACCESS_FULL).bytes(everyone) // OBJECT_INHERIT | CONTAINER_INHERIT
        }
        return b.toByteArray()
    }

    /** Windows のワイルドカード (* ? < > ") をパターン一致させる */
    fun matcher(pattern: String): (String) -> Boolean {
        if (pattern.isEmpty() || pattern == "*" || pattern == "*.*" || pattern == "<.*") return { true }
        if (pattern.none { it in "*?<>\"" }) return { it.equals(pattern, ignoreCase = true) }
        val sb = StringBuilder()
        for (c in pattern) when (c) {
            '*', '<' -> sb.append(".*")
            '?', '>' -> sb.append('.')
            '"' -> sb.append("\\.")
            else -> sb.append(Regex.escape(c.toString()))
        }
        val re = Regex(sb.toString(), RegexOption.IGNORE_CASE)
        return { re.matches(it) }
    }
}
