# Store screenshots

`frame.js` puts phone screenshots into 1080 × 1920 Google Play frames with a caption, in the colours of the
feature graphic. The captions and the order are in the `shots` list (screenshot number, title, subtitle).

1. Take the screenshots on the phone and crop off the status bar and the gesture bar (1440 × 3168 shots
   from a OnePlus 12: rows 112 to 3112), saving them as `1.png`, `2.png`, ...
2. `node frame.js <screenshots dir> <output dir>` (needs Playwright with Chromium and the Inter font).
3. Copy the results to `fastlane/metadata/android/en-US/images/phoneScreenshots/`.
