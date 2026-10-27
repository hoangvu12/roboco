package sh.roboco.android.pairing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import uniffi.roboco_core.EngineRoomRef
import uniffi.roboco_core.EngineTransport
import uniffi.roboco_core.PairedEngine
import uniffi.roboco_core.RoomMemberRecord
import uniffi.roboco_core.RoomRecord
import java.io.File
import java.util.Base64

/**
 * `PairedEngineStore` behaviour (ticket 71): pairing-order persistence,
 * restart round-trip, pin merging, orphan cleanup, and the
 * "decrypt failure keeps the engine" degradation that makes a lost
 * Keystore key show "Pair again" instead of a vanished engine.
 *
 * Robolectric: `org.json` is a stub on the plain JVM and the generated
 * `PairedEngine` record needs the Android runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PairedEngineStoreTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private val engineA = newEngine(
        engineId = "11111111-1111-4111-8111-111111111111",
        credential = "cred-AAA",
    )
    private val engineB = newEngine(
        engineId = "22222222-2222-4222-8222-222222222222",
        credential = "cred-BBB",
        sessionId = "s2",
    )

    private class Fixture(
        val store: PairedEngineStore,
        val dataStore: DataStore<Preferences>,
        val scope: CoroutineScope,
    )

    /** One DataStore per file at a time; the scope is cancellable for restarts. */
    private fun TestScope.newStore(
        cipher: FakeSecretCipher = FakeSecretCipher(),
        fileName: String = "t.preferences_pb",
    ): Fixture {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(tmpFolder.root, fileName) },
        )
        return Fixture(
            PairedEngineStore(dataStore, cipher, StandardTestDispatcher(testScheduler)),
            dataStore,
            scope,
        )
    }

    /** A process restart: new DataStore in a new scope over the same file. */
    private suspend fun TestScope.restart(
        fixture: Fixture,
        cipher: FakeSecretCipher,
    ): Fixture {
        fixture.scope.cancel()
        fixture.scope.coroutineContext.job.join()
        return newStore(cipher)
    }

    private fun storeFile(): File = File(tmpFolder.root, "t.preferences_pb")

    private suspend fun Fixture.prefNames(): Set<String> =
        dataStore.data.first().asMap().keys.map { it.name }.toSet()

    private fun credKey(engineId: String) = stringPreferencesKey("cred:$engineId")

    private suspend fun Fixture.credValue(engineId: String): String? =
        dataStore.data.first()[credKey(engineId)]

    private suspend fun Fixture.writeCred(engineId: String, value: String) {
        dataStore.edit { it[credKey(engineId)] = value }
    }

    /** Flips a byte in the tag region, keeping the blob shape valid Base64. */
    private fun corrupt(base64: String): String {
        val blob = Base64.getDecoder().decode(base64)
        val flipped = blob.copyOf()
        flipped[flipped.size - 1] = (flipped[flipped.size - 1].toInt() xor 0x5A).toByte()
        return Base64.getEncoder().encodeToString(flipped)
    }

    private fun ByteArray.indexOf(other: ByteArray): Int {
        if (other.isEmpty() || size < other.size) return -1
        outer@ for (i in 0..size - other.size) {
            for (j in other.indices) {
                if (this[i + j] != other[j]) continue@outer
            }
            return i
        }
        return -1
    }

    @Test
    fun aFreshInstallLoadsNoEngines() = runTest {
        val fixture = newStore()

        assertEquals(emptyList<PairedEngine>(), fixture.store.load())
        assertFalse(fixture.store.has(engineA.engineId))
    }

    @Test
    fun upsertThenRestartRoundTripsAllEightFieldsInOrder() = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        fixture.store.upsert(engineA)
        fixture.store.upsert(engineB)

        val restarted = restart(fixture, cipher)

        assertEquals(listOf(engineA, engineB), restarted.store.load())
    }

    @Test
    fun aReUpsertKeepsThePositionAndReplacesTheRecord() = runTest {
        val fixture = newStore()
        fixture.store.upsert(engineA)
        fixture.store.upsert(engineB)
        val prime = engineA.copy(
            credential = "cred-AAA2",
            baseUrl = "http://10.0.2.2:27656",
            sessionId = "s9",
        )

        fixture.store.upsert(prime)

        assertEquals(listOf(prime, engineB), fixture.store.load())
    }

    @Test
    fun pinPersistsTheDeviceIdAndANonBlankName() = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        fixture.store.upsert(engineA)

        fixture.store.pin(engineA.engineId, "dev-1", "MacBook Pro")

        val restarted = restart(fixture, cipher)
        assertEquals(
            listOf(engineA.copy(deviceId = "dev-1", name = "MacBook Pro")),
            restarted.store.load(),
        )
    }

    @Test
    fun aBlankPinNameKeepsThePreviousName() = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        fixture.store.upsert(engineA.copy(name = "Old Name"))

        fixture.store.pin(engineA.engineId, "dev-1", "   ")

        val restarted = restart(fixture, cipher)
        assertEquals(
            listOf(engineA.copy(deviceId = "dev-1", name = "Old Name")),
            restarted.store.load(),
        )
    }

    @Test
    fun aPinThatChangesNothingSkipsTheWrite() = runTest {
        val fixture = newStore()
        fixture.store.upsert(engineA)
        fixture.store.pin(engineA.engineId, "dev-1", "MacBook Pro")
        val bytesBefore = storeFile().readBytes()

        fixture.store.pin(engineA.engineId, "dev-1", "MacBook Pro")

        assertTrue(storeFile().readBytes().contentEquals(bytesBefore))
    }

    @Test
    fun aPinThatRacedAheadOfTheUpsertMergesIntoIt() = runTest {
        val fixture = newStore()

        fixture.store.pin(engineA.engineId, "dev-1", "MacBook Pro")

        assertTrue(
            "a pin for an unknown id writes nothing",
            fixture.dataStore.data.first().asMap().isEmpty(),
        )
        fixture.store.upsert(engineA)
        assertEquals(
            listOf(engineA.copy(deviceId = "dev-1", name = "MacBook Pro")),
            fixture.store.load(),
        )
    }

    @Test
    fun hasTracksThePersistedIds() = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        fixture.store.upsert(engineA)

        assertTrue(fixture.store.has(engineA.engineId))

        val restarted = restart(fixture, cipher)
        assertFalse("before load() the id snapshot is empty", restarted.store.has(engineA.engineId))
        restarted.store.load()
        assertTrue(restarted.store.has(engineA.engineId))
        restarted.store.remove(engineA.engineId)
        assertFalse(restarted.store.has(engineA.engineId))
    }

    @Test
    fun removeDropsTheMetadataTheCredentialAndOrphanCredKeys() = runTest {
        val fixture = newStore()
        fixture.store.upsert(engineA)
        fixture.store.upsert(engineB)
        writeOrphan(fixture)

        fixture.store.remove(engineA.engineId)

        assertEquals(
            setOf("engines", "cred:${engineB.engineId}"),
            fixture.prefNames(),
        )
        assertFalse(fixture.store.has(engineA.engineId))
    }

    @Test
    fun removingAnUnknownIdIsANoOp() = runTest {
        val fixture = newStore()
        fixture.store.upsert(engineA)
        val bytesBefore = storeFile().readBytes()

        fixture.store.remove("33333333-3333-4333-8333-333333333333")

        assertTrue(storeFile().readBytes().contentEquals(bytesBefore))
        assertEquals(listOf(engineA), fixture.store.load())
    }

    @Test
    fun orphanCredKeysDisappearOnTheNextWrite() = runTest {
        val fixture = newStore()
        fixture.store.upsert(engineA)
        writeOrphan(fixture)

        fixture.store.upsert(engineB)

        assertEquals(
            setOf("engines", "cred:${engineA.engineId}", "cred:${engineB.engineId}"),
            fixture.prefNames(),
        )
    }

    @Test
    fun aCorruptedBlobDegradesOnlyThatEngine() = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        fixture.store.upsert(engineA)
        fixture.store.upsert(engineB)
        fixture.writeCred(engineB.engineId, corrupt(fixture.credValue(engineB.engineId)!!))

        val restarted = restart(fixture, cipher)
        val loaded = restarted.store.load()

        assertEquals(engineA, loaded[0])
        assertEquals(engineB.copy(credential = ""), loaded[1])
        assertTrue("a failed decrypt still counts as known", restarted.store.has(engineB.engineId))
    }

    @Test
    fun aMissingCredentialKeyDegradesToAnEmptyCredential() = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        fixture.store.upsert(engineA)
        fixture.store.upsert(engineB)
        ShadowLog.clear()
        fixture.dataStore.edit { it.remove(credKey(engineB.engineId)) }

        val restarted = restart(fixture, cipher)

        assertEquals(engineB.copy(credential = ""), restarted.store.load()[1])
        val logged = ShadowLog.getLogs().mapNotNull { it.msg }
        assertTrue(
            "expected the missing-credential log, got $logged",
            logged.any { it == "engine credential missing engine=${engineB.engineId}" },
        )
    }

    @Test
    fun aBlobCopiedToAnotherEngineFailsItsAad() = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        fixture.store.upsert(engineA)
        fixture.store.upsert(engineB)
        ShadowLog.clear()
        fixture.writeCred(engineB.engineId, fixture.credValue(engineA.engineId)!!)

        val restarted = restart(fixture, cipher)
        val loaded = restarted.store.load()

        assertEquals(engineA, loaded[0])
        assertEquals(engineB.copy(credential = ""), loaded[1])
        val logged = ShadowLog.getLogs().mapNotNull { it.msg }
        assertTrue(
            "expected the AAD failure log, got $logged",
            logged.any {
                it == "engine credential unreadable engine=${engineB.engineId} cause=AEADBadTagException"
            },
        )
    }

    @Test
    fun aLostKeyDegradesEveryEngineButKeepsThemListed() = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        fixture.store.upsert(engineA)
        fixture.store.upsert(engineB)
        cipher.rotateKey()

        val restarted = restart(fixture, cipher)
        val loaded = restarted.store.load()

        assertEquals(2, loaded.size)
        assertTrue(loaded.all { it.credential == "" })
        assertEquals(engineB.baseUrl, loaded[1].baseUrl)
        assertEquals(engineB.label, loaded[1].label)
    }

    @Test
    fun aFailingEncryptThrowsAndChangesNothing() = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        fixture.store.upsert(engineA)
        val bytesBefore = storeFile().readBytes()
        cipher.failEncrypt = true

        val thrown: Throwable? = try {
            fixture.store.upsert(engineB)
            null
        } catch (expected: CredentialStoreException) {
            expected
        }

        assertNotNull("upsert must throw CredentialStoreException", thrown)
        assertTrue(fixture.store.has(engineA.engineId))
        assertFalse("a failed upsert never lands", fixture.store.has(engineB.engineId))
        assertTrue("the atomic edit wrote nothing", storeFile().readBytes().contentEquals(bytesBefore))
        assertEquals(listOf(engineA), fixture.store.load())
    }

    @Test
    fun aNonArrayEnginesValueLoadsEmptyAndLogsIt() = runTest {
        val fixture = newStore()
        fixture.dataStore.edit { it[stringPreferencesKey("engines")] = "not-a-json-array" }
        ShadowLog.clear()

        assertEquals(emptyList<PairedEngine>(), fixture.store.load())
        assertEquals(listOf("paired engines unreadable"), ShadowLog.getLogs().mapNotNull { it.msg })
    }

    @Test
    fun aStoreElementMissingARequiredFieldIsSkippedWhileTheOthersLoad() = runTest {
        val fixture = newStore()
        fixture.dataStore.edit { it[stringPreferencesKey("engines")] = enginesJsonWithABrokenElement() }

        assertEquals(listOf(engineB.copy(credential = "")), fixture.store.load())
    }

    @Test
    fun noCredentialBytesAreWrittenAtRest() = runTest {
        val fixture = newStore()
        fixture.store.upsert(engineA.copy(credential = "cred-ATREST"))
        fixture.store.upsert(engineB.copy(credential = "cred-ATREST-TOO"))

        val needles = listOf("cred-ATREST", "cred-ATREST-TOO").map { it.toByteArray(Charsets.UTF_8) }
        val files = tmpFolder.root.walkBottomUp().filter { it.isFile }.toList()
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val bytes = file.readBytes()
            for (needle in needles) {
                assertTrue("$file contains a credential in the clear", bytes.indexOf(needle) < 0)
            }
        }
    }

    @Test
    fun noLogLineEverContainsTheCredential() = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        ShadowLog.clear()
        fixture.store.upsert(engineA)
        fixture.store.upsert(engineB)

        val restarted = restart(fixture, cipher)
        assertEquals(listOf(engineA, engineB), restarted.store.load())
        cipher.rotateKey()
        val degraded = restarted.store.load()
        assertEquals(listOf("", ""), degraded.map { it.credential })
        restarted.store.pin(engineA.engineId, "dev-1", "MacBook Pro")
        restarted.store.remove(engineB.engineId)
        restarted.store.clearAll()

        val logged = ShadowLog.getLogs().mapNotNull { it.msg }
        assertTrue(
            "a log leaked a credential: $logged",
            logged.none { it.contains("cred-AAA") || it.contains("cred-BBB") },
        )
    }

    @Test
    fun clearAllResetsTheStore() = runTest {
        val fixture = newStore()
        fixture.store.upsert(engineA)

        fixture.store.clearAll()

        assertEquals(emptySet<String>(), fixture.prefNames())
        assertFalse(fixture.store.has(engineA.engineId))
        assertEquals(emptyList<PairedEngine>(), fixture.store.load())
    }

    // android-next#02: Tailcat records ------------------------------------

    private fun tcKey(engineId: String) = stringPreferencesKey("tc:$engineId")

    @Test
    fun aTailcatRecordRoundTripsThroughARestart() = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        val tailcat = newTailcatEngine()
        fixture.store.upsert(engineA)
        fixture.store.upsert(tailcat)

        val restarted = restart(fixture, cipher)

        assertEquals(listOf(engineA, tailcat), restarted.store.load())
        assertTrue(tcKey(tailcat.engineId).name in restarted.prefNames())
        assertFalse("a direct engine has no tc: key", tcKey(engineA.engineId).name in restarted.prefNames())
    }

    @Test
    fun aRecordStoredBeforeTailcatLoadsAsDirect() = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        fixture.store.upsert(engineA)
        // Strip the transport key, as a pre-android-next#02 build wrote it.
        fixture.dataStore.edit { prefs ->
            val array = JSONArray(prefs[stringPreferencesKey("engines")]!!)
            array.getJSONObject(0).remove("transport")
            prefs[stringPreferencesKey("engines")] = array.toString()
        }

        val restarted = restart(fixture, cipher)

        assertEquals(listOf(engineA), restarted.store.load())
        assertEquals(EngineTransport.Direct, restarted.store.load().single().transport)
    }

    @Test
    fun noTailcatSecretIsWrittenAtRestOrInTheMetadata() = runTest {
        val fixture = newStore()
        val tailcat = newTailcatEngine()
        fixture.store.upsert(tailcat)
        val route = tailcat.transport as EngineTransport.Tailcat

        val engines = fixture.dataStore.data.first()[stringPreferencesKey("engines")]!!
        assertFalse("the address is in the metadata", engines.contains(route.address))
        val needles = listOf(route.address, route.clientState, "SECRET").map { it.toByteArray(Charsets.UTF_8) }
        for (file in tmpFolder.root.walkBottomUp().filter { it.isFile }) {
            val bytes = file.readBytes()
            for (needle in needles) {
                assertTrue("$file holds a Tailcat secret in the clear", bytes.indexOf(needle) < 0)
            }
        }
    }

    @Test
    fun aCredentialBlobNeverDecryptsAsATailcatRoute() = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        val tailcat = newTailcatEngine()
        fixture.store.upsert(tailcat)
        // Swap the credential blob into the tc: slot: the distinct AAD refuses it.
        val credBlob = fixture.credValue(tailcat.engineId)!!
        fixture.dataStore.edit { it[tcKey(tailcat.engineId)] = credBlob }

        val restarted = restart(fixture, cipher)
        val loaded = restarted.store.load().single()

        assertEquals("", loaded.credential)
        assertEquals(EngineTransport.Tailcat(address = "", clientState = ""), loaded.transport)
    }

    @Test
    fun aLostTailcatBlobParksTheEngineInsteadOfDialingWithoutAnAddress() = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        val tailcat = newTailcatEngine()
        fixture.store.upsert(tailcat)
        fixture.dataStore.edit { it.remove(tcKey(tailcat.engineId)) }
        ShadowLog.clear()

        val restarted = restart(fixture, cipher)
        val loaded = restarted.store.load().single()

        assertEquals(tailcat.engineId, loaded.engineId)
        assertEquals("", loaded.credential)
        assertEquals(EngineTransport.Tailcat(address = "", clientState = ""), loaded.transport)
        val logged = ShadowLog.getLogs().mapNotNull { it.msg }
        assertTrue(logged.none { it.contains("SECRET") })
    }

    @Test
    fun aLostKeyDegradesATailcatEngineToAnEmptyCredential() = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        fixture.store.upsert(newTailcatEngine())
        cipher.rotateKey()

        val restarted = restart(fixture, cipher)
        val loaded = restarted.store.load().single()

        assertEquals("", loaded.credential)
        assertEquals(EngineTransport.Tailcat(address = "", clientState = ""), loaded.transport)
    }

    @Test
    fun repairingATailcatEngineAsDirectDropsItsRoute() = runTest {
        val fixture = newStore()
        val tailcat = newTailcatEngine()
        fixture.store.upsert(tailcat)

        fixture.store.upsert(
            tailcat.copy(baseUrl = "http://10.0.2.2:27655", transport = EngineTransport.Direct),
        )

        assertFalse(tcKey(tailcat.engineId).name in fixture.prefNames())
        assertEquals(EngineTransport.Direct, fixture.store.load().single().transport)
    }

    @Test
    fun orphanTailcatKeysDisappearOnTheNextWriteAndWithRemove() = runTest {
        val fixture = newStore()
        val tailcat = newTailcatEngine()
        fixture.store.upsert(tailcat)
        fixture.dataStore.edit { it[tcKey("orphan-id")] = Base64.getEncoder().encodeToString(ByteArray(28)) }

        fixture.store.upsert(engineA)
        assertFalse(tcKey("orphan-id").name in fixture.prefNames())

        fixture.store.remove(tailcat.engineId)
        assertFalse(tcKey(tailcat.engineId).name in fixture.prefNames())
        assertEquals(listOf(engineA), fixture.store.load())
    }

    @Test
    fun noLogLineEverContainsATailcatSecret() = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        ShadowLog.clear()
        val tailcat = newTailcatEngine()
        fixture.store.upsert(tailcat)
        val restarted = restart(fixture, cipher)
        restarted.store.load()
        cipher.rotateKey()
        restarted.store.load()
        restarted.store.remove(tailcat.engineId)

        val logged = ShadowLog.getLogs().mapNotNull { it.msg }
        assertTrue("a log leaked a Tailcat secret: $logged", logged.none { it.contains("SECRET") })
    }

    private suspend fun writeOrphan(fixture: Fixture) {
        fixture.writeCred("orphan-id", Base64.getEncoder().encodeToString(ByteArray(28)))
    }

    // Rooms (ADR 0011, ticket 04): the `room:` blob class ---------------

    private fun roomKey(roomId: String) = stringPreferencesKey("room:$roomId")

    private fun roomRecord(roomId: String = "r-1", secret: String = "room-SECRET-1"): RoomRecord =
        RoomRecord(
            roomId = roomId,
            label = "Home fleet",
            secret = secret,
            members = listOf(
                RoomMemberRecord(label = "desktop", address = "http://10.0.2.2:8123"),
                RoomMemberRecord(label = "server", address = "tcSECRETADDRESS1"),
            ),
        )

    private fun roomEngine(record: RoomRecord): PairedEngine =
        newEngine(engineId = "a-room-engine").copy(
            room = EngineRoomRef(roomId = record.roomId, label = record.label),
        )

    @Test
    fun aRoomBlobRoundTripsThroughARestart(): Unit = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        val record = roomRecord()
        fixture.store.upsert(roomEngine(record))
        fixture.store.upsertRoom(record)

        val restarted = restart(fixture, cipher)
        val rooms = restarted.store.loadRooms()

        assertEquals(listOf(record), rooms)
    }

    @Test
    fun loadRoomsDerivesTheListFromEngineMetadataOncePerRoom(): Unit = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        val record = roomRecord()
        // Two engines joined the same room: one blob, one record.
        val second = newEngine(engineId = "a-second-room-engine").copy(
            room = EngineRoomRef(roomId = record.roomId, label = record.label),
        )
        fixture.store.upsert(roomEngine(record))
        fixture.store.upsert(second)
        fixture.store.upsertRoom(record)

        assertEquals(listOf(record), fixture.store.loadRooms())
    }

    @Test
    fun aRoomWithoutItsBlobIsSkippedNotThrown(): Unit = runTest {
        val fixture = newStore()
        val record = roomRecord()
        fixture.store.upsert(roomEngine(record))
        ShadowLog.clear()

        assertEquals(emptyList<RoomRecord>(), fixture.store.loadRooms())
        val logged = ShadowLog.getLogs().mapNotNull { it.msg }
        assertTrue(
            "expected the missing-blob log, got $logged",
            logged.any { it == "room blob missing room=${record.roomId}" },
        )
        // The engine itself still loads.
        assertEquals(1, fixture.store.load().size)
    }

    @Test
    fun aCorruptedRoomBlobIsSkippedAndNeverLogsItsSecret(): Unit = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        val record = roomRecord(secret = "room-SECRET-LEAKY")
        fixture.store.upsert(roomEngine(record))
        fixture.store.upsertRoom(record)
        ShadowLog.clear()
        cipher.rotateKey()

        assertEquals(emptyList<RoomRecord>(), fixture.store.loadRooms())
        val logged = ShadowLog.getLogs().mapNotNull { it.msg }
        assertTrue("a log leaked the room secret: $logged", logged.none { it.contains("room-SECRET-LEAKY") })
    }

    @Test
    fun aRoomBlobNeverDecryptsAsACredential(): Unit = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        val record = roomRecord()
        fixture.store.upsert(roomEngine(record))
        fixture.store.upsertRoom(record)
        // Swap the room blob into the cred: slot of the room engine — the
        // distinct AAD (…v1:room:…) refuses it. (Read the blob first: a
        // `data.first()` inside `edit`'s transaction is a second store
        // hop the fixture does not need.)
        val roomBlob = fixture.dataStore.data.first()[roomKey(record.roomId)]
        assertTrue("the rig wrote no room blob to swap", roomBlob != null)
        fixture.dataStore.edit { it[credKey("a-room-engine")] = roomBlob!! }

        assertEquals(listOf(record), fixture.store.loadRooms())
        assertEquals("", fixture.store.load().single().credential)
    }

    @Test
    fun noRoomSecretOrAddressIsWrittenAtRest(): Unit = runTest {
        val fixture = newStore()
        val record = roomRecord(secret = "room-ATREST-SECRET")
        fixture.store.upsert(roomEngine(record))
        fixture.store.upsertRoom(record)

        val needles = listOf("room-ATREST-SECRET", "tcSECRETADDRESS1").map { it.toByteArray(Charsets.UTF_8) }
        for (file in tmpFolder.root.walkBottomUp().filter { it.isFile }) {
            val bytes = file.readBytes()
            for (needle in needles) {
                assertTrue("$file holds a room secret in the clear", bytes.indexOf(needle) < 0)
            }
        }
        // The metadata carries only the id and the label.
        val engines = fixture.dataStore.data.first()[stringPreferencesKey("engines")]!!
        assertTrue(!engines.contains("room-ATREST-SECRET"))
        assertTrue(!engines.contains("tcSECRETADDRESS1"))
        assertTrue(engines.contains("\"r-1\""))
        assertTrue(engines.contains("Home fleet"))
    }

    @Test
    fun forgettingTheLastEngineOfARoomDropsItsBlob(): Unit = runTest {
        val fixture = newStore()
        val record = roomRecord()
        fixture.store.upsert(roomEngine(record))
        fixture.store.upsertRoom(record)
        assertTrue(roomKey(record.roomId).name in fixture.prefNames())

        fixture.store.remove(roomEngine(record).engineId)

        assertFalse(roomKey(record.roomId).name in fixture.prefNames())
        assertEquals(emptyList<RoomRecord>(), fixture.store.loadRooms())
    }

    @Test
    fun anOrphanRoomBlobDisappearsOnTheNextWrite(): Unit = runTest {
        val fixture = newStore()
        val record = roomRecord(roomId = "orphan-room")
        fixture.store.upsert(roomEngine(record))
        fixture.store.upsertRoom(record)
        // The engine forgets its room (a re-pair that drops the tag): the
        // blob's room now has no referencing engine.
        fixture.store.upsert(roomEngine(record).copy(room = null))

        fixture.store.upsert(engineA)
        assertFalse(roomKey("orphan-room").name in fixture.prefNames())
    }

    @Test
    fun aFailingRoomEncryptThrowsAndChangesNothing(): Unit = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        val record = roomRecord()
        fixture.store.upsert(roomEngine(record))
        val bytesBefore = storeFile().readBytes()
        cipher.failEncrypt = true

        val thrown: Throwable? = try {
            fixture.store.upsertRoom(record)
            null
        } catch (expected: CredentialStoreException) {
            expected
        }

        assertNotNull("upsertRoom must throw CredentialStoreException", thrown)
        assertTrue("the atomic edit wrote nothing", storeFile().readBytes().contentEquals(bytesBefore))
    }

    @Test
    fun noLogLineEverContainsARoomSecret(): Unit = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        ShadowLog.clear()
        val record = roomRecord(secret = "room-LOG-SECRET")
        fixture.store.upsert(roomEngine(record))
        fixture.store.upsertRoom(record)
        val restarted = restart(fixture, cipher)
        restarted.store.loadRooms()
        cipher.rotateKey()
        restarted.store.loadRooms()

        val logged = ShadowLog.getLogs().mapNotNull { it.msg }
        assertTrue("a log leaked a room secret: $logged", logged.none { it.contains("room-LOG-SECRET") })
    }

    // Fleet follow (fleet-follow ticket 04): the persist sequence of a
    // pairing, its fleet join, a re-run and a re-pair never duplicates a row.

    @Test
    fun aFleetFollowAndItsReRunsNeverDuplicateEngineRows(): Unit = runTest {
        val cipher = FakeSecretCipher()
        val fixture = newStore(cipher)
        val fleet = roomRecord(roomId = "fleet-1", secret = "fleet-SECRET-1")
        val ref = EngineRoomRef(roomId = fleet.roomId, label = fleet.label)
        // Paired before the fleet existed, then the pairing that introduces it.
        val server = newEngine(engineId = "server-1", credential = "cred-server", deviceId = "dev-server")
        val desktop = newEngine(engineId = "desktop-1", credential = "cred-desktop", deviceId = "dev-desktop")
        fixture.store.upsert(server)
        fixture.store.upsert(desktop)
        // The join result: the desktop adopted by tag (same id, same
        // credential), the server adopted by tag, one new member.
        val nas = newEngine(engineId = "nas-1", credential = "cred-nas", deviceId = "dev-nas").copy(room = ref)
        val join = listOf(desktop.copy(room = ref), server.copy(room = ref), nas)
        fixture.store.upsertRoom(fleet)
        join.forEach { fixture.store.upsert(it) }
        // A re-run of the follow persists the same room and records again,
        // and a re-pair of the desktop (fresh credential, tag kept) lands too.
        fixture.store.upsertRoom(fleet)
        join.forEach { fixture.store.upsert(it) }
        val repaired = desktop.copy(credential = "cred-desktop-2", sessionId = "s-2", room = ref)
        fixture.store.upsert(repaired)

        val restarted = restart(fixture, cipher)
        val loaded = restarted.store.load()

        // One row per engine, pairing order kept, the latest record wins.
        assertEquals(listOf("server-1", "desktop-1", "nas-1"), loaded.map { it.engineId })
        assertEquals(loaded.size, loaded.mapNotNull { it.deviceId }.toSet().size)
        assertEquals("cred-desktop-2", loaded.single { it.engineId == "desktop-1" }.credential)
        assertTrue(loaded.all { it.room == ref })
        // One room, one blob; its secret only in the encrypted blob.
        assertEquals(listOf(fleet), restarted.store.loadRooms())
        assertEquals(1, restarted.prefNames().count { it.startsWith("room:") })
        val engines = restarted.dataStore.data.first()[stringPreferencesKey("engines")]!!
        assertFalse(engines.contains("fleet-SECRET-1"))
        for (file in tmpFolder.root.walkBottomUp().filter { it.isFile }) {
            assertTrue(
                "$file holds the fleet secret in the clear",
                file.readBytes().indexOf("fleet-SECRET-1".toByteArray(Charsets.UTF_8)) < 0,
            )
        }
    }

    private fun enginesJsonWithABrokenElement(): String {
        val good = JSONObject()
            .put("engineId", engineB.engineId)
            .put("baseUrl", engineB.baseUrl)
            .put("sessionId", engineB.sessionId)
            .put("label", engineB.label)
            .put("pairedAtMs", engineB.pairedAtMs)
        val broken = JSONObject(good.toString()).apply { remove("baseUrl") }
        return JSONArray().put(broken).put(good).toString()
    }
}
