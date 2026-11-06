# Run Sebastian yourself

[Back to the README](../README.md)

You need an Android 13+ phone, JDK 17, the Android SDK, Node.js and Python 3.

1. **App:** turn on Developer options and Wireless debugging on the phone. Run `./gradlew :app:assemblePlayDebug`, then `adb install -r app/build/outputs/apk/play/debug/app-play-debug.apk`. Open the app and pair once: it starts its helper over Wireless debugging.
2. **Keys:** copy `.env.example` to `.env` and fill it in. Copy `contacts.example.json` to `contacts.json`.
3. **Brain:** run `cd brain && npm install && npm run deploy`, then `python bridge/brain_secrets.py`. WhatsApp messages need a sender Worker: see the binding in `brain/wrangler.toml`.
4. **Settings:** run `python bridge/push_config.py`, then turn Sebastian on in the app.
5. **Call:** say "Hey Sebastian, call <name>", or call someone on WhatsApp yourself.

Tests: `./gradlew :app:testPlayDebugUnitTest` for the app, `cd brain && npm test` for the brain.
