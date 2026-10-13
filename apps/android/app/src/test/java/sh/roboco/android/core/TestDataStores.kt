package sh.roboco.android.core

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * The one Preferences DataStore factory for plain-JVM tests: every test
 * that writes a DataStore file goes through here.
 *
 * Why not `PreferenceDataStoreFactory.create(produceFile = …)`: the
 * Android artifact's `FileStorage` commits each write by moving
 * `<file>.tmp` over `<file>` with `atomicMoveTo`, which reads
 * `Build.VERSION.SDK_INT` — `0` from the stub android.jar on the plain
 * JVM — and so takes the pre-26 branch, `File.renameTo`. On Windows
 * `renameTo` never replaces an existing file, so the second write to any
 * file fails with "Unable to rename ….tmp". `createWithPath` does not
 * help: on Android it converts the okio path back to a `File` and builds
 * the same `FileStorage`.
 *
 * This builds an [OkioStorage] directly over [FileSystem.SYSTEM], which
 * on the JVM is okio's `NioSystemFileSystem`; its `atomicMove` is
 * `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` and replaces on every OS.
 * [PreferencesSerializer] is the same Preferences proto format the
 * production `FileStorage` writes, and `OkioStorage` keeps the same
 * one-active-DataStore-per-file rule, so restart tests must still
 * cancel and join the old scope first.
 */
internal fun testPrefsStore(scope: CoroutineScope, file: File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        storage = OkioStorage(
            fileSystem = FileSystem.SYSTEM,
            serializer = PreferencesSerializer,
            producePath = { file.absoluteFile.toOkioPath() },
        ),
        scope = scope,
    )
