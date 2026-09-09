# Viper Player — Android

O mesmo player do iPhone, do outro lado. Mesmo nome, mesmo ícone, mesmos
gestos, mesmo motor de vídeo — escrito de novo em Kotlin porque não há uma
linha de Swift que rode aqui.

Não é um porte automático nem uma casca de WebView: é a mesma **arquitetura**
reimplementada. A interface conversa com um `VlcEngine`, nunca com o VLC
direto, exatamente como lá ela conversa com o protocolo `PlaybackEngine` — e
pela mesma razão: os gestos são a cara do app e não podem ser reescritos quando
o motor mudar.

---

## O que muda de lado para lado

O app de iOS existe porque o iOS **não tem** MX Player e porque o VLC de lá
rola o vídeo aos pulos. No Android o MX Player existe. Então o que este app
oferece é o de sempre — sem anúncio, sem conta, sem codec pago, com o servidor
de casa em primeiro plano — e três coisas que no iPhone simplesmente não dão:

| | iOS | Android |
|---|---|---|
| **Rolagem quadro a quadro** | faltava a API; quase virou um motor FFmpeg próprio | `setTime(ms, fast=false)` é exposto — a busca exata é uma linha |
| **Janela flutuante (PiP)** | só sobre a camada do AVPlayer; com VLC, impossível | é um modo da atividade inteira; funciona com qualquer motor |
| **Achar os vídeos** | pasta por pasta, autorizada no seletor, com bookmark para cada | o MediaStore já indexou tudo, inclusive o pendrive na USB-C |
| **Brilho pelo gesto** | mexe no brilho do sistema inteiro | é atributo da janela: volta sozinho ao sair |
| **Instalar** | expira em 7 dias, ou US$ 99/ano | APK assinado, instala e fica |
| **Compilar** | só em macOS, ou seja, só no CI, ou seja, 10 min por erro de sintaxe | `./gradlew assembleRelease` aqui mesmo, em minutos |

Aquele último ponto muda o jeito de trabalhar: do lado do iPhone a regra é
*pipeline verde primeiro, motor depois*, porque um erro de compilação custa dez
minutos de espera. Aqui o compilador está na máquina.

## O que ainda não tem

Honestidade primeiro, porque descobrir isso sozinho custa mais caro:

- **Miniatura de vídeo no servidor SMB.** Gerar uma exigiria baixar o começo de
  cada arquivo pela rede ao abrir a pasta. Quem navega no servidor vê o ícone
  de filme — e a lista abre na hora.
- **Tamanho e data dos arquivos no servidor.** Quem lista é o próprio VLC, que
  responde nome e tipo, não metadados.
- **Áudio em segundo plano com a tela apagada.** Hoje o vídeo pausa ao sair
  (ou entra na janela flutuante). Um serviço de primeiro plano resolve; ainda
  não está escrito.
- **Legenda externa escolhida à mão.** O motor já aceita
  (`VlcEngine.addSubtitle`), mas não há botão. Legenda ao lado do arquivo com o
  mesmo nome já entra sozinha.

## Gestos

Os mesmos do irmão de iOS, que por sua vez são os do MX Player:

| Gesto | Ação |
|---|---|
| Arrastar ↔ | Rolagem com o quadro exato debaixo do dedo |
| Arrastar ↕ na metade esquerda | Brilho da tela |
| Arrastar ↕ na metade direita | Volume do aparelho |
| Toque duplo à esquerda / direita | −10 s / +10 s |
| Toque duplo no centro | Play / pause |
| Toque simples | Mostrar / ocultar a barra |
| Segurar | Acelera enquanto o dedo fica (2× por padrão, ajustável) |
| Pinça | Ampliar de 0,5× a 6× |
| Dois dedos arrastando | Mover a imagem ampliada |

E no menu ⋮: velocidade, modo noturno, temporizador para dormir, aleatório,
repetir, captura de tela, girar, bloquear a tela, e o interruptor da rolagem
quadro a quadro.

## Compilar

Precisa de JDK 17 e do SDK do Android. Nada mais — nem Android Studio.

```bash
./gradlew assembleRelease
```

Os APKs saem em `app/build/outputs/apk/release/`, um por arquitetura.
**Instale o `app-arm64-v8a-release.apk`**: todo celular deste lado de 2015 é
arm64. O `armeabi-v7a` é para aparelho antigo de 32 bits e o `x86_64`, para
emulador.

Não existe APK universal de propósito: o VLC vem compilado para cada
arquitetura e os três juntos passam de 200 MB, dos quais o aparelho usaria um
terço.

```bash
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

Um push na `main` faz o mesmo no GitHub Actions e publica em
`releases/download/android-latest/` — que baixa com um `curl` seco, sem login,
direto do celular.

### A chave de assinatura está no repositório

`keystore/viper.jks`, senha `viperplayer`. Isso seria inaceitável num app de
loja e é o certo aqui: o Android **recusa** atualizar um app cuja assinatura
mudou, então uma chave gerada a cada build obrigaria a desinstalar a versão
anterior — e perder histórico e retomadas — a cada atualização. O que a chave
protege é a continuidade da instalação, não um segredo.

Se um dia isto virar app de loja, a chave sai daqui e vai para os *secrets* do
repositório.

## Estrutura

```
app/src/main/java/com/mauricio/viperplayer/
  core/      Media · Prefs · ResumeStore · Theme
  library/   MediaLibrary (MediaStore) · LibraryScreen · Thumbnails
  smb/       SmbServerStore · SmbBrowser · SmbScreens
  player/    VlcEngine · PlayerActivity · Playback · Vlc
  MainActivity.kt
app/src/main/res/layout/activity_player.xml   a tela de reprodução
```

A biblioteca é Compose; o player é Views e XML. Não é inconsistência: é a mesma
divisão do lado de lá, onde a biblioteca é SwiftUI e o player é UIKit. A tela
que precisa interceptar cada toque, decidir o eixo do arrasto e conviver com
botões quer controle fino sobre o evento — e é justamente a que menos muda.

## Um motor só, para tocar e para navegar

O `libvlc-all` traz tudo: MKV, HEVC, AC-3/DTS, legenda ASS e PGS, decodificação
por hardware e **acesso SMB nativo**. A navegação no servidor sai dele também
(`Media.parseAsync` + `subItems`), e não de um cliente SMB em Java.

A alternativa daria erros de senha mais legíveis e o tamanho dos arquivos. Em
troca traria o pior modo de falhar possível: navegação e reprodução com
implementações diferentes de SMB, dialetos diferentes, e a pasta abrindo com o
vídeo recusando. Com um motor só, o que lista é o mesmo que toca.
