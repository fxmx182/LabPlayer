//  Cabeçalho-ponte: expõe a API C do FFmpeg para o Swift.
//
//  O FFmpeg é C puro, sem módulo Clang. Sem esta ponte o Swift não enxerga
//  nada da biblioteca. Os caminhos resolvem porque HEADER_SEARCH_PATHS aponta
//  recursivamente para dentro do FFmpeg.xcframework (ver project.yml).

#ifndef FFmpegBridge_h
#define FFmpegBridge_h

#include <libavformat/avformat.h>
#include <libavformat/avio.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libavutil/time.h>
#include <libavutil/channel_layout.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>

#endif /* FFmpegBridge_h */
