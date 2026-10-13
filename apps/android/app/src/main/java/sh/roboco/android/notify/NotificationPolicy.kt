package sh.roboco.android.notify

/**
 * The notification delivery gates (android-app#28's policy table). Pure —
 * the sink consults it before every post, so the matrix is unit-testable
 * without Android.
 *
 * | Gate | Rule | Source |
 * |---|---|---|
 * | Foreground | **No banner and no chime** while the process is foreground (≥ STARTED). Covers "the chat currently open". | iOS `willPresent` → `[]` (PushNotifications.swift:160-167) |
 * | Master | `prefs.enabled && status == Granted` | `MoreViewController.swift:126-129` |
 * | Kind | the per-kind pref; LinkLost rides the `.failed` pref | #99 |
 */
object NotificationPolicy {

    /**
     * Whether a session event may post. The foreground gate is absolute
     * (iOS shows nothing in front); the master gate pairs the pref with
     * the runtime permission; the kind gate is the per-kind switch.
     */
    fun sessionEventAllowed(
        kind: NotificationKind,
        prefs: NotificationPrefs.Snapshot,
        status: NotificationStatus,
        appInForeground: Boolean,
    ): Boolean =
        !appInForeground &&
            masterOn(prefs, status) &&
            prefs.isOn(kind)

    /** LinkLost uses the `.failed` kind's gate and copy category. */
    fun linkLostAllowed(
        prefs: NotificationPrefs.Snapshot,
        status: NotificationStatus,
        appInForeground: Boolean,
    ): Boolean = sessionEventAllowed(NotificationKind.Failed, prefs, status, appInForeground)

    /**
     * The master + permission gate: the state the foreground service's
     * start rule calls `notificationsOn` (the FGS exists to deliver
     * notifications, so it runs only when they can appear).
     */
    fun masterOn(prefs: NotificationPrefs.Snapshot, status: NotificationStatus): Boolean =
        prefs.enabled && status == NotificationStatus.Granted
}
