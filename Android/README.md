# LibertyX Player — Android

O mesmo player do iPhone, do outro lado. Mesmo nome, mesmo ícone, mesmos
gestos, mesmo motor de vídeo — escrito de novo em Kotlin porque não há uma
linha de Swift que rode aqui. E o mesmo APK também é o app de televisão: veja
[Televisão](#televisão).

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

- **Legenda externa escolhida à mão.** O motor já aceita
  (`VlcEngine.addSubtitle`), mas não há botão. Legenda ao lado do arquivo com o
  mesmo nome já entra sozinha.

## Achar o servidor sozinho

A tela de Servidores procura na rede assim que abre — sem botão, porque
procurar é o que se quer fazer ao entrar ali. Um toque no que apareceu leva o
nome e o endereço prontos para o formulário; só sobram usuário e senha.

São duas técnicas somadas, as mesmas do irmão de iOS, porque cada uma sozinha
deixa buraco:

| | acha | traz |
|---|---|---|
| **mDNS** (`_smb._tcp`) | quem se anuncia — NAS, Windows, Samba com Avahi | o nome legível da máquina |
| **Varredura da porta 445** | qualquer coisa que aceite conexão SMB | só o endereço |

Uma diferença em relação ao iOS que não é cosmética: lá o código pergunta pela
interface `en0`, o Wi-Fi do iPhone. Aqui isso não serve — uma caixinha de
Android TV quase sempre está no cabo, e fixar o Wi-Fi deixaria a descoberta
morta justamente no aparelho que mais precisa dela. Quem responde qual é a rede
em uso é o sistema, e aí funciona no celular, na TV e com VPN ligada.

A varredura é 32 endereços por vez, com 700 ms de prazo cada: numa rede local
quem responde responde em milissegundos, e esperar mais só faria a busca
inteira demorar.

**No Android de PC** (emulador, BlueStacks, Waydroid) as duas técnicas batiam
num muro: o Android fica numa rede virtual atrás do PC, o anúncio mDNS não
atravessa, e a varredura só enxergava a própria rede virtual — enquanto o
endereço digitado à mão conectava normalmente. Por isso, além da rede do
aparelho, a varredura percorre a rede dos servidores já salvos e, quando a rede
do aparelho é uma das virtuais conhecidas (10.0.2, 10.0.3, 192.168.240,
172.16–31), as faixas de fábrica dos roteadores de casa — 96 por vez e 400 ms
de prazo, porque a maioria delas não existe.

## No carro (Android Auto)

Só na variante de celular, e **só o áudio** — essa é a primeira coisa a dizer,
porque a expectativa natural é outra. O Android Auto não entrega superfície de
vídeo a app de terceiro, e a política do Google recusa app de vídeo no carro.
Não é limitação do LibertyX: não existe player de vídeo de terceiro rodando na
tela do carro, de ninguém.

O que existe, e é o que está implementado: o carro navega a biblioteca — as
pastas do aparelho **e os servidores SMB** — numa lista feita para ser lida de
relance, e toca a faixa de áudio do arquivo escolhido. Play, pausa, faixa
anterior e próxima respondem no volante. Serve para show, documentário, aula
gravada, podcast em vídeo: tudo que se ouve sem precisar ver.

O mesmo serviço (`auto/LibertyXMediaService.kt`) resolve o **áudio em segundo
plano** no celular, que estava na lista de pendências — são o mesmo problema,
tocar sem tela na frente, e escrevê-lo duas vezes seria desperdício.

Dois cuidados que um app de carro precisa ter e que estão lá:

- **Foco de áudio.** Sem pedir, o som se sobrepõe ao GPS e à ligação em vez de
  baixar ou parar. Com o foco, ele abaixa para 25% quando o GPS fala e volta
  sozinho.
- **Retomar sem perguntar.** No celular o app pergunta se você quer continuar
  de onde parou; no carro não há como responder uma pergunta, então ele retoma
  e deixa a barra de progresso desfazer isso num toque.

**Testado até onde dá aqui:** o serviço sobe, registra a sessão de mídia (é o
que o carro procura) e responde aos comandos de transporte sem quebrar. O
percurso completo — o carro listando as pastas — precisa do Desktop Head Unit
ou de um carro de verdade.

## Televisão

O mesmo APK roda na TV. Não há versão separada, nem outro download: o app
descobre onde está (`UI_MODE_TYPE_TELEVISION`) e troca o que precisa ser
trocado — porque o motor, o SMB, a retomada e as preferências são idênticos, e
o que muda é quem comanda.

| | Celular | Televisão |
|---|---|---|
| **Tela inicial** | grade de miniaturas | faixas horizontais, servidor na primeira |
| **Foco** | não existe — o dedo aponta | borda amarela e crescimento, legível a três metros |
| **Rolar o vídeo** | arrastar o dedo | ◀ ▶ com a barra escondida; a barra focada rola fino |
| **Menu** | botão ⋮ | tecla MENU do controle, ou o mesmo ⋮ |
| **Fora do menu** | — | sem "girar tela" nem "bloquear tela": não há o problema que elas resolvem |
| **Janela flutuante** | sim | não existe em TV — o botão sai |

Vale a pena entender por que o servidor vem primeiro na TV: numa caixinha de
Android TV quase não existe vídeo local. O acervo mora no servidor de casa, e
esconder isso atrás de um ícone de canto seria enterrar justamente o que a
pessoa ligou a televisão para ver.

Serve Chromecast com Google TV, Fire TV, Nvidia Shield e as TVs com Google TV
de fábrica — instale o **`LibertyXPlayer-TV.apk`**, que traz as duas arquiteturas
ARM. As teclas de mídia (play, avanço, faixa) valem também em teclado bluetooth
e em controle de jogo.

**Não instale pela Play Store.** O app não está publicado lá; a loja da TV vai
dizer que ele não é compatível simplesmente por não o conhecer. Os caminhos que
funcionam:

- **Downloader** (existe nas duas lojas): digite a URL do release e ele baixa e
  instala. Antes, ligue *Ajustes → Sistema → Sobre → tocar 7× em "Versão"* e
  depois *Opções do desenvolvedor → Apps de fontes desconhecidas*.
- **adb**, com a depuração pela rede ligada:
  ```bash
  adb connect IP-DA-TV:5555
  adb install -r dist/LibertyXPlayer-TV.apk
  ```

## Gestos

No celular. Os mesmos do irmão de iOS, que por sua vez são os do MX Player:

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

Os APKs prontos ficam em **`Android/dist/`**, com nome de aparelho:

| Arquivo | Para quê | Tamanho |
|---|---|---|
| `LibertyXPlayer-TV.apk` | **televisão** — Google TV, Fire TV, Shield | 106 MB |
| `LibertyXPlayer-Celular.apk` | **celular** deste lado de 2015 | 67 MB |
| `LibertyXPlayer-Celular-Antigo.apk` | celular anterior a isso | 53 MB |
| `LibertyXPlayer-PC-x86_64.apk` | **PC e Chromebook** — emulador, Waydroid, BlueStacks | 75 MB |

São **variantes exclusivas**, não o mesmo arquivo com dois nomes. O de TV exige
o recurso `leanback`, que celular nenhum tem — então ele nem instala num
telefone. O de celular não declara a categoria da tela inicial de TV, então não
aparece na grade da televisão. É o `productFlavors` do Gradle: mesmo código
fonte, dois pacotes que se recusam mutuamente.

O da TV é o dobro do tamanho porque carrega as **duas** arquiteturas ARM, e
essa é a única forma honesta de resolver uma armadilha: muita caixinha de
Google TV e quase todo Fire Stick rodam Android de **32 bits** sobre um
processador de 64. Um APK só de arm64 é genuinamente incompatível com elas — e
a mensagem que aparece, *"este app não é compatível com sua TV"*, não diz qual
recurso faltou. Com as duas dentro, ninguém precisa descobrir de quantos bits é
a sua TV.

### O que faz uma TV recusar um app

Vale registrar porque não é óbvio e o sintoma engana. Duas coisas reprovam:

1. **Uma arquitetura que o aparelho não tem** — o caso acima.
2. **Um recurso declarado como obrigatório que a TV não possui.** E há
   permissões que declaram recursos por conta própria: `ACCESS_WIFI_STATE`
   implica `android.hardware.wifi` como **exigido**, o que reprova qualquer TV
   ligada por cabo. Por isso o manifesto declara explicitamente wifi, câmera,
   telefonia, microfone e localização como `required="false"`.

Para conferir num APK qualquer, sem instalar:

```bash
aapt2 dump badging arquivo.apk | grep -E "native-code|feature"
```

Nenhuma linha deve dizer `uses-feature:` ou `uses-implied-feature:` — só
`uses-feature-not-required:`.

`app/build/outputs/apk/release/` continua tendo os mesmos arquivos com o nome
que o Gradle dá (`app-arm64-v8a-release.apk`). É diretório de compilação: quem
vai instalar usa o `dist/`.

Não existe APK universal de propósito: o VLC vem compilado para cada
arquitetura e os três juntos passam de 200 MB, dos quais o aparelho usaria um
terço.

O `x86_64` sai da variante de celular, que é a completa: ele roda em emulador,
Waydroid, BlueStacks e Chromebook. A variante de TV não o carrega — caixinha de
televisão é sempre ARM, e incluí-lo lá engordaria o APK universal em 25 MB à
toa.

Para testar a versão de televisão no emulador é preciso acrescentar o x86 de
**32 bits** — é a única arquitetura das imagens de Android TV que roda acelerada
num PC comum, e ela não vai no release porque não existe aparelho assim:

```bash
./gradlew assembleRelease -PlibertyxX86
```

```bash
adb install -r dist/LibertyXPlayer-Celular.apk
```

Um push na `main` faz o mesmo no GitHub Actions e publica um **release novo a
cada build**, etiquetado com a data e o commit. O mais recente é marcado como
"latest", o que dá uma URL que nunca muda:

```bash
curl -L -O https://github.com/fxmx182/LabPlayer/releases/latest/download/LibertyXPlayer-TV.apk
```

Assim existem as duas coisas: histórico para voltar à versão de ontem, e um
link fixo para instalar. Os dez últimos ficam guardados; os mais velhos são
podados, porque cada build são 300 MB e ninguém volta trinta versões.

### O número da versão é o relógio

`versionCode` é o tempo em minutos desde o começo de 2026. Precisa ser
monotônico e não pode depender de quem compilou: o Android recusa instalar por
cima um APK com número menor, e o app vem de dois lugares — o release do CI e a
pasta `dist/` desta máquina. Com o relógio, o build mais recente é sempre o
maior, tenha nascido onde tiver.

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
app/src/main/java/com/mauricio/libertyx/
  core/      Media · Prefs · ResumeStore · Theme · Device (celular ou TV?)
  library/   MediaLibrary (MediaStore) · LibraryScreen · Thumbnails
  smb/       SmbServerStore · SmbBrowser · SmbScreens
  player/    VlcEngine · PlayerActivity · Playback · Vlc
  tv/        TvHomeScreen · TvFocus (o realce que a TV exige)
  MainActivity.kt
app/src/celular/    manifesto próprio + auto/LibertyXMediaService (Android Auto)
app/src/tv/         manifesto próprio + o banner da tela inicial da TV
app/src/main/res/layout/activity_player.xml   a tela de reprodução
```

A biblioteca é Compose; o player é Views e XML. Não é inconsistência: é a mesma
divisão do lado de lá, onde a biblioteca é SwiftUI e o player é UIKit. A tela
que precisa interceptar cada toque, decidir o eixo do arrasto e conviver com
botões quer controle fino sobre o evento — e é justamente a que menos muda.

## A armadilha da senha no SMB

Fica registrada porque o sintoma aponta para o lugar errado. O jeito óbvio de
mandar credenciais ao VLC é embuti-las na URL — `smb://usuario:senha@servidor`.
O VLC aceita, avisa `Password in a URI is DEPRECATED` no log **e segue sem a
senha**.

O resultado é um app que "não conecta" com todos os dados certos, e um servidor
que não registra nem uma falha de autenticação — porque o usuário nunca chegou
lá. Procura-se rede, firewall e digitação; o problema estava na URL.

O certo é mandar por opção do próprio media:

```kotlin
media.addOption(":smb-user=$usuario")
media.addOption(":smb-pwd=$senha")
media.addOption(":smb-domain=WORKGROUP")
```

De quebra some uma classe inteira de defeito: senha com `@`, `/`, `:` ou acento
não precisa mais sobreviver a uma codificação de URL.

## Um motor só, para tocar e para navegar

O `libvlc-all` traz tudo: MKV, HEVC, AC-3/DTS, legenda ASS e PGS, decodificação
por hardware e **acesso SMB nativo**. A navegação no servidor sai dele também
(`Media.parseAsync` + `subItems`), e não de um cliente SMB em Java.

A alternativa daria erros de senha mais legíveis e o tamanho dos arquivos. Em
troca traria o pior modo de falhar possível: navegação e reprodução com
implementações diferentes de SMB, dialetos diferentes, e a pasta abrindo com o
vídeo recusando. Com um motor só, o que lista é o mesmo que toca.

A pasta de rede tem a cara da biblioteca local — grade ou lista, miniatura,
ordenação por título, data, tamanho ou duração, nas mesmas opções. Duas peças
tornam isso possível, e nenhuma decide o que aparece ou o que toca:

- **A miniatura no servidor é o VLC também** (`QuadroDoVlc`): uma reprodução
  muda que desenha num `ImageReader` fora da tela, com busca a um terço do
  arquivo. Duas armadilhas já pagas: o VLC entrega os pixels em RGBX, e um
  `ImageReader` criado em RGBA recusa todo quadro; e o decodificador de
  hardware escreve numa superfície própria, então a extração decodifica em
  software e sai por OpenGL. A miniatura fica em cache no disco, com a duração
  descoberta no caminho, e espera enquanto um vídeo está tocando.
- **Tamanho e data vêm do `smbj`**, porque o VLC não os informa. Se ele falhar,
  a pasta abre igual, só sem esses dois campos. Em sessão de convidado ele
  quebra no SMB 3 (não há chave de sessão para derivar as de assinatura), então
  convidado conversa em SMB 2.1.
