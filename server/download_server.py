# -*- coding: utf-8 -*-
"""
Albo Downloader API Server
===========================
Formats: video, mp3, subtitles, textonly, playlist, photos
Photos use gallery-dl with flat output (no subfolders)
Supports custom save folder per request
"""

import os, re, uuid, threading, subprocess, shutil, json as _json
from flask import Flask, request, jsonify, send_file, after_this_request
from pathlib import Path
from datetime import datetime

app = Flask(__name__)

BASE_DIR     = Path(__file__).parent
DOWNLOAD_DIR = BASE_DIR / "downloads_temp"
COOKIES_FILE = BASE_DIR / "cookies.txt"
DOWNLOAD_DIR.mkdir(exist_ok=True)

# Default save folder if none specified by app
DEFAULT_SAVE_FOLDER = Path.home() / "Downloads" / "Albo Downloader"
DEFAULT_SAVE_FOLDER.mkdir(parents=True, exist_ok=True)

PORT = 5005
jobs: dict = {}
jobs_lock = threading.Lock()

def log(icon, message):
    ts = datetime.now().strftime("%H:%M:%S")
    print(f"  {icon}  [{ts}] {message}", flush=True)

def get_ytdlp():
    for c in [BASE_DIR/".venv"/"Scripts"/"yt-dlp.exe",
               BASE_DIR/".venv"/"bin"/"yt-dlp", "yt-dlp"]:
        if shutil.which(str(c)): return str(c)
    return "yt-dlp"

def get_gallery_dl():
    for c in [BASE_DIR/".venv"/"Scripts"/"gallery-dl.exe",
               BASE_DIR/".venv"/"bin"/"gallery-dl", "gallery-dl"]:
        if shutil.which(str(c)): return str(c)
    return None

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

SUBTITLE_PLATFORMS = {"YouTube", "Vimeo", "Twitch", "Dailymotion"}
TEXTONLY_PLATFORMS = {"YouTube"}
PHOTOS_PLATFORMS   = {"Instagram", "TikTok", "Pinterest", "Twitter/X", "Threads"}


