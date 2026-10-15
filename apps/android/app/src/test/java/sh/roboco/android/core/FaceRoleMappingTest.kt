package sh.roboco.android.core

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.roboco_core.FaceRole

/**
 * `Face` ↔ `FaceRole` mapping (plain JVM, for the merge check): the Kotlin
 * table (`FontFaces.kt`) and the UniFFI enum (`mob:layout/style.rs:11-24`)
 * line up by ordinal, so `FaceRole.entries[face.ordinal]` is a faithful
 * bijection. The names are asserted through a snake-case conversion — the
 * one property the ordinal mapping silently depends on.
 */
class FaceRoleMappingTest {

    @Test
    fun twelveFacesMapOntoTwelveRolesByOrdinal() {
        assertEquals(12, Face.entries.size)
        assertEquals(12, FaceRole.entries.size)
        assertEquals(FaceRole.entries, Face.entries.map { it.role })
        assertEquals(Face.entries, FaceRole.entries.map { it.face })
    }

    @Test
    fun faceNamesSnakeCaseToFaceRoleNames() {
        // "SansSemiboldItalic" -> "SANS_SEMIBOLD_ITALIC": the Kotlin name
        // is the Rust name in snake case, in the same order.
        assertEquals(
            FaceRole.entries.map { it.name },
            Face.entries.map { face -> snake(face.name) },
        )
    }

    @Test
    fun roundTripsPreserveBothSides() {
        for (face in Face.entries) {
            assertEquals(face, face.role.face)
        }
        for (role in FaceRole.entries) {
            assertEquals(role, role.face.role)
        }
    }

    private fun snake(name: String): String =
        name.replace(Regex("(?<!^)(?=[A-Z])"), "_").uppercase()
}
