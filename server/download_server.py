"""
Albo Downloader API Server
Runs on your Windows PC alongside your other bots.
Android app sends URLs here, this downloads them and sends back the file.
"""

import os
import re
import uuid
import threading
import subprocess
import shutil
from flask import Flask, request, jsonify, send_file, after_this_request
from pathlib import Path

app = Flask(__name__)

# Where files are temporarily saved before sending to phone
DOWNLOAD_DIR = Path(os.environ.get("DOWNLOAD_DIR", "downloads_temp"))
DOWNLOAD_DIR.mkdir(exist_ok=True)

# Track active downloads: {job_id: {status, progress, filename, error}}
jobs = {}
jobs_lock = threading.Lock()

# ── helpers ──────────────────────────────────────────────────────────────────

def get_ytdlp():
    """Find yt-dlp in venv or system."""
    candidates = [
        Path(__file__).parent.parent / ".venv" / "Scripts" / "yt-dlp.exe",
        Path(__file__).parent.parent / ".venv" / "bin" / "yt-dlp",
        "yt-dlp",
    ]
    for c in candidates:
        if shutil.which(str(c)):
            return str(c)
    return "yt-dlp"

def clean_ansi(text):
    return re.sub(r'\x1b\[[0-9;]*m', '', text)

def run_download(job_id, url, fmt, subtitles):
    ytdlp = get_ytdlp()
    out_path = DOWNLOAD_DIR / job_id
    out_path.mkdir(exist_ok=True)

    # Build yt-dlp command
    cmd = [ytdlp, url, "--no-playlist" if fmt != "playlist" else "--yes-playlist"]

    if fmt == "mp3":
        cmd += ["-x", "--audio-format", "mp3", "--audio-quality", "0"]
    elif fmt == "best":
        cmd += ["-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"]
    elif fmt == "720p":
        cmd += ["-f", "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720]"]
    elif fmt == "480p":
        cmd += ["-f", "bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/best[height<=480]"]
    elif fmt == "playlist":
        cmd += [
            "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
            "--playlist-items", "1-150",
        ]

    if subtitles:
        cmd += ["--write-subs", "--write-auto-subs", "--sub-langs", "en", "--convert-subs", "srt"]

    cmd += [
        "-o", str(out_path / "%(title)s.%(ext)s"),
        "--no-warnings",
        "--progress",
    ]

    with jobs_lock:
        jobs[job_id]["status"] = "downloading"
        jobs[job_id]["progress"] = 0

    try:
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace"
        )

        for line in proc.stdout:
            line = clean_ansi(line).strip()
            # Parse progress percentage
            m = re.search(r'(\d+\.?\d*)%', line)
            if m:
                pct = float(m.group(1))
                with jobs_lock:
                    jobs[job_id]["progress"] = int(pct)

        proc.wait()

        if proc.returncode != 0:
            with jobs_lock:
                jobs[job_id]["status"] = "error"
                jobs[job_id]["error"] = "Download failed - check the URL"
            return

        # Find downloaded file(s)
        files = list(out_path.glob("*"))
        # Filter out subtitle files for the main file reference
        video_files = [f for f in files if f.suffix not in (".srt", ".vtt", ".json")]

        if not video_files:
            with jobs_lock:
                jobs[job_id]["status"] = "error"
                jobs[job_id]["error"] = "No file downloaded"
            return

        with jobs_lock:
            jobs[job_id]["status"] = "done"
            jobs[job_id]["progress"] = 100
            jobs[job_id]["files"] = [str(f.name) for f in files]
            jobs[job_id]["main_file"] = video_files[0].name
            jobs[job_id]["out_path"] = str(out_path)

    except Exception as e:
        with jobs_lock:
            jobs[job_id]["status"] = "error"
            jobs[job_id]["error"] = str(e)


# ── routes ───────────────────────────────────────────────────────────────────

@app.route("/ping")
def ping():
    """Health check - app uses this to test connection."""
    return jsonify({"status": "ok", "service": "Albo Downloader"})


@app.route("/info", methods=["POST"])
def get_info():
    """Get video title and available formats before downloading."""
    data = request.json or {}
    url = data.get("url", "").strip()
    if not url:
        return jsonify({"error": "No URL provided"}), 400

    ytdlp = get_ytdlp()
    try:
        result = subprocess.run(
            [ytdlp, "--dump-json", "--no-playlist", url],
            capture_output=True, text=True, timeout=30,
            encoding="utf-8", errors="replace"
        )
        if result.returncode != 0:
            return jsonify({"error": "Could not fetch video info"}), 400

        import json
        info = json.loads(result.stdout.split('\n')[0])
        return jsonify({
            "title": info.get("title", "Unknown"),
            "duration": info.get("duration", 0),
            "thumbnail": info.get("thumbnail", ""),
            "uploader": info.get("uploader", ""),
            "platform": info.get("extractor_key", ""),
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/download", methods=["POST"])
def start_download():
    """Start a download job. Returns job_id immediately."""
    data = request.json or {}
    url = data.get("url", "").strip()
    fmt = data.get("format", "best")       # best, 720p, 480p, mp3, playlist
    subtitles = data.get("subtitles", False)

    if not url:
        return jsonify({"error": "No URL provided"}), 400

    job_id = str(uuid.uuid4())[:8]
    with jobs_lock:
        jobs[job_id] = {
            "status": "queued",
            "progress": 0,
            "url": url,
            "format": fmt,
        }

    thread = threading.Thread(
        target=run_download,
        args=(job_id, url, fmt, subtitles),
        daemon=True
    )
    thread.start()

    return jsonify({"job_id": job_id})


@app.route("/status/<job_id>")
def job_status(job_id):
    """Poll download progress."""
    with jobs_lock:
        job = jobs.get(job_id)
    if not job:
        return jsonify({"error": "Job not found"}), 404
    return jsonify(job)


@app.route("/file/<job_id>/<filename>")
def get_file(job_id, filename):
    """Stream a completed file to the phone."""
    with jobs_lock:
        job = jobs.get(job_id)

    if not job or job["status"] != "done":
        return jsonify({"error": "File not ready"}), 404

    file_path = Path(job["out_path"]) / filename
    if not file_path.exists():
        return jsonify({"error": "File not found"}), 404

    @after_this_request
    def cleanup(response):
        # Clean up temp files after sending
        try:
            shutil.rmtree(Path(job["out_path"]), ignore_errors=True)
            with jobs_lock:
                jobs.pop(job_id, None)
        except Exception:
            pass
        return response

    return send_file(
        str(file_path),
        as_attachment=True,
        download_name=filename
    )


@app.route("/jobs")
def list_jobs():
    """List all active jobs."""
    with jobs_lock:
        return jsonify(dict(jobs))


if __name__ == "__main__":
    print("=" * 50)
    print("  Albo Downloader Server")
    print("  Running on http://0.0.0.0:5005")
    print("  Android app should use: http://192.168.1.200:5005")
    print("=" * 50)
    app.run(host="0.0.0.0", port=5005, debug=False, threaded=True)
