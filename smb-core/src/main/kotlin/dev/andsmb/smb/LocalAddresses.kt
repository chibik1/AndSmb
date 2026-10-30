package dev.andsmb.smb

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** 同じ LAN の中にしかいないはずのアドレスか (インターネットからは届かない範囲) */
object LocalAddresses {
    fun isLocal(a: InetAddress): Boolean = when (a) {
        // 10/8・172.16/12・192.168/16、169.254/16、127/8
        is Inet4Address -> a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress
        // fe80::/10、::1、ユニークローカル fc00::/7
        is Inet6Address -> a.isLinkLocalAddress || a.isLoopbackAddress || (a.address[0].toInt() and 0xFE) == 0xFC
        else -> false
    }
}
