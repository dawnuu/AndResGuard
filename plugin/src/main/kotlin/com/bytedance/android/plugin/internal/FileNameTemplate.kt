package com.bytedance.android.plugin.internal

import org.gradle.api.GradleException

private val placeholderRegex = Regex("""\$?\{([^}]*)}""")
private val illegalFileNameChars = Regex("""[\\/:*?"<>|\s]""")

/**
 * Expands the shared AAB/APK file-name template and applies the output format.
 * The form without `$` avoids Groovy's own string interpolation in double-quoted strings.
 * Supported: applicationId, versionName, versionCode, variantName, buildTime, format.
 * Unknown placeholders and names that are not a plain file name fail the build.
 */
internal fun resolveFileName(template: String, values: Map<String, String>, format: String): String {
    val variables = values + ("format" to format)
    val name = placeholderRegex.replace(template) { match ->
        val key = match.groupValues[1]
        val value = variables[key]
            ?: throw GradleException(
                "Unknown placeholder {$key} in obfuscatedFileName. Supported: " +
                    variables.keys.joinToString(", ") { "{$it}" }
            )
        value.replace(illegalFileNameChars, "_")
    }
    if (name.isBlank() || name.contains('/') || name.contains('\\') || name == "." || name == "..") {
        throw GradleException("obfuscatedFileName must be a plain file name, but resolved to: '$name'")
    }
    val base = name.replace(Regex("\\.(aab|apk)$", RegexOption.IGNORE_CASE), "")
    if (base.isBlank() || base == "." || base == "..") {
        throw GradleException("obfuscatedFileName must include a file name before the extension.")
    }
    return "$base.$format"
}
