/*
 * Copyright 2022-2026 Chartboost, Inc.
 * 
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    dependencies {
        classpath("org.jfrog.buildinfo:build-info-extractor-gradle:4.28.1")
    }
}

plugins {
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.21" apply false
}

allprojects {
    // The Facebook Audience Network SDK transitively resolves androidx.browser
    // up to 1.9.0, whose AAR metadata declares minCompileSdk=36 and
    // minAgpVersion=8.9.1. That trips checkRemoteReleaseAarMetadata during
    // assembleRemote / release while this adapter compiles against API 34 on
    // AGP 8.2.2. 1.8.0 is the latest release compatible with API 34 and our
    // current AGP; the Custom Tabs API surface is unchanged between 1.8.0 and
    // 1.9.0. Remove once this repo moves to compileSdk 36 + AGP 8.9.1+.
    configurations.all {
        resolutionStrategy {
            force("androidx.browser:browser:1.8.0")
        }
    }
}

task<Delete>("clean") {
    delete(rootProject.buildDir)
}

task("ci") {
    dependsOn("clean")
    dependsOn(":MetaAudienceNetworkAdapter:assembleRemote")
}
