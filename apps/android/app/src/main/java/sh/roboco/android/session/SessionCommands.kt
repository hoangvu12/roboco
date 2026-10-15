package sh.roboco.android.session

import sh.roboco.android.composer.DeliveryMode
import sh.roboco.android.composer.StagedImage
import uniffi.roboco_core.BusyPolicy
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.FileMatch
import uniffi.roboco_core.OutgoingAttachment
import uniffi.roboco_core.SendRequest
import uniffi.roboco_core.SessionHandle

/**
 * The FFI the composer needs (android-ui-parity #78): send, interrupt
 * and the `@file` search — the port of `CoreSessionSource.swift:110-131,
 * 247-249`. All suspend, all called **off main** by
 * [SessionComposerBinding], and fakeable in tests and the gallery.
 *
 * iOS's `send` is synchronous and returns `Bool`; Roboco's is an engine
 * round trip, so it is `suspend` and reports failures by throwing (the
 * binding turns that into the send-failure pill and a kept draft).
 */
interface SessionCommands {
    /**
     * Exactly one `SessionHandle.send` per call (see [busyPolicyFor]);
     * the `SendOutcome` is ignored, as on iOS.
     */
    suspend fun send(text: String, images: List<StagedImage>, mode: DeliveryMode)

    /** `handle.interrupt()` — the running turn's stop. */
    suspend fun interrupt()

    /** `client.searchFiles` over the chat's workspace, `spaceId = nil`. */
    suspend fun searchFiles(query: String): List<FileMatch>
}

/**
 * The delivery mode → `BusyPolicy` table (android-app#08 §Send rule 6).
 * **Never interrupt-then-send**: "Stop and send" is one send with
 * [BusyPolicy.INTERRUPT] — Roboco's `interrupt` pauses the queue and
 * cancels held sends, so the iOS order would strand the message
 * (upstream #609/#621's trap).
 */
internal fun busyPolicyFor(mode: DeliveryMode): BusyPolicy = when (mode) {
    DeliveryMode.Queue -> BusyPolicy.QUEUE
    DeliveryMode.Steer -> BusyPolicy.STEER
    DeliveryMode.Interrupt -> BusyPolicy.INTERRUPT
}

/**
 * The live [SessionCommands] over one open session's client and handle
 * (`CoreSessionSource`'s `handle` / `client`). Built once per route by
 * ticket 76's `SessionScreenRoute` and handed to 78's
 * `rememberSessionComposer`.
 */
class CoreSessionCommands(
    private val client: CoreClient,
    private val handle: SessionHandle,
    private val chatId: String,
    /**
     * `composer.host.deviceId` as of the last chrome refresh (ticket 51's
     * `SessionController.hostDeviceId`) — read at call time, like iOS's
     * cached `hostDevice` (`CoreSessionSource.swift:42`).
     */
    private val hostDeviceId: () -> String,
) : SessionCommands {

    override suspend fun send(text: String, images: List<StagedImage>, mode: DeliveryMode) {
        handle.send(
            SendRequest(
                text = text,
                attachments = images.map { image ->
                    OutgoingAttachment(
                        name = image.name,
                        // iOS `AppModel.swift:647`: staged images are JPEG.
                        mimeType = "image/jpeg",
                        data = image.bytes,
                    )
                },
                worktree = null,
                busy = busyPolicyFor(mode),
            ),
        )
    }

    override suspend fun interrupt() = handle.interrupt()

    override suspend fun searchFiles(query: String): List<FileMatch> =
        client.searchFiles(deviceId = hostDeviceId(), chatId = chatId, spaceId = null, query = query)
}
