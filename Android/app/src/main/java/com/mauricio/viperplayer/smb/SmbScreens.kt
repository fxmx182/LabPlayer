package com.mauricio.viperplayer.smb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.mauricio.viperplayer.core.Device
import com.mauricio.viperplayer.core.LabTheme
import com.mauricio.viperplayer.core.MediaItem
import com.mauricio.viperplayer.core.labCard
import com.mauricio.viperplayer.player.Playback
import com.mauricio.viperplayer.tv.setasTrocamDeCampo
import com.mauricio.viperplayer.tv.tvFocus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbServersScreen(onBack: () -> Unit, onOpen: (SmbServer) -> Unit) {
    val contexto = LocalContext.current
    val store = remember { SmbServerStore.get(contexto) }
    var servidores by remember { mutableStateOf(store.servers) }
    var editando by remember { mutableStateOf<SmbServer?>(null) }
    var criando by remember { mutableStateOf(false) }
    val primeiroFoco = remember { FocusRequester() }

    // Sem foco inicial, a primeira seta do controle não move nada — e da
    // poltrona isso não se distingue de uma tela travada.
    LaunchedEffect(Unit) { runCatching { primeiroFoco.requestFocus() } }

    Scaffold(
        containerColor = LabTheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Servidores", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    // Na televisão este botão sai: a busca de foco do Compose
                    // não chega até a barra superior, então ele ficaria à
                    // mostra e inalcançável — pior que não existir. Lá quem
                    // adiciona é a primeira linha da lista.
                    if (!Device.isTv(contexto)) {
                        IconButton(onClick = { criando = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Adicionar servidor")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LabTheme.background,
                    titleContentColor = LabTheme.text,
                    actionIconContentColor = LabTheme.muted,
                    navigationIconContentColor = LabTheme.muted,
                ),
            )
        },
    ) { padding ->
        // Uma lista só, sempre — com a ação de adicionar dentro dela.
        //
        // O "+" da barra superior é confortável no polegar e inalcançável no
        // controle remoto: a busca de foco não chega até ele, e o usuário fica
        // preso na seta de voltar sem nenhum jeito de cadastrar um servidor.
        // Numa tela vista a três metros, a ação tem que ser uma linha da lista,
        // do tamanho das outras.
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        .focusRequester(primeiroFoco)
                        .tvFocus(LabTheme.radiusCard)
                        .clip(RoundedCornerShape(LabTheme.radiusCard)).labCard()
                        .clickable { criando = true }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, null, tint = LabTheme.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Adicionar servidor", color = LabTheme.text, fontWeight = FontWeight.Medium)
                }
            }

            if (servidores.isEmpty()) {
                item {
                    Text(
                        "Nenhum servidor salvo ainda. Adicione o endereço do servidor de casa " +
                            "e o Viper toca os vídeos direto de lá, sem baixar nada antes.",
                        color = LabTheme.muted, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                }
            }

            items(servidores, key = { it.id }) { servidor ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        // O realce de foco só aparece com controle remoto;
                        // no celular, onde o dedo aponta, ele nunca acende.
                        .tvFocus(LabTheme.radiusCard)
                        .clip(RoundedCornerShape(LabTheme.radiusCard)).labCard()
                        .clickable { onOpen(servidor) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Dns, null, tint = LabTheme.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(servidor.name, color = LabTheme.text, fontWeight = FontWeight.Medium)
                        Text(
                            servidor.displayHost + if (servidor.isGuest) " · convidado" else " · ${servidor.username}",
                            color = LabTheme.muted, fontSize = 12.sp,
                        )
                    }
                    IconButton(onClick = { editando = servidor }, modifier = Modifier.tvFocus(24.dp)) {
                        Icon(Icons.Filled.Edit, "Editar", tint = LabTheme.muted)
                    }
                    IconButton(
                        onClick = {
                            store.remove(servidor)
                            servidores = store.servers
                        },
                        modifier = Modifier.tvFocus(24.dp),
                    ) {
                        Icon(Icons.Filled.Delete, "Remover", tint = LabTheme.muted)
                    }
                }
            }
        }
    }

    if (criando || editando != null) {
        EditorDeServidor(
            servidor = editando,
            senhaAtual = editando?.let { store.password(it) },
            onCancel = { criando = false; editando = null },
            onSave = { servidor, senha ->
                store.save(servidor, senha)
                servidores = store.servers
                criando = false
                editando = null
            },
        )
    }
}

