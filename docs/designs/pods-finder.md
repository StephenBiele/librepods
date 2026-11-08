# Pods Finder: signal-strength finder and left-behind alert

Status: implemented (uncommitted) in the Android app of this fork.

## Problem

Android users of AirPods lose them with no recourse inside the app. iOS offers a
Find My experience; LibrePods has no equivalent, and the Find My network path is
blocked (the accessory's master beacon secret is never exposed over AACP, only the
IRK and ENC proximity keys). The Android OS *does* alert "tracking device nearby",
which proves the pods still advertise as Find My accessories, but that alert belongs
to Play Services, not to us.

## Goal

Give the user two things that work with zero root and zero Apple account:

1. A warmer/colder finder for pods that are nearby but not visible.
2. A left-behind alert when the pods stop advertising close to the phone.

## Non-goals

- Decrypting Apple's crowd-sourced Find My location reports.
- Playing a tone in the buds or case (no AACP opcode is mapped for it).
- Any iCloud authentication.

## Signal model

`BLEManager` receives adverts filtered to manufacturer ID 76 whose first bytes are
`07 19`, at least 20 bytes long. The finder is fed only adverts whose address passes the
stored-IRK verification (or is already cached as verified) — **not** every Apple advert,
since a neighbour's AirPods would otherwise move our meter. The RSSI is pushed before the
payload de-duplication, because RSSI changes even when the bytes are identical. Without a
stored IRK no advert verifies, so the finder shows nothing; that is a hard prerequisite.

The raw value is noisy, so `PodsFinder` applies an EMA (alpha 0.25) for the distance
readout from the log-distance path loss model (`RSSI_1m = -59 dBm`, `n = 2.4`, a typical
indoor-with-body-shadowing value).

The trend is computed differently from the distance, deliberately: it compares the
**median raw RSSI of the last 3 s against the median of the previous 3 s**, with a 3 dB
threshold and two consecutive agreeing samples required before the shown trend changes.
Comparing consecutive EMA outputs was wrong — with alpha 0.25 a 1.5 dB change in smoothed
output requires a 6 dB jump in the raw reading, so slow walking read as "steady" while a
single loud advert read as movement. Medians reject single-sample spikes.

The UI shows the distance as an approximation and a 0..1 bar mapped over
-100..-40 dBm. The description string tells the user the number is a guess.

## Left-behind state machine

- `onDeviceStatusChanged` and `onBroadcastFromNewAddress` record `lastAdvertSeenAt` and
  cancel any pending alert.
- `onDeviceDisappeared` schedules a check 120 s later, and so does the AACP socket-loss
  path — a socket that drops and never returns must not suppress the alert forever.
- The delayed check fires only if: the preference is on, the AACP socket is not connected,
  and the last advert is older than the grace period. If adverts were still arriving when
  the timer fired, it re-arms for the remainder of the grace period instead of dropping the
  alert; an earlier version silently lost every departure that began while the pods were
  still nearby.
- `onDestroy` cancels the pending runnable.
- The notification uses its own high-importance channel so it can be silenced
  independently of the connection notification.

## Entry points

- Connected: a row in the AirPods settings list.
- Disconnected: a button in the "not connected" view, which is the state in which a
  lost-pods user actually is.

## Open questions

1. `-59 dBm @ 1 m` and `n = 2.4` are literature values, not measured on AirPods Pro 3.
   Calibration on a real device may need a per-model offset.
2. 120 s grace is a guess; too short and a walk to the kitchen alerts, too long and
   the user is already on the bus. It currently starts from stale-device cleanup, which is
   itself 15 s after the last advert, so the real delay is 120–135 s.
3. The alert does not record the last known location, because that would require a
   location permission prompt for a BLE-only feature.
4. The finder only works while the phone's scan is running; scan behaviour when the
   app is backgrounded or killed is untested.
5. **Alert state is in memory only.** Service or process death during the grace period
   loses the alert; durable scheduling (WorkManager) is a separate design decision, not
   implemented.
6. Bluetooth-disabled, scan-failure and denied notification-permission paths are not
   handled beyond a log line.
