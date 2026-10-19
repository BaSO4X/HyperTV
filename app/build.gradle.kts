plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.z1812.hypertv"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.z1812.hypertv"
        // 电视盒子普遍还在 Android 9/11，放宽到 26
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        // libxposed api 101 是 Java 17 字节码
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // libxposed 模块描述（module.prop / scope.list / java_init.list）必须原样打进 APK
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    // 只编译期可见，运行时由框架提供
    compileOnly(libs.libxposed.api)

    // 模块 UI：XposedServiceHelper / XposedService（AAR 自带 XposedService Provider）
    implementation(libs.libxposed.service)
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // 运行时按结构动态定位宿主类/方法/字段，不依赖混淆名
    implementation(libs.dexkit)
}
