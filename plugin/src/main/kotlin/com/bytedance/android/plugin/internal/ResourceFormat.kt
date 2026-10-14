package com.bytedance.android.plugin.internal

import org.gradle.api.GradleException
import java.util.Locale

internal enum class ResourceFormat(val aab: Boolean, val apk: Boolean) {
    AAB(true, false), APK(false, true), ALL(true, true);

    companion object {
        fun parse(value: String): ResourceFormat = when (value.trim().lowercase(Locale.ROOT)) {
            "aab" -> AAB
            "apk" -> APK
            "all" -> ALL
            else -> throw GradleException("andResGuard.format must be 'aab', 'apk' or 'all', but was '$value'.")
        }
    }
}
