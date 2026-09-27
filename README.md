# OpenCell Android app

The phone side of an OpenCell terminal (Meshnology W12: ESP32-S3 + LR2021),
over BLE. The terminal runs all signalling (activation, MILENAGE
registration, call control) and holds every key; the app is the user
interface and holds no secrets. The app:

- activates a terminal from a one-time QR code (camera or pasted text);
- shows the registered number, the network's mode (Part 15 / Part 97) and the link;
- places calls, rings for incoming calls itself (a looping ringtone and
  vibration, whatever screen is showing), answers, rejects and hangs up;
- offers a data-frame test in a connected call (voice is not in this step);
- keeps the v1 bring-up tools: terminal list and STATUS, console, loopback test.

The BLE contract (v2) is `firmware/components/lc_term/include/lc_term_gatt.h`.
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
phone state, call-flow, link and loopback tests, and the Robolectric UI tests in `:app`.

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

| Permission | Why | Asked |
|---|---|---|
| Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) | Find and connect to terminals. Scanning is declared `neverForLocation`, so no location permission is needed. | **Grant** card on the Terminal tab |
| Notifications (`POST_NOTIFICATIONS`) | The ongoing "Terminal link" notification, and the incoming-call notification (Answer / Reject, full screen). Ringing itself (the ringtone and vibration) doesn't need it. | Same card; also an **Allow** card on the Phone tab (it also shows when notifications or the calls channel are blocked in Settings), which opens the app's notification settings when the permission is already granted or a request was denied for good |
| Camera (`CAMERA`) | Scanning the activation QR code. Pasting the code works without it. | When you tap **Scan QR code**; if it was denied for good, the Phone tab says so and links to the app's settings |
| Full-screen calls (`USE_FULL_SCREEN_INTENT`) | Incoming calls over the lock screen. Android 14+ grants it by default only to Play-listed calling apps, so allow it once in Settings. Without it a call shows as a heads-up notification. | **Allow** card on the Phone tab, which opens the system setting |

