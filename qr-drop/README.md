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

Mobile data on its own can't work: carriers don't let one phone reach a
server on another.

## How it works

- The phone runs a small web server while the app is open, on port 8484
  (or 47284 / 52484 if another app has it, then any free port).
- Everything lives under a random path in the QR code, e.g.
  `http://192.168.1.20:8484/k7m2pq9xha/`, so someone who merely shares the
  Wi-Fi can't send you files. The path changes each time the app starts.
- The page uploads one file at a time with `PUT …/upload?name=…` and shows a
  progress bar for each.
- Files are written through MediaStore, so no storage permission is needed.
  Duplicate names get " (1)" added.
- The screen stays on while the app is open; uploads stop if you leave it.

## Build and install

GitHub Actions runs the unit tests and builds a debug APK (artifact
`qr-drop-debug-apk`). Or run `./gradlew :app:assembleDebug` here, then
`adb install app-debug.apk`.
