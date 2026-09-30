# TARA 0.2 verification

- Clean Android debug build completed; APK metadata reports version code 2/version 0.2, package `ai.tara.personal`, minimum API 26.
- APK ZIP integrity check passed; Vosk native libraries are present for arm64-v8a, armeabi-v7a, x86 and x86_64.
- APK 0.1 and 0.2 contain the same signing certificate (SHA-1 AB:66:E0:62:AF:89:13:24:DC:AE:4D:16:B2:D3:11:A4:B3:8B:7F:30), supporting an in-place update.
- JUnit reports: 8 tests, 0 failures, 0 errors, 0 skipped. Tests cover provider endpoint boundaries, localhost restrictions, credential-bearing URL rejection, approval state/expiry rules, memory-intent checks, URL provenance checks, and recipient header/multiple-address rejection.
- The downloaded Vosk English model was loaded in a host-side test; the Tara wake grammar constructed successfully and `tara` was present in the model vocabulary. Model ZIP checksum: 30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498.

Not verified: installation/rendering on an Android phone, microphone behavior with the display off, accent/noise false activations, real Tamil speech, power consumption, Android 16 KB page-size behavior, live Ollama on the user's phone, external API accounts/tokens, successful external posts, Gmail OAuth/drafts, exact process-death handling, and the Room migration on an installed v0.1 database. No production readiness or end-to-end account verification is implied by build success.
