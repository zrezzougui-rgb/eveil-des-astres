"""Construit www/index.html (appli Android) à partir de src/game.html (source du jeu)."""
from pathlib import Path

root = Path(__file__).resolve().parent.parent
body = (root / "src" / "game.html").read_text(encoding="utf-8")
head = """<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover, user-scalable=no">
<meta name="theme-color" content="#121731">
<style>:root{padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}[hidden]{display:none!important}img{max-width:100%}body{margin:0}</style>
</head>
<body>
"""
(root / "www" / "index.html").write_text(head + body + "\n</body>\n</html>\n", encoding="utf-8")
print("www/index.html reconstruit")
