package com.mauricio.viperplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mauricio.viperplayer.core.Device
import com.mauricio.viperplayer.core.LabTheme
import com.mauricio.viperplayer.core.ViperTheme
import com.mauricio.viperplayer.library.LibraryScreen
import com.mauricio.viperplayer.tv.TvHomeScreen
import com.mauricio.viperplayer.smb.SmbBrowserScreen
import com.mauricio.viperplayer.smb.SmbServer
import com.mauricio.viperplayer.smb.SmbServersScreen

/**
 * A navegação do app, em três telas.
 *
 * Sem biblioteca de navegação: são três destinos e um deles é modal. Uma
 * dependência a mais para isso trocaria trinta linhas legíveis por um grafo,
 * um `NavController` e um contrato de argumentos serializados.
 */
private sealed interface Screen {
    data object Library : Screen
    data object Servers : Screen
    data class Browser(val server: SmbServer) : Screen
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ViperTheme {
                Surface(color = LabTheme.background, modifier = Modifier.fillMaxSize()) {
                    App()
                }
            }
        }
    }
}

@Composable
private fun App() {
    var tela by remember { mutableStateOf<Screen>(Screen.Library) }

    // O botão "voltar" do Android tem que voltar dentro do app, e não sair
    // dele. Sem isto, sair da tela de servidores fecha o Viper — e como a
    // navegação é um estado e não uma pilha de atividades, o sistema não tem
    // como adivinhar sozinho.
    BackHandler(enabled = tela is Screen.Servers) { tela = Screen.Library }

    val naTv = Device.isTv(LocalContext.current)

    when (val atual = tela) {
        // A mesma origem de dados nos dois casos; o que muda é a tela — grade
        // para o dedo, faixas para o controle remoto.
        is Screen.Library -> ComPermissao(bloqueia = !naTv) {
            if (naTv) TvHomeScreen(onOpenServers = { tela = Screen.Servers })
            else LibraryScreen(onOpenServers = { tela = Screen.Servers })
        }
        is Screen.Servers -> SmbServersScreen(
            onBack = { tela = Screen.Library },
            onOpen = { servidor -> tela = Screen.Browser(servidor) },
        )
        is Screen.Browser -> SmbBrowserScreen(
            server = atual.server,
            onBack = { tela = Screen.Servers },
        )
    }
}

/**
 * A permissão de ler vídeo, pedida onde ela é usada.
 *
 * O nome dela mudou no Android 13, e essa é a única complicação: até o 12 é a
 * permissão genérica de armazenamento; do 13 em diante, uma específica para
 * vídeo. Pedir a errada devolve uma negativa que o sistema nem mostra ao
 * usuário — o app só fica com a lista vazia, sem explicação.
 */
@Composable
private fun ComPermissao(bloqueia: Boolean = true, conteudo: @Composable () -> Unit) {
    val contexto = LocalContext.current
    val permissao = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var concedida by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(contexto, permissao) == PackageManager.PERMISSION_GRANTED
        )
    }

    val pedido = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { resposta -> concedida = resposta }

    LaunchedEffect(Unit) { if (!concedida) pedido.launch(permissao) }

    // Na televisão a permissão é pedida do mesmo jeito, mas a negativa não
    // tranca a porta: numa caixinha de TV quase não há vídeo local, e o que
    // interessa — o servidor de casa — não depende dela. Trancar tudo aqui
    // deixaria o usuário sem o acervo por causa de uma pasta vazia.
    if (concedida || !bloqueia) {
        conteudo()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "O Viper precisa de acesso aos vídeos do aparelho para montar a biblioteca. " +
                    "Nada sai daqui — a varredura é local.",
                style = MaterialTheme.typography.bodyMedium,
                color = LabTheme.muted,
                textAlign = TextAlign.Center,
            )
            Button(onClick = { pedido.launch(permissao) }, modifier = Modifier.padding(top = 20.dp)) {
                Text("Permitir")
            }
        }
    }
}
