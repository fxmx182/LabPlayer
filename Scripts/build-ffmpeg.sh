#!/usr/bin/env bash
#
# Compila o FFmpeg para iOS e empacota como FFmpeg.xcframework.
#
# Roda no runner macOS do CI (~12 min) e o resultado é cacheado — o cache é
# invalidado pelo hash deste arquivo, então mexer aqui força recompilação.
#
# Licença: LGPL. Sem --enable-gpl e sem --enable-nonfree, de propósito. O que o
# GPL traria (x264, x265) é irrelevante aqui: o app só decodifica, nunca
# codifica, e a decodificação pesada vai para o VideoToolbox.
#
set -euo pipefail

FFMPEG_VERSION="${FFMPEG_VERSION:-7.1.1}"
MIN_IOS="${MIN_IOS:-17.0}"

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TRABALHO="$RAIZ/.ffmpeg-build"
PREFIXO="$TRABALHO/install"
SAIDA="$RAIZ/Vendor/FFmpeg.xcframework"

SDK_PATH="$(xcrun --sdk iphoneos --show-sdk-path)"
CC_BIN="$(xcrun --sdk iphoneos -f clang)"

# ---------------------------------------------------------------------------
# Configuração: partimos de --disable-everything e ligamos só o necessário.
#
# O padrão do FFmpeg liga centenas de codificadores e filtros que nunca vamos
# usar; a build fica lenta e o binário enorme (o iOS tem limite de tamanho para
# download em rede móvel). Esta lista é o que um player de verdade precisa.
# ---------------------------------------------------------------------------

# Contêineres que o AVFoundation não abre — a razão de existir desta fase.
DEMUXERS=(
  matroska mov avi mpegts mpegtsraw mpegps flv live_flv asf ogg
  wav mp3 flac aac ac3 eac3 dts truehd
  hls dash concat image2 srt ass webvtt mpegvideo h264 hevc
)

# Vídeo: VideoToolbox faz H.264/HEVC/ProRes por hardware; estes são o resto
# e o caminho de software para quando o hardware recusar o stream.
DECODERS_VIDEO=( h264 hevc mpeg2video mpeg4 msmpeg4v3 vp8 vp9 av1 theora
                 prores dvvideo huffyuv ffv1 rawvideo wmv3 vc1 )

# Áudio: é aqui que o VLC ganha do player nativo. DTS e TrueHD são o motivo
# de tanto arquivo "sem som" no iOS.
DECODERS_AUDIO=( aac aac_latm ac3 eac3 dca truehd mlp mp1 mp2 mp3 flac alac
                 vorbis opus wmav1 wmav2 pcm_s16le pcm_s16be pcm_s24le
                 pcm_u8 pcm_f32le pcm_alaw pcm_mulaw )

DECODERS_SUB=( subrip ass ssa movtext webvtt dvdsub dvbsub pgssub xsub )

# Sem os parsers certos, o demuxer entrega pacotes que o decodificador recusa.
PARSERS=( h264 hevc aac aac_latm ac3 dca mpegaudio mpeg4video mpegvideo
          vp8 vp9 av1 opus flac vorbis vc1 )

# file/pipe atendem USB e sandbox; http(s) atende streaming.
# SMB NÃO entra aqui: vai por AVIOContext próprio, com callbacks em Swift.
PROTOCOLS=( file pipe http https tcp tls crypto data )

BSFS=( h264_mp4toannexb hevc_mp4toannexb extract_extradata aac_adtstoasc )

juntar() { local p="$1"; shift; for x in "$@"; do printf -- "--enable-%s=%s " "$p" "$x"; done; }

# ---------------------------------------------------------------------------

echo "==> FFmpeg $FFMPEG_VERSION | iOS mínimo $MIN_IOS"
echo "    SDK: $SDK_PATH"

mkdir -p "$TRABALHO"
cd "$TRABALHO"

FONTE="ffmpeg-$FFMPEG_VERSION"
if [ ! -d "$FONTE" ]; then
  echo "==> baixando fontes…"
  curl -fsSL "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" -o ffmpeg.tar.xz
  tar xf ffmpeg.tar.xz
  rm -f ffmpeg.tar.xz
fi

cd "$FONTE"

if [ ! -f config.h ]; then
  echo "==> configurando…"
  # shellcheck disable=SC2046
  ./configure \
    --prefix="$PREFIXO" \
    --enable-cross-compile \
    --target-os=darwin \
    --arch=arm64 \
    --cc="$CC_BIN" \
    --as="$CC_BIN" \
    --sysroot="$SDK_PATH" \
    --extra-cflags="-arch arm64 -mios-version-min=$MIN_IOS -fembed-bitcode-marker -fno-stack-check" \
    --extra-ldflags="-arch arm64 -mios-version-min=$MIN_IOS" \
    --enable-static --disable-shared \
    --enable-pic \
    --disable-programs --disable-doc --disable-debug \
    --disable-everything \
    --disable-gpl --disable-nonfree \
    --disable-avdevice --disable-postproc \
    --enable-avformat --enable-avcodec --enable-avutil \
    --enable-swresample --enable-swscale --enable-avfilter \
    --enable-videotoolbox \
    --enable-hwaccel=h264_videotoolbox \
    --enable-hwaccel=hevc_videotoolbox \
    --enable-hwaccel=vp9_videotoolbox \
    --enable-hwaccel=av1_videotoolbox \
    --enable-securetransport \
    --enable-filter=aresample --enable-filter=aformat \
    --enable-filter=scale --enable-filter=format \
    --enable-protocol=file \
    $(juntar demuxer "${DEMUXERS[@]}") \
    $(juntar decoder "${DECODERS_VIDEO[@]}") \
    $(juntar decoder "${DECODERS_AUDIO[@]}") \
    $(juntar decoder "${DECODERS_SUB[@]}") \
    $(juntar parser  "${PARSERS[@]}") \
    $(juntar protocol "${PROTOCOLS[@]}") \
    $(juntar bsf "${BSFS[@]}")
fi

echo "==> compilando…"
make -j"$(sysctl -n hw.ncpu)"
make install

# ---------------------------------------------------------------------------
# Empacotamento
#
# Juntamos as libs numa só antes do xcframework: seis .a separadas obrigariam
# o alvo a listar todas na ordem certa de link, o que quebra em silêncio.
# ---------------------------------------------------------------------------
echo "==> empacotando xcframework…"
cd "$PREFIXO"
libtool -static -o libffmpeg.a \
  lib/libavformat.a lib/libavcodec.a lib/libavfilter.a \
  lib/libswresample.a lib/libswscale.a lib/libavutil.a

rm -rf "$SAIDA"
mkdir -p "$(dirname "$SAIDA")"
xcodebuild -create-xcframework \
  -library "$PREFIXO/libffmpeg.a" \
  -headers "$PREFIXO/include" \
  -output "$SAIDA"

echo
echo "pronto: $SAIDA"
du -sh "$SAIDA"
