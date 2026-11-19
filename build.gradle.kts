import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.test { useJUnit() }

tasks.named<ShadowJar>("shadowJar") {
    // Forge 1.7.10 scans every class in the jar, including multi-release variants.
    exclude("META-INF/versions/**")
    mergeServiceFiles()
    val legalFiles = listOf("LICENSE", "LICENSE.txt", "NOTICE", "NOTICE.txt",
        "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt")
    legalFiles.forEach { append(it, "\n\n") }
    filesMatching(legalFiles + listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}
