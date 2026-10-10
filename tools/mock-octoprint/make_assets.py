"""Test imagery for the mock OctoPrint: slicer-style thumbnails and a webcam loop."""
import math, os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(__file__), "assets")
os.makedirs(OUT, exist_ok=True)
SS = 4          # supersampling
SIZE = 300


def shade(rgb, k):
    return tuple(max(0, min(255, int(c * k))) for c in rgb) + (255,)


class Iso:
    """Tiny isometric painter: boxes drawn back to front."""

    def __init__(self, scale, origin):
        self.img = Image.new("RGBA", (SIZE * SS, SIZE * SS), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.img)
        self.s = scale * SS
        self.ox, self.oy = origin[0] * SS, origin[1] * SS

    def pt(self, x, y, z):
        return (self.ox + (x - y) * self.s * 0.866, self.oy + (x + y) * self.s * 0.5 - z * self.s)

    def box(self, x, y, z, w, d, h, rgb):
        p = self.pt
        top = [p(x, y, z + h), p(x + w, y, z + h), p(x + w, y + d, z + h), p(x, y + d, z + h)]
        left = [p(x, y + d, z + h), p(x + w, y + d, z + h), p(x + w, y + d, z), p(x, y + d, z)]
        right = [p(x + w, y, z + h), p(x + w, y + d, z + h), p(x + w, y + d, z), p(x + w, y, z)]
        self.d.polygon(left, fill=shade(rgb, 0.72))
        self.d.polygon(right, fill=shade(rgb, 0.52))
        self.d.polygon(top, fill=shade(rgb, 1.0))

    def done(self, name):
        self.img.resize((SIZE, SIZE), Image.LANCZOS).save(os.path.join(OUT, name))


def chessboard():
    rgb = (236, 231, 219)
    i = Iso(1.55, (150, 82))
    i.box(0, 0, 0, 100, 100, 6, rgb)
    n = 4
    cell = 100 / n
    for a in range(n):
        for b in range(n):
            if (a + b) % 2 == 0:
                i.box(a * cell + 1.2, b * cell + 1.2, 6, cell - 2.4, cell - 2.4, 3.2, rgb)
    i.done("chessboard.png")


def bracket():
    rgb = (255, 138, 61)
    i = Iso(2.0, (150, 118))
    i.box(0, 0, 0, 60, 44, 8, rgb)
    i.box(0, 0, 8, 10, 44, 46, rgb)
    i.box(10, 17, 8, 34, 10, 22, rgb)
    i.done("bracket.png")


def stand():
    rgb = (99, 217, 166)
    i = Iso(1.9, (150, 96))
    i.box(0, 0, 0, 70, 62, 6, rgb)
    i.box(0, 0, 6, 70, 10, 58, rgb)
    i.box(0, 50, 6, 70, 12, 14, rgb)
    i.done("stand.png")


