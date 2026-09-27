/**
 * O relógio de onde sai o número da versão (ver `versionCode`).
 *
 * Em minutos, e não em segundos, para caber folgado num Int mesmo multiplicado
 * por dez para o final de cada variante: dá ~400 anos de margem.
 */
val minutosDesde2026: Int =
    ((System.currentTimeMillis() - 1_767_225_600_000L) / 60_000L).coerceAtLeast(1L).toInt()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mauricio.libertyx"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mauricio.libertyx"
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
        // Vezes dez: o último algarismo diz a variante (ver `productFlavors`).
        // O salto de escala uma vez só não é problema — o número só cresceu.
        versionCode = minutosDesde2026 * 10
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
            "\"" + (project.findProperty("libertyxCommit")?.toString() ?: "local") + "\"",
        )

        // O Pro já liberado, sem teste nem compra: é o APK do dono.
        //
        // Liberado por padrão no que se compila NESTA máquina — a pasta
        // `dist/` —, e travado no que o CI publica e no que vai para a loja.
        // Pelo padrão, e não por uma opção a lembrar: esquecer a opção num
        // build para a pasta faria o dono do app cair no próprio paywall
        // depois de sete dias. `-PlibertyxLoja` força o travado aqui também,
        // para testar a tela de compra.
        val liberado = System.getenv("CI") == null && !project.hasProperty("libertyxLoja")
        buildConfigField("boolean", "LIBERADO", liberado.toString())
        // Só para testar o fim do teste sem esperar uma semana (o emulador
        // não deixa adiantar o relógio): `-PlibertyxDiasDeTeste=0`.
        buildConfigField(
            "int", "DIAS_DE_TESTE",
            project.findProperty("libertyxDiasDeTeste")?.toString() ?: "7",
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
            // Final 1 no celular e 2 na TV. As duas variantes têm o mesmo
            // pacote e vão para a mesma página da Play Store (a TV pela faixa
            // própria de Android TV), e lá dois envios nunca podem repetir o
            // número. Compiladas juntas, no mesmo minuto, repetiriam.
            versionCode = minutosDesde2026 * 10 + 1
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
                if (project.hasProperty("libertyxX86")) abiFilters += "x86"
            }
        }
        create("tv") {
            dimension = "aparelho"
            versionCode = minutosDesde2026 * 10 + 2
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
                // As imagens de Android TV que rodam aceleradas num PC são
                // todas x86; sem isto não há como testar esta variante.
                if (project.hasProperty("libertyxX86")) abiFilters += listOf("x86", "x86_64")
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
        // A chave de ENVIO da Play Store — esta, sim, secreta.
        //
        // Não assina o app que chega ao usuário: a Play reassina com a chave
        // dela (Play App Signing). Esta só prova ao Google que o envio veio do
        // dono da conta. Mora fora do repositório, que é público, e a senha
        // vem de ~/.gradle/gradle.properties. Sem ela, o `bundleLoja` falha e
        // todo o resto compila normalmente.
        val envio = project.findProperty("libertyxEnvioArquivo")?.toString()
        if (envio != null) create("envio") {
            storeFile = file(envio)
            storePassword = project.property("libertyxEnvioSenha").toString()
            keyAlias = project.property("libertyxEnvioApelido").toString()
            keyPassword = project.property("libertyxEnvioSenha").toString()
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
        // O que vai para a Play Store: `./gradlew bundleLoja`.
        //
        // Igual ao release, com duas diferenças que não podem ser esquecidas
        // na hora do envio — por isso são um tipo de build e não opções: a
        // assinatura é a de envio, e o Pro nunca vem liberado.
        create("loja") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.findByName("envio")
            buildConfigField("boolean", "LIBERADO", "false")
            buildConfigField("int", "DIAS_DE_TESTE", "7")
            matchingFallbacks += "release"
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

    // O idioma segue o do aparelho, e a partir do Android 13 dá para escolher
    // um só para o LibertyX em Configurações › Apps › Idioma. A lista de
    // idiomas sai das pastas values-xx; o inglês é o padrão (values/).
    androidResources {
        generateLocaleConfig = true
    }

    // Na loja, todos os idiomas vão juntos.
    //
    // O pacote da Play entrega por padrão só o idioma do aparelho. Mas o app
    // deixa escolher outro em Configurações › Apps › Idioma, e aí o idioma
    // escolhido não estaria instalado — o app cairia no inglês sem avisar.
    // Os textos dos três idiomas somam poucos KB.
    bundle {
        language { enableSplit = false }
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
                // Manifesto OSGi que o Bouncy Castle (do smbj) e o jspecify
                // trazem no mesmo caminho; o Android não usa nenhum dos dois.
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
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
        rename { "LibertyXPlayer-TV.apk" }
    }
    from(saida.map { it.dir("celular/release") }) {
        include("*-arm64-v8a-release.apk")
        rename { "LibertyXPlayer-Celular.apk" }
    }
    from(saida.map { it.dir("celular/release") }) {
        include("*-armeabi-v7a-release.apk")
        rename { "LibertyXPlayer-Celular-Antigo.apk" }
    }
    from(saida.map { it.dir("celular/release") }) {
        include("*-x86_64-release.apk")
        rename { "LibertyXPlayer-PC-x86_64.apk" }
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

/**
 * Os pacotes da Play Store, em `Android/dist/play/`: um para celular e um para
 * a faixa de Android TV. Mesmo pacote, números de versão diferentes.
 */
val publicarPacotes = tasks.register<Copy>("publicarPacotes") {
    description = "Copia os .aab da loja para Android/dist/play/."
    val saida = layout.buildDirectory.dir("outputs/bundle")
    from(saida.map { it.dir("celularLoja") }) {
        include("*.aab")
        rename { "LibertyXPlayer-Celular.aab" }
    }
    from(saida.map { it.dir("tvLoja") }) {
        include("*.aab")
        rename { "LibertyXPlayer-TV.aab" }
    }
    into(rootProject.layout.projectDirectory.dir("dist/play"))
    outputs.upToDateWhen { false }
}

tasks.whenTaskAdded {
    if (name == "assembleRelease") finalizedBy(publicarApks)
    if (name == "bundleLoja") finalizedBy(publicarPacotes)
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

    // Tamanho e data dos arquivos do servidor — e só isso.
    //
    // A listagem continua sendo a do VLC, pelo motivo acima. Mas ele não conta
    // tamanho nem data do arquivo, e sem os dois a pasta de rede não ordenaria
    // como a biblioteca local. Se este cliente falhar, a pasta abre igual, só
    // sem esses dois campos.
    implementation("com.hierynomus:smbj:0.14.0")

    // A compra única do Pro. Sem servidor: a Play guarda a compra na conta
    // Google, e o app só pergunta a ela.
    implementation(libs.billing)
}
