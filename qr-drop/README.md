# QR Drop

Receive photos and files from an iPhone on an Android phone, with no app on
the iPhone. The phone shows a QR code; the iPhone scans it with the Camera app,
opens a page in Safari, and sends. Files land in **Downloads › QR Drop**.

## Two ways to connect

- **Same Wi-Fi**: both phones on one network. Scan the QR code and send.
- **Hotspot**: no shared Wi-Fi (or you're on mobile data). Tap **Start private
  hotspot**: the phone makes a private Wi-Fi network with a random name and
  password and no internet. QR code 1 joins it from the iPhone camera; QR
  code 2 opens the send page. It uses no mobile data. If your regular hotspot
  is already on, the app uses that instead.

  The private network is a Wi-Fi Direct group on 5 GHz, which the iPhone joins
  like any other Wi-Fi. Android's own app hotspot ("local-only hotspot") is
  stuck on 2.4 GHz at 20 MHz, several times slower, so it's only the fallback:
  when Wi-Fi is switched off (Wi-Fi Direct needs it on, not joined to anything)
  or the phone can't make a Wi-Fi Direct group.

Mobile data on its own can't work: carriers don't let one phone reach a
server on another.

## How it works

- The phone runs a small web server while the app is open, on port 8484
  (or 47284 / 52484 if another app has it, then any free port).
- Everything lives under a random path in the QR code, e.g.
  `http://192.168.1.20:8484/k7m2pq9xha/`, so someone who merely shares the
  Wi-Fi can't send you files. The path changes each time the app starts.
- The page uploads up to three files at once with `PUT …/upload?name=…` and shows a
  progress bar for each. The phone's Received list shows the same progress
  (bytes so far / size) while each file streams in.
- Files are written through MediaStore, so no storage permission is needed.
  Duplicate names get " (1)" added.
- The screen stays on while the app is open; uploads stop if you leave it.

## Build and install

GitHub Actions runs the unit tests and builds a debug APK (artifact
`qr-drop-debug-apk`). Or run `./gradlew :app:assembleDebug` here, then
`adb install app-debug.apk`.

## Releases for friends

Release APKs are signed with a dedicated key (alias `qrdrop`), kept out of the
repo. Push a tag like `qr-drop-v0.4.0` to run `.github/workflows/qr-drop-release.yml`,
which runs the tests and builds a shrunk release APK.

- If the repo has the secrets `QRDROP_KEYSTORE_B64`, `QRDROP_KEYSTORE_PASSWORD`
  and `QRDROP_KEY_ALIAS`, CI signs the APK and publishes a GitHub Release with
  `QR-Drop.apk`, its SHA-256 and the notes in `RELEASE_NOTES.md`.
- Without them, the unsigned APK is uploaded as an artifact; sign it locally
  with apksigner (or apksig) and attach it to the release.

Friends download `QR-Drop.apk` from the latest release:
https://github.com/kenneth-cheong/testing-public/releases/latest/download/QR-Drop.apk

**Android developer verification.** From 30 September 2026, certified Android
phones in Singapore (and Brazil, Indonesia, Thailand) only install apps from
registered developers. Register `io.github.kennethcheong.qrdrop` with this
key's SHA-256 certificate fingerprint in the Android Developer Console (the
free hobbyist account covers a small number of devices) before sharing.
Losing the key means friends can't update over their install, so back it up.
