#!/usr/bin/env python3
"""
O ícone do LibertyX Player, gerado por geometria.

Duas fitas de ouro cruzadas em X — elipses vazadas, grossas nas pontas e finas
nos lados —, trançadas nos cruzamentos que ficam à vista, com o play no meio e
um vão recortado em volta dele. Tudo é subtração de formas (shapely), e não
traço da cor do fundo por cima: assim o mesmo desenho serve no ícone
adaptativo, no monocromático do Android 13 e dentro do app, sobre qualquer
fundo.

Escreve em app/src/main/res e app/src/tv/res:
  drawable/ic_launcher_{foreground,background,monochrome}.xml, drawable/ic_marca.xml
  mipmap-*/ic_launcher{,_round}.png (Android 7, que não tem ícone adaptativo)
  tv/drawable-{mdpi,xhdpi}/tv_banner.png
e Scripts/play-512.png, o ícone da Play Store.

Dependências: pip install shapely resvg-py pillow
"""
import io, math, os
import resvg_py
from PIL import Image, ImageDraw, ImageFont
from shapely import affinity
from shapely.geometry import Polygon
from shapely.ops import unary_union

AQUI = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(AQUI, "../app/src/main/res")
TV = os.path.join(AQUI, "../app/src/tv/res")
FONT = os.path.join(RES, "font")

OURO = [(0, "#FFF3CC"), (0.38, "#F5C84E"), (0.75, "#C98A1E"), (1, "#7A4A0E")]
OURO_P = [(0, "#FFF6D6"), (0.45, "#FBC501"), (1, "#B97A06")]   # o amarelo do app no meio
FUNDO = [(0, "#2E2010"), (0.55, "#150E07"), (1, "#070403")]
C = (54, 54)
def elipse(rx, ry, ang, n=360):
    p = Polygon([(54+rx*math.cos(t), 54+ry*math.sin(t)) for t in (2*math.pi*i/n for i in range(n))])
    return affinity.rotate(p, ang, origin=C)

def triangulo(h, r, cx=54, cy=54, n=24):
    w = h*math.sqrt(3)/2; x0 = cx - w/3
    P = [(x0, cy-h/2), (x0+w, cy), (x0, cy+h/2)]
    k = r/math.tan(math.radians(30)); pts = []
    def rumo(a, b, t):
        L = math.dist(a, b); return (a[0]+(b[0]-a[0])*t/L, a[1]+(b[1]-a[1])*t/L)
    for i in range(3):
        p0, p1, p2 = P[i-1], P[i], P[(i+1)%3]
        a, b = rumo(p1, p0, k), rumo(p1, p2, k)
        for j in range(n+1):
            t = j/n
            pts.append(((1-t)**2*a[0]+2*(1-t)*t*p1[0]+t*t*b[0], (1-t)**2*a[1]+2*(1-t)*t*p1[1]+t*t*b[1]))
    return Polygon(pts)

def marca(rx=33, ry=10, irx=27.5, iry=8.7, ang=45, h=27, r=2.4, folga=1.6, trama=0.9):
    A = elipse(rx, ry, -ang).difference(elipse(irx, iry, -ang))   # sobe para a direita
    B = elipse(rx, ry, ang).difference(elipse(irx, iry, ang))
    tri = triangulo(h, r)
    # trama: nos cruzamentos que ficam à vista, uma fita passa por cima da outra
    if trama:
        pts = [g.centroid for g in (A.intersection(B)).geoms] if A.intersection(B).geom_type == 'MultiPolygon' else [A.intersection(B).centroid]
        for i, p in enumerate(sorted(pts, key=lambda p: math.atan2(p.y-54, p.x-54))):
            zona = p.buffer(7)
            if i % 2 == 0: B = B.difference(A.intersection(zona).buffer(trama))
            else:          A = A.difference(B.intersection(zona).buffer(trama))
    fitas = unary_union([A, B]).difference(tri.buffer(folga, join_style='round'))
    return fitas, tri

def para_path(g, nd=2):
    polys = getattr(g, 'geoms', [g])
    d = []
    for p in polys:
        for anel in [p.exterior, *p.interiors]:
            c = list(anel.simplify(0.01).coords)[:-1]
            d.append("M" + " ".join(f"{x:.{nd}f},{y:.{nd}f}" for x, y in c) + "Z")
    return "".join(d)

def stops(L): return "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in L)

def png(svg, px, h=None):
    b = resvg_py.svg_to_bytes(svg_string=svg, width=px, height=h or px)
    return Image.open(io.BytesIO(bytes(b))).convert("RGBA")

