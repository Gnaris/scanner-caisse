"""
Scanner Listener — service Windows pour recevoir les exports CSV / JSON / Sage
depuis l'app Scanner Caisse (Android).

- Serveur HTTP sur un port configurable (défaut 8080), endpoint POST /export
- Icône dans la zone de notification (systray)
- Fenêtre de paramètres (nom entreprise, dossier d'export, port, autostart)
- Démarrage automatique via la clé registre HKCU\\...\\Run
- Mise à jour auto depuis GitHub Releases (vérification silencieuse au
  démarrage, téléchargement en tâche de fond, l'utilisateur voit juste
  « Redémarrer et installer v… » dans le menu tray quand c'est prêt)

Config dans %APPDATA%\\ScannerListener\\config.json.
Logs dans %APPDATA%\\ScannerListener\\listener.log.
"""

from __future__ import annotations

import json
import logging
import os
import re
import socket
import subprocess
import sys
import tempfile
import threading
import tkinter as tk
import urllib.error
import urllib.request
import winreg
from datetime import datetime
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from typing import Optional
from urllib.parse import parse_qs, urlparse

import pystray
from PIL import Image, ImageDraw


# ========== Version & dépôt ==========
# APP_VERSION est remplacé par le workflow GitHub Actions avant build :
#   (Get-Content listener.py) -replace 'APP_VERSION = "0.0.0-dev"', ...
# Le suffixe `-dev` désactive l'updater côté dev local.
APP_VERSION = "0.0.0-dev"
GITHUB_REPO = "Gnaris/scanner-caisse"


APP_NAME = "ScannerListener"
APP_DISPLAY = "Scanner Listener"

# Port loopback dédié à la détection de 2e instance. Si une 2e instance est
# lancée (double-clic sur l'exe/raccourci alors que l'app tourne déjà), elle
# ne peut pas bind ce port → sait qu'une instance existe → envoie "SHOW"
# qui fait apparaître la fenêtre Paramètres de l'instance en cours → puis quit.
SINGLE_INSTANCE_PORT = 54327
REGISTRY_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
DEFAULT_PORT = 8080
DEFAULT_OUT_SUBDIR = "scanner-exports"

CONFIG_DIR = Path(os.environ.get("APPDATA", Path.home())) / APP_NAME
CONFIG_FILE = CONFIG_DIR / "config.json"
LOG_FILE = CONFIG_DIR / "listener.log"


# ========== Config ==========

def default_output_dir() -> str:
    return str(Path.home() / "Documents" / DEFAULT_OUT_SUBDIR)


def _default_cfg() -> dict:
    return {
        "port": DEFAULT_PORT,
        "output_dir": default_output_dir(),
        "company": "",
        "autostart": False,
        # Mémorise la version précédente pour détecter un auto-update et
        # ouvrir la fenêtre Paramètres au premier démarrage post-update.
        "last_known_version": "",
    }


def load_config() -> dict:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    if not CONFIG_FILE.exists():
        cfg = _default_cfg()
        save_config(cfg)
        return cfg
    try:
        existing = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return _default_cfg()
    merged = _default_cfg()
    merged.update(existing)
    return merged


def save_config(cfg: dict) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_FILE.write_text(json.dumps(cfg, indent=2, ensure_ascii=False), encoding="utf-8")


# ========== Autostart (clé registre) ==========

def _current_exe_command() -> str:
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}"'
    return f'"{sys.executable}" "{Path(__file__).resolve()}"'


def set_autostart(enabled: bool) -> None:
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, REGISTRY_KEY, 0, winreg.KEY_SET_VALUE) as key:
        if enabled:
            winreg.SetValueEx(key, APP_NAME, 0, winreg.REG_SZ, _current_exe_command())
        else:
            try:
                winreg.DeleteValue(key, APP_NAME)
            except FileNotFoundError:
                pass


def get_autostart() -> bool:
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, REGISTRY_KEY, 0, winreg.KEY_READ) as key:
            winreg.QueryValueEx(key, APP_NAME)
            return True
    except FileNotFoundError:
        return False


# ========== Serveur HTTP ==========

