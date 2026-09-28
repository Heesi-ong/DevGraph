from pathlib import Path
import sys

from PIL import Image, ImageDraw, ImageFont


root = Path(sys.argv[1])
for directory in sorted(path for path in root.iterdir() if path.is_dir()):
    pages = sorted(directory.glob("page-*.png"), key=lambda path: int(path.stem.split("-")[-1]))
    if not pages:
        continue
    thumbs = []
    for page in pages:
        image = Image.open(page).convert("RGB")
        target_width = 650
        target_height = round(image.height * target_width / image.width)
        thumbs.append(image.resize((target_width, target_height), Image.Resampling.LANCZOS))
    columns = 2
    label_height = 34
    gap = 24
    rows = (len(thumbs) + columns - 1) // columns
    cell_height = max(image.height for image in thumbs) + label_height
    sheet = Image.new("RGB", (columns * 650 + (columns + 1) * gap, rows * cell_height + (rows + 1) * gap), "#D7DCE2")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default(size=18)
    for index, image in enumerate(thumbs):
        row, column = divmod(index, columns)
        x = gap + column * (650 + gap)
        y = gap + row * cell_height
        draw.text((x, y), f"Page {index + 1}", fill="#20252B", font=font)
        sheet.paste(image, (x, y + label_height))
    sheet.save(root / f"{directory.name}-contact.png", quality=92)
    print(root / f"{directory.name}-contact.png")
