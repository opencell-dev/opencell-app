# OpenCell Android app

Bring-up and diagnostics app for an OpenCell terminal (Meshnology W12:
ESP32-S3 + LR2021) over BLE. It scans for terminals, keeps one connected
(also with the screen off), shows the decoded STATUS, sends UP payloads,
logs DOWN payloads, and runs the bench loopback test. Voice and call control
are not here yet; the data layer is built so they can sit on top of it.

The BLE contract is `firmware/components/lc_term/include/lc_term_gatt.h`.
Its Kotlin mirror is `core/src/main/kotlin/org/opencell/core/protocol/GattContract.kt`.

## Build

Needs JDK 17 or newer and the Android SDK with platform `android-37`
(current AndroidX needs compileSdk 37; the app targets 36):

```sh
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # if ANDROID_HOME isn't set
./gradlew testDebugUnitTest assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`.
`testDebugUnitTest` runs the JVM tests of both modules: the `:core` protocol,
link and loopback tests, and the Robolectric UI smoke tests in `:app`.

## Install on the Galaxy Z Fold 7

1. Turn on Developer options: Settings > About phone > Software information,
   then tap **Build number** seven times.
2. In Settings > Developer options, turn on **USB debugging**, or
   **Wireless debugging** for a cable-free install.
   - USB: plug in the phone and accept the RSA prompt.
   - Wireless: in Wireless debugging, tap **Pair device with pairing code**. Then run
     `adb pair <ip>:<pair-port> <code>` and `adb connect <ip>:<port>`.
3. Install (or update in place):
   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## Permissions

The app asks for these on first use (the **Grant** card on the Terminal tab):

| Permission | Why |
|---|---|
| Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) | Find and connect to terminals. Scanning is declared `neverForLocation`, so no location permission is needed. |
| Notifications (`POST_NOTIFICATIONS`) | The ongoing "Terminal link" notification of the foreground service. |

The app also declares these, and they need no prompt:
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`: keep the link up with the screen off.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: open the system battery dialog.

## Samsung battery settings (do this once)

One UI kills background apps aggressively. A foreground service helps, but
for a link that must survive hours with the screen off, also do all of the following:

- Settings > Apps > OpenCell > Battery > **Unrestricted**. The **Allow**
  button on the "Background use" card opens the same system dialog.
- Settings > Battery > Background usage limits > **Never sleeping apps** > add OpenCell.
- Optional: Settings > Display > **Continue apps on cover screen** > OpenCell.
  With this on, the app stays open when you fold the phone. With it off, the
  app goes to the background, and the link keeps running in the service.

## Using it

- **Terminal**: tap **Scan**. Terminals advertise as `OpenCell-XXXXXXXX`, where
  the suffix is the TMID. A terminal stops advertising while a phone is
  connected. Tap one to connect; the app reconnects by itself (1 s, 2 s, 4 s
  … 30 s) until you tap **Disconnect**, here or in the notification.
  **Demo terminal** is a simulated terminal plus echoing cell, for trying the
  app without hardware.
- **Status**: the decoded STATUS (state, band, tier, RSSI, SNR, TMID, frame,
  cell seed). It updates live on each notification; **Refresh** reads it.
- **Console**: send text (UTF-8) or hex (`48 45 4c`, `48-45-4C`, `0x48 …`).
  - Limits: at most 20 bytes per payload, and at most 8 bytes while the terminal is IDLE (RACH).
  - Retries: ATT **0x80** ("not now") is retried after 120, 240, 480 and 960 ms,
    then every 1 s, for 8 attempts in all (about 4.8 s). ATT **0x0D** ("too long")
    is never retried.
  - Testing 0x0D: turn on the "Allow over 20 bytes" switch to send an oversize
    payload.
  - Every UP, DOWN and link event is logged with a timestamp, in hex and ASCII.

### Loopback test against a terminal

This mirrors the bench test: the cell echoes each UL payload on DL.

1. Bring up the test cell that echoes, and power the terminal. Wait until the
   Status tab shows **Granted**. **Idle** also works, but only for probes of
   8 bytes or less.
2. Open **Loopback**. The defaults are 20 probes, one every 1000 ms, payload
   `HELLO`, and a sequence tag, so each probe is `HELLO#00`, `HELLO#01`, …
   (8 bytes). The tag matches each echo to its probe exactly.
3. Tap **Start**. Each probe shows its latency, or "lost" / the refusal reason.
   The summary shows:
   - counts: sent, echoed, lost, refused, and stray DOWNs;
   - latency: mean, median, min and max;
   - how many echoes arrived within 500 ms.

   Latency runs from the start of the UP write the terminal accepted to the
   DOWN notification. It includes the BLE write round trip, the wait for the
   UL slot, the cell's turnaround and the notification.
   The bench measured about 0.43 s.

## Architecture

```
:core  (pure Kotlin/JVM, unit-tested)
  protocol/  GattContract, TerminalStatus decoder, payload rules, Hex, Tmid
  link/      TerminalLink  <- the seam every upper layer uses (opaque ≤20-byte payloads)
             LinkManager   (connect, reconnect with backoff, DOWN/STATUS flows)
             UplinkSender  (validation + 0x80 retry policy, ordered sends)
  loopback/  LoopbackRunner, LoopbackStats
  session/   TerminalSession (link + sender + console + loopback), ConsoleLog
  sim/       SimulatedTerminal (demo mode and tests)
:app   (Android)
  ble/       GattConnector/GattConnection (serialized GATT ops), BleScanner
  data/      TerminalRepository (app-scoped owner of the session)
  service/   LinkService (connectedDevice foreground service + notification)
  ui/        Compose: navigation suite, list-detail Terminal/Status, Console, Loopback
```

The link lives in the Application, not in an Activity or ViewModel.
Folding or unfolding the phone, rotating it, or closing the UI never
disconnects. The foreground service keeps the process alive while a link is
wanted.

Voice and call control will plug into `TerminalLink`, using the `downlink`
flow and `writeUp`. A codec should write once per 120 ms frame and drop a
frame on 0x80 instead of retrying, because a late voice frame is useless.
`UplinkSender` is for ordered control traffic. Codec2 1200/1300 packs three
40 ms frames into about 19.5 bytes, which fits one 20-byte payload per frame.
