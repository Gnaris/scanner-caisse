"""Génère build/icon.ico depuis le même design que l'icône tray (carré arrondi
violet + S blanc). Multi-résolutions pour que Windows affiche nickel dans :
  - l'explorateur de fichiers (16, 32, 48, 256)
  - la barre des tâches (32, 48)
  - l'écran UAC (256)
  - l'écran d'install Inno Setup (16, 32)
"""

from pathlib import Path
from PIL import Image, ImageDraw


def make_image(size: int) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Carré arrondi violet (couleur primaire de l'app : 0xFF4F46E5)
    margin = max(1, size // 16)
    radius = max(2, size // 6)
    d.rounded_rectangle(
        [(margin, margin), (size - margin, size - margin)],
        radius=radius,
        fill=(79, 70, 229, 255),
    )
    # S blanc stylisé (mêmes proportions que l'icône tray)
    # Normalisé sur un canvas 64×64 puis scalé.
    scale = size / 64.0
    def r(x1, y1, x2, y2):
        d.rectangle(
            [(x1 * scale, y1 * scale), (x2 * scale, y2 * scale)],
            fill="white",
        )
    r(18, 14, 46, 20)
    r(18, 20, 24, 32)
    r(18, 28, 46, 34)
    r(40, 34, 46, 46)
    r(18, 42, 46, 48)
    return img


def main() -> None:
    out = Path(__file__).parent / "build" / "icon.ico"
    out.parent.mkdir(parents=True, exist_ok=True)
    sizes = [16, 24, 32, 48, 64, 128, 256]
    # Il faut partir d'une image grande (256) et laisser Pillow en downscaler
    # une variante par taille — plus net que d'upscaler depuis 16×16.
    base = make_image(256)
    base.save(
        out,
        format="ICO",
        sizes=[(s, s) for s in sizes],
    )
    print(f"Écrit {out} ({', '.join(str(s) for s in sizes)})")


if __name__ == "__main__":
    main()
