"""Génère l'icône Android et l'écran de démarrage à partir d'un portrait (assets/portraits)."""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

root = Path(__file__).resolve().parent.parent
res = root / "android/app/src/main/res"
hero = sys.argv[1] if len(sys.argv) > 1 else "pip"
src = Image.open(root / f"assets/portraits/{hero}.jpg").convert("RGB")

INK, INK2, GOLD, GOLD_HI = (18, 23, 49), (37, 45, 87), (217, 173, 98), (243, 208, 143)
S = 1024  # travail en haute résolution puis réduction
TIGHT = (222, 150, 802, 730)  # cadrage serré sur le visage pour les petites tailles

def medallion(size, crop=(132, 90, 892, 850)):
    """Portrait rond cerclé d'or, fond transparent."""
    face = src.crop(crop).resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(face, (0, 0), mask)
    d = ImageDraw.Draw(out)
    w = max(4, size // 22)
    d.ellipse((w // 2, w // 2, size - w // 2 - 1, size - w // 2 - 1), outline=GOLD, width=w)
    d.ellipse((w * 1.6, w * 1.6, size - w * 1.6, size - w * 1.6), outline=GOLD_HI + (160,), width=max(1, w // 4))
    return out

def background(size, radius=None):
    bg = Image.new("RGBA", (size, size), INK + (255,))
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((size * .1, size * .05, size * .9, size * .85), fill=INK2 + (255,))
    bg = Image.alpha_composite(bg, glow.filter(ImageFilter.GaussianBlur(size // 8)))
    if radius:
        m = Image.new("L", (size, size), 0)
        ImageDraw.Draw(m).rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
        bg.putalpha(m)
    return bg

def star(d, cx, cy, r, fill):
    pts = []
    import math
    for i in range(8):
        a = math.pi / 4 * i - math.pi / 2
        rr = r if i % 2 == 0 else r * .28
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    d.polygon(pts, fill=fill)

# Icône classique (carré arrondi) et ronde
legacy = background(S, radius=S // 5)
med = medallion(int(S * .84), TIGHT)
legacy.alpha_composite(med, ((S - med.width) // 2, (S - med.height) // 2))
star(ImageDraw.Draw(legacy), S * .84, S * .16, S * .07, GOLD_HI)
roundi = background(S)
m = Image.new("L", (S, S), 0); ImageDraw.Draw(m).ellipse((0, 0, S - 1, S - 1), fill=255)
med2 = medallion(int(S * .9), TIGHT); roundi.alpha_composite(med2, ((S - med2.width) // 2, (S - med2.height) // 2)); roundi.putalpha(m)

# Premier plan de l'icône adaptative : le médaillon tient dans la zone sûre (66/108)
fg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
med3 = medallion(int(S * .64), TIGHT); fg.alpha_composite(med3, ((S - med3.width) // 2, (S - med3.height) // 2))

for dpi, (leg, fgs) in {"mdpi": (48, 108), "hdpi": (72, 162), "xhdpi": (96, 216), "xxhdpi": (144, 324), "xxxhdpi": (192, 432)}.items():
    d = res / f"mipmap-{dpi}"
    legacy.resize((leg, leg), Image.LANCZOS).save(d / "ic_launcher.png")
    roundi.resize((leg, leg), Image.LANCZOS).save(d / "ic_launcher_round.png")
    fg.resize((fgs, fgs), Image.LANCZOS).save(d / "ic_launcher_foreground.png")
(res / "values/ic_launcher_background.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#121731</color>\n</resources>\n')

# Écrans de démarrage : on garde les dimensions existantes
def font(px):
    for f in ["/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf", "/usr/share/fonts/dejavu/DejaVuSerif.ttf"]:
        if Path(f).exists():
            return ImageFont.truetype(f, px)
    return ImageFont.load_default()
for p in res.glob("drawable*/splash.png"):
    w, h = Image.open(p).size
    img = Image.new("RGBA", (w, h), INK + (255,))
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((w * .2, h * .2, w * .8, h * .7), fill=INK2 + (255,))
    img = Image.alpha_composite(img, glow.filter(ImageFilter.GaussianBlur(min(w, h) // 8)))
    side = int(min(w, h) * .42)
    md = medallion(side)
    cy = int(h * .44)
    img.alpha_composite(md, ((w - side) // 2, cy - side // 2))
    d = ImageDraw.Draw(img)
    f = font(max(12, int(min(w, h) * .075)))
    t = "Éveil des Astres"
    tw = d.textlength(t, font=f)
    d.text(((w - tw) / 2, cy + side // 2 + min(w, h) * .05), t, font=f, fill=GOLD_HI)
    img.convert("RGB").save(p)
print("Icônes et écrans de démarrage générés à partir de", hero)
