# Campus Assistant

An unofficial Android study app for CUPK students.

## Features
Today's schedule, timetable and class reminders; local learning tasks, focus sessions, check-ins and statistics; shared learning resources and bookmarks; themes and backgrounds. AI suggestions, cloud refresh and feedback require a valid cloud identity.

Course-seat monitoring has been removed. The app does not select courses automatically.

## Timetable and privacy
Sign in on the school's official HTTPS page to import a timetable. The app does not read or save the academic-affairs password and does not upload that password or school cookies to its own backend. Local storage is the default. Cloud synchronization requires explicit agreement to the terms after import.

Cloud synchronization uploads a snapshot, not ongoing access to the school system. Import again when the timetable changes. New cloud identities are random guests expiring in approximately 30 days. Recovery after uninstalling, clearing data or logging out, and cross-device recovery, are not supported. Local-only users can continue using local features. Cloud-only entry points explain the requirement and direct users to import and consent.

This is not an official school app. The embedded school page has known layout compatibility issues. Reminder delivery depends on system permissions and battery settings. AI suggestions do not guarantee results. External resources are provided by their respective websites.

## Download
Android 8.0 or newer: [Releases](https://github.com/chuying8ban/campus-client/releases). Upgrade an existing official installation without uninstalling it. Separate test builds do not share data with official builds.

## Build
JDK 21 and Android SDK. Set sdk.dir in local.properties; override the backend with -PapiBase if necessary. Publishing requires your own signing keystore. Never commit signing passwords.

```sh
./gradlew :app:testDebugUnitTest --offline --max-workers=2
./gradlew :app:assembleDebug
```

See [release instructions](docs/RELEASING.md). Backend code and private deployment/signing credentials are not included.

[中文](README.md) · [License](LICENSE)