def _sanitize_filename(name: str) -> str:
    name = os.path.basename(name)
    bad = '<>:"/\\|?*\0'
    cleaned = "".join("_" if c in bad else c for c in name).strip()
    if not cleaned:
        cleaned = f"export-{datetime.now():%Y%m%d-%H%M%S}.csv"
    return cleaned[:200]


def write_export(cfg: dict, filename: str, body: bytes) -> Path:
    safe_name = _sanitize_filename(filename)
    out_dir = Path(cfg.get("output_dir", default_output_dir()))
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / safe_name
    target.write_bytes(body)
    logging.info("Reçu %s (%d octets) → %s", safe_name, len(body), target)
    return target


SAGEBRIDGE_URL = "http://127.0.0.1:8765/facture"
SAGEBRIDGE_TIMEOUT = 150  # SageBridge borne Sage à 120 s en interne


def relay_to_sagebridge(body: bytes) -> tuple[int, bytes, str]:
    """Relaie un POST JSON vers SageBridge (loopback). Renvoie
    (status, body, content_type). Les erreurs réseau sont converties en
    HTTP 502 avec un JSON lisible côté PDA — SageBridge n'étant pas joignable
    signifie « ouvre SageBridge sur ce PC »."""
    req = urllib.request.Request(
        SAGEBRIDGE_URL,
        data=body,
        method="POST",
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    try:
        with urllib.request.urlopen(req, timeout=SAGEBRIDGE_TIMEOUT) as r:
            data = r.read()
            ctype = r.headers.get("Content-Type", "application/json; charset=utf-8")
            return r.status, data, ctype
    except urllib.error.HTTPError as e:
        # SageBridge renvoie 409 (doublon), 500 (refus Sage), 400, 403, 413…
        # On transfère tel quel : le PDA sait lire { ok, error, numero } côté JSON.
        payload = e.read() or b""
        ctype = e.headers.get("Content-Type", "application/json; charset=utf-8") if e.headers else "application/json; charset=utf-8"
        return e.code, payload, ctype
    except urllib.error.URLError as e:
        logging.warning("Relais SageBridge échoué (%s)", e)
        msg = (
            '{"ok":false,"error":"SageBridge est injoignable sur ce PC. '
            'Ouvre SageBridge puis réessaie."}'
        )
        return 502, msg.encode("utf-8"), "application/json; charset=utf-8"
    except Exception as e:
        logging.warning("Relais SageBridge erreur (%s)", e)
        msg = '{"ok":false,"error":"Erreur de relais vers SageBridge."}'
        return 502, msg.encode("utf-8"), "application/json; charset=utf-8"


class ExportHandler(BaseHTTPRequestHandler):
    server_cfg: dict = {}

    def log_message(self, format, *args):  # noqa: A002
        logging.info("%s - %s", self.address_string(), format % args)

    def do_POST(self):  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        if path == "/sage-facture":
            return self._handle_sage_facture()
        if path not in ("/export", ""):
            self.send_response(404); self.end_headers(); return
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > 32 * 1024 * 1024:
            self.send_response(400); self.end_headers()
            self.wfile.write(b"Invalid payload size"); return
        body = self.rfile.read(length)
        raw_name = parse_qs(parsed.query).get("filename", ["export.csv"])[0]
        target = write_export(self.server_cfg, raw_name, body)
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(f"OK {target.name}".encode("utf-8"))

    def _handle_sage_facture(self) -> None:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > 2 * 1024 * 1024:
            self.send_response(400); self.end_headers()
            self.wfile.write(b'{"ok":false,"error":"Payload invalide."}')
            return
        body = self.rfile.read(length)
        logging.info("Relais → SageBridge (%d octets)", len(body))
        status, data, ctype = relay_to_sagebridge(body)
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class ListenerServer:
    def __init__(self) -> None:
        self._httpd: Optional[HTTPServer] = None
        self._thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()
        self.status: str = "arrêté"

    def start(self, cfg: dict) -> None:
        with self._lock:
            if self._httpd is not None:
                return
            class Bound(ExportHandler): pass
            Bound.server_cfg = cfg
            try:
                self._httpd = HTTPServer(("0.0.0.0", int(cfg["port"])), Bound)
            except OSError as e:
                self.status = f"erreur port {cfg['port']} : {e}"
                logging.error(self.status)
                return
            self._thread = threading.Thread(target=self._httpd.serve_forever, name="HTTPServer", daemon=True)
            self._thread.start()
            self.status = f"écoute HTTP sur 0.0.0.0:{cfg['port']}"
            logging.info(self.status)

    def stop(self) -> None:
        with self._lock:
            if self._httpd is None:
                return
            self._httpd.shutdown()
            self._httpd.server_close()
            self._httpd = None
            self._thread = None
            self.status = "arrêté"

    def restart(self, cfg: dict) -> None:
        self.stop()
        self.start(cfg)


# ========== Mise à jour auto ==========

class Updater:
    """Vérifie GitHub Releases, télécharge en tâche de fond, puis remplace
    l'exe au prochain démarrage via un petit script `.cmd` d'auto-remplacement."""

    def __init__(self) -> None:
        self.status: str = "idle"  # idle | checking | downloading | ready | error | uptodate
        self.latest_version: Optional[str] = None
        self.download_path: Optional[Path] = None
        self._on_change = []

    def on_change(self, cb) -> None:
        self._on_change.append(cb)

    def _emit(self) -> None:
        for cb in self._on_change:
            try: cb()
            except Exception as e: logging.warning("updater callback: %s", e)
        # Auto-apply : dès que l'update est prête, on l'installe seule au
        # bout de 3s. Zéro clic utilisateur requis.
        if self.status == "ready" and not getattr(self, "_auto_apply_scheduled", False):
            self._auto_apply_scheduled = True
            threading.Timer(3.0, self.apply_and_restart).start()
            logging.info("Updater : auto-install dans 3s…")

    def is_enabled(self) -> bool:
        """Updater désactivé en dev (APP_VERSION se termine par -dev) ou si
        l'app ne tourne pas depuis un bundle PyInstaller."""
        return (
            not APP_VERSION.endswith("-dev")
            and getattr(sys, "frozen", False)
            and "REPLACE_WITH" not in GITHUB_REPO
        )

    def check_in_background(self) -> None:
        if not self.is_enabled():
            logging.info("Updater désactivé (dev build ou repo non configuré).")
            return
        threading.Thread(target=self._check_and_download, name="Updater", daemon=True).start()

    def _check_and_download(self) -> None:
        self.status = "checking"; self._emit()
        # IMPORTANT : on NE PEUT PAS utiliser /releases/latest. Le repo contient
        # aussi les releases de l'app Android (tag "app-v*") qui s'intercalent
        # chronologiquement. /releases/latest renverrait alors un APK au lieu
        # d'un ZIP et l'updater tomberait en erreur. On liste donc toutes les
        # releases récentes et on filtre sur le préfixe de tag "pc-listener-v".
        try:
            url = f"https://api.github.com/repos/{GITHUB_REPO}/releases?per_page=20"
            req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json"})
            with urllib.request.urlopen(req, timeout=10) as r:
                all_releases = json.loads(r.read().decode("utf-8"))
        except urllib.error.URLError as e:
            logging.warning("Updater : check impossible (%s)", e)
            self.status = "error"; self._emit(); return
        except Exception as e:
            logging.warning("Updater : réponse invalide (%s)", e)
            self.status = "error"; self._emit(); return

        if not isinstance(all_releases, list) or not all_releases:
            logging.warning("Updater : aucune release trouvée.")
            self.status = "uptodate"; self._emit(); return

        # Garde uniquement les releases pc-listener (tag "pc-listener-vX.Y.Z"),
        # non draft, non prerelease, puis prend la plus haute version semver.
        candidates = []
        for rel in all_releases:
            if rel.get("draft") or rel.get("prerelease"):
                continue
            raw_tag = str(rel.get("tag_name", ""))
            if not raw_tag.startswith("pc-listener-v"):
                continue
            m = re.search(r"(\d+(?:\.\d+)+)", raw_tag)
            if m:
                candidates.append((m.group(1), rel))

        if not candidates:
            logging.warning("Updater : aucune release pc-listener-v* trouvée.")
            self.status = "uptodate"; self._emit(); return

        # Tri décroissant par version semver.
        candidates.sort(key=lambda t: [int(x) for x in t[0].split(".")], reverse=True)
        tag, data = candidates[0]

        if _version_cmp(tag, APP_VERSION) <= 0:
            self.status = "uptodate"
            logging.info("Updater : à jour (v%s, latest=%s).", APP_VERSION, tag)
            self._emit(); return
        logging.info("Updater : v%s dispo (local v%s), téléchargement…", tag, APP_VERSION)

        # Chaque release contient 2 assets :
        #   - ScannerListener-X.Y.Z.zip        → onedir zippé (pour l'updater)
        #   - ScannerListener-Setup-X.Y.Z.exe  → installeur Inno Setup (1re install)
        # On veut le ZIP : extraction instantanée, pas d'anti-virus qui corrompt.
        assets = data.get("assets", [])
        zip_asset = next(
            (a for a in assets if str(a.get("name", "")).lower().endswith(".zip")),
            None,
        )
        if not zip_asset:
            logging.warning("Updater : aucun .zip trouvé dans la release.")
            self.status = "error"; self._emit(); return

        download_url = zip_asset["browser_download_url"]
        self.status = "downloading"; self.latest_version = tag; self._emit()
        try:
            tmp = Path(tempfile.gettempdir()) / f"ScannerListener-update-{tag}.zip"
            urllib.request.urlretrieve(download_url, tmp)
            _strip_mark_of_the_web(tmp)
            # Extrait le ZIP dans un dossier temp pour que le script bat ait
            # juste à faire un xcopy /e vers le dossier d'install.
            extract_dir = Path(tempfile.gettempdir()) / f"ScannerListener-update-{tag}"
            if extract_dir.exists():
                import shutil
                shutil.rmtree(extract_dir, ignore_errors=True)
            import zipfile
            with zipfile.ZipFile(tmp) as zf:
                zf.extractall(extract_dir)
            tmp.unlink(missing_ok=True)
            self.download_path = extract_dir
            self.status = "ready"
            logging.info("Updater : v%s extraite dans %s.", tag, extract_dir)
            self._emit()
        except Exception as e:
            logging.error("Updater : download/extract échoué (%s)", e)
            self.status = "error"; self._emit()

    def schedule_auto_apply(self, delay_s: float = 3.0) -> None:
        """Lance apply_and_restart dans X secondes dès que status==ready.
        Pensé pour l'auto-update complet sans intervention utilisateur :
        l'app détecte, télécharge, s'installe toute seule au bout de X secondes."""
        if self.status != "ready":
            return
        threading.Timer(delay_s, self.apply_and_restart).start()

    def apply_and_restart(self) -> None:
        """Avec --onedir, l'install est un DOSSIER. Le script bat :
         1. attend que l'app soit fermée (poll jusqu'à max 15 s)
         2. retire les MOTW sur le dossier source (fichiers venus d'Internet)
         3. xcopy le contenu du dossier d'update par-dessus l'install
         4. relance le launcher .exe
         5. logge tout dans %TEMP%\\ScannerListener-apply-update.log pour debug
        """
        if self.status != "ready" or self.download_path is None:
            return
        if not getattr(sys, "frozen", False):
            return
        current_exe = Path(sys.executable)
        install_dir = current_exe.parent
        update_dir = self.download_path
        exe_name = current_exe.name
        cmd_file = Path(tempfile.gettempdir()) / "ScannerListener-apply-update.cmd"
        log_file = Path(tempfile.gettempdir()) / "ScannerListener-apply-update.log"
        script = (
            "@echo off\r\n"
            f'set "LOG={log_file}"\r\n'
            'echo [%date% %time%] START >> "%LOG%"\r\n'
            f'echo From: {update_dir} >> "%LOG%"\r\n'
            f'echo To:   {install_dir} >> "%LOG%"\r\n'
            "timeout /t 2 /nobreak >nul\r\n"
            f'taskkill /im "{exe_name}" /f /t >> "%LOG%" 2>&1\r\n'
            'echo [%date% %time%] waitkill start >> "%LOG%"\r\n'
            "set /a __waits=0\r\n"
            ":waitkill\r\n"
            f'tasklist /fi "imagename eq {exe_name}" 2>nul | find /i "{exe_name}" >nul\r\n'
            "if errorlevel 1 goto killed\r\n"
            "set /a __waits+=1\r\n"
            "if %__waits% geq 15 goto killed\r\n"
            "timeout /t 1 /nobreak >nul\r\n"
            "goto waitkill\r\n"
            ":killed\r\n"
            'echo [%date% %time%] killed after %__waits% waits >> "%LOG%"\r\n'
            # Retire tous les MOTW du dossier source (défensif, déjà fait sur le ZIP)
            f'powershell -NoProfile -Command "Get-ChildItem -Recurse -LiteralPath \'{update_dir}\' | Unblock-File -ErrorAction SilentlyContinue" >> "%LOG%" 2>&1\r\n'
            'echo [%date% %time%] xcopy >> "%LOG%"\r\n'
            # /E = sous-dossiers (vides inclus) /I = assumer dest est dir
            # /H = fichiers cachés /Y = overwrite silencieux
            f'xcopy "{update_dir}\\*" "{install_dir}\\" /E /I /H /Y >> "%LOG%" 2>&1\r\n'
            "if errorlevel 1 (\r\n"
            '    echo XCOPY FAILED errorlevel %errorlevel% >> "%LOG%"\r\n'
            "    goto end\r\n"
            ")\r\n"
            'echo [%date% %time%] cleanup update dir >> "%LOG%"\r\n'
            f'rmdir /s /q "{update_dir}" >nul 2>&1\r\n'
            'echo [%date% %time%] launching >> "%LOG%"\r\n'
            f'start "" "{current_exe}"\r\n'
            ":end\r\n"
            'echo [%date% %time%] DONE >> "%LOG%"\r\n'
            'del "%~f0"\r\n'
        )
        cmd_file.write_text(script, encoding="utf-8")
        CREATE_NO_WINDOW = 0x08000000
        subprocess.Popen(
            ["cmd", "/c", str(cmd_file)],
            creationflags=CREATE_NO_WINDOW,
            close_fds=True,
        )
        os._exit(0)


def _strip_mark_of_the_web(path: Path) -> None:
    """Supprime l'ADS NTFS 'Zone.Identifier' que Windows attache aux fichiers
    téléchargés via HTTP. Sans ça, Windows Defender surveille le process au
    lancement et peut interrompre l'extraction PyInstaller du _MEI, causant
    'Failed to load Python DLL'."""
    ads = str(path) + ":Zone.Identifier"
    try:
        if os.path.exists(ads):
            os.remove(ads)
            logging.info("Updater : MOTW retiré sur %s", path.name)
    except OSError as e:
        logging.warning("Updater : impossible de retirer le MOTW (%s)", e)


def _version_cmp(a: str, b: str) -> int:
    """Compare 2 versions `x.y.z[-suffix]`. Suffixe ignoré."""
    def parts(s: str):
        base = s.split("-", 1)[0]
        return [int(x) for x in base.split(".") if x.isdigit()]
    pa, pb = parts(a), parts(b)
    for x, y in zip(pa, pb):
        if x != y: return 1 if x > y else -1
    return (len(pa) > len(pb)) - (len(pa) < len(pb))


# ========== Single-instance detection ==========

def try_single_instance_lock() -> Optional[socket.socket]:
    """Essaie de bind le port loopback de single-instance.
    Retourne la socket serveur si on est unique, sinon None (2e instance :
    on a envoyé SHOW à la 1re, on peut quitter)."""
    try:
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 0)
        srv.bind(("127.0.0.1", SINGLE_INSTANCE_PORT))
        srv.listen(5)
        return srv
    except OSError:
        # Port pris → une instance tourne déjà. On lui demande d'afficher
        # la fenêtre Paramètres puis on quitte.
        try:
            with socket.create_connection(("127.0.0.1", SINGLE_INSTANCE_PORT), timeout=1) as c:
                c.sendall(b"SHOW\n")
            logging.info("Déjà lancé — SHOW envoyé à l'instance en cours, on quitte.")
        except OSError as e:
            logging.warning("2e instance détectée mais contact impossible : %s", e)
        return None


