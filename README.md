# OpenCell Android app

The phone side of an OpenCell terminal (Meshnology W12: ESP32-S3 + LR2021),
over BLE. The terminal runs all signalling (activation, MILENAGE
registration, call control) and holds every key; the app is the user
interface and holds no secrets. The app:

- activates a terminal from a one-time QR code (camera or pasted text);
- shows the registered number, the network's mode (Part 15 / Part 97) and the link;
- places calls from a phone keypad, rings for incoming calls itself (a looping
  ringtone and vibration, whatever screen is showing), answers, rejects and hangs up;
- keeps a call log on the phone (Recents), with a missed-call badge and notification;
- carries voice in a connected call: Codec2 1200, three 40 ms frames in each
  18-byte app data frame, with mute and speaker;
- plays call progress tones itself (ringback, busy, reorder, SIT or number
  unobtainable), North American or UK;
- shows and edits the terminal's scan list (**Channels** tab): where it is looking
  for a cell right now, the channels it tries in order and where each came from,
  up to 4 channels of your own, and when to search outside the list;
- keeps the v1 bring-up tools: terminal list and STATUS, console, loopback test,
  and a demo terminal — behind Developer options, a static code (matching the
  iOS app).

The BLE contract (v4) is `firmware/components/oc_term/include/oc_term_gatt.h`.
v4 adds the scan list: the SCAN characteristic, COMMAND 0x07 and 7 more STATUS bytes.
Firmware older than v4 has no SCAN; the Channels tab then says to update it.
Its Kotlin mirror is `core/src/main/kotlin/org/opencell/core/protocol/GattContract.kt`.
A terminal still on v2-numbering firmware refuses v3 DIAL/ACTIVATE arguments and sends
v2-length EVENTs; the app can't tell that from a genuinely bad argument or number, so it
shows "Terminal firmware uses old numbers: update it" (or, on a refused command, the same
explanation folded into the refusal reason) rather than guessing.

## Build

Needs JDK 17 or newer and the Android SDK with platform `android-37`
(current AndroidX needs compileSdk 37; the app targets 36), NDK
`27.2.12479018` and CMake `3.22.1` (`sdkmanager "ndk;27.2.12479018" "cmake;3.22.1"`)
for the Codec2 library, and a host C compiler for the unit tests (they load a
host build of the same C code):

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
| Notifications (`POST_NOTIFICATIONS`) | The ongoing "Terminal link" notification, the incoming-call notification (Answer / Reject, full screen), and the silent missed-call notification. Ringing itself (the ringtone and vibration) doesn't need it. | Same card; also an **Allow** card on the Phone tab (it also shows when notifications or the calls channel are blocked in Settings), which opens the app's notification settings when the permission is already granted or a request was denied for good |
| Camera (`CAMERA`) | Scanning the activation QR code. Pasting the code works without it. | When you tap **Scan QR code**; if it was denied for good, the Phone tab says so and links to the app's settings |
| Microphone (`RECORD_AUDIO`) | Your side of a call. Without it the other side hears silence; you still hear them. | When a call first connects; again from **Allow microphone** on the call screen |
| Full-screen calls (`USE_FULL_SCREEN_INTENT`) | Incoming calls over the lock screen. Android 14+ grants it by default only to Play-listed calling apps, so allow it once in Settings. Without it a call shows as a heads-up notification. | **Allow** card on the Phone tab, which opens the system setting |

