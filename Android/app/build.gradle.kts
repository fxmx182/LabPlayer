plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mauricio.viperplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mauricio.viperplayer"
        // 24 é onde o libVLC 3.x ainda roda bem e onde o Android já traz o
        // decodificador de HEVC por hardware. Descer mais custaria muito e
        // atenderia aparelhos que sequer aguentam 1080p HEVC.
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables { useSupportLibrary = true }

        // O commit carimbado dentro do app, mostrado nas opções.
        //
        // Vem do irmão de iOS, onde a falta disso custou várias rodadas: sem
        // saber qual build está instalado, "a mudança não funcionou" e "instalei
        // o APK velho" são indistinguíveis, e as duas hipóteses levam a
        // investigações opostas.
        buildConfigField(
            "String",
            "COMMIT",
            "\"" + (project.findProperty("viperCommit")?.toString() ?: "local") + "\"",
        )
    }

    // A assinatura mora no repositório de propósito.
    //
    // Não é segredo de app de loja: é um app pessoal instalado à mão, e o que
    // importa aqui é a assinatura ser SEMPRE A MESMA. Android recusa atualizar
    // um app cuja assinatura mudou — com chave gerada a cada build, toda nova
    // versão exigiria desinstalar a anterior e perder o histórico.
    signingConfigs {
        create("viper") {
            storeFile = rootProject.file("keystore/viper.jks")
            storePassword = "viperplayer"
            keyAlias = "viper"
            keyPassword = "viperplayer"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("viper")
            // R8 desligado. O ganho seria de alguns megabytes num APK cujo
            // peso é 95% biblioteca nativa do VLC — e em troca viriam regras
            // de keep para as classes que o JNI chama por nome, que falham
            // silenciosamente e só em tempo de execução.
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // Um APK por arquitetura.
    //
    // O VLC traz o motor inteiro compilado para cada ABI; num APK só, as três
    // juntas passam de 200 MB para o aparelho usar uma. O de arm64 serve todo
    // celular fabricado da metade dos anos 2010 para cá e é o que se instala.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            // Sem APK universal: ele soma as três arquiteturas e passa de
            // 200 MB, dos quais o aparelho usa um terço. Quem instala escolhe
            // o de arm64 — todo celular deste lado de 2015 é arm64 — e o
            // README diz isso em uma linha.
            isUniversalApk = false
        }
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
            )
        }
        // As .so do VLC ficam comprimidas: economiza espaço no download e o
        // Android moderno as extrai na instalação de qualquer jeito.
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.media)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.documentfile)

    // Um só motor para tudo: ele reproduz E navega no servidor.
    //
    // A alternativa era um cliente SMB em Java só para listar pastas. Mas aí
    // navegação e reprodução teriam implementações diferentes de SMB, e o modo
    // de falhar mais irritante possível seria normal: a pasta abre, o vídeo
    // não. Com um motor só, o que lista é o mesmo que toca.
    implementation(libs.libvlc)
}
