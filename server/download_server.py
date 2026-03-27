# -*- coding: utf-8 -*-
"""
Albo Downloader API Server
===========================
Runs alongside tiktok_bot.py on your Windows PC.
Handles: best, 720p, 480p, mp3, subtitles, textonly, playlist
"""

import os
import re
import uuid
import threading
import subprocess
import shutil
from flask import Flask, request, jsonify, send_file, after_this_request
from pathlib import Path
from datetime import datetime

app = Flask(__name__)

BASE_DIR     = Path(__file__).parent
DOWNLOAD_DIR = BASE_DIR / "downloads_temp"
COOKIES_FILE = BASE_DIR / "cookies.txt"
DOWNLOAD_DIR.mkdir(exist_ok=True)

PORT = 5005
jobs: dict = {}
jobs_lock = threading.Lock()

def log(icon, message):
    ts = datetime.now().strftime("%H:%M:%S")
    print(f"  {icon}  [{ts}] {message}", flush=True)

def get_ytdlp():
    for c in [BASE_DIR/".venv"/"Scripts"/"yt-dlp.exe",
               BASE_DIR/".venv"/"bin"/"yt-dlp", "yt-dlp"]:
        if shutil.which(str(c)):
            return str(c)
    return "yt-dlp"

def get_cookie_args():
    return ["--cookies", str(COOKIES_FILE)] if COOKIES_FILE.exists() else []

def clean_ansi(text):
    return re.sub(r'\x1b\[[0-9;]*m', '', text)

def get_platform(url):
    u = url.lower()
    if "tiktok.com" in u:      return "TikTok"
    if "instagram.com" in u:   return "Instagram"
    if "facebook.com" in u or "fb.watch" in u: return "Facebook"
    if "youtube.com" in u or "youtu.be" in u:  return "YouTube"
    if "twitter.com" in u or "x.com" in u:     return "Twitter/X"
    if "reddit.com" in u:      return "Reddit"
    if "vimeo.com" in u:       return "Vimeo"
    if "twitch.tv" in u:       return "Twitch"
    if "snapchat.com" in u:    return "Snapchat"
    if "pinterest.com" in u:   return "Pinterest"
    if "dailymotion.com" in u: return "Dailymotion"
    if "threads.net" in u:     return "Threads"
    return "Video"

def run_download(job_id, url, fmt, subtitles):
    ytdlp    = get_ytdlp()
    out_path = DOWNLOAD_DIR / job_id
    out_path.mkdir(exist_ok=True)
    platform = get_platform(url)

    with jobs_lock:
        jobs[job_id].update({"status": "downloading", "progress": 0, "platform": platform})

    log("⬇️", f"[{job_id}] {platform} | {fmt} | {url[:60]}...")

    cmd = [ytdlp, *get_cookie_args()]

    if fmt == "mp3":
        cmd += [url, "--no-playlist",
                "-x", "--audio-format", "mp3", "--audio-quality", "0",
                "--embed-thumbnail", "--embed-metadata",
                "--parse-metadata", "%(title)s:%(meta_title)s",
                "--parse-metadata", "%(uploader)s:%(meta_artist)s",
                "--concurrent-fragments", "8", "--retries", "3"]

    elif fmt == "textonly":
        cmd += [url, "--no-playlist",
                "--skip-download",
                "--write-subs", "--write-auto-subs",
                "--sub-langs", "en", "--convert-subs", "srt"]

    elif fmt == "playlist":
        cmd += [url, "--yes-playlist", "--playlist-end", "150",
                "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                "--merge-output-format", "mp4",
                "--concurrent-fragments", "8", "--retries", "3", "--ignore-errors"]

    elif fmt == "720p":
        cmd += [url, "--no-playlist",
                "-f", "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720]",
                "--merge-output-format", "mp4",
                "--concurrent-fragments", "8", "--retries", "3"]

    elif fmt == "480p":
        cmd += [url, "--no-playlist",
                "-f", "bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/best[height<=480]",
                "--merge-output-format", "mp4",
                "--concurrent-fragments", "8", "--retries", "3"]

    else:  # best
        cmd += [url, "--no-playlist",
                "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                "--merge-output-format", "mp4",
                "--concurrent-fragments", "8", "--retries", "3"]

    if subtitles and fmt not in ("mp3", "textonly"):
        cmd += ["--write-subs", "--write-auto-subs",
                "--sub-langs", "en", "--convert-subs", "srt"]

    cmd += ["-o", str(out_path / "%(title).80s.%(ext)s"),
            "--newline", "--no-warnings"]

    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, encoding="utf-8", errors="replace")
        for line in proc.stdout:
            line = clean_ansi(line).strip()
            m = re.search(r'(\d+\.?\d*)%', line)
            if m:
                with jobs_lock:
                    jobs[job_id]["progress"] = int(float(m.group(1)))
        proc.wait()

        if proc.returncode != 0 and fmt != "playlist":
            with jobs_lock:
                jobs[job_id].update({"status": "error",
                                     "error": "Download failed — check URL or try another format"})
            return

        all_files   = list(out_path.glob("*"))
        skip_exts   = {".part", ".ytdl"}
        media_files = [f for f in all_files if f.suffix.lower() not in skip_exts]

        if not media_files:
            with jobs_lock:
                jobs[job_id].update({"status": "error", "error": "No file was downloaded"})
            return

        if fmt == "textonly":
            srt_files = [f for f in media_files if f.suffix.lower() == ".srt"]
            main_file = srt_files[0] if srt_files else media_files[0]
        else:
            video_files = [f for f in media_files if f.suffix.lower() not in {".srt",".vtt",".json"}]
            video_files.sort(key=lambda f: f.stat().st_size, reverse=True)
            main_file = video_files[0] if video_files else media_files[0]

        size_mb = main_file.stat().st_size / (1024 * 1024)

        with jobs_lock:
            jobs[job_id].update({
                "status":    "done",
                "progress":  100,
                "files":     [f.name for f in media_files],
                "main_file": main_file.name,
                "size_mb":   round(size_mb, 1),
                "out_path":  str(out_path),
            })

        log("✅", f"[{job_id}] Done — {main_file.name} ({size_mb:.1f} MB)")

    except Exception as e:
        with jobs_lock:
            jobs[job_id].update({"status": "error", "error": str(e)})
        log("❌", f"[{job_id}] Exception: {e}")