def listen_for_show_requests(srv: socket.socket, on_show) -> None:
    """Thread daemon : accepte les connections sur le port single-instance et
    déclenche on_show quand il reçoit 'SHOW'."""
    def _loop():
        while True:
            try:
                conn, _ = srv.accept()
                try:
                    data = conn.recv(16)
                    if data.strip() == b"SHOW":
                        on_show()
                finally:
                    try: conn.close()
                    except OSError: pass
            except OSError as e:
                logging.warning("single-instance accept: %s", e)
                break
    threading.Thread(target=_loop, name="SingleInstance", daemon=True).start()


# ========== UI paramètres (Tkinter) ==========

def local_ips() -> list[str]:
    ips: set[str] = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None):
            addr = info[4][0]
            if ":" in addr or addr.startswith("127."): continue
            ips.add(addr)
    except OSError:
        pass
    return sorted(ips)


class SettingsWindow:
    def __init__(self, cfg: dict, on_save, open_output_dir, updater: Updater, server: ListenerServer) -> None:
        self.cfg = cfg
        self.on_save = on_save
        self.open_output_dir = open_output_dir
        self.updater = updater
        self.server = server

        self.root = tk.Tk()
        self.root.title(f"{APP_DISPLAY} — paramètres")
        self.root.resizable(False, False)
        self.root.protocol("WM_DELETE_WINDOW", self.root.withdraw)

        pad = {"padx": 10, "pady": 6}
        row = 0

        ttk.Label(self.root, text=f"{APP_DISPLAY}  v{APP_VERSION}", font=("", 10, "bold")).grid(
            row=row, column=0, columnspan=2, sticky="w", **pad)
        row += 1
        ttk.Label(self.root, text="✓ Auto-update OK — onedir + GitHub Releases 🚀",
                  foreground="#15803D").grid(
            row=row, column=0, columnspan=2, sticky="w", padx=10)
        row += 1

        ttk.Label(self.root, text="Entreprise (étiquette)").grid(row=row, column=0, sticky="w", **pad)
        self.company_var = tk.StringVar(value=cfg.get("company", ""))
        ttk.Entry(self.root, textvariable=self.company_var, width=38).grid(row=row, column=1, sticky="w", **pad); row += 1

        ttk.Label(self.root, text="Port d'écoute HTTP").grid(row=row, column=0, sticky="w", **pad)
        self.port_var = tk.StringVar(value=str(cfg.get("port", DEFAULT_PORT)))
        ttk.Entry(self.root, textvariable=self.port_var, width=10).grid(row=row, column=1, sticky="w", **pad); row += 1

        ttk.Label(self.root, text="Dossier d'export").grid(row=row, column=0, sticky="w", **pad)
        self.outdir_var = tk.StringVar(value=cfg.get("output_dir", default_output_dir()))
        frame_out = ttk.Frame(self.root)
        frame_out.grid(row=row, column=1, sticky="w", **pad)
        ttk.Entry(frame_out, textvariable=self.outdir_var, width=32).pack(side="left")
        ttk.Button(frame_out, text="…", width=3, command=self._choose_dir).pack(side="left", padx=4)
        row += 1

        self.autostart_var = tk.BooleanVar(value=cfg.get("autostart", False))
        ttk.Checkbutton(self.root, text="Démarrer automatiquement avec Windows",
                        variable=self.autostart_var).grid(row=row, column=0, columnspan=2, sticky="w", **pad); row += 1

        ips = local_ips()
        if ips:
            ttk.Label(self.root, text="URL à coller sur le PDA").grid(row=row, column=0, sticky="nw", **pad)
            ips_text = "\n".join(f"http://{ip}:{cfg.get('port', DEFAULT_PORT)}/export" for ip in ips)
            ttk.Label(self.root, text=ips_text, foreground="#4F46E5",
                      font=("Consolas", 9)).grid(row=row, column=1, sticky="w", **pad); row += 1

        ttk.Separator(self.root, orient="horizontal").grid(row=row, column=0, columnspan=2, sticky="we", padx=10, pady=4); row += 1

        ttk.Label(self.root, text="Mise à jour").grid(row=row, column=0, sticky="w", **pad)
        self.update_status_var = tk.StringVar(value="…")
        ttk.Label(self.root, textvariable=self.update_status_var, foreground="#6B7280").grid(
            row=row, column=1, sticky="w", **pad); row += 1

        self.update_buttons_frame = ttk.Frame(self.root)
        self.update_buttons_frame.grid(row=row, column=0, columnspan=2, sticky="we", padx=10, pady=4); row += 1
        self._rebuild_update_buttons()

        self.status_var = tk.StringVar(value="")
        ttk.Label(self.root, textvariable=self.status_var, foreground="#15803D").grid(
            row=row, column=0, columnspan=2, sticky="w", **pad); row += 1

        actions = ttk.Frame(self.root)
        actions.grid(row=row, column=0, columnspan=2, sticky="we", padx=10, pady=10)
        ttk.Button(actions, text="Ouvrir le dossier", command=self.open_output_dir).pack(side="left")
        ttk.Button(actions, text="Fermer", command=self.root.withdraw).pack(side="right")
        ttk.Button(actions, text="Enregistrer", command=self._save).pack(side="right", padx=6)

        self.updater.on_change(lambda: self.root.after(0, self._refresh_update_ui))
        self._refresh_update_ui()
        self.root.withdraw()

    def _choose_dir(self) -> None:
        chosen = filedialog.askdirectory(initialdir=self.outdir_var.get() or str(Path.home()))
        if chosen: self.outdir_var.set(chosen)

    def _save(self) -> None:
        try:
            port = int(self.port_var.get())
            if not (1 <= port <= 65535): raise ValueError
        except ValueError:
            messagebox.showerror(APP_DISPLAY, "Port invalide (1-65535)"); return
        new_cfg = {
            "company": self.company_var.get().strip(),
            "port": port,
            "output_dir": self.outdir_var.get().strip() or default_output_dir(),
            "autostart": bool(self.autostart_var.get()),
        }
        self.on_save(new_cfg)
        self.status_var.set("Enregistré ✓  —  serveur redémarré")
        self.root.after(2500, lambda: self.status_var.set(""))

    def _refresh_update_ui(self) -> None:
        s = self.updater.status
        if not self.updater.is_enabled():
            self.update_status_var.set("Désactivé (dev build)")
        elif s == "idle":
            self.update_status_var.set("Pas encore vérifié")
        elif s == "checking":
            self.update_status_var.set("Vérification en cours…")
        elif s == "downloading":
            self.update_status_var.set(f"Téléchargement de la v{self.updater.latest_version}…")
        elif s == "ready":
            self.update_status_var.set(f"v{self.updater.latest_version} prête — redémarre pour installer")
        elif s == "uptodate":
            self.update_status_var.set(f"À jour (v{APP_VERSION})")
        elif s == "error":
            self.update_status_var.set("Impossible de vérifier")
        self._rebuild_update_buttons()

    def _rebuild_update_buttons(self) -> None:
        for w in self.update_buttons_frame.winfo_children():
            w.destroy()
        if self.updater.status == "ready":
            ttk.Button(self.update_buttons_frame,
                       text=f"Redémarrer et installer v{self.updater.latest_version}",
                       command=self.updater.apply_and_restart).pack(side="left")
        else:
            ttk.Button(self.update_buttons_frame, text="Vérifier maintenant",
                       command=self.updater.check_in_background).pack(side="left")

    def show(self) -> None:
        self.root.deiconify(); self.root.lift(); self.root.focus_force()

    def run_mainloop(self) -> None: self.root.mainloop()
    def quit(self) -> None: self.root.quit()


