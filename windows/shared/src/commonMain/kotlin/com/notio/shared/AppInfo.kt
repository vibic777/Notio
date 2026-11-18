package com.notio.shared

/** Identity of the desktop build; the sync protocol version must match the Android app's (AGENT.md D09). */
object AppInfo {
    const val NAME = "Notio by vibic"
    const val VERSION = BuildInfo.VERSION

    /** The build number shown in About (the phone's versionCode); one more for every release. */
    const val BUILD = BuildInfo.BUILD
    const val DEVELOPER = "vibic"
    const val SYNC_PROTOCOL_VERSION = 1
}
