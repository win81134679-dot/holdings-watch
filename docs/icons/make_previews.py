"""Render the three icon candidates as phone (squircle) and watch (circle) previews on one sheet."""
import pathlib, sys
import fitz
from PIL import Image, ImageDraw, ImageFont

HERE = pathlib.Path(__file__).parent
OUT = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else HERE / "preview.png"

RED, GREEN, INK = "#E5484D", "#30A46C", "#14161F"

CANDIDATES = {
    "A 錶框K線": f"""
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">
  <rect width="108" height="108" fill="{INK}"/>
  <circle cx="54" cy="54" r="30" fill="none" stroke="#FFFFFF" stroke-opacity="0.18" stroke-width="5"/>
  <path d="M 54 24 A 30 30 0 0 1 82 64" fill="none" stroke="{RED}" stroke-width="5" stroke-linecap="round"/>
  <line x1="44" y1="44" x2="44" y2="72" stroke="{GREEN}" stroke-width="2.5" stroke-linecap="round"/>
  <rect x="39.5" y="52" width="9" height="14" rx="1.5" fill="{GREEN}"/>
  <line x1="62" y1="36" x2="62" y2="66" stroke="{RED}" stroke-width="2.5" stroke-linecap="round"/>
  <rect x="57.5" y="40" width="9" height="18" rx="1.5" fill="{RED}"/>
</svg>""",
    "B 上升走勢": f"""
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">
  <rect width="108" height="108" fill="{RED}"/>
  <polyline points="28,70 44,56 54,63 76,38" fill="none" stroke="#FFFFFF" stroke-width="6"
            stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M 66 38 L 76 38 L 76 48" fill="none" stroke="#FFFFFF" stroke-width="6"
        stroke-linecap="round" stroke-linejoin="round"/>
  <line x1="28" y1="80" x2="80" y2="80" stroke="#FFFFFF" stroke-opacity="0.45" stroke-width="3" stroke-linecap="round"/>
</svg>""",
    "C 環形持股": f"""
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">
  <rect width="108" height="108" fill="#F6F4EF"/>
  <circle cx="54" cy="54" r="26" fill="none" stroke="{INK}" stroke-opacity="0.12" stroke-width="12"/>
  <path d="M 54 28 A 26 26 0 1 1 30.6 65.4" fill="none" stroke="{RED}" stroke-width="12"/>
  <path d="M 30.6 65.4 A 26 26 0 0 1 54 28" fill="none" stroke="{GREEN}" stroke-width="12"/>
  <circle cx="54" cy="54" r="9" fill="{INK}"/>
</svg>""",
}

SIZE = 432


def render(svg: str, px: int) -> Image.Image:
    doc = fitz.open(stream=svg.encode(), filetype="svg")
    zoom = px / doc[0].rect.width
    pix = doc[0].get_pixmap(matrix=fitz.Matrix(zoom, zoom), alpha=False)
    return Image.frombytes("RGB", (pix.width, pix.height), pix.samples)


def masked(img: Image.Image, shape: str) -> Image.Image:
    w, h = img.size
    mask = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(mask)
    if shape == "circle":
        d.ellipse((0, 0, w - 1, h - 1), fill=255)
    else:
        d.rounded_rectangle((0, 0, w - 1, h - 1), radius=int(w * 0.23), fill=255)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def main() -> None:
    font = ImageFont.truetype(r"C:\Windows\Fonts\msjhbd.ttc", 34)
    small = ImageFont.truetype(r"C:\Windows\Fonts\msjh.ttc", 22)
    pad, col_w = 40, SIZE * 2 + 120
    sheet = Image.new("RGB", (pad * 2 + col_w * len(CANDIDATES), SIZE + 260), "#FFFFFF")
    d = ImageDraw.Draw(sheet)
    for i, (name, svg) in enumerate(CANDIDATES.items()):
        (HERE / f"icon_{name[0]}.svg").write_text(svg.strip() + "\n", encoding="utf-8")
        big = render(svg, SIZE)
        x = pad + i * col_w
        d.text((x, 20), name, fill="#111111", font=font)
        sheet.paste(masked(big, "squircle"), (x, 80), masked(big, "squircle"))
        sheet.paste(masked(big, "circle"), (x + SIZE + 40, 80), masked(big, "circle"))
        d.text((x, SIZE + 95), "手機", fill="#555555", font=small)
        d.text((x + SIZE + 40, SIZE + 95), "手錶", fill="#555555", font=small)
        tiny = masked(render(svg, 96), "squircle")
        sheet.paste(tiny, (x, SIZE + 140), tiny)
        d.text((x + 110, SIZE + 170), "實際大小", fill="#555555", font=small)
    sheet.save(OUT)
    print(OUT)


if __name__ == "__main__":
    main()