# ========== Icône systray ==========

def tray_icon_image() -> Image.Image:
    size = 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([(4, 4), (size - 4, size - 4)], radius=10, fill=(79, 70, 229, 255))
    d.rectangle([(18, 14), (46, 20)], fill="white")
    d.rectangle([(18, 20), (24, 32)], fill="white")
    d.rectangle([(18, 28), (46, 34)], fill="white")
    d.rectangle([(40, 34), (46, 46)], fill="white")
    d.rectangle([(18, 42), (46, 48)], fill="white")
    return img


def build_tray(server: ListenerServer, settings: SettingsWindow, updater: Updater, open_output_dir) -> pystray.Icon:
    icon = pystray.Icon(APP_NAME, tray_icon_image(), APP_DISPLAY)

    def _settings(_i, _it): settings.root.after(0, settings.show)
    def _folder(_i, _it): open_output_dir()
    def _check(_i, _it): updater.check_in_background()
    def _install(_i, _it): updater.apply_and_restart()
    def _quit(_i, _it):
        server.stop(); _i.stop(); settings.root.after(0, settings.quit)

    def menu():
        items = [
            pystray.MenuItem("Paramètres…", _settings, default=True),
            pystray.MenuItem("Ouvrir le dossier d'export", _folder),
            pystray.Menu.SEPARATOR,
        ]
        if updater.status == "ready":
            items.append(pystray.MenuItem(f"Redémarrer et installer v{updater.latest_version}", _install))
        elif updater.is_enabled():
            items.append(pystray.MenuItem("Vérifier les mises à jour", _check))
        items += [pystray.Menu.SEPARATOR, pystray.MenuItem("Quitter", _quit)]
        return pystray.Menu(*items)

    icon.menu = menu()
    updater.on_change(lambda: setattr(icon, "menu", menu()) or icon.update_menu())
    return icon