PARAM = dict(trama=1.1, folga=2.0, h=25, ry=11.5, iry=10, rx=34, irx=28.5)
ESC = 0.9   # o símbolo dentro do círculo seguro de 66 de 108
fitas, tri = marca(**PARAM)
fitas = affinity.scale(fitas, ESC, ESC, origin=(54, 54)); tri = affinity.scale(tri, ESC, ESC, origin=(54, 54))
PF, PT = para_path(fitas), para_path(tri)
# gradientes nas mesmas coordenadas, escalados junto
def e(v): return 54 + (v-54)*ESC
G_OURO = (e(26), e(26), e(82), e(82)); G_P = (e(46), e(40), e(64), e(68))

def vd_grad(tipo, attrs, lst):
    itens = "\n".join(f'                    <item android:offset="{o}" android:color="#FF{c[1:]}" />' for o, c in lst)
    return (f'            <aapt:attr name="android:fillColor">\n'
            f'                <gradient android:type="{tipo}" {attrs}>\n{itens}\n                </gradient>\n'
            f'            </aapt:attr>')

CAB = '<?xml version="1.0" encoding="utf-8"?>\n'
def vector(corpo, w=108, vw=108, vh=108, h=None, tx=0, ty=0, comentario=""):
    h = h or w
    g0, g1 = (f'    <group android:translateX="{tx}" android:translateY="{ty}">\n', '    </group>\n') if (tx or ty) else ("", "")
    return (CAB + comentario +
        f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:aapt="http://schemas.android.com/aapt"\n'
        f'    android:width="{w}dp" android:height="{h}dp"\n    android:viewportWidth="{vw}" android:viewportHeight="{vh}">\n'
        + g0 + corpo + g1 + '</vector>\n')

def corpo_marca(mono=False):
    if mono:
        return (f'    <path android:fillType="evenOdd" android:fillColor="#FFFFFFFF"\n        android:pathData="{PF}" />\n'
                f'    <path android:fillColor="#FFFFFFFF"\n        android:pathData="{PT}" />\n')
    x1, y1, x2, y2 = G_OURO; a1, b1, a2, b2 = G_P
    return (f'    <path android:fillType="evenOdd"\n        android:pathData="{PF}">\n'
            + vd_grad("linear", f'android:startX="{x1:.2f}" android:startY="{y1:.2f}" android:endX="{x2:.2f}" android:endY="{y2:.2f}"', OURO) + '\n    </path>\n'
            f'    <path\n        android:pathData="{PT}">\n'
            + vd_grad("linear", f'android:startX="{a1:.2f}" android:startY="{b1:.2f}" android:endX="{a2:.2f}" android:endY="{b2:.2f}"', OURO_P) + '\n    </path>\n')

COM_F = ('<!-- O símbolo do LibertyX: duas fitas de ouro cruzadas em X, trançadas nos\n'
         '     cruzamentos à vista, e o play recortado no meio. Gerado por geometria\n'
         '     (Android/Scripts/icone.py), não desenhado à mão: o recorte em volta do\n'
         '     play é um buraco de verdade, e não um traço da cor do fundo, então o\n'
         '     mesmo desenho serve sobre qualquer fundo e no ícone monocromático. -->\n')
open(f"{RES}/drawable/ic_launcher_foreground.xml", "w").write(vector(corpo_marca(), comentario=COM_F))
open(f"{RES}/drawable/ic_launcher_monochrome.xml", "w").write(vector(corpo_marca(True),
    comentario='<!-- O mesmo símbolo numa cor só, para o ícone temático do Android 13+. -->\n'))
# a marca recortada, para usar dentro do app sem a margem de corte do launcher
x0, y0, x1, y1 = fitas.union(tri).bounds; lado = max(x1-x0, y1-y0) + 1
open(f"{RES}/drawable/ic_marca.xml", "w").write(vector(corpo_marca(), w=48, vw=round(lado,2), vh=round(lado,2),
    tx=round(-(54-lado/2),2), ty=round(-(54-lado/2),2),
    comentario='<!-- O símbolo sem a margem do ícone adaptativo, para a marca dentro do app. -->\n'))
fundo = ('    <path android:pathData="M0,0h108v108h-108z">\n'
         + vd_grad("radial", 'android:centerX="54" android:centerY="34" android:gradientRadius="82"', FUNDO) + '\n    </path>\n')
open(f"{RES}/drawable/ic_launcher_background.xml", "w").write(vector(fundo,
    comentario='<!-- Preto quente com uma luz vinda de cima: o ouro parece iluminado, não\n     colado sobre um preto chapado. -->\n'))