The app also declares these, and they need no prompt:
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`: keep the link up with the screen off.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: open the system battery dialog.
- `VIBRATE`: vibrate for an incoming call.
- `FOREGROUND_SERVICE_MICROPHONE`: keep the microphone during a call with the screen off.
- `MODIFY_AUDIO_SETTINGS`: the call's audio mode and earpiece/speaker/headset choice.

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
  up to 3 phones, and pairing a 4th phone removes the oldest pairing. A failed
  or cancelled pairing is not retried by itself (each try costs one of the
  terminal's 3), not even when Android restarts the app: tap **Retry**.
- **"The terminal forgot this phone."** Holding PRG for 5 s on the terminal's
  Pairing screen clears its pairings, and pairing a 4th phone removes the
  oldest one; either way the phone keeps its half. Tap
  **Bluetooth settings**, open the terminal (`OpenCell-…`), choose **Forget**,
  then **Retry** and enter the new code. (Apps can't remove a pairing
  themselves.)
- **Demo terminal** is a simulated terminal and network, for trying everything
  without hardware. It offers **Use a demo code** for activation, its test peer
  (**Test numbers > Echo test (core 1)**, +883-1-606-555-00100) answers after 3 s, calling your own
  number is busy, and +883-1-606-555-09999 is unreachable. It's a developer
  feature (see **Developer options** below): it only shows up once unlocked.

### Activate (Phone tab)

1. Get a code: from the portal, or on the bench
   `oc-core admin sub issue +883-1-606-555-01234` (it prints the
   `opencell:2:…` text, and the QR code if `qrencode` is installed). A code
   from before numbering v2 (`opencell:1:…`) is refused: ask for a new one.
2. **Scan QR code**, or paste the text and tap **Check code**. The app checks the
   code exactly like the terminal (prefix, length, base64url, version, reserved
   bytes, CRC, number) and shows its number and expiry before sending anything.
3. **Activate**. The terminal agrees its keys with the network (a few seconds),
   then registers. Failures say why: unknown code, code already used, expired,
   bad tag, or no answer from the network.

**Activate with a new code** (menu) replaces the terminal's keys only if the
network accepts the new code. **Deactivate terminal** (menu, with a
confirmation) wipes the terminal's keys; the menu is hidden during a call
(and the terminal refuses DEACTIVATE anyway if it ever reaches it).

### Calls

- **Make a call** (Phone tab, **Keypad**): type the number on the keypad and tap
  the green **Call**. In your own country the national number is enough:
  `606-555-01234`, or `606-555-1234` (a leading 0 of the 5-digit subscriber
  number can be left out); from anywhere, the full `+883-1-606-555-01234`
  (long-press **0** for `+`). The number groups itself as you type; the line
  under it shows the full number that will be dialled
  ([`numbering-plan.md`](https://github.com/opencell-dev/opencell/blob/main/numbering-plan.md)),
  and **Call** is enabled only for a number the dial plan accepts. OpenCell
  carries no emergency calls: 911, 112 and 999 are refused. **Delete** removes a
  digit (long-press: clear); long-press the number to **Paste** or **Copy**
  (a pasted extension or pause, `ext 4`, `x4`, `,`, is left off).
  **Test numbers** calls the echo and playback services of core 1 and core 2.
  Keys sound their DTMF tone (local only, muted in silent and vibrate modes):
  **⋮ > Keypad tones** turns that off. The whole keypad always fits without
  scrolling, on both Fold screens, sideways and in large font: when the line
  card and the readiness card don't fit above it they shrink to one line each
  (tap the line for the whole card), and a short window puts the number beside
  the keys.
- **Recents** (a second tab on the cover screen; beside the keypad on the inner
  screen): every call, newest first by day, outgoing, incoming, missed and
  rejected, with its time, duration (connected time) or how it ended. The call
  button calls back; tapping a row puts its number on the keypad; a long press
  offers Copy number and Delete; **⋮ > Clear call log** empties it. Behind
  Developer options each call also shows its codec and voice counters. The log
  stays on this phone (at most 500 calls), out of cloud backup and out of a
  device-to-device transfer to a new phone (`data_extraction_rules.xml`), and survives
  **Deactivate terminal**. Unseen missed calls show as a count on **Phone** and
  **Recents** and as a silent **Missed call** notification that opens Recents
  (swiped away, it comes back only with the next missed call);
  closing a missed call's screen opens Recents too. A call that starts and ends
  while no phone is connected to the terminal can't be logged (the terminal
  keeps no events for the phone).
  Numbers are shown in the international form, `+883-1-606-555-01234`.
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
- **Connected**: voice, both ways. The first connected call asks for the
  microphone; without it the other side hears silence (the call screen says
  so and offers **Allow microphone**). **Mute** sends silence; **Speaker**
  moves the audio from the earpiece (or a Bluetooth or wired headset, which
  win when connected) to the loudspeaker. Once Developer options are unlocked
  (see below), a small line under the buttons counts voice frames sent, not
  sent (the terminal was busy, 0x80), received and concealed (a lost frame
  replaced by the last one, quieter). Calling the echo service (00100) plays
  your own voice back about a second later.
  The microphone works in the background only if the call started while
  OpenCell was on the screen (Android's rule for the foreground service's
  microphone); otherwise open the app once during the call.
- **Call progress tones** are made on the phone from the call's state; nothing
  about them goes over the air. While an outgoing call rings: ringback. When
  it ends before it connected: busy (busy or rejected, 6 s), reorder (no
  answer, network failure, link lost, 4 s) or, for an unreachable number,
  the special information tone once (UK: number unobtainable, 4 s); a
  connected call cut by a network failure or a lost link: reorder, 4 s.
  Answering, hanging up or closing the ended call stops a tone at once.
  They play where the call's audio goes (earpiece, speaker, headset). The
  plan follows the phone's region (United Kingdom: UK tones, anywhere else
  North American); **⋮ > Call tones** on the Phone tab picks one.
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
  Available to every user: it's what confirms the terminal is alive.
- **Console** and **Loopback** (tabs), the demo terminal and the call screen's
  voice frame counters are developer features, behind **Developer options**
  (below); ordinary use of the app — activating, calling, checking status —
  never needs them.
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
    It needs a grant: outside a call that means a test cell (`ocbench cell`) that
    keeps the terminal granted. The defaults are 20 probes, one every 1000 ms,
    payload `HELLO` with a sequence tag (`HELLO#00`, …). The summary shows sent,
    echoed, lost, refused, stray DOWNs, and latency.