@app.route("/ping")
def ping():
    return jsonify({"status": "ok", "service": "Albo Downloader", "port": PORT})

@app.route("/info", methods=["POST"])
def get_info():
    data = request.json or {}
    url  = data.get("url", "").strip()
    if not url:
        return jsonify({"error": "No URL provided"}), 400
    try:
        result = subprocess.run(
            [get_ytdlp(), *get_cookie_args(), "--dump-json", "--no-playlist", url],
            capture_output=True, text=True, timeout=30, encoding="utf-8", errors="replace")
        if result.returncode != 0:
            return jsonify({"error": "Could not fetch info"}), 400
        import json
        info = json.loads(result.stdout.split("\n")[0])
        return jsonify({
            "title":    info.get("title", "Unknown"),
            "duration": info.get("duration", 0),
            "uploader": info.get("uploader", ""),
            "platform": get_platform(url),
        })
    except subprocess.TimeoutExpired:
        return jsonify({"error": "Timed out"}), 504
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route("/download", methods=["POST"])
def start_download():
    data      = request.json or {}
    url       = data.get("url", "").strip()
    fmt       = data.get("format", "best")
    subtitles = data.get("subtitles", False)
    if not url:
        return jsonify({"error": "No URL provided"}), 400
    job_id = str(uuid.uuid4())[:8]
    with jobs_lock:
        jobs[job_id] = {"status": "queued", "progress": 0, "url": url, "format": fmt}
    threading.Thread(target=run_download, args=(job_id, url, fmt, subtitles), daemon=True).start()
    log("📥", f"[{job_id}] Queued {fmt} — {url[:60]}")
    return jsonify({"job_id": job_id})

@app.route("/status/<job_id>")
def job_status(job_id):
    with jobs_lock:
        job = jobs.get(job_id)
    if not job:
        return jsonify({"error": "Job not found"}), 404
    return jsonify(job)

@app.route("/file/<job_id>/<filename>")
def get_file(job_id, filename):
    with jobs_lock:
        job = jobs.get(job_id)
    if not job or job.get("status") != "done":
        return jsonify({"error": "File not ready"}), 404
    file_path = Path(job["out_path"]) / filename
    if not file_path.exists():
        return jsonify({"error": "File not found"}), 404

    @after_this_request
    def cleanup(response):
        try:
            shutil.rmtree(Path(job["out_path"]), ignore_errors=True)
            with jobs_lock:
                jobs.pop(job_id, None)
        except Exception:
            pass
        return response

    log("📤", f"[{job_id}] Sending {filename}")
    return send_file(str(file_path), as_attachment=True, download_name=filename)

@app.route("/jobs")
def list_jobs():
    with jobs_lock:
        return jsonify(dict(jobs))

if __name__ == "__main__":
    import sys
    if sys.platform == "win32":
        os.system("")
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print("\n" + "=" * 52)
    print("  🎬  Albo Downloader Server")
    print(f"  Running on  : http://0.0.0.0:{PORT}")
    print(f"  Android app : http://192.168.1.200:{PORT}")
    print(f"  Cookies     : {'✅ Found' if COOKIES_FILE.exists() else '❌ Not found'}")
    print(f"  yt-dlp      : {get_ytdlp()}")
    print("=" * 52 + "\n")
    app.run(host="0.0.0.0", port=PORT, debug=False, threaded=True)
