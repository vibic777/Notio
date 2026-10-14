# Notio

[Русский](README.md) · **English**

This is already a business idea, not a draft left on the shelf. It is a useful tool: I use it every day and keep developing it.

Notio combines Android and Windows editions of a personal organizer in one Gradle project. The codebase is being consolidated gradually while platform-specific interfaces and system features remain in their respective app layers.

Build from the repository root:
- .gradlew.bat :app:assembleDebug :app:assembleRelease
- .gradlew.bat :desktopApp:release :desktopApp:packageExe
- .gradlew.bat :packageDelivery

An Android SDK is required for Android builds. Configure it in the ignored local.properties; never commit signing keys or passwords. See the [Russian README](README.md) and docs directory for current migration and delivery details.