@Composable
private fun EditorDeServidor(
    servidor: SmbServer?,
    senhaAtual: String?,
    onCancel: () -> Unit,
    onSave: (SmbServer, String?) -> Unit,
) {
    var nome by remember { mutableStateOf(servidor?.name ?: "") }
    var host by remember { mutableStateOf(servidor?.host ?: "") }
    var porta by remember { mutableStateOf((servidor?.port ?: 445).toString()) }
    var usuario by remember { mutableStateOf(servidor?.username ?: "") }
    var senha by remember { mutableStateOf(senhaAtual ?: "") }
    var convidado by remember { mutableStateOf(servidor?.isGuest ?: false) }
    var mostrarSenha by remember { mutableStateOf(false) }

    val rolagem = rememberScrollState()

    AlertDialog(
        onDismissRequest = onCancel,
        // Sem a largura padrão da plataforma o diálogo aproveita a tela larga
        // da televisão em vez de virar uma coluna estreita e altíssima — que é
        // justamente o formato que não cabe numa tela deitada.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 560.dp).padding(horizontal = 24.dp),
        containerColor = LabTheme.surface,
        title = { Text(if (servidor == null) "Novo servidor" else "Editar servidor") },
        text = {
            // **O conteúdo rola.** Sem isto, numa tela deitada — televisão, ou
            // celular virado — os últimos campos e os botões ficam abaixo da
            // borda, e o que não dá para focar não dá para digitar: a senha
            // simplesmente não aceitava texto porque nunca recebia o foco.
            Column(Modifier.verticalScroll(rolagem).setasTrocamDeCampo()) {
                OutlinedTextField(
                    value = nome, onValueChange = { nome = it },
                    label = { Text("Nome") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("Endereço (IP ou nome)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = porta, onValueChange = { porta = it.filter(Char::isDigit) },
                    label = { Text("Porta") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number, imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = convidado, onCheckedChange = { convidado = it })
                    Text("Entrar como convidado", color = LabTheme.text, fontSize = 13.sp)
                }
                if (!convidado) {
                    OutlinedTextField(
                        value = usuario, onValueChange = { usuario = it },
                        label = { Text("Usuário") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = senha, onValueChange = { senha = it },
                        label = { Text("Senha") }, singleLine = true,
                        visualTransformation = if (mostrarSenha) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (mostrarSenha) KeyboardType.Text else KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Ver o que foi digitado importa mais aqui do que num
                    // celular: com um controle remoto, cada caractere é uma
                    // viagem pelo teclado da tela, e uma senha mascarada não
                    // dá como conferir se sobrou uma letra pelo caminho.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = mostrarSenha, onCheckedChange = { mostrarSenha = it })
                        Text("Mostrar a senha", color = LabTheme.text, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank(),
                onClick = {
                    val base = servidor ?: SmbServer(name = "", host = "")
                    onSave(
                        base.copy(
                            name = nome.ifBlank { host },
                            host = host.trim(),
                            port = porta.toIntOrNull() ?: 445,
                            username = usuario.trim(),
                            isGuest = convidado,
                        ),
                        if (convidado) null else senha,
                    )
                },
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } },
    )
}

/**
 * Navegação dentro do servidor.
 *
 * A pilha de caminho é uma lista de nomes, e não uma string: o botão de voltar
 * precisa desfazer um nível de cada vez, e cortar texto por barra erraria em
 * pasta cujo nome tem barra codificada.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbBrowserScreen(server: SmbServer, onBack: () -> Unit) {
    val contexto = LocalContext.current
    val store = remember { SmbServerStore.get(contexto) }
    val senha = remember(server.id) { store.password(server) }
    val credenciais = remember(server.id) { SmbBrowser.credenciais(server, senha) }

    var caminho by remember { mutableStateOf(listOf<String>()) }
    var entradas by remember { mutableStateOf<List<SmbBrowser.Entry>>(emptyList()) }
    var carregando by remember { mutableStateOf(true) }
    var erro by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(server.id, caminho) {
        carregando = true
        erro = null
        val uri = if (caminho.isEmpty()) {
            SmbBrowser.rootUri(server)
        } else {
            SmbBrowser.uri(server, caminho.first(), caminho.drop(1).joinToString("/"))
        }
        val resultado = SmbBrowser.list(contexto, uri, credenciais)
        if (resultado == null) {
            erro = if (caminho.isEmpty()) {
                "Este servidor não devolveu a lista de compartilhamentos."
            } else {
                "Não deu para abrir esta pasta. Confira o usuário e a senha — e se o aparelho " +
                    "está na mesma rede que ${server.displayHost}."
            }
            entradas = emptyList()
        } else {
            entradas = resultado
        }
        carregando = false
    }

    // Voltar sobe um nível de pasta antes de sair da tela — é o que o dedo
    // espera quando se está três pastas fundo no servidor.
    BackHandler { if (caminho.isEmpty()) onBack() else caminho = caminho.dropLast(1) }

    val titulo = if (caminho.isEmpty()) server.name else caminho.last()

    Scaffold(
        containerColor = LabTheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(titulo, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (caminho.isNotEmpty()) {
                            Text(
                                caminho.joinToString(" / "),
                                color = LabTheme.faint, fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (caminho.isEmpty()) onBack() else caminho = caminho.dropLast(1)
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LabTheme.background,
                    titleContentColor = LabTheme.text,
                    navigationIconContentColor = LabTheme.muted,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                carregando -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                // Na raiz, falha não é beco sem saída: muitos servidores não
                // deixam listar os compartilhamentos (é uma chamada de RPC
                // separada, que o Samba costuma negar a quem não é do domínio)
                // mesmo abrindo cada um deles sem reclamar. Então a raiz sempre
                // oferece digitar o nome — é o caminho que funciona sempre.
                caminho.isEmpty() -> RaizDoServidor(
                    entradas = entradas,
                    aviso = erro,
                    onAbrirShare = { nome -> caminho = listOf(nome) },
                )

                erro != null -> Text(
                    erro!!,
                    color = LabTheme.muted, textAlign = TextAlign.Center, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )

                entradas.isEmpty() -> Text(
                    "Pasta vazia.",
                    color = LabTheme.muted,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> {
                    // A fila de reprodução é só o que dá para tocar nesta pasta,
                    // na ordem em que aparece — pastas não entram.
                    val fila = remember(entradas, caminho) {
                        if (caminho.isEmpty()) emptyList()
                        else SmbBrowser.playableItems(
                            server, caminho.first(), caminho.drop(1).joinToString("/"), entradas,
                        )
                    }

                    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                        items(entradas, key = { it.uri.toString() }) { entrada ->
                            LinhaDoServidor(entrada) {
                                if (entrada.isDirectory) {
                                    caminho = caminho + entrada.name
                                } else {
                                    val item = fila.firstOrNull { it.title == entrada.name }
                                    if (item != null) Playback.start(contexto, item, fila)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinhaDoServidor(entrada: SmbBrowser.Entry, onClick: () -> Unit) {
    val ehVideo = MediaItem.isVideo(entrada.name)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .tvFocus(LabTheme.radiusSmall, scale = 1.02f)
            .clip(RoundedCornerShape(LabTheme.radiusSmall))
            .background(LabTheme.glass, RoundedCornerShape(LabTheme.radiusSmall))
            .clickable(enabled = entrada.isDirectory || ehVideo, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entrada.isDirectory) Icons.Filled.Folder else Icons.Filled.Movie,
            null,
            tint = if (entrada.isDirectory) LabTheme.accent else LabTheme.muted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            entrada.name,
            color = if (entrada.isDirectory || ehVideo) LabTheme.text else LabTheme.faint,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A raiz do servidor: os compartilhamentos que ele quis contar, e a porta dos
 * fundos para os que ele não contou.
 *
 * Listar compartilhamentos é uma chamada à parte, que muitos servidores negam
 * a quem não faz parte do domínio — enquanto abrem cada compartilhamento sem
 * reclamar nenhuma. Um app que só saiba listar fica inútil nesses; um que
 * aceite o nome digitado funciona em todos.
 */
@Composable
private fun RaizDoServidor(
    entradas: List<SmbBrowser.Entry>,
    aviso: String?,
    onAbrirShare: (String) -> Unit,
) {
    var perguntando by remember { mutableStateOf(false) }
    var nome by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        items(entradas, key = { it.uri.toString() }) { entrada ->
            LinhaDoServidor(entrada) { onAbrirShare(entrada.name) }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = if (entradas.isEmpty()) 0.dp else 6.dp)
                    .tvFocus(LabTheme.radiusSmall, scale = 1.02f)
                    .clip(RoundedCornerShape(LabTheme.radiusSmall))
                    .background(LabTheme.glass, RoundedCornerShape(LabTheme.radiusSmall))
                    .clickable { perguntando = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, null, tint = LabTheme.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Abrir compartilhamento pelo nome…", color = LabTheme.text, fontSize = 14.sp)
            }

            if (aviso != null) {
                Text(
                    aviso + " Isso é comum e não quer dizer que ele esteja fora do ar — " +
                        "digite o nome do compartilhamento acima.",
                    color = LabTheme.faint, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            }
        }
    }

    if (perguntando) {
        AlertDialog(
            onDismissRequest = { perguntando = false },
            containerColor = LabTheme.surface,
            title = { Text("Compartilhamento") },
            text = {
                Column {
                    OutlinedTextField(
                        value = nome, onValueChange = { nome = it },
                        label = { Text("Nome") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "O nome que aparece depois do endereço: em \\\\servidor\\Filmes, é “Filmes”.",
                        color = LabTheme.faint, fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = nome.isNotBlank(), onClick = {
                    perguntando = false
                    onAbrirShare(nome.trim())
                }) { Text("Abrir") }
            },
            dismissButton = { TextButton(onClick = { perguntando = false }) { Text("Cancelar") } },
        )
    }
}