def run_download(job_id, url, fmt, save_folder):
    ytdlp    = get_ytdlp()
    tmp_path = DOWNLOAD_DIR / job_id       # temp working dir
    tmp_path.mkdir(exist_ok=True)
    platform = get_platform(url)

    # Final destination folder
    dest_folder = Path(save_folder) if save_folder else DEFAULT_SAVE_FOLDER
    dest_folder.mkdir(parents=True, exist_ok=True)

    with jobs_lock:
        jobs[job_id].update({"status": "downloading", "progress": 0, "platform": platform})

    log("⬇️", f"[{job_id}] {platform} | {fmt} | {url[:60]}...")

    try:
        # ── Photos — gallery-dl with flat output ─────────────────────────────
        if fmt == "photos":
            gdl = get_gallery_dl()
            if not gdl:
                with jobs_lock:
                    jobs[job_id].update({"status": "error",
                        "error": "gallery-dl not installed. Run: pip install gallery-dl"})
                return

            cmd = [
                gdl,
                *get_cookie_args(),
                "--dest",      str(tmp_path),  # download to temp first
                "--no-part",
                "--filename",  "{num:>03}_{filename}.{extension}",  # flat numbered files
                url
            ]
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, encoding="utf-8", errors="replace")
            downloaded_count = 0
            for line in proc.stdout:
                line = clean_ansi(line).strip()
                if line:
                    log("📷", line)
                    if "Downloading" in line:
                        downloaded_count += 1
                        with jobs_lock:
                            jobs[job_id]["progress"] = min(downloaded_count * 10, 90)
            proc.wait()

        # ── Text Only — SRT then strip timestamps ────────────────────────────
        elif fmt == "textonly":
            cmd = [ytdlp, *get_cookie_args(), url, "--no-playlist",
                   "--skip-download", "--write-subs", "--write-auto-subs",
                   "--sub-langs", "en", "--convert-subs", "srt",
                   "-o", str(tmp_path / "%(title).80s.%(ext)s"),
                   "--newline", "--no-warnings"]
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, encoding="utf-8", errors="replace")
            proc.communicate()
            proc.wait()

            for srt_file in tmp_path.glob("*.srt"):
                txt_path = srt_file.with_suffix(".txt")
                try:
                    content = srt_file.read_text(encoding="utf-8", errors="replace")
                    text_lines = []
                    for line in content.split("\n"):
                        line = line.strip()
                        if not line or line.isdigit() or "-->" in line:
                            continue
                        line = re.sub(r'<[^>]+>', '', line)
                        if line:
                            text_lines.append(line)
                    txt_path.write_text("\n".join(text_lines), encoding="utf-8")
                    srt_file.unlink()
                except Exception as e:
                    log("⚠️", f"Strip timestamps failed: {e}")

        # ── Subtitles — SRT with timestamps ──────────────────────────────────
        elif fmt == "subtitles":
            cmd = [ytdlp, *get_cookie_args(), url, "--no-playlist",
                   "--skip-download", "--write-subs", "--write-auto-subs",
                   "--sub-langs", "en", "--convert-subs", "srt",
                   "-o", str(tmp_path / "%(title).80s.%(ext)s"),
                   "--newline", "--no-warnings"]
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, encoding="utf-8", errors="replace")
            proc.communicate()
            proc.wait()

        # ── MP3 ───────────────────────────────────────────────────────────────
        elif fmt == "mp3":
            cmd = [ytdlp, *get_cookie_args(), url, "--no-playlist",
                   "-x", "--audio-format", "mp3", "--audio-quality", "0",
                   "--embed-thumbnail", "--embed-metadata",
                   "--parse-metadata", "%(title)s:%(meta_title)s",
                   "--parse-metadata", "%(uploader)s:%(meta_artist)s",
                   "--concurrent-fragments", "8", "--retries", "5",
                   "-o", str(tmp_path / "%(title).80s.%(ext)s"),
                   "--newline", "--no-warnings"]
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, encoding="utf-8", errors="replace")
            for line in proc.stdout:
                m = re.search(r'(\d+\.?\d*)%', clean_ansi(line))
                if m:
                    with jobs_lock: jobs[job_id]["progress"] = int(float(m.group(1)))
            proc.wait()

        # ── Playlist ──────────────────────────────────────────────────────────
        elif fmt == "playlist":
            cmd = [ytdlp, *get_cookie_args(), url,
                   "--yes-playlist", "--playlist-end", "150",
                   "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best",
                   "--merge-output-format", "mp4",
                   "--concurrent-fragments", "8", "--retries", "3", "--ignore-errors",
                   "-o", str(tmp_path / "%(playlist_index)03d - %(title).60s.%(ext)s"),
                   "--newline", "--no-warnings"]
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, encoding="utf-8", errors="replace")
            for line in proc.stdout:
                m = re.search(r'(\d+\.?\d*)%', clean_ansi(line))
                if m:
                    with jobs_lock: jobs[job_id]["progress"] = int(float(m.group(1)))
            proc.wait()

        # ── Video (best quality) ───────────────────────────────────────────────
        else:
            cmd = [ytdlp, *get_cookie_args(), url, "--no-playlist",
                   "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best",
                   "--merge-output-format", "mp4",
                   "--concurrent-fragments", "8", "--retries", "5",
                   "-o", str(tmp_path / "%(title).80s.%(ext)s"),
                   "--newline", "--no-warnings"]
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, encoding="utf-8", errors="replace")
            for line in proc.stdout:
                m = re.search(r'(\d+\.?\d*)%', clean_ansi(line))
                if m:
                    with jobs_lock: jobs[job_id]["progress"] = int(float(m.group(1)))
            proc.wait()

        # ── Collect all downloaded files (including gallery-dl subfolders) ────
        all_files   = [f for f in tmp_path.rglob("*") if f.is_file()]
        skip_exts   = {".part", ".ytdl", ".json"}
        media_files = [f for f in all_files if f.suffix.lower() not in skip_exts]

        if not media_files:
            with jobs_lock:
                jobs[job_id].update({"status": "error",
                    "error": "No file downloaded — check the URL"})
            return

        # ── Move all files to the chosen destination folder ───────────────────
        saved_files = []
        for f in media_files:
            dest = dest_folder / f.name
            # Avoid overwriting by adding a number suffix
            counter = 1
            while dest.exists():
                stem = f.stem
                dest = dest_folder / f"{stem}_{counter}{f.suffix}"
                counter += 1
            shutil.move(str(f), str(dest))
            saved_files.append(dest)
            log("💾", f"Saved: {dest}")

        # Clean up temp folder
        shutil.rmtree(str(tmp_path), ignore_errors=True)

        main_file = saved_files[0]
        size_mb   = main_file.stat().st_size / (1024 * 1024)

        with jobs_lock:
            jobs[job_id].update({
                "status":      "done",
                "progress":    100,
                "count":       len(saved_files),
                "main_file":   main_file.name,
                "save_folder": str(dest_folder),
                "size_mb":     round(size_mb, 1),
                # List of filenames (not full paths) for the app to display
                "files":       [f.name for f in saved_files],
            })

        log("✅", f"[{job_id}] {len(saved_files)} file(s) saved to {dest_folder}")

    except Exception as e:
        shutil.rmtree(str(tmp_path), ignore_errors=True)
        with jobs_lock:
            jobs[job_id].update({"status": "error", "error": str(e)})
        log("❌", f"[{job_id}] Exception: {e}")