def vase():
    rgb = (108, 184, 255)
    img = Image.new("RGBA", (SIZE * SS, SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx = SIZE * SS / 2
    base, top = 262 * SS, 44 * SS
    layers = 150
    for k in range(layers + 1):
        t = k / layers
        y = base + (top - base) * t
        r = (46 + 30 * math.sin(t * math.pi * 1.15 + 0.35) - 16 * t) * SS
        twist = math.sin(t * 9) * 0.06
        ry = r * 0.3
        # body ring, darker to the right
        for band in range(24):
            f = band / 23
            x0 = cx - r + 2 * r * f
            k_light = 1.0 - 0.5 * abs(f - 0.3 + twist) ** 1.2
            d.rectangle([x0, y - ry * 0.25, x0 + 2 * r / 23 + 1, y + ry * 0.25 + SS], fill=shade(rgb, k_light * (0.94 + 0.06 * (k % 2))))
        if k == layers:
            d.ellipse([cx - r, y - ry, cx + r, y + ry], fill=shade(rgb, 0.42))
            d.ellipse([cx - r * 0.9, y - ry * 0.86, cx + r * 0.9, y + ry * 0.86], fill=shade(rgb, 0.3))
    # cut the silhouette sides round
    mask = Image.new("L", img.size, 0)
    md = ImageDraw.Draw(mask)
    pts_l, pts_r = [], []
    for k in range(layers + 1):
        t = k / layers
        y = base + (top - base) * t
        r = (46 + 30 * math.sin(t * math.pi * 1.15 + 0.35) - 16 * t) * SS
        pts_l.append((cx - r, y))
        pts_r.append((cx + r, y))
    md.polygon(pts_l + pts_r[::-1], fill=255)
    r0 = (46 + 30 * math.sin(0.35)) * SS
    md.ellipse([cx - r0, base - r0 * 0.3, cx + r0, base + r0 * 0.3], fill=255)
    rt = (46 + 30 * math.sin(math.pi * 1.15 + 0.35) - 16) * SS
    md.ellipse([cx - rt, top - rt * 0.3, cx + rt, top + rt * 0.3], fill=255)
    img.putalpha(Image.composite(img.getchannel("A"), Image.new("L", img.size, 0), mask))
    img.resize((SIZE, SIZE), Image.LANCZOS).save(os.path.join(OUT, "vase.png"))


def clips():
    rgb = (183, 156, 255)
    i = Iso(1.7, (150, 104))
    for a in range(3):
        for b in range(2):
            x, y = a * 30, b * 42
            i.box(x, y, 0, 22, 34, 5, rgb)
            i.box(x, y, 5, 22, 6, 12, rgb)
            i.box(x, y + 28, 5, 22, 6, 12, rgb)
    i.done("clips.png")


def webcam():
    """A dim room, an Ender-style frame, a part on the bed, the head sweeping."""
    W, H = 1280, 720
    rng = np.random.default_rng(4)
    frames = 16
    for n in range(frames):
        img = Image.new("RGB", (W, H), (14, 15, 18))
        d = ImageDraw.Draw(img, "RGBA")
        # wall and desk
        for y in range(H):
            t = y / H
            d.line([(0, y), (W, y)], fill=(int(26 + 26 * (1 - t)), int(28 + 26 * (1 - t)), int(34 + 30 * (1 - t)), 255))
        d.polygon([(0, 560), (W, 520), (W, H), (0, H)], fill=(38, 31, 26, 255))
        # LED wash from the top bar
        glow = Image.new("RGB", (W, H), (0, 0, 0))
        gd = ImageDraw.Draw(glow)
        gd.ellipse([240, -40, 1040, 520], fill=(120, 112, 98))
        glow = glow.filter(ImageFilter.GaussianBlur(120))
        img = Image.blend(img, Image.fromarray(np.clip(np.asarray(img, dtype=np.int16) + np.asarray(glow, dtype=np.int16), 0, 255).astype(np.uint8)), 1.0)
        d = ImageDraw.Draw(img, "RGBA")
        # uprights and top bar
        alu = (52, 54, 58, 255)
        d.rectangle([300, 60, 336, 600], fill=alu)
        d.rectangle([944, 60, 980, 600], fill=alu)
        d.rectangle([300, 60, 980, 92], fill=(60, 62, 66, 255))
        d.rectangle([330, 86, 950, 94], fill=(255, 244, 220, 255))            # LED strip
        # bed
        bed = [(250, 520), (1030, 520), (1120, 610), (160, 610)]
        d.polygon(bed, fill=(30, 31, 33, 255))
        d.polygon([(160, 610), (1120, 610), (1120, 628), (160, 628)], fill=(20, 20, 22, 255))
        for k in range(1, 12):
            x_top = 250 + (780 * k / 12)
            x_bot = 160 + (960 * k / 12)
            d.line([(x_top, 520), (x_bot, 610)], fill=(44, 45, 48, 255), width=1)
        # the part: a pale slab partway up
        d.polygon([(470, 540), (810, 540), (850, 588), (430, 588)], fill=(226, 221, 208, 255))
        d.polygon([(430, 588), (850, 588), (850, 600), (430, 600)], fill=(168, 163, 152, 255))
        # gantry beam and head
        beam_y = 450
        d.rectangle([300, beam_y, 980, beam_y + 26], fill=(58, 60, 64, 255))
        hx = 420 + 400 * (0.5 - 0.5 * math.cos(2 * math.pi * n / frames))
        d.rectangle([hx - 44, beam_y - 44, hx + 44, beam_y + 62], fill=(36, 37, 40, 255))
        d.rectangle([hx - 30, beam_y - 30, hx + 30, beam_y + 8], fill=(24, 25, 27, 255))
        d.polygon([(hx - 10, beam_y + 62), (hx + 10, beam_y + 62), (hx + 3, beam_y + 86), (hx - 3, beam_y + 86)], fill=(190, 150, 70, 255))
        d.ellipse([hx - 14, beam_y + 80, hx + 14, beam_y + 98], fill=(255, 150, 60, 60))
        # spool, top right
        d.ellipse([1040, 40, 1220, 220], fill=(40, 41, 44, 255))
        d.ellipse([1070, 70, 1190, 190], fill=(226, 221, 208, 255))
        d.ellipse([1112, 112, 1148, 148], fill=(30, 31, 33, 255))
        arr = np.asarray(img, dtype=np.int16)
        arr = arr + rng.normal(0, 3.2, arr.shape)
        # vignette
        yy, xx = np.mgrid[0:H, 0:W]
        v = 1 - 0.38 * (((xx - W / 2) / (W / 2)) ** 2 + ((yy - H / 2) / (H / 2)) ** 2)
        arr = arr * v[..., None]
        Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)).save(os.path.join(OUT, "cam%02d.jpg" % n), quality=78)


chessboard(); bracket(); stand(); vase(); clips(); webcam()
print(sorted(os.listdir(OUT)))
