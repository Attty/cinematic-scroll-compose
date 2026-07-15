# Renders the neon-tunnel frame sequence for the scroll-scrub demo.
# Usage:
#   python generate_frames.py --preview        -> 3 sample frames into ./preview
#   python generate_frames.py                  -> full sequence into app assets
import argparse
import os

import numpy as np
from PIL import Image

W, H = 720, 1440
N_FRAMES = 150
FLIGHT_DEPTH = 9.0  # tunnel units travelled across the whole scroll

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "frames")
PREVIEW_DIR = os.path.join(os.path.dirname(__file__), "preview")

# pixel grid, aspect-correct, y down
px = (np.arange(W) - W / 2) / (H / 2)
py = (np.arange(H) - H / 2) / (H / 2)
X, Y = np.meshgrid(px, py)
R_SCREEN = np.sqrt(X * X + Y * Y)


def frac(a):
    return a - np.floor(a)


def render(t: float) -> np.ndarray:
    dist = t * FLIGHT_DEPTH

    # camera sway - gives the "hand-held cinematic" feel
    cx = 0.30 * np.sin(t * 2 * np.pi * 0.9 + 1.0)
    cy = 0.22 * np.cos(t * 2 * np.pi * 0.7)

    dx = X - cx
    dy = Y - cy
    r = np.sqrt(dx * dx + dy * dy) + 1e-4
    ang = np.arctan2(dy, dx)

    v = 0.45 / r + dist                       # depth along the tunnel
    ang = ang + 0.45 * np.sin(v * 0.35 + t * 2.0)  # slow twist of the walls
    u = ang * 8.0 / np.pi                     # 16 stripes around

    fu = np.abs(frac(u) - 0.5)
    fv = np.abs(frac(v * 1.5) - 0.5)

    # crisp neon lines + narrow soft halo around them
    line_u = np.exp(-fu * fu / 0.002)
    line_v = np.exp(-fv * fv / 0.0015)
    halo_u = np.exp(-fu * fu / 0.05) * 0.16
    halo_v = np.exp(-fv * fv / 0.04) * 0.20

    # near walls bright, tunnel center falls into black
    fade = np.clip(r * 1.7, 0, 1) ** 1.2
    depth_att = np.exp(-0.10 * (v - dist))  # farther rings are dimmer

    inten = (line_u * 0.7 + line_v * 1.0 + line_u * line_v * 1.8 + halo_u + halo_v)
    inten *= fade * (0.25 + 0.75 * depth_att)

    # neon palette: cyan <-> magenta bands travelling along depth, violet undertone
    w1 = 0.5 + 0.5 * np.sin(v * 0.9 + t * 1.5)
    w2 = 0.5 + 0.5 * np.sin(v * 0.23 + 2.0)
    cyan = np.array([0.10, 0.85, 1.30])
    magenta = np.array([1.30, 0.12, 0.95])
    violet = np.array([0.45, 0.20, 1.30])
    col = w1[..., None] * cyan + (1 - w1)[..., None] * magenta
    col = col * (1 - 0.35 * w2[..., None]) + violet * (0.35 * w2[..., None])

    rgb = inten[..., None] * col

    # vignette + a touch of film grain so frames don't look sterile
    vig = 1.0 - 0.35 * np.clip((R_SCREEN / 1.25) ** 2, 0, 1)
    rgb *= vig[..., None]
    rng = np.random.default_rng(int(t * 100000) + 7)
    rgb += (rng.random((H, W, 1)) - 0.5) * 0.035

    rgb = 1.0 - np.exp(-rgb * 1.9)  # soft tonemap
    rgb = np.clip(rgb, 0, 1) ** (1 / 1.9)
    return (rgb * 255).astype(np.uint8)


def save(img: np.ndarray, path: str):
    Image.fromarray(img).save(path, "WEBP", quality=82, method=4)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true")
    args = ap.parse_args()

    if args.preview:
        os.makedirs(PREVIEW_DIR, exist_ok=True)
        for i in (0, N_FRAMES // 2, N_FRAMES - 1):
            save(render(i / (N_FRAMES - 1)), os.path.join(PREVIEW_DIR, f"preview_{i:03d}.webp"))
        print("preview done")
        return

    os.makedirs(OUT_DIR, exist_ok=True)
    for i in range(N_FRAMES):
        save(render(i / (N_FRAMES - 1)), os.path.join(OUT_DIR, f"frame_{i:03d}.webp"))
        if i % 25 == 0:
            print(f"{i}/{N_FRAMES}")
    print("done")


if __name__ == "__main__":
    main()
