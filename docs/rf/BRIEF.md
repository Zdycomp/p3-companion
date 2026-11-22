# RF / Bluetooth specialist group — shared brief

Goal: make every LensMoo function work reliably in P3 Companion (/home/user/p3, Kotlin, Android framework only, minSdk 30, target 35) on NovaSight P3 / Shenju "XK" family glasses. Owner uses a Samsung phone, phone only.

Facts:
- Primary link is Bluetooth CLASSIC: RFCOMM SPP (UUID 00001101-..., channel 8). HFP/SCO (mic), A2DP (speakers), AVRCP (button relay). BLE GATT exists but is auxiliary (HID 0x1812, aaa0, fff0) per xk-one-pro-sdk docs/HARDWARE.md.
- App protocol code: app/src/main/java/com/zdycomp/p3companion/XkProtocol.kt, XkGlasses.kt. Other screens in the same folder.
- Sources (MIT): /home/claude/beneduzi/xk-one-pro-sdk (docs, python, flutter_example Kotlin), /home/claude/joaosouz4dev/lensmoo-crash-fix (docs on preview/audio/video). Summarize decompiled-vendor facts; never copy vendor code.
- Teammates: Theoretical Physicist writes docs/PROTOCOL-NOTES.md; Experimental Physicist writes test vectors + docs/DEVICE-TEST-PLAN.md + docs/REVIEW-protocol.md; Senior Code Specialist does ALL code changes.

Rules for specialists: do not edit any file outside docs/rf/; write ONE file docs/rf/NN-<topic>.md. No hearthbot tools, no git push, no contacting anyone, no network access beyond reading local files.
Your file must contain: (1) findings with source file:line, (2) concrete defects in the current app code with file:line, (3) exact recommended changes as Kotlin snippets or unified diffs the Code Specialist can apply (framework APIs only), (4) on-device checks the owner can run, (5) confidence per item. Keep it under ~250 lines. Final message: under 200 words, top 3 changes by impact.
