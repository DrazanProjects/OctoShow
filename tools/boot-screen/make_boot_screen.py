"""
Draws OctoShow's boot screen: the Echo Show's boot animation, and the picture the
app shows while it starts. The animation ends on exactly that picture, at exactly
the same place on the screen, so the boot runs straight into the app.

    python tools/boot-screen/make_boot_screen.py

writes
    android/app/src/main/res/drawable-nodpi/boot_logo.png   (the app's start screen)
    tools/boot-screen/build/bootanimation.zip               (for /system/media)
    tools/boot-screen/build/preview/                        (a few frames to look at)

Needs Pillow. The mark is the app icon's four layers, the last one printed in
ember; the name is set in the app's own Archivo, in its colors.
"""

import io
import math
import zipfile
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
FONT = ROOT / "android/app/src/main/res/font/archivo.ttf"
LOGO_OUT = ROOT / "android/app/src/main/res/drawable-nodpi/boot_logo.png"
BUILD = Path(__file__).resolve().parent / "build"

SCREEN_W, SCREEN_H = 960, 480     # the Echo Show 5's panel, in pixels
FPS = 30
SS = 4                            # drawn this many times larger, then scaled down

# the app's own inks (ui/theme/Theme.kt)
BLACK = (0, 0, 0)
BONE = (0xF3, 0xF1, 0xEC)
EMBER = (0xFF, 0x6B, 0x2C)
EMBER_HI = (0xFF, 0xC0, 0x8A)

# the mark: the app icon's bars (40 x 6 units, 4 apart), at 4 px a unit
BAR_LEN, BAR_H, BAR_GAP = 160, 24, 16
MARK_H = 4 * BAR_H + 3 * BAR_GAP
NAME = "OctoShow"
NAME_SIZE = 66
NAME_GAP = 40                     # between the mark and the name
MARGIN = 24                       # room around the lockup for the nozzle's glow

# intro timing, in frames
LEAD_IN = 6                       # black before the first layer
LAYER_FRAMES = 9                  # laying one bar down
LAYER_STEP = 11                   # from one bar's start to the next
HEAT_FRAMES = 10                  # a fresh bar cooling from ember-hot to its color
NAME_START = LEAD_IN + 4 * LAYER_STEP
NAME_FRAMES = 14
INTRO_FRAMES = NAME_START + NAME_FRAMES + 10
# the loop that plays until Android is ready: a glint runs along the top layer
LOOP_FRAMES = 60
GLINT_FRAMES = 36


def font():
    f = ImageFont.truetype(str(FONT), NAME_SIZE * SS)
    f.set_variation_by_axes([700, 125])   # the app's headline: weight 700, width 125
    return f


NAME_FONT = font()
NAME_BOX = NAME_FONT.getbbox(NAME)        # at SS scale
NAME_W = math.ceil((NAME_BOX[2] - NAME_BOX[0]) / SS)
CAP_H = (NAME_FONT.getbbox("H")[3] - NAME_FONT.getbbox("H")[1]) / SS

LOCKUP_W = BAR_LEN + NAME_GAP + NAME_W
# frame size: even, so it centres on the screen to the pixel, both in the boot
# animation and in the app's window
W = (LOCKUP_W + 2 * MARGIN + 1) // 2 * 2
H = (MARK_H + 2 * MARGIN + 1) // 2 * 2
MARK_X = (W - LOCKUP_W) / 2
MARK_Y = (H - MARK_H) / 2


def bar_y(i):
    """Top of bar i, 0 being the top (ember) bar."""
    return MARK_Y + i * (BAR_H + BAR_GAP)


def ease_in_out(t):
    t = min(max(t, 0.0), 1.0)
    return 4 * t * t * t if t < 0.5 else 1 - (-2 * t + 2) ** 3 / 2


def ease_out(t):
    t = min(max(t, 0.0), 1.0)
    return 1 - (1 - t) ** 3


def s(v):
    return round(v * SS)


def bar_mask(i, a, b):
    """The bar shape from x=a to x=b (frame px), rounded both ends."""
    m = Image.new("L", (W * SS, H * SS), 0)
    lo, hi = min(a, b), max(a, b)
    hi = max(hi, lo + BAR_H)
    y = bar_y(i)
    ImageDraw.Draw(m).rounded_rectangle([s(lo), s(y), s(hi), s(y + BAR_H)], radius=s(BAR_H / 2), fill=255)
    return m


def ramp(fn):
    """A full-frame mask whose value at each column is fn(x in frame px), 0..1."""
    row = bytes(max(0, min(255, round(255 * fn(x / SS)))) for x in range(W * SS))
    return Image.frombytes("L", (W * SS, 1), row).resize((W * SS, H * SS))


def scale(mask, k):
    return mask.point(lambda v: round(v * k)) if k < 1 else mask


def glow_sprite():
    r = s(BAR_H * 1.1)
    g = Image.radial_gradient("L").resize((2 * r, 2 * r))      # 0 in the middle, 255 at the edge
    return g.point(lambda v: round(255 * max(0.0, 1 - v / 255) ** 2)), r