# ── Routes ────────────────────────────────────────────────────────────────────

@app.route("/ping")
def ping():
    return jsonify({
        "status":         "ok",
        "service":        "Albo Downloader",
        "port":           PORT,
        "default_folder": str(DEFAULT_SAVE_FOLDER),
    })

@app.route("/info", methods=["POST"])
def get_info():
    data     = request.json or {}
    url      = data.get("url", "").strip()
    if not url:
        return jsonify({"error": "No URL provided"}), 400

    platform = get_platform(url)

    try:
        result = subprocess.run(
            [get_ytdlp(), *get_cookie_args(), "--dump-json", "--no-playlist", url],
            capture_output=True, text=True, timeout=30,
            encoding="utf-8", errors="replace")
        info = {}
        if result.returncode == 0 and result.stdout.strip():
            info = _json.loads(result.stdout.split("\n")[0])
    except Exception:
        info = {}

    return jsonify({
        "title":          info.get("title", ""),
        "duration":       info.get("duration", 0),
        "uploader":       info.get("uploader", ""),
        "platform":       platform,
        "show_subtitles": platform in SUBTITLE_PLATFORMS,
        "show_textonly":  platform in TEXTONLY_PLATFORMS,
        "show_photos":    platform in PHOTOS_PLATFORMS,
        "show_playlist":  "list=" in url and ("youtube.com" in url or "youtu.be" in url),
    })

@app.route("/folders", methods=["GET"])
def list_folders():
    """Return sensible folder choices for the app to display."""
    home = Path.home()
    folders = {
        "Downloads":         str(home / "Downloads"),
        "Albo Downloader":   str(home / "Downloads" / "Albo Downloader"),
        "Videos":            str(home / "Videos"),
        "Music":             str(home / "Music"),
        "Pictures":          str(home / "Pictures"),
        "Desktop":           str(home / "Desktop"),
    }
    return jsonify(folders)

@app.route("/download", methods=["POST"])
def start_download():
    data        = request.json or {}
    url         = data.get("url", "").strip()
    fmt         = data.get("format", "video")
    save_folder = data.get("save_folder", "")   # empty = use default

    if not url:
        return jsonify({"error": "No URL provided"}), 400

    job_id = str(uuid.uuid4())[:8]
    with jobs_lock:
        jobs[job_id] = {"status": "queued", "progress": 0, "url": url, "format": fmt}

    threading.Thread(
        target=run_download,
        args=(job_id, url, fmt, save_folder),
        daemon=True
    ).start()

    log("📥", f"[{job_id}] Queued {fmt} → {save_folder or 'default'}")
    return jsonify({"job_id": job_id})

@app.route("/status/<job_id>")
def job_status(job_id):
    with jobs_lock:
        job = jobs.get(job_id)
    if not job:
        return jsonify({"error": "Job not found"}), 404
    return jsonify(job)

@app.route("/jobs")
def list_jobs():
    with jobs_lock: return jsonify(dict(jobs))


if __name__ == "__main__":
    import sys
    if sys.platform == "win32":
        os.system("")
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    print("\n" + "=" * 56)
    print("  🎬  Albo Downloader Server")
    print(f"  Running on    : http://0.0.0.0:{PORT}")
    print(f"  Android app   : http://192.168.1.200:{PORT}")
    print(f"  Default save  : {DEFAULT_SAVE_FOLDER}")
    print(f"  Cookies       : {'✅ Found' if COOKIES_FILE.exists() else '❌ Not found'}")
    print(f"  yt-dlp        : {get_ytdlp()}")
    gdl = get_gallery_dl()
    print(f"  gallery-dl    : {gdl if gdl else '❌ Not installed (pip install gallery-dl)'}")
    print("=" * 56 + "\n")

    app.run(host="0.0.0.0", port=PORT, debug=False, threaded=True)
