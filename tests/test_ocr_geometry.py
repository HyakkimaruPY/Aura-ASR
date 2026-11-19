"""Geometric projection tests mirroring SubtitleCropper.kt rules on 9:16 synthetic frames
and on the bundled reference asset (sample_frame.jpg), without neural inference or disk clutter."""
import unittest
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from helpers import ASSETS_DIR


def _load_cjk_font():
    preferred = Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc")
    if preferred.exists():
        return ImageFont.truetype(str(preferred), 28)
    matches = list(Path("/usr/share/fonts").rglob("*Sans*CJK*"))
    return ImageFont.truetype(str(matches[0]), 28) if matches else ImageFont.load_default()


def _group_runs(indices: list[int], max_gap: int) -> list[list[int]]:
    runs: list[list[int]] = []
    for idx in indices:
        if not runs or idx - runs[-1][-1] > max_gap:
            runs.append([idx])
        else:
            runs[-1].append(idx)
    return runs


def _project_roi(image: Image.Image):
    w, h = image.size
    left, right, top, bottom = int(w * 0.08), int(w * 0.83), int(h * 0.58), int(h * 0.935)
    roi = image.crop((left, top, right, bottom)).convert("RGB")
    rw, rh = roi.size
    px = roi.load()
    bright = [[False] * rw for _ in range(rh)]
    dark = [[False] * rw for _ in range(rh)]
    for y in range(rh):
        for x in range(rw):
            r, g, b = px[x, y]
            bright[y][x] = min(r, g, b) > 215 and max(r, g, b) - min(r, g, b) < 38
            dark[y][x] = (r + g + b) < 405
    ink = [[False] * rw for _ in range(rh)]
    row_score = [0] * rh
    for y in range(rh):
        for x in range(9, rw - 9):
            if dark[y][x] and (bright[y][x - 8] or bright[y][x - 4] or bright[y][x + 4] or bright[y][x + 8]):
                ink[y][x] = True
                row_score[y] += 1
    bands = [
        run
        for run in _group_runs([y for y, score in enumerate(row_score) if score >= max(9, rw // 55)], 5)
        if run[-1] - run[0] + 1 >= max(8, h // 85)
    ]
    return left, top, rw, rh, ink, row_score, bands


class SubtitleCropperGeometryTests(unittest.TestCase):
    def test_synthetic_9_by_16_detects_one_two_and_three_caption_bands(self):
        font = _load_cjk_font()
        for expected_lines in (1, 2, 3):
            canvas = Image.new("RGB", (768, 1360), (70, 70, 70))
            draw = ImageDraw.Draw(canvas)
            for idx in range(expected_lines):
                y = 805 + idx * 74
                draw.rounded_rectangle((105, y - 10, 620, y + 51), radius=13, fill=(255, 255, 255))
                draw.text((135, y + 2), "你好 这么多东西当苦力", font=font, fill=(5, 5, 5))
            _, _, _, _, _, _, bands = _project_roi(canvas)
            self.assertEqual(len(bands), expected_lines)

    def test_reference_sample_frame_projects_expected_chinese_caption_box(self):
        sample = ASSETS_DIR / "sample_frame.jpg"
        self.assertTrue(sample.is_file())
        with Image.open(sample) as raw:
            left, top, rw, rh, ink, row_score, bands = _project_roi(raw)

        ranked = sorted(
            sorted(bands, key=lambda z: (z[-1] - z[0] + 1) * sum(row_score[z[0] : z[-1] + 1]), reverse=True)[:3],
            key=lambda z: z[0],
        )
        boxes = []
        for band in ranked:
            ys, ye = max(0, band[0] - 7), min(rh, band[-1] + 8)
            col_scores = [sum(1 for y in range(ys, ye) if ink[y][x]) for x in range(rw)]
            cols = [g for g in _group_runs([x for x, v in enumerate(col_scores) if v >= 3], 22) if g[-1] - g[0] > max(55, rw // 7)]
            if not cols:
                continue
            best = max(cols, key=len)
            xs, xe = max(0, best[0] - 13), min(rw, best[-1] + 14)
            boxes.append((xs + left, ys + top, xe + left, ye + top))

        self.assertTrue(boxes, "Nenhuma faixa de legenda detectada em sample_frame.jpg")
        self.assertTrue(any(box[1] < 1590 < box[3] for box in boxes))


if __name__ == "__main__":
    unittest.main()
