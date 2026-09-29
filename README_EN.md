[中文](README.md) | English

# Campus timetable and study client (Android, China University of Petroleum-Beijing at Karamay)

A student-built Android client for timetables, evening self-study, tasks, a shared resource catalogue and a stats board, plus a poller that watches one course's remaining seats. Data lives on the phone first (Room); the networked half talks to the academic affairs system of China University of Petroleum-Beijing at Karamay (`eams.cupk.edu.cn`, read-only) and to a backend service by the same author which is not part of this repository.

> **Unofficial project.** Built by one student. It has no affiliation with, authorisation from, or endorsement by the university's academic affairs office or any official school system, and using it may violate your school's rules on the academic affairs system. Use at your own risk. The repository contains client source code only: no personal data, no author backend address, no prebuilt APK.

## Features

Seven bottom tabs:

| Tab | What it does |
| --- | --- |
| Today | Today's classes, morning and evening self-study, today's tasks, class reminder toggle |
| Timetable | Week view and same-day list; imports your own timetable read-only from the academic affairs system |
| Grab | Watches remaining seats, notifies you at the right moment, shows the selectable-course list (same backend as the web version) |
| Study | Stage switching, task cards, study steps and progress; the "AI study plan" entry sits at the top |
| Library | Shared resource catalogue: pick links by course or type, and they become tasks in your own list |
| Board | Stat cards, a 14-day chart, milestones, self-check |
| Me | Account, password management, network self-check, security and privacy, permissions and allowlist, check for updates, feedback |

Around that: automatic reminders before class and auto-mute during class (both need system permissions, and a dedicated screen walks you through granting them), plus in-app update (check version, download, hand off to the system installer) because the app is sideloaded. The site admin page also opens inside the app, at the address given by `apiBase`.

**About the seat-grab tab**: it watches and notifies. It polls how many seats a course has left and pings you at the right time; you still do the actual enrolment yourself in the academic affairs system. Rebuilding the course list and changing academic-affairs credentials happen in the web version, and the app tells you so where it matters.

## Build

You need JDK 17 and the Android SDK (versions per `app/build.gradle`).

**You must supply your own backend address**, otherwise the build fails on purpose. Shipping a default would mean the default package points at somebody else's server, and that mistake is invisible at compile time.

```bash
# command line
./gradlew assembleRelease -PapiBase=https://your-backend.example.com
```

```properties
# or in local.properties (gitignored)
apiBase=https://your-backend.example.com
```

Release builds sign with `campus.keystore` in the repository root (not tracked); four settings can be overridden through the environment: `CAMPUS_KEYSTORE`, `CAMPUS_STORE_PASS`, `CAMPUS_KEY_ALIAS`, `CAMPUS_KEY_PASS`, or through `local.properties` keys `campusKeystore` / `campusStorePass` / `campusKeyPass`. **Neither the keystore file nor the passwords are in the repository**, so a release build needs your own. During development, `assembleDebug` is enough: the debug variant uses the default debug signing.

To use the academic affairs half as-is: `eams.cupk.edu.cn` is the default in the source, so you log in with your own student id and password. **The password never leaves the phone in cleartext** — it is hashed first with a salt issued by the server, and only the hash is submitted.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Everything runs on the JVM (Robolectric plus Compose render smoke tests). No device or emulator. Counts are aggregated from `app/build/test-results/testDebugUnitTest/*.xml`, not from console output.

**One recorded run** (pasted verbatim, not an illustration; it ran against the 213 version-controlled files in this repository):

```
$ ./gradlew :app:testDebugUnitTest --offline --rerun-tasks
BUILD SUCCESSFUL in 2m 43s
→ 85 classes / 761 tests / 0 failures / 0 errors / 0 skipped

$ ./gradlew :app:assembleRelease --offline --rerun-tasks
BUILD SUCCESSFUL in 1m 32s
51 actionable tasks: 51 executed
→ app-release.apk 7601768 B   (sha256 71bc278c06c85da645e2f90865dcbab5dc7eaf3a056e90320dfccdd8f29fc191)
```

That release build was signed with a one-off demo keystore (generated on the spot, random password, deleted afterwards) to prove the toolchain works end to end; it means nothing as a distributable package — sign your own with your own keystore. Class and test counts move as the project does, so the numbers above belong to that one run.

## Stack

Kotlin with Jetpack Compose (Material 3), Room and kotlinx.serialization, built with Gradle, minSdk 26 / targetSdk 34. Exact versions live in `app/build.gradle` and `gradle/wrapper/gradle-wrapper.properties`.

## Layout

```
app/src/main/java/top/ccbase/campus/
├── alarm/    class reminders, auto-mute, seat-grab watchers, alarm rescheduling after boot
├── data/     local database (Room), seed import, study plan templates
├── domain/   pure logic for timetable, today, week view, tasks, board
├── net/      API clients (including the read-only academic affairs client) and transport
├── ui/       screens (Compose)
└── update/   in-app update
```

## Bundled data and privacy

Two files ship inside the package under `app/src/main/assets/`: `seed.json` (period times and self-study slots, plus synthetic demo courses, tasks, study steps, resources, checklist and milestones) and `plan_templates.json` (plan templates).

At startup the app **imports only the period times and self-study slots**: before importing it explicitly blanks courses, tasks, study steps, resources, checklist and milestones (the `full.copy(...)` call in `CampusApplication.kt`), so the demo sections never reach anyone's database. Everyone's tasks are derived from their own timetable; until someone syncs their own plan the screen says so, and an empty list beats borrowed data.

Those demo sections exist to keep the UI and the logic tests fed: task text reads "演示条目NNN：…", people are named 讲师甲 and the like, and the campus is "示例大学（北京）示例市校区". Demo course titles do mention real courses and their universities from public MOOC platforms, which makes the catalogue look plausible; that is public information, not this institution's course data.

Personally identifying data is at **zero hits** across the repository: a cleanup pass replaced real values with synthetic ones (教师一…教师十二, `XXXXXXXXXX` and similar), and a gate re-scans the whole tree to keep it that way. `tools/privacy_scan.py` is the same idea for a built package:

```bash
python3 tools/privacy_scan.py dist/your-build.apk
```

## Provenance and boundaries

- Target system: the academic affairs system of China University of Petroleum-Beijing at Karamay (`eams.cupk.edu.cn`), read-only, limited to the signed-in user's own timetable.
- This repository is the Android side of one tool. The same author's web version (Python / FastAPI) is not included; `apiBase` points at it, and it also serves the /admin/ page.
- What was read for reference, which techniques are generic, and what would count as copying: see [docs/PRIOR-ART.md](docs/PRIOR-ART.md).
- Timetable rules (week-number conversion, streak semantics and so on) were aligned with the web version item by item and encoded in tests, not invented here: a divergence between the two implementations turns the suite red.
- Deeper documentation lives in docs/ and is Chinese for now.

## License

MIT, see [LICENSE](LICENSE).
