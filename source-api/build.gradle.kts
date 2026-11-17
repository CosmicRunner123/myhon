plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "eu.kanade.tachiyomi.source"
}

dependencies {
    implementation(projects.core.common)

    implementation(libs.kotlinx.serialization.json)
}