GLOW, GLOW_R = glow_sprite()


def draw_name(canvas, alpha, dx):
    if alpha <= 0:
        return
    layer = Image.new("L", canvas.size, 0)
    x = MARK_X + BAR_LEN + NAME_GAP + dx
    # cap height centred on the mark
    top = MARK_Y + MARK_H / 2 - CAP_H / 2
    hb = NAME_FONT.getbbox("H")
    ImageDraw.Draw(layer).text((s(x) - NAME_BOX[0], s(top) - hb[1]), NAME, font=NAME_FONT, fill=255)
    canvas.paste(BONE, (0, 0), scale(layer, alpha))


def bar_color(i):
    return EMBER if i == 0 else BONE


def frame(f=None, glint=None):
    """
    One frame. f is a frame of the intro; None draws the finished lockup, which is
    also the app's start screen. glint (0..1) runs a highlight along the top bar.
    """
    c = Image.new("RGB", (W * SS, H * SS), BLACK)
    x0, x1 = MARK_X, MARK_X + BAR_LEN
    for k in range(4):                       # k = 0 is the first, bottom layer
        i = 3 - k
        if f is None:
            c.paste(bar_color(i), (0, 0), bar_mask(i, x0, x1))
            continue
        start = LEAD_IN + k * LAYER_STEP
        if f < start:
            continue
        p = ease_in_out((f - start + 1) / LAYER_FRAMES)
        left_to_right = k % 2 == 0           # back and forth, like the nozzle
        a, head = (x0, x0 + p * BAR_LEN) if left_to_right else (x1, x1 - p * BAR_LEN)
        shape = bar_mask(i, a, head)
        c.paste(bar_color(i), (0, 0), shape)
        # fresh plastic is hot: ember-light at the nozzle, cooling behind it
        cool = 1 - ease_out((f - start - LAYER_FRAMES) / HEAT_FRAMES) if f >= start + LAYER_FRAMES else 1.0
        if cool > 0:
            trail = 70
            def heat(x, head=head, ltr=left_to_right):
                d = (head - x) if ltr else (x - head)
                return max(0.0, 1 - d / trail) if d >= -BAR_H else 0.0
            c.paste(EMBER_HI, (0, 0), scale(ImageChops.multiply(shape, ramp(heat)), 0.9 * cool))
            gx, gy = s(head) - GLOW_R, s(bar_y(i) + BAR_H / 2) - GLOW_R
            c.paste(EMBER_HI, (gx, gy), scale(GLOW, 0.55 * cool))
    if glint is not None:
        top = bar_mask(0, x0, x1)
        gx = x0 - 30 + ease_in_out(glint) * (BAR_LEN + 60)
        band = ramp(lambda x: math.exp(-((x - gx) / 22) ** 2))
        c.paste(EMBER_HI, (0, 0), scale(ImageChops.multiply(top, band), 0.7))
    if f is None:
        draw_name(c, 1.0, 0)
    elif f >= NAME_START:
        t = (f - NAME_START) / NAME_FRAMES
        draw_name(c, ease_out(t), -18 * (1 - ease_out(t)))
    return c.resize((W, H), Image.LANCZOS)


def png(img):
    b = io.BytesIO()
    img.save(b, "PNG", optimize=True)
    return b.getvalue()


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    rest = frame()
    LOGO_OUT.parent.mkdir(parents=True, exist_ok=True)
    rest.save(LOGO_OUT, optimize=True)

    intro = [frame(f) for f in range(INTRO_FRAMES)]
    intro[-1] = rest
    loop = [frame(glint=n / (GLINT_FRAMES - 1)) if n < GLINT_FRAMES else rest for n in range(LOOP_FRAMES)]

    # 'p' parts stop the moment Android is ready, so the animation never holds the boot up
    desc = f"{W} {H} {FPS}\np 1 0 part0\np 0 0 part1\n"
    zpath = BUILD / "bootanimation.zip"
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_STORED) as z:   # must be stored, not deflated
        z.writestr("desc.txt", desc)
        for part, frames in (("part0", intro), ("part1", loop)):
            for n, img in enumerate(frames):
                z.writestr(f"{part}/{n:05d}.png", png(img))

    prev = BUILD / "preview"
    prev.mkdir(exist_ok=True)
    for n in (8, 14, 20, 30, 38, 46, 52, 58, INTRO_FRAMES - 1):
        on_screen(intro[n]).save(prev / f"intro-{n:03d}.png")
    for n in (8, 18, 28):
        on_screen(loop[n]).save(prev / f"loop-{n:03d}.png")
    on_screen(rest).save(prev / "rest.png")
    print(f"frame {W}x{H}, intro {len(intro)} frames, loop {len(loop)}; {zpath.stat().st_size // 1024} KB")


def on_screen(img):
    """The frame as the Echo Show shows it: centred on a black 960x480 screen."""
    sc = Image.new("RGB", (SCREEN_W, SCREEN_H), BLACK)
    sc.paste(img, ((SCREEN_W - W) // 2, (SCREEN_H - H) // 2))
    return sc


if __name__ == "__main__":
    main()
