"""Build a captioned screen tour from real, unmodified browser-demo captures.

Requires FFmpeg/ffprobe on PATH. No app/database access, generated UI or AI assets.
Source PNGs stay in ignored server/target; only the selected outputs are published.
"""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SCENES = (
    ("live.png", 0, "01 / Live telemetry", "Committed server updates, with measurement age shown separately.", "Missing sensors remain unavailable. All readings are simulated."),
    ("trip.png", 420, "02 / Trip evidence", "One simulated commute: 8 minutes, 241 trip samples, estimated 6.5 km.", "Estimated distance includes coverage; it is not the vehicle odometer."),
    ("trip.png", 1530, "03 / Measurement history", "Persisted RPM, speed and temperature plotted in UTC.", "Bounded pages and gaps preserve uncertainty instead of inventing data."),
    ("diagnostics.png", 870, "04 / Diagnostic observations", "Code cycles, partial reads and unknown definitions remain explicit.", "An observation is not a failed-part diagnosis or a safe-to-drive claim."),
    ("maintenance.png", 550, "05 / Maintenance context", "Manual inputs, selected schedules and versioned rule provenance.", "These are demonstration records, not the owner's service history."),
    ("maintenance.png", 1880, "06 / Service history", "Service records and odometer corrections preserve their history.", "Matching service updates its reminder, not unrelated diagnostic events."),
)


def run(args, cwd=None):
    result = subprocess.run(args, cwd=cwd, capture_output=True, text=True, timeout=180)
    if result.returncode:
        raise RuntimeError(result.stderr[-5000:])
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, default=ROOT / "server/target/readme-demo-sources")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "docs/demo")
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--font", type=Path, help="Local TrueType font; not embedded or redistributed")
    args = parser.parse_args()
    ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        parser.error("Install FFmpeg and ffprobe first; no runtime or UI is synthesized as a substitute.")
    fonts = [args.font] if args.font else [Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts/arial.ttf",
                                         Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")]
    font = next((p.resolve() for p in fonts if p.is_file()), None)
    if font is None:
        parser.error("Supply --font with a local TrueType font path.")
    escaped_font = font.as_posix().replace(":", "\\:").replace("'", "\\'")
    font_filter = f"fontfile='{escaped_font}'"
    source, output = args.source_dir.resolve(), args.output_dir.resolve()
    outputs = [output / n for n in ("autopulse-demo.mp4", "autopulse-demo.gif", "preview.png")]
    if not args.overwrite and any(p.exists() for p in outputs):
        parser.error("Output exists; use --overwrite only to regenerate these three named demo files.")
    for name, offset, *_ in SCENES:
        path = source / name
        if not path.is_file():
            parser.error(f"Missing real screenshot: {path}; see docs/demo/README.md")
        info = json.loads(run([ffprobe, "-v", "error", "-show_streams", "-of", "json", str(path)]))["streams"][0]
        if info["width"] != 1280 or info["height"] < offset + 720:
            parser.error(f"Capture dimensions changed for {name}; review the crop before publishing.")
    output.mkdir(parents=True, exist_ok=True)
    temporary_root = ROOT / "server/target"
    temporary_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="demo-render-", dir=temporary_root) as work:
        work = Path(work)
        clips = []

        def clip(index, duration, filters, image=None):
            name = f"{index:02d}.mp4"
            inputs = ["-loop", "1", "-framerate", "10", "-i", str(image)] if image else ["-f", "lavfi", "-i", "color=c=0x10292d:s=1280x900:r=10"]
            run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", *inputs, "-t", str(duration), "-vf", filters.replace("font=Arial", font_filter),
                 "-c:v", "libx264", "-preset", "fast", "-crf", "21", "-pix_fmt", "yuv420p", "-an", "-map_metadata", "-1", name], cwd=work)
            clips.append(name)

        (work / "intro.txt").write_text("AutoPulse\nSimulator-first automotive telemetry\nPython / Spring Boot / PostgreSQL / React", encoding="utf-8", newline="\n")
        (work / "disclosure.txt").write_text("REAL UI CAPTURES / SIMULATED DATA\nCaptioned screen tour, not a continuous driving recording", encoding="utf-8", newline="\n")
        clip(0, 3, "drawtext=textfile=intro.txt:font=Arial:fontsize=42:fontcolor=white:line_spacing=24:x=70:y=270,drawtext=textfile=disclosure.txt:font=Arial:fontsize=22:fontcolor=0x93cfbe:line_spacing=14:x=70:y=530")
        for index, (name, offset, title, line1, line2) in enumerate(SCENES, 1):
            (work / "title.txt").write_text(title, encoding="utf-8", newline="\n")
            (work / "caption.txt").write_text(line1 + "\n" + line2, encoding="utf-8", newline="\n")
            clip(index, 5, f"crop=1280:720:0:{offset},pad=1280:900:0:68:color=0x10292d,"
                 "drawtext=textfile=title.txt:font=Arial:fontsize=27:fontcolor=white:x=28:y=17,"
                 "drawtext=text='SIMULATOR ONLY':font=Arial:fontsize=17:fontcolor=0x93cfbe:x=1070:y=24,"
                 "drawtext=textfile=caption.txt:font=Arial:fontsize=23:fontcolor=white:line_spacing=12:x=28:y=815", source / name)
        (work / "end.txt").write_text("Built for honest evidence.\nHardware, durable edge buffering and deployment are next.\nNo vehicle hardware connected. No ECU-control features.", encoding="utf-8", newline="\n")
        clip(7, 3, "drawtext=textfile=end.txt:font=Arial:fontsize=30:fontcolor=white:line_spacing=24:x=70:y=300")
        (work / "concat.txt").write_text("".join(f"file '{name}'\n" for name in clips), encoding="utf-8", newline="\n")
        run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0", "-i", "concat.txt", "-c", "copy", "-map_metadata", "-1", "-movflags", "+faststart", str(outputs[0])], cwd=work)
        run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(outputs[0]), "-filter_complex",
             "[0:v]fps=5,scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle",
             "-loop", "0", str(outputs[1])])
        run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-ss", "4", "-i", str(outputs[0]), "-frames:v", "1", str(outputs[2])])
    print(json.dumps({"durationSeconds": 36, "source": "real isolated browser-demo screenshots", "outputs": [str(p) for p in outputs]}))


if __name__ == "__main__":
    main()
