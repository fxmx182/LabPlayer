# Publicar o LibertyX na Play Store — passo a passo

O que já está pronto no código está marcado ✅. O resto é feito no navegador, no
[Play Console](https://play.google.com/console), e só o dono da conta pode fazer.

## O modelo escolhido

- **App grátis + compra única "LibertyX Pro"** (produto `libertyx_pro`, US$ 4,99,
  com o preço de cada país sugerido pela Play).
- **7 dias de Pro grátis** contados da primeira abertura. Depois volta ao grátis,
  que continua um player completo: biblioteca, player, gestos, busca quadro a
  quadro, legendas e faixas de áudio.
- **Pro:** pastas da rede (SMB), segundo plano + Android Auto, janela flutuante,
  timer para dormir, captura de quadro.
- **Uma página na loja** para celular e TV: mesmo pacote, a TV pela faixa própria
  de Android TV. A compra vale nos dois.

## O que o código já faz

- ✅ Compra pela Google Play Billing 8, reconhecimento automático (sem isso a
  Play estorna em 3 dias), pagamento pendente de boleto/Pix e restauração.
- ✅ Tela do Pro, selo PRO nas ferramentas, fileira do Pro na TV e tela de
  licenças (libVLC/LGPL e as demais).
- ✅ `./gradlew bundleLoja` gera `dist/play/LibertyXPlayer-Celular.aab` e
  `LibertyXPlayer-TV.aab`, assinados com a **chave de envio**.
- ✅ Números de versão: celular terminam em 1, TV em 2 (a Play não aceita
  repetidos).
- ✅ Política de privacidade: [`../PRIVACY.md`](../PRIVACY.md), em inglês,
  português e espanhol. Endereço para a Play:
  `https://github.com/fxmx182/LabPlayer/blob/main/Android/PRIVACY.md`
- ✅ Textos da página nos três idiomas: [`FICHA.md`](FICHA.md). Imagens em `imagens/`.

## A chave de envio — guarde uma cópia

`/DATA/Claudinho/chaves/libertyx-envio.jks`, com a senha em
`/DATA/Claudinho/chaves/LEIA-libertyx-envio.txt` e em `~/.gradle/gradle.properties`.
**Fora do repositório**, que é público. Faça uma cópia fora desta máquina (pendrive,
cofre de senhas). Se perder, o Google troca, mas leva dias e trava as atualizações.

Ela não é a chave que assina o app no celular das pessoas: no primeiro envio, aceite
o **Play App Signing** (a Play guarda a chave final e reassina).

## Passo a passo no Play Console

1. **Conta de desenvolvedor** (US$ 25, uma vez). Conta *pessoal* criada depois de
   nov/2023 exige o **teste fechado** do passo 8 antes de liberar a produção.
2. **Perfil de pagamentos** (Configurações › Perfil de pagamentos): sem ele não dá
   para cadastrar produto pago. Pede CPF, endereço e conta bancária.
3. **Criar app**: nome *LibertyX Player*, idioma padrão *Português (Brasil)*,
   *App*, *Gratuito*. (Gratuito é o certo: quem cobra é a compra dentro do app.)
4. **Primeiro envio** em *Teste › Teste interno*: suba
   `dist/play/LibertyXPlayer-Celular.aab` e aceite o Play App Signing. O produto
   só pode ser criado depois que houver um envio com a biblioteca de cobrança.
5. **Produto** em *Monetizar › Produtos › Produtos no app*:
   - ID: `libertyx_pro` (**exatamente assim**: o app procura esse ID)
   - Nome: *LibertyX Pro* · Descrição: *Pastas da rede, segundo plano e Android
     Auto, janela flutuante, timer e captura.*
   - Preço: US$ 4,99 → "Atualizar taxas de câmbio" preenche os outros países.
     Ajuste o Brasil à mão se quiser um número redondo (ex.: R$ 14,90).
   - Ativar.
6. **Testadores de licença** (Configurações › Teste de licença): coloque o seu
   Gmail. Compras dessa conta são de teste e **não cobram**. É assim que você
   testa a compra, e também como fica com o Pro de graça nos seus aparelhos
   instalando pela loja.
7. **Conteúdo do app** (menu *Política › Conteúdo do app*):
   - *Política de privacidade*: o endereço acima.
   - *Acesso ao app*: "Todas as funcionalidades estão disponíveis sem restrições".
     O revisor usa os 7 dias de teste.
   - *Anúncios*: Não.
   - *Classificação de conteúdo*: questionário. Tudo "não": o app não tem conteúdo
     próprio, só toca o do usuário.
   - *Público-alvo*: 18+ (evita as regras de app infantil).
   - *Segurança dos dados*: **não coleta nem compartilha dados**. A compra é
     processada pelo Google e não conta como coleta do app.
   - *Permissões de fotos e vídeos*: o app usa `READ_MEDIA_VIDEO` porque é
     um player de vídeo que mostra a biblioteca inteira do aparelho. É o uso
     principal, e é permitido.
   - *Serviço em primeiro plano*: tipo **media playback**. Justificativa: "toca o
     áudio do vídeo com a tela apagada e no Android Auto, com controles na
     notificação". Pede um vídeo curto mostrando isso. Grave a tela do celular
     tocando e apagando a tela, e suba no YouTube como "não listado".
8. **Teste fechado** (*Teste › Teste fechado*): crie a faixa, suba o `.aab` e
   convide **pelo menos 12 pessoas** que fiquem com o app instalado por **14 dias
   seguidos**. Depois disso aparece o botão "Solicitar acesso à produção".
9. **Android TV** (*Configurações › Configuração avançada › Formatos*): adicione
   Android TV e crie a faixa própria dela. Suba `LibertyXPlayer-TV.aab`, o banner
   `imagens/tv-banner-1280x720.png` e pelo menos uma captura de TV. A TV passa por
   uma revisão de qualidade própria (navegação só pelo controle remoto).
10. **Android Auto**: em *Formatos*, adicione Android Auto e aceite os termos. A
    revisão do carro é separada e mais rígida. Se ela recusar, o resto do app sai
    normalmente e o Auto pode ser resolvido depois.
11. **Página da loja**: textos de `FICHA.md`, ícone `../Scripts/play-512.png`,
    imagem de destaque e capturas de `imagens/`. Categoria: *Players e editores de
    vídeo*. E-mail de contato: obrigatório e público.
12. **Produção**: com o teste fechado cumprido, promova a versão. A primeira
    revisão costuma levar de 1 a 7 dias.

## Toda atualização depois disso

```bash
cd Android && ./gradlew bundleLoja -PlibertyxCommit=$(git rev-parse --short HEAD)
```

Suba os dois `.aab` de `dist/play/`: o de celular na faixa principal e o de TV na
faixa de Android TV.

## APKs do GitHub e da pasta `dist/`

- **Pasta `dist/`** (compilada nesta máquina): Pro **liberado**. São os seus.
- **Release do GitHub** (compilado pelo CI): versão igual à da loja, com os 7 dias
  e o Pro à venda. Quem instala por lá é mandado à Play para comprar.
- Assinaturas diferentes: o app da Play não instala por cima de um APK do GitHub
  ou da pasta. Para trocar, desinstale antes.
