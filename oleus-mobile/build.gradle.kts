plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "io.oleus.mobile"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release")
    }
}

dependencies {
    // Optional integrations — only active when the host app ships these.
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("com.jakewharton.timber:timber:5.0.1")

    // Pure-JVM unit tests (no Android Context required — see OleusIdentity).
    testImplementation("junit:junit:4.13.2")
}

// ── Publication: io.oleus:oleus-mobile → GitHub Packages ─────────────────────
// Publish with:
//   GITHUB_ACTOR=<user> GITHUB_TOKEN=<pat> ./gradlew :oleus-mobile:publish
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.property("GROUP") as String
                artifactId = "oleus-mobile"
                version = project.property("VERSION_NAME") as String

                pom {
                    name.set("Oleus Mobile SDK (Android)")
                    description.set("Crash reporting, ANR detection, sessions, and breadcrumbs for the Oleus observability platform")
                    url.set("https://github.com/slowdutch/oleus-mobile-android")
                    licenses {
                        license {
                            name.set("MIT")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/" + (System.getenv("GITHUB_REPOSITORY") ?: "slowdutch/oleus-mobile-android"))
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}
