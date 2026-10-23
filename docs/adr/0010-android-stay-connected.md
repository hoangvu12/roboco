# Android can stay connected in the background, as an opt-in foreground service

ADR 0009 kept the Android app's foreground service (`RunWatchService`) up
only while a run is Working. A run started from the phone often ends while
the user is in another app: the service stopped 15 s later, Android 14+
froze the cached process about 10 s after that, and the engine dropped the
silent sockets 45 s later, so tapping the Done notification landed on a
reconnect. With no push channel (ADR 0004, no FCM) the only way to keep the
connections live is to keep the process in a foreground service. We
decided:

- **Linger.** The service outlives the last Working run by **2 minutes**
  (it was a 15 s queue-handoff grace). That covers the usual gap between
  the Done notification and the user's tap, so the app comes back to live
  sockets. A longer linger would mostly leave the ongoing "Staying
  connected" notification up for no one.
- **Stay connected in background** (Settings → Background, off by
  default). While on and at least one engine is paired, the service runs
  whether or not a run is Working and whether or not notifications are on,
  with a permanent, silent, low-importance notification ("Staying
  connected"). Off restores the run-only rules.
- **Service type.** The manifest declares `dataSync|specialUse`. Run
  watching and the linger keep `dataSync`. Stay connected uses
  `specialUse` on API 34+, because on API 35+ `dataSync` gets 6 h per
  24 h in the background and then `onTimeout`. The other types without a
  time limit don't fit: `remoteMessaging` is for relaying text messages
  between devices, and `connectedDevice` needs a hardware or
  `CHANGE_NETWORK_STATE`-style prerequisite that the app doesn't otherwise
  use. `specialUse` needs `FOREGROUND_SERVICE_SPECIAL_USE` and a
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification. Only Play review reads
  that justification, and Roboco ships no Play build. Below API 34 Stay
  connected runs as `dataSync`, which has no limit there. Flipping the
  setting while the service is up re-enters the foreground with the new
  type.

## Consequences

- The service still starts only while the app is visible (Android 12+
  forbids background starts). The Settings toggle satisfies that, and so
  does every later app open. The service is `START_NOT_STICKY`: if the
  system kills the process, Stay connected resumes at the next app open.
- With Stay connected off, a run that starts while the app is backgrounded
  and the service is down is not covered. No background-start exemption
  applies, and the frozen process never sees the run start. A run that
  starts while the service is up (during another run, the linger or Stay
  connected) is kept.
- No wake locks, still. The process stays out of the cached/frozen set and
  Doze leaves a foreground service's network alone; the engine's 15 s
  pings keep the sockets busy. Stay connected costs some battery, which
  the setting's footer says.
- The "service runs only while a run is active" line of ADR 0009 is
  amended by this ADR.