The app also declares these, and they need no prompt:
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`: keep the link up with the screen off.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: open the system battery dialog.
- `VIBRATE`: vibrate for an incoming call.

QR scanning uses CameraX and ZXing: it works offline and without Google Play Services.

## Samsung battery settings (do this once)

One UI kills background apps aggressively. A foreground service helps, but
for a link that must survive hours with the screen off (and ring for calls),
also do all of the following:

- Settings > Apps > OpenCell > Battery > **Unrestricted**. The **Allow**
  button on the "Background use" card opens the same system dialog.
- Settings > Battery > Background usage limits > **Never sleeping apps** > add OpenCell.
- Optional: Settings > Display > **Continue apps on cover screen** > OpenCell.
  With this on, the app stays open when you fold the phone. With it off, the
  app goes to the background, and the link keeps running in the service.

## Using it

### Connect

- **Terminal** tab: tap **Scan**. Terminals advertise as `OpenCell-XXXXXXXX`, where
  the suffix is the TMID. A terminal stops advertising while a phone is
  connected (it takes one connection: stop `tools/ble/oc_ble.py` first). Tap one to
  connect; the app reconnects by itself (1 s, 2 s, 4 s … 30 s) until you tap
  **Disconnect**, here or in the notification. Turning Bluetooth off counts
  as losing the link; turning it back on reconnects at once.
- **Pairing.** The terminal only talks to paired phones. The first time you
  connect, Android asks for a code and the app shows: *Enter the 6-digit code
  shown on the terminal's screen (press PRG to reach the Pairing screen)*. You
  have 30 seconds to enter it, or the terminal ends the attempt and you'll
  need to try again. The terminal's screen jumps to `PAIR CODE` by itself when
  the phone starts pairing. The code changes after every disconnect and every
  wrong try, and 3 wrong tries in a minute lock pairing for 60 s
  (`LOCKED 42S`). A paired phone reconnects without a code; the terminal keeps
  up to 3 phones. A failed or cancelled pairing is not retried by itself (each
  try costs one of the terminal's 3): tap **Retry**.
- **"The terminal forgot this phone."** Holding PRG for 5 s on the terminal's
  Pairing screen clears its pairings, but the phone keeps its half. Tap
  **Bluetooth settings**, open the terminal (`OpenCell-…`), choose **Forget**,
  then **Retry** and enter the new code. (Apps can't remove a pairing
  themselves.)
- **Demo terminal** is a simulated terminal and network, for trying everything
  without hardware. It offers **Use a demo code** for activation, its test peer
  (**Test peer**, +883 606 555 0100) answers after 3 s, calling your own number
  is busy, and +883 606 555 9999 is unreachable.

### Activate (Phone tab)

1. Get a code: from the portal, or on the bench `lcbench mkqr --number +883…`
   (it prints the QR code and the `opencell:1:…` text).
2. **Scan QR code**, or paste the text and tap **Check code**. The app checks the
   code exactly like the terminal (prefix, length, base64url, version, CRC) and
   shows its number and expiry before sending anything.
3. **Activate**. The terminal agrees its keys with the network (a few seconds),
   then registers. Failures say why: unknown code, code already used, expired,
   bad tag, or no answer from the network.

**Activate with a new code** (menu) replaces the terminal's keys only if the
network accepts the new code. **Deactivate terminal** (menu, with a
confirmation) wipes the terminal's keys; the menu is hidden during a call
(and the terminal refuses DEACTIVATE anyway if it ever reaches it).

### Calls

- **Make a call**: type a +883 number (spaces and dashes are fine) and tap **Call**.
  The call screen shows Calling, Ringing, Connected, and at the end the cause
  (busy, no answer, unreachable, rejected, link lost…).
- **Incoming**: the phone rings itself — a looping ringtone and vibration
  from the foreground service, honouring the ringer mode (silent: neither;
  vibrate: vibration only) — while a call is incoming and the terminal link
  is up, whatever screen is showing; neither the call screen nor the
  notification make any sound of their own. While Do Not Disturb is on (any
  interruption filter other than "all", checked when the ring starts) it
  doesn't ring or vibrate at all, even for callers DND would let through;
  the call notification and call screen still appear. The call notification carries
  **Answer** / **Reject** and opens the full-screen call screen over the
  lock screen when allowed (see Permissions above).
- **Connected**: voice is not in this step. **Send 5 test frames** sends the
  frames `tools/ble/oc_ble.py send` sends (`b0 <seq> "oc-send"`, 9 bytes),
  each written once (no retries — a late test frame is as useless as a late
  voice frame would be) and only while the call is still connected; the run
  stops the moment the call ends or the link drops. The bench's test peer
  echoes them, another terminal receives them. The screen counts what comes
  back.
- If the phone loses the terminal during a call, the call goes on in the
  terminal. The terminal doesn't queue events while no phone is connected, so on
  reconnecting the app reads STATUS and shows where the call is; a call that
  started while the phone was away shows as "Unknown caller" and can still be
  answered or rejected. While the link is down the call screen offers
  **Close** (its buttons can't reach the terminal); if the call is still up
  when the terminal is back, it shows again. **Disconnect** (on the link
  notification or the Terminal tab) ends the call on the phone ("the phone
  was disconnected from the terminal"): nothing reconnects after that.

### Diagnostics

- **Status** (Terminal tab): the decoded STATUS (state, band, tier, signalling
  state, RSSI, SNR, TMID, frame, cell seed). It updates live; **Refresh** reads it.
- **Console**: send text (UTF-8) or hex (`48 45 4c`, `48-45-4C`, `0x48 …`) on UP, and see
  every UP, DOWN, EVENT, COMMAND and link event with a timestamp.
  - Limits: at most 18 bytes per frame, and only while the terminal holds a
    grant (ATT **0x80** otherwise).
  - Retries: 0x80 on UP is retried after 120, 240, 480 and 960 ms, then every
    1 s, for 8 attempts in all (about 4.8 s). ATT **0x0D** ("too long") is never
    retried. COMMANDs are never retried: 0x80 there means the terminal is in
    another state, so the app shows why and reads STATUS again; sending the
    same command again while one is still in flight (a double-tap on Answer,
    say) is ignored, not queued.
- **Loopback**: the bench loopback test (a cell that echoes each UL frame on DL).
  It needs a grant: outside a call that means a test cell (`lcbench cell`) that
  keeps the terminal granted. The defaults are 20 probes, one every 1000 ms,
  payload `HELLO` with a sequence tag (`HELLO#00`, …). The summary shows sent,
  echoed, lost, refused, stray DOWNs, and latency.