# SVG equivalente, para os PNG (ícone antigo, Play Store, faixa da TV)
def svg(size, fundo=True, forma=None):
    x1, y1, x2, y2 = G_OURO; a1, b1, a2, b2 = G_P
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="{size}" height="{size}">
<defs><radialGradient id="f" cx="54" cy="34" r="82" gradientUnits="userSpaceOnUse">{stops(FUNDO)}</radialGradient>
<linearGradient id="o" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" gradientUnits="userSpaceOnUse">{stops(OURO)}</linearGradient>
<linearGradient id="p" x1="{a1}" y1="{b1}" x2="{a2}" y2="{b2}" gradientUnits="userSpaceOnUse">{stops(OURO_P)}</linearGradient></defs>
{'<rect width="108" height="108" fill="url(#f)"/>' if fundo else ''}
<path fill-rule="evenodd" fill="url(#o)" d="{PF}"/><path fill="url(#p)" d="{PT}"/></svg>'''


def recorte(img, forma, margem):
    px = img.size[0]; S = 4; m = Image.new("L", (px*S, px*S), 0); d = ImageDraw.Draw(m)
    i = margem*px*S; box = [i, i, px*S-i, px*S-i]
    if forma == "circulo": d.ellipse(box, fill=255)
    else: d.rounded_rectangle(box, radius=(px*S-2*i)*0.22, fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0)); out.paste(img, (0, 0), m.resize((px, px), Image.LANCZOS)); return out

# ícone antigo (Android 7): a peça inteira de 108 corta para 72 de miolo, como o adaptativo mostraria
for dens, px in dict(mdpi=48, hdpi=72, xhdpi=96, xxhdpi=144, xxxhdpi=192).items():
    grande = png(svg(1), px*108//72*4)
    g = grande.size[0]; c = int(g*18/108); miolo = grande.crop((c, c, g-c, g-c)).resize((px*4, px*4), Image.LANCZOS)
    for nome, forma in (("ic_launcher", "quadrado"), ("ic_launcher_round", "circulo")):
        recorte(miolo, forma, 0.02).resize((px, px), Image.LANCZOS).save(f"{RES}/mipmap-{dens}/{nome}.png", optimize=True)
    try: os.remove(f"{RES}/drawable-{dens}/ic_launcher_foreground.png")
    except FileNotFoundError: pass
# Play Store: 512, quadrado cheio (a loja arredonda sozinha)
g = png(svg(1), 512*108//72); c = int(g.size[0]*18/108)
g.crop((c, c, g.size[0]-c, g.size[0]-c)).resize((512, 512), Image.LANCZOS).convert("RGB").save(os.path.join(AQUI, "play-512.png"))

def render(svg, w, h):
    return Image.open(io.BytesIO(bytes(resvg_py.svg_to_bytes(svg_string=svg, width=w, height=h)))).convert("RGBA")

def banner(W, H):
    """A faixa da TV: o símbolo e o nome, o conjunto centrado na faixa."""
    S = 2; w, h = W*S, H*S
    fundo = render(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 180" width="{w}" height="{h}"><defs>'
                   f'<radialGradient id="f" cx="110" cy="40" r="260" gradientUnits="userSpaceOnUse">{stops(FUNDO)}</radialGradient></defs>'
                   f'<rect width="320" height="180" fill="url(#f)"/></svg>', w, h)
    m = render(svg(108, fundo=False), h*2, h*2)
    m = m.crop(m.getbbox())
    alto = int(h*0.56); m = m.resize((int(m.width*alto/m.height), alto), Image.LANCZOS)
    d = ImageDraw.Draw(fundo)
    f1 = ImageFont.truetype(os.path.join(FONT, "manrope_extrabold.ttf"), int(h*0.2))
    f2 = ImageFont.truetype(os.path.join(FONT, "manrope_semibold.ttf"), int(h*0.062))
    larg_nome = d.textlength("LibertyX", font=f1)
    vao = int(h*0.09)
    x = int((w - (m.width + vao + larg_nome)) // 2)
    fundo.alpha_composite(m, (x, (h - alto)//2))
    x += m.width + vao; base = int(h*0.54)
    d.text((x, base), "Liberty", font=f1, fill=(240, 240, 240), anchor="ls")
    x2 = x + d.textlength("Liberty", font=f1)
    d.text((x2, base), "X", font=f1, fill=(0xFB, 0xC5, 0x01), anchor="ls")
    # PLAYER espaçado na largura exata do nome
    letras = "PLAYER"; larg = sum(d.textlength(c, font=f2) for c in letras)
    esp = (larg_nome - larg - 4) / (len(letras)-1); cx = x + 3
    for c in letras:
        d.text((cx, base + int(h*0.13)), c, font=f2, fill=(235, 235, 245, 150), anchor="ls")
        cx += d.textlength(c, font=f2) + esp
    return fundo.resize((W, H), Image.LANCZOS).convert("RGB")

for pasta, (W, H) in {"drawable-mdpi": (320, 180), "drawable-xhdpi": (640, 360)}.items():
    banner(W, H).save(os.path.join(TV, pasta, "tv_banner.png"), optimize=True)
print("ok")
