# Strix Application Security & White-Box Audit Report — `Klock`

- **Target Repository**: `/Users/rajat/personal/projects/Klock`
- **Application Type**: Native Android Application (Kotlin, Jetpack Compose, Room, DataStore, OkHttp, `AlarmManager`, Foreground `MediaPlayback` Service)
- **Methodology**: Strix White-Box Code & Exploit-Proof Testing (`usestrix/strix` `application-security-testing` + `find-security-vulnerabilities-in-code` + automated JUnit/MockWebServer/Robolectric PoC verification in `StrixSecurityExploitTest.kt`)

---

## 1. Asset & Trust-Boundary Map

| Asset / Boundary | Trust Level | Key Risk Surface | Mitigation Implemented & Verified |
|---|---|---|---|
| Live Educational Source Responses (`learn.microsoft.com`, `en.wikipedia.org`, `api.stackexchange.com`) | Untrusted External Network Data | SSRF, HTTP 302 Open-Redirect SSRF to Cloud Metadata/LAN, Cleartext MITM Downgrade, Oversized Payload OOM on 2 GB RAM | `SecurityGuards.isValidPublicHttpsUrl` + `isTrustedEducationalCitationUrl` enforce `https://`, domain allowlisting, and block loopback, RFC1918, link-local, CGNAT, cloud metadata (`169.254.169.254`), and IPv6-mapped IPv4. `LiveEducationalSourcesClient` disables automatic redirects (`followRedirects = false`), validates every redirect hop manually, and enforces a hard `192 KB` (`LOW_RAM`) streaming byte cap. |
| User-Submitted Alarm Answers (SQL, Python Output, DAX/Code, Prose, Numerical) | Untrusted Local Input | Arbitrary Code Execution (`eval`/`exec`/`ScriptEngine`), SQL Injection, ReDoS / Buffer DoS | `AnswerValidator` enforces a `512`-character input bound, uses pure deterministic string/token/arithmetic evaluation with zero code execution (`Runtime.exec`, `ProcessBuilder`, `ScriptEngine`, `DexClassLoader`, and `WebView` JS are completely absent), and Room DAOs use parameterized `@Query` bindings exclusively. |
| Alarm Dismissal & Foreground Service Lifecycle | Integrity-Critical Local Boundary | Trivial Stop/Swipe Bypass, Unexported Intent Spoofing, Process-Kill Reset | `AlarmRingingService`, `ActiveAlarmActivity`, and `AlarmTriggerReceiver` set `android:exported="false"`; all `PendingIntent`s enforce `PendingIntent.FLAG_IMMUTABLE`. Ongoing `CATEGORY_ALARM` notification exposes zero stop/dismiss actions. `ACTION_STOP_AFTER_VERIFIED_COMPLETION` verifies `session.completed == true` in Room before stopping audio. Emergency override requires a continuous 8-second hold OR the cryptographic `SecureRandom` `KLOCK-XXXXXX` session code. |
| Optional BYOK Cloud AI Key (`EncryptedByokSecretStore`) | Sensitive User Secret | Plaintext Disk Exposure, APK Key Leakage, Cloud Backup Exfiltration | Zero API keys exist in source code, `BuildConfig`, or APK resources. Optional runtime BYOK keys are encrypted with Android Keystore `MasterKey` (`AES256_SIV` / `AES256_GCM`) in `klock_secure_prefs.xml`, which is explicitly excluded in `res/xml/data_extraction_rules.xml`, and fall back strictly to volatile RAM (never plaintext disk) if Keystore is unavailable. |

---

## 2. Ranked Exploit Verification Results

1. **Validated Unauthenticated Remote Exploits**: **0 open**
   - Cleartext HTTP downgrade, RFC1918/loopback/CGNAT/IPv6-mapped SSRF, `file://`/`javascript:` URI injection, and HTTP `302 -> http://169.254.169.254/latest/meta-data/` redirect pivots are all blocked and proven in `StrixSecurityExploitTest`.
   - 260 KB oversized payload bomb against the `2 GB LOW_RAM` profile is aborted mid-stream with `IOException` before heap allocation exceeds `192 KB`.
2. **Validated Local Integrity / Dismissal Bypass Issues**: **0 open**
   - Wrong answers increment retry counters without advancing `solvedCount`; audio stops only at `5/5` verified answers or validated emergency override (`SessionDismissalAndNetworkResilienceTest`).
   - Unfinished active sessions survive UI recreation and reboot recovery (`AlarmBootAndTimeReceiver`).
3. **Static Secret & RCE Primitive Scan**: **0 findings**
   - Automated AST/regex scan across all Kotlin sources and `AndroidManifest.xml` / `network_security_config.xml` confirmed `usesCleartextTraffic="false"`, `cleartextTrafficPermitted="false"`, zero hardcoded API tokens, and zero dynamic code execution APIs.

---

## 3. Honest Coverage Statement

- **Executed Automated Verification**: Full JVM unit tests, `MockWebServer` adversarial HTTP/redirect/OOM tests, live HTTPS smoke tests, Robolectric SDK 34 `AlarmManager`/boot-receiver/Compose `320dp` UI tests (`./gradlew testDebugUnitTest`), debug APK compilation (`./gradlew assembleDebug`), and R8-minified release build (`./gradlew assembleRelease`).
- **Not Executed in This Environment**: Physical-device lock-screen audio fuzzing and live cloud Docker Strix dynamic proxy fuzzing (`adb devices` and `docker` are not attached/installed on this host).
