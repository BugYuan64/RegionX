plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/*
 * Windows 上的 Gradle 4~8 会把 worker 的 @argfile 按平台编码（GBK）写入，
 * 而 JVM 按 UTF-8 读取，导致本项目这种含中文的路径（Days-项目）在
 * 单元测试的 classpath 上全部失效（表现为 ClassNotFoundException）。
 *
 * 规避方式：把构建目录落到 %TEMP% 下一个纯 ASCII 路径（%TEMP% 本身通常没有中文）。
 * 注意不要用目录联接（junction）指回项目根：在联接里递归删除会命中真实项目文件。
 */
val asciiBuildRoot: File? = run {
    val projectRoot = rootProject.projectDir
    if (projectRoot.absolutePath.all { it.code < 128 }) return@run null

    val temp = File(System.getProperty("java.io.tmpdir"), "regionx-gradle-build")
    // 旧版本可能留下指回项目根的联接，先尝试删除（非空则不处理，改用另一层目录）
    if (temp.exists() && !temp.isDirectory) temp.delete()
    temp.mkdirs()
    if (temp.absolutePath.all { it.code < 128 }) temp else null
}

if (asciiBuildRoot != null) {
    layout.buildDirectory.set(File(asciiBuildRoot, "app"))
    rootProject.layout.buildDirectory.set(File(asciiBuildRoot, "root"))
}

android {
    namespace = "com.regionx.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.regionx.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("zh", "en")
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    implementation("androidx.savedstate:savedstate-ktx:1.3.3")

    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
