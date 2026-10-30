package dev.andsmb.smb

import java.io.File

/** Mac/PC 上での動作確認用: `./gradlew :smb-core:run --args="<dir> <user> <pass> [port]"` */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("usage: <dir> <user> <password> [port]")
        return
    }
    val log = object : SmbLogger {
        override fun info(msg: String) = println(msg)
        override fun error(msg: String, t: Throwable?) {
            System.err.println(msg); t?.printStackTrace()
        }
    }
    val config = SmbConfig(
        port = args.getOrNull(3)?.toInt() ?: 4450,
        shares = listOf(SmbShare("share", File(args[0]).absoluteFile)),
        users = mapOf(args[1] to args[2]),
    )
    SmbServer(config, log).start()
    Thread.currentThread().join()
}
