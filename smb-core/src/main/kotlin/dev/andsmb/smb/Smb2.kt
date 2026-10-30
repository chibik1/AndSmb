package dev.andsmb.smb

import java.io.File
import java.net.InetAddress

data class SmbShare(val name: String, val root: File, val readOnly: Boolean = false, val comment: String = "")

data class SmbConfig(
    val port: Int = 4450,
    val serverName: String = "ANDSMB",
    val shares: List<SmbShare>,
    /** ユーザー名 → パスワード */
    val users: Map<String, String>,
    /** 未登録ユーザー・匿名をゲストとして通すか (Windows 11 24H2 以降はゲストを拒否する) */
    val allowGuest: Boolean = false,
    val bindAddress: String? = null,
    /** 同時接続数の上限。超えた接続はすぐに切る */
    val maxConnections: Int = 32,
    /** 接続してから認証を終えるまでの制限時間 (ミリ秒) */
    val authTimeoutMs: Int = 30_000,
    /** 1 フレームの長さを受け取ってから本体を受け取り終えるまでの制限時間 (ミリ秒) */
    val frameTimeoutMs: Int = 30_000,
    /** 接続を受け付ける相手。既定では LAN 内のアドレスだけ (インターネットからの接続は切る) */
    val clientFilter: (InetAddress) -> Boolean = LocalAddresses::isLocal,
)

interface SmbLogger {
    fun info(msg: String)
    fun error(msg: String, t: Throwable? = null)
}

object Cmd {
    const val NEGOTIATE = 0
    const val SESSION_SETUP = 1
    const val LOGOFF = 2
    const val TREE_CONNECT = 3
    const val TREE_DISCONNECT = 4
    const val CREATE = 5
    const val CLOSE = 6
    const val FLUSH = 7
    const val READ = 8
    const val WRITE = 9
    const val LOCK = 10
    const val IOCTL = 11
    const val CANCEL = 12
    const val ECHO = 13
    const val QUERY_DIRECTORY = 14
    const val CHANGE_NOTIFY = 15
    const val QUERY_INFO = 16
    const val SET_INFO = 17
    const val OPLOCK_BREAK = 18
}

object Status {
    const val SUCCESS = 0
    const val NO_MORE_FILES = 0x80000006.toInt()
    const val BUFFER_OVERFLOW = 0x80000005.toInt()
    const val NOT_IMPLEMENTED = 0xC0000002.toInt()
    const val INVALID_INFO_CLASS = 0xC0000003.toInt()
    const val INFO_LENGTH_MISMATCH = 0xC0000004.toInt()
    const val INVALID_HANDLE = 0xC0000008.toInt()
    const val INVALID_PARAMETER = 0xC000000D.toInt()
    const val NO_SUCH_FILE = 0xC000000F.toInt()
    const val INVALID_DEVICE_REQUEST = 0xC0000010.toInt()
    const val END_OF_FILE = 0xC0000011.toInt()
    const val MORE_PROCESSING_REQUIRED = 0xC0000016.toInt()
    const val ACCESS_DENIED = 0xC0000022.toInt()
    const val BUFFER_TOO_SMALL = 0xC0000023.toInt()
    const val OBJECT_NAME_INVALID = 0xC0000033.toInt()
    const val OBJECT_NAME_NOT_FOUND = 0xC0000034.toInt()
    const val OBJECT_NAME_COLLISION = 0xC0000035.toInt()
    const val OBJECT_PATH_NOT_FOUND = 0xC000003A.toInt()
    const val DELETE_PENDING = 0xC0000056.toInt()
    const val LOGON_FAILURE = 0xC000006D.toInt()
    const val ACCOUNT_LOCKED_OUT = 0xC0000234.toInt()
    const val DISK_FULL = 0xC000007F.toInt()
    const val FILE_IS_A_DIRECTORY = 0xC00000BA.toInt()
    const val NOT_SUPPORTED = 0xC00000BB.toInt()
    const val BAD_NETWORK_NAME = 0xC00000CC.toInt()
    const val INTERNAL_ERROR = 0xC00000E5.toInt()
    const val DIRECTORY_NOT_EMPTY = 0xC0000101.toInt()
    const val NOT_A_DIRECTORY = 0xC0000103.toInt()
    const val FILE_CLOSED = 0xC0000128.toInt()
    const val FS_DRIVER_REQUIRED = 0xC000019C.toInt()
    const val USER_SESSION_DELETED = 0xC0000203.toInt()
    const val NOT_FOUND = 0xC0000225.toInt()
    const val NOT_A_REPARSE_POINT = 0xC0000275.toInt()
    const val NETWORK_NAME_DELETED = 0xC00000C9.toInt()
}

internal object Attr {
    const val READONLY = 0x1
    const val HIDDEN = 0x2
    const val DIRECTORY = 0x10
    const val ARCHIVE = 0x20
    const val NORMAL = 0x80
}

/** 書き込み系のアクセス権ビット (GENERIC_WRITE / GENERIC_ALL / DELETE / WRITE_DATA 等) */
internal const val WRITE_ACCESS_MASK = 0x40000000 or 0x10000000 or 0x00010000 or 0x2 or 0x4 or 0x10 or 0x100

internal const val ACCESS_FULL = 0x001F01FF
internal const val ACCESS_READ = 0x001200A9
