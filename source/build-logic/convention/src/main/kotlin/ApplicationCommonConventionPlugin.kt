import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.util.Properties

private class ReleaseSigning(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

// 签名信息来源：优先环境变量（CI），其次 source/keystore.properties（本地，不入库）。
// 两者都没有时返回 null，release 包不签名、debug 包用默认 debug 签名。
private fun Project.resolveReleaseSigning(): ReleaseSigning? {
    val envStoreFile = System.getenv("STORE_FILE")
    if (!envStoreFile.isNullOrBlank()) {
        return ReleaseSigning(
            storeFile = file(envStoreFile),
            storePassword = System.getenv("STORE_PASSWORD") ?: "",
            keyAlias = System.getenv("KEY_ALIAS") ?: "",
            keyPassword = System.getenv("KEY_PASSWORD") ?: "",
        ).takeIf { it.storeFile.exists() }
    }
    val propsFile = rootProject.file("keystore.properties")
    if (!propsFile.exists()) return null
    val props = Properties().apply { propsFile.inputStream().use { load(it) } }
    return ReleaseSigning(
        storeFile = rootProject.file(props.getProperty("storeFile") ?: "release.jks"),
        storePassword = props.getProperty("storePassword") ?: "",
        keyAlias = props.getProperty("keyAlias") ?: "",
        keyPassword = props.getProperty("keyPassword") ?: "",
    ).takeIf { it.storeFile.exists() }
}

private fun Project.configureCommon() {
    pluginManager.apply("com.android.application")
    pluginManager.apply("org.jetbrains.kotlin.android")

    val releaseSigning = resolveReleaseSigning()

    extensions.getByType<ApplicationExtension>().apply {
        if (releaseSigning != null) {
            signingConfigs {
                create("release") {
                    storeFile = releaseSigning.storeFile
                    storePassword = releaseSigning.storePassword
                    keyAlias = releaseSigning.keyAlias
                    keyPassword = releaseSigning.keyPassword
                }
            }
        }

        buildTypes {
            release {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
                buildConfigField("Boolean", "ENABLE_VERBOSE", "false")
                if (releaseSigning != null) {
                    signingConfig = signingConfigs.getByName("release")
                }
            }
            debug {
                isMinifyEnabled = false
                isShrinkResources = false
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
                buildConfigField("Boolean", "ENABLE_VERBOSE", "false")
                // 仅当提供了有效 keystore 才复用 release 签名，否则回退到默认 debug 签名
                if (releaseSigning != null) {
                    signingConfig = signingConfigs.getByName("release")
                }
            }
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        buildFeatures {
            buildConfig = true
        }

        packaging {
            resources {
                excludes += "/META-INF/{AL2.0,LGPL2.1}"
                excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
                excludes += "/META-INF/DEPENDENCIES"  // 新增
            }
        }

        //tasks.withType<KotlinCompile>().configureEach {
          //  compilerOptions {
            //    jvmTarget.set(JvmTarget.JVM_17)
              //  freeCompilerArgs.add("-Xcontext-receivers")
            //}
        //}
        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
}

class ApplicationCommonConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            configureCommon()
        }
    }
}