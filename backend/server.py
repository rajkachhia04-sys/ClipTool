import json
import os
import shutil
import subprocess
import time
import uuid
from pathlib import Path

from fastapi import FastAPI, Form, HTTPException, UploadFile, File
from fastapi.responses import FileResponse, JSONResponse
from google import genai
from google.genai import types

WORK = Path("/tmp/cliptool")
WORK.mkdir(parents=True, exist_ok=True)
MODEL = "gemini-2.5-flash"
MAX_CLIPS = 3
COOKIES = Path("/etc/secrets/cookies.txt")

app = FastAPI(title="Clip Tool AI")

PROMPT = """You are a viral short-video editor. Watch this video and pick the best {n} self-contained clips, each 20 to 60 seconds long, that would work well as YouTube Shorts.

For each clip give:
- start and end, in seconds from the start of the FULL video (numbers).
- title: a short catchy title in Hinglish.
- captions: short caption lines (3 to 8 words each) covering everything said in the clip. Write spoken Hindi in roman letters (Hinglish), keep English words as they are, and do not translate. Give each caption a start and end in seconds from the start of the FULL video, inside the clip's range.

Return only JSON in this exact shape:
{"clips": [{"start": 0.0, "end": 0.0, "title": "", "captions": [{"start": 0.0, "end": 0.0, "text": ""}]}]}"""


def get_client():
    key = os.environ.get("GEMINI_API_KEY")
    if not key:
        raise HTTPException(500, "GEMINI_API_KEY is not set on the server.")
    return genai.Client(api_key=key)


def ass_time(sec: float) -> str:
    cs = int(round(sec * 100))
    h, rem = divmod(cs, 360000)
    m, rem = divmod(rem, 6000)
    s, cs = divmod(rem, 100)
    return f"{h}:{m:02d}:{s:02d}.{cs:02d}"


def write_ass(captions, clip_start: float, path: Path):
    lines = [
        "[Script Info]",
        "ScriptType: v4.00+",
        "PlayResX: 1080",
        "PlayResY: 1920",
        "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
        "Style: Default,DejaVu Sans,64,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,5,2,2,60,60,260,1",
        "",
        "[Events]",
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
    ]
    for c in captions:
        start = max(0.0, float(c["start"]) - clip_start)
        end = max(start + 0.3, float(c["end"]) - clip_start)
        text = str(c["text"]).replace("\n", " ").replace("{", "(").replace("}", ")")
        lines.append(f"Dialogue: 0,{ass_time(start)},{ass_time(end)},Default,,0,0,0,,{text}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def download_video(url: str, dest: Path):
    cmd = [
        "yt-dlp", "--no-playlist",
        "-f", "mp4[height<=720]/best[height<=720]",
        "--merge-output-format", "mp4",
        "-o", str(dest),
    ]
    if COOKIES.exists():
        cmd += ["--cookies", str(COOKIES)]
    cmd.append(url)
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=900)
    if r.returncode != 0 or not dest.exists():
        raise HTTPException(400, "Video download nahi hua: " + r.stderr[-300:])


def cut_clip(src: Path, start: float, end: float, workdir: Path, ass_name: str, out: Path):
    cmd = [
        "ffmpeg", "-y",
        "-ss", f"{start:.2f}", "-i", str(src), "-t", f"{end - start:.2f}",
        "-vf", f"scale=-2:1920,crop=1080:1920,subtitles={ass_name}",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
        "-c:a", "aac", "-b:a", "128k",
        "-movflags", "+faststart",
        str(out),
    ]
    r = subprocess.run(cmd, cwd=workdir, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr[-400:])


def run_pipeline(client, job: Path, src: Path, contents):
    try:
        resp = client.models.generate_content(
            model=MODEL,
            contents=contents,
            config=types.GenerateContentConfig(response_mime_type="application/json"),
        )
        data = json.loads(resp.text)
    except Exception as e:
        raise HTTPException(500, f"AI step failed: {e}")

    results = []
    for i, clip in enumerate(data.get("clips", [])[:MAX_CLIPS], start=1):
        start, end = float(clip["start"]), float(clip["end"])
        if end <= start:
            continue
        ass = job / f"captions{i}.ass"
        out = job / f"clip{i}.mp4"
        write_ass(clip.get("captions", []), start, ass)
        try:
            cut_clip(src, start, end, job, ass.name, out)
        except RuntimeError as e:
            raise HTTPException(500, f"Video cutting failed: {e}")
        results.append({
            "title": clip.get("title", f"Clip {i}"),
            "url": f"/files/{job.name}/{out.name}",
        })

    if not results:
        raise HTTPException(500, "AI ko koi clip nahi mila.")

    src.unlink(missing_ok=True)
    return JSONResponse({"clips": results})


@app.get("/")
def health():
    return {"status": "ok"}


@app.post("/process")
async def process(url: str = Form(...)):
    if "youtube.com" not in url and "youtu.be" not in url:
        raise HTTPException(400, "YouTube ka link daaliye.")
    if shutil.which("ffmpeg") is None or shutil.which("yt-dlp") is None:
        raise HTTPException(500, "FFmpeg ya yt-dlp server par installed nahi hai.")
    client = get_client()

    job = WORK / uuid.uuid4().hex[:10]
    job.mkdir(parents=True)
    src = job / "source.mp4"
    download_video(url, src)

    return run_pipeline(client, job, src, [
        types.Part(file_data=types.FileData(file_uri=url)),
        PROMPT.replace("{n}", str(MAX_CLIPS)),
    ])


@app.get("/files/{job}/{name}")
def get_file(job: str, name: str):
    path = WORK / job / name
    if not path.is_file():
        raise HTTPException(404, "Not found.")
    return FileResponse(path, media_type="video/mp4", filename=name)
