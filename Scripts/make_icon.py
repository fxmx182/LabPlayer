#!/usr/bin/env python3
"""Gera o ícone do app (1024x1024) sem depender de ferramenta de design.

O iOS já aplica a máscara arredondada, então desenhamos o quadrado cheio.
Motivo do desenho: o triângulo de play sobre a linha do tempo com o marcador
deslocado — que é exatamente o que o app se propõe a fazer melhor.
"""
from PIL import Image, ImageDraw, ImageFilter
import sys

S = 1024
BG_TOP = (24, 26, 34)
BG_BOTTOM = (12, 13, 18)
ACCENT_A = (86, 204, 242)   # ciano
ACCENT_B = (47, 128, 237)   # azul
TRACK = (255, 255, 255, 48)


def vertical_gradient(size, top, bottom):
    img = Image.new("RGB", (1, size), top)
    px = img.load()
    for y in range(size):
        t = y / (size - 1)
        px[0, y] = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
    return img.resize((size, size), Image.BILINEAR)


def diagonal_gradient(size, a, b):
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            px[x, y] = tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))
    return img


def rounded_polygon_mask(size, points, radius):
    """Máscara de polígono com cantos arredondados.

    Em vez de calcular arcos por vértice (que erra fácil em ângulos agudos como
    a ponta do play), borramos a máscara e limiarizamos: o borrão corta os
    cantos de forma uniforme e as arestas retas voltam nítidas no limiar.
    """
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).polygon(points, fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(radius))
    return mask.point(lambda v: 255 if v >= 128 else 0)


def build():
    base = vertical_gradient(S, BG_TOP, BG_BOTTOM).convert("RGBA")

    # Brilho suave no canto superior esquerdo, para o fundo não ficar chapado.
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for i in range(70, 0, -1):
        r = int(S * 0.62 * i / 70)
        gd.ellipse([S * 0.16 - r, S * 0.08 - r, S * 0.16 + r, S * 0.08 + r],
                   fill=(70, 140, 220, 2))
    base = Image.alpha_composite(base, glow)

    # Máscara do triângulo, depois preenchida com gradiente.
    cx, cy, size = S * 0.5, S * 0.44, S * 0.30
    pts = [
        (cx - size * 0.52, cy - size),
        (cx - size * 0.52, cy + size),
        (cx + size * 0.92, cy),
    ]
    mask = rounded_polygon_mask(S, pts, radius=S * 0.030)

    tri = diagonal_gradient(S, ACCENT_A, ACCENT_B).convert("RGBA")
    base.paste(tri, (0, 0), mask)

    # Linha do tempo com o marcador fora do centro: a referência ao scrub.
    d = ImageDraw.Draw(base, "RGBA")
    y = S * 0.79
    x0, x1 = S * 0.20, S * 0.80
    h = S * 0.016
    d.rounded_rectangle([x0, y - h / 2, x1, y + h / 2], radius=h, fill=TRACK)
    knob_x = x0 + (x1 - x0) * 0.38
    d.rounded_rectangle([x0, y - h / 2, knob_x, y + h / 2], radius=h, fill=ACCENT_A + (255,))
    r = S * 0.035
    d.ellipse([knob_x - r, y - r, knob_x + r, y + r], fill=(255, 255, 255, 255))

    return base.convert("RGB")


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "AppIcon.png"
    build().save(out, "PNG")
    print(f"gerado: {out}")
