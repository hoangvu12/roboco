package sh.roboco.android.composer

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.roboco_core.FileMatch

/**
 * [MentionIndex.encode] against the real core (android-app#10's host
 * `roboco_mobile` library, reached through the Gradle-set
 * `-Djna.library.path`): the tokens expand into the canonical
 * `[name](roboco-file:path)` links that
 * `crates/proto/src/file_mentions.rs` builds — the same outputs
 * `crates/ui/src/composer.rs:14098-14122` asserts. Robolectric because the
 * generated Kotlin references `android.os.Build` (`android = true` in
 * `uniffi.toml`).
 *
 * There is no API to map an arbitrary `@x` token; the tokens always go
 * through [MentionIndex.token].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MentionLinkFfiTest {

    @Test
    fun specialCharactersAreEscapedAndPercentEncoded() {
        val i = MentionIndex()
        val token = i.token(FileMatch("src/a file#[x].rs", false))
        assertEquals("@a file#[x].rs", token)
        assertEquals("[a file#\\[x\\].rs](roboco-file:src/a%20file%23%5Bx%5D.rs)", i.encode(token))
    }

    @Test
    fun directoriesGetATrailingSlash() {
        val i = MentionIndex()
        i.token(FileMatch("src/components", true))
        assertEquals("[components](roboco-file:src/components/)", i.encode("@components"))
    }

    @Test
    fun plainFilesLinkByBasename() {
        val i = MentionIndex()
        i.token(FileMatch("src/lib.rs", false))
        assertEquals("fix [lib.rs](roboco-file:src/lib.rs)", i.encode("fix @lib.rs"))
    }
}