### Developer options

Console, Loopback, the demo terminal and the call screen's voice frame
counters are behind a static access code — **Terminal tab > ⋮ > Developer
options** — matching what the iOS app does. The code is `67362355`
("OPENCELL" on a phone keypad); it lives in this public source, so it's a
speed bump against cluttering the app for ordinary users, not security. A
wrong code just says so (no lockout, try again straight away); the right
code unlocks Console, Loopback, the demo terminal and the voice stats line
for good — remembered like any other setting — until **Developer options >
Lock** turns them off again.

## Known limitations

- **The terminal's screen is the pairing trust anchor.** The BLE link is
  paired (LE Secure Connections, passkey) and encrypted, so nobody in range
  can send commands, read the link or sniff the activation code without
  pairing, and pairing needs the code on the terminal's OLED. Anyone who can
  see that screen can pair a phone. The app still lists every device that
  advertises the OpenCell service: pairing with the code on the screen in
  front of you is what proves it is your terminal. See [`security-model.md`](https://github.com/opencell-dev/opencell/blob/main/security-model.md)'s
  "BLE hop (terminal ↔ phone)" section on the `terminal` branch.
- **Voice adds about half a second on the phone** on top of the radio path:
  120 ms to fill a block from the microphone, 240 ms of jitter buffer and
  about 120 ms in the audio output. No Android Telecom integration yet:
  the system's call controls, car kits and the power key don't see OpenCell
  calls, and a cellular call during an OpenCell call isn't arbitrated.
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
             PhoneSession  (commands, resync on connect, finished calls)
             CallTracker   (each call once, when it stops being active: the log's source)
             DialPad, ServiceNumbers (the keypad's rules, the test numbers)
  calllog/   CallLog (entries, cap, unseen missed), CallLogCodec (text form),
             CallLogDisplay (Recents' wording and day groups)
  voice/     VoiceSession  (uplink paced by the microphone, drop on 0x80; downlink
             JitterBuffer with concealment), BlockCodec (3 codec frames per app
             data frame), VoiceCodec/CodecId, AudioIo; CallTonePlayer (toneFor),
             ToneGenerator, TonePlans (North American, UK), DtmfTones (key tones)
  loopback/  LoopbackRunner, LoopbackStats
  session/   TerminalSession (link + sender + phone + console + loopback + channels),
             ChannelSession (scan list: SCAN reads, COMMAND SCAN), ConsoleLog
  sim/       SimulatedTerminal (terminal + network: demo mode and tests)
  dev/       DeveloperAccess (the static code, JVM-testable, shared with iOS's copy)
:codec2 (Android library)
             Codec2 (VoiceCodec over JNI), libcodec2.so (vendored by
             tools/codec2/vendor.sh), libopencell_codec2.so (the JNI glue)
:app   (Android)
  audio/     AndroidAudio (AudioRecord/AudioTrack, 8 kHz), CallAudioRoute (mode,
             focus, earpiece/speaker/headset), CallAudio, TonePlanSetting,
             KeypadTones (setting), KeyTonePlayer (sonification AudioTrack)
  ble/       GattConnector/GattConnection (serialized GATT ops), Bonder (createBond +
             ACTION_BOND_STATE_CHANGED), BleScanner
  data/      TerminalRepository (app-scoped owner of the session), PrefsPhoneMemory,
             DeveloperUnlock (Console/Loopback/demo terminal gate, remembered),
             PrefsCallLogStore (the call log, its own preferences file)
  scan/      QrDecoder (ZXing), QrScanner (CameraX)
  service/   LinkService (connectedDevice foreground service, owns ringing via
             RingPlan and the microphone type via MicPlan), CallRinger,
             CallNotifier, CallActionReceiver, MissedCallNotifier
  ui/        Compose: Phone (activation, home, Keypad, Recents), CallScreen/CallActivity,
             Terminal/Status, Channels, Console, Loopback, DeveloperOptionsDialog
```

The link lives in the Application, not in an Activity or ViewModel.
Folding or unfolding the phone, rotating it, or closing the UI never
disconnects. The foreground service keeps the process alive while a link is
wanted.

GATT connections are opened only in `ble/GattConnector.kt`, which also bonds
with the terminal (`ble/Bonder.kt`) before touching any characteristic. Above
`Connector`, pairing shows up only as `LinkState.Pairing` (the code hint) and
`LinkState.PairingFailed` (Retry, or Bluetooth settings for a stale bond).

Voice (`core/.../voice/VoiceSession.kt`) runs while the call is CONNECTED and
the link is up. It writes once per 120 ms block with `TerminalLink.writeUp` and
drops a block on 0x80 instead of retrying, because a late voice frame is
useless. Codec2 1200 packs three 40 ms frames into 18 bytes, one app data frame
per radio frame. CONNECTED's codec byte picks the codec (1 = Codec2 1200, the
only one any network sends); see the voice design spec in the opencell
repository.

## Third-party notices

QR codes are decoded with [ZXing](https://github.com/zxing/zxing) (`com.google.zxing:core`),
Copyright ZXing authors, licensed under the Apache License, Version 2.0
(https://www.apache.org/licenses/LICENSE-2.0). The camera viewfinder uses
AndroidX CameraX, also Apache-2.0.

Voice uses [Codec 2](https://github.com/drowe67/codec2) by David Rowe and
contributors, licensed under the GNU Lesser General Public License, version 2.1
(`codec2/src/main/cpp/codec2/COPYING`). The vocoder's source is in
`codec2/src/main/cpp/codec2` exactly as `tools/codec2/vendor.sh` takes it from
upstream commit `310777b1c6f1af0bc7c72f5b32f80f6fd9136962`; it is built as its
own shared library, `libcodec2.so`, which can be replaced with a modified build.
Every APK carries the licence and a notice in its assets (`licenses/codec2/COPYING`,
`licenses/codec2/NOTICE`, written by the same script).
