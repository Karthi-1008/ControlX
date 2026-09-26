plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.controlx.nativemgba"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        targetSdk = 30

        externalNativeBuild {
            cmake {
                arguments(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_LIBRETRO=ON",
                    "-DLIBRETRO_STATIC=ON",
                    "-DM_CORE_GBA=ON",
                    "-DM_CORE_GB=ON",
                    "-DSKIP_LIBRARY=ON",
                    "-DDISABLE_DEPS=ON",
                    "-DUSE_EPOXY=OFF",
                    "-DUSE_PNG=OFF",
                    "-DUSE_LIBZIP=OFF",
                    "-DUSE_SQLITE3=OFF",
                    "-DUSE_EDITLINE=OFF",
                    "-DENABLE_DEBUGGERS=OFF",
                    "-DENABLE_SCRIPTING=OFF"
                )
                abiFilters("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
