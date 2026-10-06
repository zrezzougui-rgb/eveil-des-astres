"""Détoure les portraits (assets/portraits) en sprites transparents (assets/sprites) avec rembg."""
from pathlib import Path
from PIL import Image
from rembg import remove, new_session

root = Path(__file__).resolve().parent.parent
src, dst = root / "assets/portraits", root / "assets/sprites"
dst.mkdir(exist_ok=True)
session = new_session("isnet-anime")
H = 360  # hauteur finale des sprites
# brasa, kael et iris (formes de base) ont été détourés avec l'outil Canva : on ne les écrase pas.
SKIP = {"brasa", "kael", "iris"}
for f in sorted(src.glob("*.jpg")):
    if f.stem in SKIP:
        continue
    im = Image.open(f).convert("RGB")
    if im.width < 600:  # petites images : agrandir avant détourage pour un masque plus net
        im = im.resize((im.width * 3, im.height * 3), Image.LANCZOS)
    cut = remove(im, session=session, post_process_mask=True)
    a = cut.getchannel("A").point(lambda v: 255 if v > 140 else (0 if v < 40 else v))
    cut.putalpha(a)
    bbox = a.point(lambda v: 255 if v > 60 else 0).getbbox()
    cut = cut.crop(bbox)
    w = round(cut.width * H / cut.height)
    cut = cut.resize((w, H), Image.LANCZOS)
    cut.save(dst / (f.stem + ".webp"), "WEBP", quality=86, method=6)
    print(f.stem, cut.size)
