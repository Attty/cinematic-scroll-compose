# Slices any video into a frame sequence ready for CinematicScroll.
#
#   python video_to_frames.py flight.mp4
#   python video_to_frames.py flight.mp4 --frames 200 --height 1440 --aspect 0.5
#   python video_to_frames.py flight.mp4 --out ..\app\src\main\assets\frames
#
# Picks N evenly spaced frames, center-crops to a portrait aspect,
# scales to the target height and saves WebP. Needs ffmpeg on PATH.
import argparse
import json
import os
import shutil
import subprocess
import sys


def ffprobe_duration(path: str) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "json", path],
        capture_output=True, text=True, check=True,
    ).stdout
    return float(json.loads(out)["format"]["duration"])


def main():
    ap = argparse.ArgumentParser(description="Video -> CinematicScroll frame sequence")
    ap.add_argument("video", help="input video file")
    ap.add_argument("--frames", type=int, default=None,
                    help="frame count (default: auto — 12 per second of video, 60..360)")
    ap.add_argument("--height", type=int, default=1440, help="output frame height (default 1440)")
    ap.add_argument("--aspect", type=float, default=0.5,
                    help="output width/height, e.g. 0.5 = portrait 1:2 (default 0.5)")
    ap.add_argument("--quality", type=int, default=82, help="webp quality (default 82)")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__) or ".",
                                                  "..", "app", "src", "main", "assets", "frames"),
                    help="output dir (default: this repo's demo assets)")
    args = ap.parse_args()

    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        sys.exit("ffmpeg/ffprobe not found on PATH — install from https://ffmpeg.org")
    if not os.path.isfile(args.video):
        sys.exit(f"no such file: {args.video}")

    duration = ffprobe_duration(args.video)
    # frames must be close in time or the scrub turns into a slideshow;
    # ~12 samples per second of footage keeps neighbouring frames near-identical
    if args.frames is None:
        args.frames = min(max(int(duration * 12), 60), 360)
    out_dir = os.path.abspath(args.out)
    os.makedirs(out_dir, exist_ok=True)
    for old in os.listdir(out_dir):
        if old.startswith("frame_") and old.endswith(".webp"):
            os.remove(os.path.join(out_dir, old))

    width = int(args.height * args.aspect) // 2 * 2  # ffmpeg wants even dims
    height = args.height // 2 * 2
    # sample just inside the clip so the last frame isn't past the end
    fps = args.frames / duration * 0.999
    vf = (
        f"fps={fps},"
        f"crop='min(iw,ih*{args.aspect})':'min(ih,iw/{args.aspect})',"
        f"scale={width}:{height}"
    )

    print(f"{args.video}: {duration:.1f}s -> {args.frames} frames "
          f"{width}x{height} -> {out_dir}")
    subprocess.run(
        ["ffmpeg", "-v", "error", "-stats", "-i", args.video,
         "-vf", vf, "-frames:v", str(args.frames),
         # -c:v libwebp: the default webp encoder is the ANIMATED one, which
         # silently packs every frame into a single file ignoring the pattern
         "-c:v", "libwebp", "-q:v", str(args.quality), "-compression_level", "4",
         "-f", "image2", os.path.join(out_dir, "frame_%03d.webp")],
        check=True,
    )

    files = [f for f in os.listdir(out_dir) if f.startswith("frame_") and f.endswith(".webp")]
    total_mb = sum(os.path.getsize(os.path.join(out_dir, f)) for f in files) / 1e6
    print(f"done: {len(files)} frames, {total_mb:.1f} MB in assets")
    if len(files) < args.frames:
        print(f"note: clip was short on frames ({len(files)}/{args.frames}) — "
              f"CinematicScroll just uses what's there, no action needed")


if __name__ == "__main__":
    main()