## Known limitations

- **The terminal's screen is the pairing trust anchor.** The BLE link is
  paired (LE Secure Connections, passkey) and encrypted, so nobody in range
  can send commands, read the link or sniff the activation code without
  pairing, and pairing needs the code on the terminal's OLED. Anyone who can
  see that screen can pair a phone. The app still lists every device that
  advertises the OpenCell service: pairing with the code on the screen in
  front of you is what proves it is your terminal. See `security-model.md`'s
  "BLE hop (terminal ↔ phone)" section on the `ble-pair` branch.
- **Voice isn't in this step.** A connected call has the data-frame test
  above, not audio; see Architecture below for where the codec plugs in.
- The search counts only packets the radio demodulates in the edge tier
  (LoRa SF7, 500 kHz). Other systems raise the noise floor only, and
  packets that fail the LoRa header are not reported.
- BLE STATUS does not carry the terminal's noise floor (shown on its OLED
  Radio/Status screens only); the app shows "No signal" while searching
  when nothing was heard.
- The W12's RSSI is not calibrated against a reference.
- **QR scanning costs APK size.** CameraX and ZXing add about 8.8 MB to the
  unminified release APK (24.5 → 33.3 MB) and 10.8 MB to the debug APK
  (32.0 → 42.8 MB), measured by building the commits before and after QR
  scanning was added (035e598, a270aba). Minification is off.

## Architecture

```
:core  (pure Kotlin/JVM, unit-tested)
  protocol/  GattContract, TerminalStatus (+ SigState), Command, TerminalEvent,
             PhoneNumber (BCD), ActivationQr (+ CRC-16), payload rules, Hex, Tmid
  link/      TerminalLink  <- the seam every upper layer uses (app data, COMMAND, EVENT, STATUS)
             LinkManager   (connect, reconnect with backoff, flows; stops on a pairing problem)
             PairingRules  (auth statuses, stale bond vs failed pairing)
             UplinkSender  (validation + 0x80 retry policy, ordered sends)
  phone/     PhoneReducer  (pure state machine: EVENTs, STATUS byte 3, accepted commands)
             PhoneSession  (commands, resync on connect, in-call data test)
  loopback/  LoopbackRunner, LoopbackStats
  session/   TerminalSession (link + sender + phone + console + loopback), ConsoleLog
  sim/       SimulatedTerminal (terminal + network: demo mode and tests)
:app   (Android)
  ble/       GattConnector/GattConnection (serialized GATT ops), Bonder (createBond +
             ACTION_BOND_STATE_CHANGED), BleScanner
  data/      TerminalRepository (app-scoped owner of the session), PrefsPhoneMemory
  scan/      QrDecoder (ZXing), QrScanner (CameraX)
  service/   LinkService (connectedDevice foreground service, owns ringing via
             RingPlan), CallRinger, CallNotifier, CallActionReceiver
  ui/        Compose: Phone (activation, home, dialer), CallScreen/CallActivity,
             Terminal/Status, Console, Loopback
```

The link lives in the Application, not in an Activity or ViewModel.
Folding or unfolding the phone, rotating it, or closing the UI never
disconnects. The foreground service keeps the process alive while a link is
wanted.

GATT connections are opened only in `ble/GattConnector.kt`, which also bonds
with the terminal (`ble/Bonder.kt`) before touching any characteristic. Above
`Connector`, pairing shows up only as `LinkState.Pairing` (the code hint) and
`LinkState.PairingFailed` (Retry, or Bluetooth settings for a stale bond).

The voice codec will plug into `TerminalLink` next to `PhoneSession`: it should
write once per 120 ms frame between CONNECTED and ENDED and drop a frame on
0x80 instead of retrying, because a late voice frame is useless. Codec2 1200
packs three 40 ms frames into 18 bytes, one app data frame per radio frame.

## Third-party notices

QR codes are decoded with [ZXing](https://github.com/zxing/zxing) (`com.google.zxing:core`),
Copyright ZXing authors, licensed under the Apache License, Version 2.0
(https://www.apache.org/licenses/LICENSE-2.0). The camera viewfinder uses
AndroidX CameraX, also Apache-2.0.