# ========== Entrée principale ==========

def setup_logging() -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(
        filename=LOG_FILE, level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )


def open_output_dir_factory(cfg_holder: dict):
    def _open():
        out = Path(cfg_holder.get("output_dir", default_output_dir()))
        out.mkdir(parents=True, exist_ok=True)
        os.startfile(str(out))  # noqa: S606
    return _open


def main() -> None:
    setup_logging()

    # Avant toute init : check single-instance. Si une 2e instance est
    # lancée (double-clic sur l'exe/raccourci bureau/menu démarrer), on
    # affiche la fenêtre Paramètres de l'instance existante et on quitte.
    single_instance_socket = try_single_instance_lock()
    if single_instance_socket is None:
        return

    cfg = load_config()
    cfg["autostart"] = cfg.get("autostart", False) and get_autostart()
    if cfg["autostart"] and not get_autostart():
        set_autostart(True)

    # Détection auto-update : si la version a changé depuis la dernière
    # exécution, on ouvre la fenêtre Paramètres au lancement pour que
    # l'utilisateur voie clairement la nouvelle version.
    previous_version = cfg.get("last_known_version", "")
    just_updated = previous_version and previous_version != APP_VERSION
    cfg["last_known_version"] = APP_VERSION
    save_config(cfg)

    server = ListenerServer()
    updater = Updater()

    def on_save(new_cfg: dict) -> None:
        cfg.update(new_cfg)
        save_config(cfg)
        set_autostart(cfg["autostart"])
        server.restart(cfg)

    open_output_dir = open_output_dir_factory(cfg)
    settings = SettingsWindow(cfg, on_save, open_output_dir, updater, server)

    server.start(cfg)

    # Démarre le listener single-instance : quand un `SHOW` arrive sur le
    # port 54327 (= 2e instance lancée), on affiche la fenêtre.
    listen_for_show_requests(
        single_instance_socket,
        on_show=lambda: settings.root.after(0, settings.show),
    )

    icon = build_tray(server, settings, updater, open_output_dir)
    threading.Thread(target=icon.run, name="pystray", daemon=True).start()

    # Si on vient de se mettre à jour, afficher la fenêtre Paramètres pour
    # que l'utilisateur voie la nouvelle version apparaître à l'écran.
    if just_updated:
        logging.info("Détection auto-update %s → %s, ouverture Paramètres.",
                     previous_version, APP_VERSION)
        settings.root.after(500, settings.show)

    # Vérification silencieuse 4 secondes après démarrage.
    threading.Timer(4.0, updater.check_in_background).start()

    try:
        settings.run_mainloop()
    finally:
        server.stop(); icon.stop()
        try: single_instance_socket.close()
        except OSError: pass


if __name__ == "__main__":
    main()
