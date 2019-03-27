import org.gradle.api.internal.plugins.DslObject
import org.jetbrains.dokka.gradle.DokkaAndroidTask
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    id("com.android.library")
    id("kotlin-android")
    id("org.jlleitschuh.gradle.ktlint")
    id("org.jetbrains.dokka-android")
    id("maven")
    id("com.github.ben-manes.versions")
}

group = Consts.groupId
version = Consts.artifactId

android {
    defaultConfig {
        minSdkVersion(Versions.minSdkVersion)
        compileSdkVersion(Versions.compileSdkVersion)
        targetSdkVersion(Versions.targetSdkVersion)
        versionName = Consts.version
        version = Consts.version
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles(
            file("proguard-rules.pro")
        )
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                cppFlags += "-fno-rtti"
                cppFlags += "-fno-exceptions"
            }
        }
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
        getByName("androidTest").java.srcDirs("src/androidTest/kotlin")
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            isUseProguard = false
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=DEBUG"
                    cppFlags += "-DBUILD_DEBUG"
                    getcFlags() += "-DBUILD_DEBUG"
                }
            }
        }
        release {
            isMinifyEnabled = false
            isUseProguard = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                file("proguard-rules.pro")
            )
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=RELEASE"
                    cppFlags -= "-DBUILD_DEBUG"
                    getcFlags() -= "-DBUILD_DEBUG"
                }
            }
        }
    }
    externalNativeBuild {
        cmake {
            setPath("src/main/cpp/CMakeLists.txt")
        }
    }
}

configure<KtlintExtension> {
    android.set(true)
    reporters.set(setOf(ReporterType.CHECKSTYLE))
    ignoreFailures.set(true)
}

tasks.withType(DokkaAndroidTask::class.java) {
    // https://github.com/Kotlin/dokka/issues/229
    reportUndocumented = false
    outputDirectory = "$buildDir/javadoc"
    outputFormat = "javadoc"
    includeNonPublic = true
    includes = listOf("doc/module_package.md")
    jdkVersion = 7
}


dependencies {
    api(kotlin("stdlib-jdk7", KotlinCompilerVersion.VERSION))
    api(Libs.androidxAnnotation)
    api(Libs.androidxVectorDrawable)

    testImplementation(Libs.junit)
    testImplementation(Libs.robolectric)
    testImplementation(Libs.mockitoInline)
    testImplementation(Libs.mockitoKotlin)
}


val sourcesJarTask = tasks.create<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(android.sourceSets["main"].java.srcDirs)
}

tasks.getByName<Upload>("uploadArchives") {
    DslObject(repositories).convention
        .getPlugin<MavenRepositoryHandlerConvention>()
        .mavenDeployer {
            withGroovyBuilder {
                "snapshotRepository"("url" to Consts.snapshotRepositoryUrl)
            }
            pom {
                project {
                    packaging = "aar"
                    groupId = Consts.groupId
                    artifactId = Consts.artifactId
                    version = Consts.version
                }
            }
        }
}

artifacts {
    add("archives", sourcesJarTask)
}
