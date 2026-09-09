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
        // O número da versão é o tempo, em minutos, desde o começo de 2026.
        //
        // Precisa ser monotônico e não pode depender de quem compilou: o
        // Android recusa instalar por cima um APK com número menor, e um app
        // que vem de dois lugares — o release do CI e a pasta `dist/` desta
        // máquina — travaria na hora em que o menor chegasse depois. Com o
        // relógio, o build mais recente é sempre o maior, seja lá onde tenha
        // nascido.
        //
        // Em minutos, e não em segundos, para caber folgado num Int: dá ~4 mil
        // anos de margem.
        versionCode = (((System.currentTimeMillis() - 1_767_225_600_000L) / 60_000L)
            .coerceAtLeast(1L)).toInt()
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

    // Dois aplicativos, e não um que se adapta.
    //
    // O código é o mesmo — a tela de TV e a de celular convivem no mesmo fonte,
    // e `Device.isTv()` continua escolhendo entre elas. O que se separa é o
    // **pacote**: cada um declara só o que serve ao seu aparelho, e recusa o
    // outro. O de TV exige `leanback`, então não instala num celular; o de
    // celular não se anuncia à tela inicial da televisão, então não aparece lá.
    //
    // A separação também resolve o peso: o de TV carrega as duas arquiteturas
    // ARM porque as caixinhas se dividem entre 32 e 64 bits e ninguém deveria
    // ter de descobrir qual é a sua; o de celular carrega só a sua e fica na
    // metade do tamanho.
    flavorDimensions += "aparelho"
    productFlavors {
        create("celular") {
            dimension = "aparelho"
            ndk {
                abiFilters.clear()
                // O x86_64 entra no release, e não só nos testes: é o que roda
                // em PC — emulador, Waydroid, BlueStacks — e em Chromebook.
                // A variante de TV não o carrega: caixinha de TV é sempre ARM,
                // e incluí-lo lá engordaria o APK universal em 25 MB à toa.
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
                // O x86 de 32 bits continua só sob demanda: não existe aparelho
                // assim, mas é a arquitetura das imagens de Android TV que
                // rodam aceleradas num PC.
                if (project.hasProperty("viperX86")) abiFilters += "x86"
            }
        }
        create("tv") {
            dimension = "aparelho"
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
                // As imagens de Android TV que rodam aceleradas num PC são
                // todas x86; sem isto não há como testar esta variante.
                if (project.hasProperty("viperX86")) abiFilters += listOf("x86", "x86_64")
            }
        }
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

    // O celular recebe um APK por arquitetura; a TV recebe o universal.
    //
    // A divisão vale no celular, onde 14 MB de diferença aparecem no download.
    // Na televisão ela seria uma armadilha: o aparelho não diz de quantos bits
    // é, e escolher errado devolve "este app não é compatível com sua TV" sem
    // dizer o motivo. Lá vai o universal, com as duas ARM dentro.
    splits {
        abi {
            isEnable = true
            reset()
            // O x86_64 só existe na variante de celular (é lá que o
            // `abiFilters` o inclui), então listá-lo aqui não afeta a de TV.
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
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

/**
 * Os APKs com nome de gente, em `Android/dist/`.
 *
 * O Gradle nomeia a saída pela variante e pela arquitetura —
 * `app-tv-universal-release.apk` — que é exatamente a informação que quem vai
 * instalar não tem como usar. Aqui cada arquivo passa a dizer em que aparelho
 * entra. O que não é escolhido (os APKs por arquitetura da variante de TV)
 * fica no diretório de compilação e não chega ao `dist/`.
 */
val publicarApks = tasks.register<Copy>("publicarApks") {
    description = "Copia os APKs para Android/dist/ com nome de aparelho, não de arquitetura."

    val saida = layout.buildDirectory.dir("outputs/apk")

    from(saida.map { it.dir("tv/release") }) {
        include("*-universal-release.apk")
        rename { "ViperPlayer-TV.apk" }
    }
    from(saida.map { it.dir("celular/release") }) {
        include("*-arm64-v8a-release.apk")
        rename { "ViperPlayer-Celular.apk" }
    }
    from(saida.map { it.dir("celular/release") }) {
        include("*-armeabi-v7a-release.apk")
        rename { "ViperPlayer-Celular-Antigo.apk" }
    }
    from(saida.map { it.dir("celular/release") }) {
        include("*-x86_64-release.apk")
        rename { "ViperPlayer-PC-x86_64.apk" }
    }

    into(rootProject.layout.projectDirectory.dir("dist"))

    // Sempre roda.
    //
    // A tarefa apaga os APKs antigos antes de copiar os novos, e isso briga
    // com o cálculo de "já está feito" do Gradle: numa execução ele
    // considerava a cópia atualizada, pulava tudo — inclusive o apagamento — e
    // a pasta ficava com a geração anterior. Copiar 300 MB de novo custa
    // segundos; entregar o APK errado custa uma investigação.
    outputs.upToDateWhen { false }

    doFirst {
        // Nome antigo some junto: duas gerações de nomes na mesma pasta é
        // pior que nenhuma, porque não dá para saber qual é a atual.
        rootProject.layout.projectDirectory.dir("dist").asFile
            .listFiles { f -> f.name.endsWith(".apk") }
            ?.forEach { it.delete() }
    }
}

tasks.whenTaskAdded {
    if (name == "assembleRelease") finalizedBy(publicarApks)
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
