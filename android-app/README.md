# Android App

Target ownership marker for Android-specific composition. The existing `app/` module remains the logical Android app and authoritative runtime during migration.

This tree owns future platform, UI, and VM-service separation. It does not own VM-domain policy, guest workloads, controller logic, or orchestration, and it is not included by Gradle in Milestone 1.
