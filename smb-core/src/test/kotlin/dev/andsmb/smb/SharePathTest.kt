package dev.andsmb.smb

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SharePathTest {
    private lateinit var tmp: File
    private lateinit var root: File
    private lateinit var outside: File

    @Before fun setUp() {
        tmp = Files.createTempDirectory("sharepath").toFile()
        root = File(tmp, "share").apply { mkdir() }
        outside = File(tmp, "outside").apply { mkdir() }
        File(outside, "secret.txt").writeText("secret")
        File(root, "inner").mkdir()
        File(root, "inner/a.txt").writeText("a")
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    private fun link(name: String, target: File) = Files.createSymbolicLink(File(root, name).toPath(), target.toPath())

    private fun assertDenied(name: String) {
        try {
            SharePath.resolve(root, name)
            fail("共有外を指すパスが通った: $name")
        } catch (e: SmbError) {
            assertEquals(Status.ACCESS_DENIED, e.status)
        }
    }

    @Test fun normalPathsResolve() {
        assertEquals(File(root, "inner/a.txt") to "inner\\a.txt", SharePath.resolve(root, "inner\\a.txt"))
        assertEquals(root to "", SharePath.resolve(root, ""))
        // まだ無いファイル (新規作成) も通す
        assertEquals("inner\\new.txt", SharePath.resolve(root, "inner\\new.txt").second)
    }

    @Test fun dotDotIsRejected() {
        try { SharePath.resolve(root, "..\\outside\\secret.txt"); fail() } catch (e: SmbError) {
            assertEquals(Status.OBJECT_NAME_INVALID, e.status)
        }
    }

    @Test fun linkToOutsideFileIsDenied() {
        link("leak.txt", File(outside, "secret.txt"))
        assertDenied("leak.txt")
    }

    @Test fun linkToOutsideDirIsDenied() {
        link("out", outside)
        assertDenied("out")
        assertDenied("out\\secret.txt")
        // リンク先ディレクトリに新しいファイルを作ることもできない
        assertDenied("out\\created.txt")
        assertDenied("out\\sub\\created.txt")
    }

    @Test fun danglingLinkIsDenied() {
        // 作成時にリンクをたどって共有外にファイルができるのを防ぐ
        link("dangling.txt", File(outside, "not-yet.txt"))
        assertDenied("dangling.txt")
        link("dangling-dir", File(outside, "no-dir"))
        assertDenied("dangling-dir\\x.txt")
        assertFalse(File(outside, "not-yet.txt").exists())
    }

    @Test fun linkInsideShareIsAllowed() {
        link("alias", File(root, "inner"))
        assertEquals("alias\\a.txt", SharePath.resolve(root, "alias\\a.txt").second)
    }

    @Test fun rootGivenThroughLinkStillWorks() {
        // 共有ルート自体がリンク経由 (例: /sdcard) でも中のファイルは使える
        val viaLink = File(tmp, "share-link")
        Files.createSymbolicLink(viaLink.toPath(), root.toPath())
        assertEquals("inner\\a.txt", SharePath.resolve(viaLink, "inner\\a.txt").second)
    }

    @Test fun listingHidesLinksToOutside() {
        link("out", outside)
        link("alias", File(root, "inner"))
        val names = SharePath.listChildren(root, root).map { it.name }.sorted()
        assertEquals(listOf("alias", "inner"), names)
    }
}
