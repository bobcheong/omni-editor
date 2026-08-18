package com.omnieditor.desktop

import java.util.Properties

object DesktopBuildInfo {
    private val props = Properties().apply {
        val stream = DesktopBuildInfo::class.java.classLoader?.getResourceAsStream("version.properties")
        if (stream != null) load(stream)
    }

    val versionName: String = "${props["major"] ?: "0"}.${props["minor"] ?: "0"}.${props["patch"] ?: "0"}"
    val gitSha: String = System.getProperty("omni.git.sha", "")
    val buildType: String = System.getProperty("omni.build.type", "release")

    val aboutString: String = "$versionName ($gitSha) · $buildType"
}
