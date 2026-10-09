# LibertyX Folder — iPhone e iPad

O gerenciador de arquivos do Android ([/DATA/Claudinho/Arquivos](../../../Arquivos), pacote `com.mauricio.libertyx.files`) refeito em SwiftUI, com os mesmos parâmetros da versão de celular da Play: **tudo grátis, sem Pro, sem pasta segura, sem guia** (`PRO`, `PASTA_SEGURA` e `GUIA` desligados, como no flavor `celular`). Bundle `com.mauricio.libertyx.files`, iOS 17+.

- **Início:** marca, pesquisa no aparelho, arquivos recentes, categorias (Imagens, Vídeos, Áudio, Documentos, Downloads, Compactados), armazenamento e rede.
- **Pastas:** lista/grade, ordem natural, ocultos, trilha de navegação, puxar para atualizar, pesquisa na pasta e nas subpastas.
- **Seleção** (toque longo, como no Android): mover, copiar (colar na pasta de destino), compartilhar, excluir, renomear, abrir com, salvar na galeria, detalhes.
- **SMB:** busca automática (Bonjour + varredura da porta 445 + nome NetBIOS), compartilhamentos, usuário/senha ou convidado, ler/gravar/renomear/mover/excluir, miniaturas de foto, vídeo e áudio **tocando sem baixar**.
- **Operações** com progresso e cancelamento, entre quaisquer dois lugares; nunca sobrescreve ("nome (1).ext").

## O que muda no iOS: o acesso às pastas

O Android pede "acesso a todos os arquivos" e enxerga o armazenamento inteiro. **O iOS não tem essa permissão.** Cada app vive numa caixa fechada e, fora dela, só vê o que a pessoa escolhe no seletor do sistema. Por isso o "Armazenamento" do início é uma lista de **lugares** (`FS/Lugares.swift`):

| Lugar | O que é |
|---|---|
| **No iPhone** | a pasta do próprio app (`Documents`). No app Arquivos: *No iPhone › LibertyX Folder*. Sempre disponível. |
| **Pastas autorizadas** | qualquer pasta escolhida em *Adicionar pasta*: iCloud Drive, *No iPhone*, pendrive/HD na USB-C, a pasta de outro app. O acesso vale para a árvore inteira e é guardado como **bookmark com escopo de segurança** — não pergunta de novo. |
| `tmp` (escondido) | onde chega o que vem da galeria ou do seletor de arquivos antes de ir ao destino, para importar usar a mesma operação de copiar com progresso — e servir também para pasta de servidor. |

- Um `Loc.local` é **raiz + caminho relativo**; acima da raiz não se sobe (o `root` do Android).
- O escopo de cada pasta é aberto uma vez e **segurado enquanto o app vive**: soltar e pegar a cada leitura custaria uma chamada por arquivo, e uma cópia longa perderia o acesso no meio.
- Bookmark vencido é renovado na hora. Pasta que não abre (pendrive desligado, pasta apagada) fica na lista como **Indisponível — toque para autorizar de novo**; ao voltar ao app, tenta abrir de novo.
- **Downloads** não é categoria: o iOS não deixa o app achar a pasta sozinho. O atalho explica e pede para escolhê-la uma vez; daí em diante abre direto.
- **Categorias, recentes e pesquisa no aparelho** saem de um índice nosso (`FS/Indice.swift`), varrendo os lugares (até 30 mil itens, 12 níveis) — o iOS não tem um MediaStore que um app possa consultar.
- **iCloud:** arquivo que ainda não desceu aparece com uma nuvem na miniatura e é baixado ao abrir, copiar ou compartilhar (`LocalFs.garantirBaixado`).
- **Galeria:** fotos e vídeos vivem no app Fotos, não em pastas. *Importar da galeria* usa o seletor do sistema (não pede permissão); *Salvar na galeria* pede só a permissão de adicionar.
- **De outros apps:** o Folder aparece em *Compartilhar*/*Abrir com*; o que chega vai para *No iPhone › Recebidos*.
- **Operações em segundo plano:** o Android tem serviço em primeiro plano; o iOS dá só uns segundos depois de sair do app. Cópia longa precisa do app aberto — se o tempo acaba, a operação para com "Cancelado" em vez de sumir.

## Abrir arquivos

- No aparelho: **QuickLook** (o visualizador do Arquivos) — foto, vídeo, áudio, PDF, Office, texto. As fotos e vídeos da mesma pasta viram páginas: desliza-se entre eles.
- Do servidor: MP4/MOV/M4V, MP3/M4A/AAC/WAV **tocam sem baixar** (`Rede/StreamSmb.swift`, o `SMBResourceLoader` do Player com as armadilhas já pagas). O resto desce para `Caches/abertos` (limpo a cada abertura do app) com progresso e Cancelar, e abre no QuickLook.
- *Abrir com* = a folha de compartilhar do sistema (é como o iOS lista os apps que aceitam o arquivo). MKV, por exemplo, vai para o LibertyX Player por ali.

## Visual

O de `ui/Theme.kt`, valor por valor (`Core/Tema.swift`): ouro `#FBC501` sobre preto quente `#0C0B0A`, vidro a 5,5% com borda a 11%, cantos de 22, Manrope em tudo, títulos de seção pequenos em caixa alta, brilho dourado no alto e azul frio no rodapé. Escuro sempre. Cada tipo de arquivo com a sua cor; a pasta é a cor da marca (selo leve na lista, placa de vidro na grade).

**Ícone** gerado por `Scripts/icone.py`, que carrega a geometria do script do Android (`/DATA/Claudinho/Arquivos/Scripts/icone.py`) e grava `AppIcon-1024.png` e a marca `Marca.imageset`. Precisa de `shapely resvg-py pillow`.

## Idiomas

Como no Player: inglês é o padrão (`CFBundleDevelopmentRegion=en`), com português e espanhol. Os textos ficam **em português no código** e são a chave das tabelas em `App/Resources/{en,es}.lproj/Localizable.strings`; plurais em `Localizable.stringsdict` (inclusive `pt.lproj`). Quase todas as traduções vieram dos `strings.xml` do Android.

- `Text(variavel)` e ternário de literais **não** traduzem — passar por `String(localized:)`.
- Texto novo entra nas tabelas en/es (interpolação vira `%@` para texto e `%lld` para número).

## Compilar e instalar

Sem macOS em casa, **o CI é o compilador**: `.github/workflows/folder-ios.yml` roda em `macos-15` a cada push que toca `Folder/iOS/**`, gera o projeto com XcodeGen, compila sem assinar, assina ad-hoc (sem isso o iOS recusa com "spawn failed, error=85") e publica:

```bash
curl -L -o LibertyXFolder.ipa https://github.com/fxmx182/LabPlayer/releases/download/folder-latest/LibertyXFolder.ipa
```

Instalar pelo Sideloadly (Windows), como o Player. Nenhum entitlement de propósito: continua assinável por Apple ID gratuito. Erro de compilação aparece como **anotação** do job (legível sem token: `GET /repos/fxmx182/LabPlayer/commits/{sha}/check-runs` → `.../annotations`). O commit sai carimbado em Configurações › Sobre.
