# Tuner for Wear OS

A chromatic tuner for the watch: play a note and it shows the note name, how
many cents sharp or flat you are, and the frequency.

- **Needle**: the arc across the top runs from 50 cents flat to 50 cents sharp.
  Green is within 5 cents, amber within 15, red beyond.
- **Transposition**: tap the left pill to cycle C (concert) → B♭ (clarinet,
  trumpet, tenor sax) → E♭ (alto and baritone sax) → F (horn). Note names are
  then shown as written for that instrument.
- **A4**: 442 Hz by default. Turn the bezel or crown to change it by 1 Hz
  (430–450 Hz); tap the right pill to go back to 442.
- The screen stays on while the tuner is open, and the microphone runs only
  while it is on screen. Both settings are remembered.

Pitch detection is YIN (de Cheveigné & Kawahara, 2002) on 50 ms frames, with a
median of the last five readings to steady the needle. Unit tests check it
stays within one cent from 82 Hz to 1865 Hz on a clarinet-like tone.

## Build and install

GitHub Actions builds a debug APK on every change (artifact
`wear-tuner-debug-apk`). Or run `./gradlew :app:assembleDebug` here.
Install with `adb install app-debug.apk` over the watch's wireless debugging.
