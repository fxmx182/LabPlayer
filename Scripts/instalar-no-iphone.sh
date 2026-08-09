#!/usr/bin/env bash
#
# Instala o LabPlayer.ipa no iPhone ligado por USB neste servidor.
#
# Pré-requisitos (uma vez só):
#   sudo apt install -y usbmuxd libimobiledevice-utils ideviceinstaller
#   container `anisette` rodando na porta 6969
#   binário AltServer em ~/.local/bin/AltServer
#
# Uso:
#   Scripts/instalar-no-iphone.sh caminho/para/LabPlayer.ipa
#
set -euo pipefail

ANISETTE_URL="${ALTSERVER_ANISETTE_SERVER:-http://127.0.0.1:6969}"
ALTSERVER="$HOME/.local/bin/AltServer"

falhar() { echo "erro: $*" >&2; exit 1; }

IPA="${1:-}"
[ -n "$IPA" ] || falhar "informe o caminho do .ipa
  exemplo: $0 ~/Downloads/LabPlayer.ipa"
[ -f "$IPA" ] || falhar "arquivo não encontrado: $IPA"

# O artefato do GitHub vem como .zip com o .ipa dentro; erro comum.
case "$IPA" in
  *.zip) falhar "isso é o .zip do artefato. Descompacte primeiro:
  unzip '$IPA' && $0 LabPlayer.ipa" ;;
esac

[ -x "$ALTSERVER" ] || falhar "AltServer não encontrado em $ALTSERVER"

command -v idevice_id >/dev/null || falhar "libimobiledevice ausente. Rode:
  sudo apt install -y usbmuxd libimobiledevice-utils ideviceinstaller"

systemctl is-active --quiet usbmuxd || echo "aviso: serviço usbmuxd não está ativo"

curl -sf --max-time 10 "$ANISETTE_URL" >/dev/null \
  || falhar "servidor anisette não responde em $ANISETTE_URL
  suba com: docker start anisette"

echo "==> procurando o iPhone…"
UDID="$(idevice_id -l 2>/dev/null | head -1 || true)"
[ -n "$UDID" ] || falhar "nenhum aparelho encontrado.
  Ligue o iPhone por USB neste servidor, desbloqueie a tela e toque em
  'Confiar neste computador'. Depois rode de novo."

NOME="$(ideviceinfo -u "$UDID" -k DeviceName 2>/dev/null || echo '?')"
VERSAO="$(ideviceinfo -u "$UDID" -k ProductVersion 2>/dev/null || echo '?')"
echo "    $NOME (iOS $VERSAO)"
echo "    udid: $UDID"

# A senha vai direto para a Apple via anisette local. Não é gravada em disco,
# não entra no histórico do shell e não sai desta máquina para mais ninguém.
echo
read -rp "Apple ID (e-mail): " APPLE_ID
read -rsp "Senha do Apple ID: " APPLE_SENHA
echo; echo

echo "==> assinando e instalando (o 2FA vai ser pedido aqui)…"
ALTSERVER_ANISETTE_SERVER="$ANISETTE_URL" \
  "$ALTSERVER" -u "$UDID" -a "$APPLE_ID" -p "$APPLE_SENHA" "$IPA"

echo
echo "pronto. O LabPlayer deve estar na tela inicial do iPhone."
echo
echo "Antes de abrir, confie no certificado uma vez:"
echo "  Ajustes > Geral > VPN e Gerenciamento de Dispositivo > seu Apple ID > Confiar"
echo
echo "Com Apple ID gratuito o app expira em 7 dias — rode este script de novo"
echo "para renovar, ou instale o SideStore para ele renovar sozinho pelo Wi-Fi."
