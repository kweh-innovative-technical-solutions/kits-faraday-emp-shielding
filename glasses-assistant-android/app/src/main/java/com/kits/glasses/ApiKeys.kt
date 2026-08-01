package com.kits.glasses

/**
 * Single accessor for secrets. The Anthropic key is injected at build time from
 * local.properties (gitignored) into [BuildConfig.ANTHROPIC_KEY] — it is never
 * hardcoded and never committed. See app/build.gradle.kts.
 */
object ApiKeys {
    val anthropic: String
        get() = BuildConfig.ANTHROPIC_KEY
}
