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


class ExportHandler(BaseHTTPRequestHandler):
    server_cfg: dict = {}

    def log_message(self, format, *args):  # noqa: A002
        logging.info("%s - %s", self.address_string(), format % args)

    def do_POST(self):  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path.rstrip("/") not in ("/export", ""):
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
        try:
            url = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"
            req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json"})
            with urllib.request.urlopen(req, timeout=10) as r:
                data = json.loads(r.read().decode("utf-8"))
        except urllib.error.URLError as e:
            logging.warning("Updater : check impossible (%s)", e)
            self.status = "error"; self._emit(); return
        except Exception as e:
            logging.warning("Updater : réponse invalide (%s)", e)
            self.status = "error"; self._emit(); return

        tag = str(data.get("tag_name", "")).lstrip("v").strip()
        if not tag:
            self.status = "uptodate"; self._emit(); return
        if _version_cmp(tag, APP_VERSION) <= 0:
            self.status = "uptodate"
            logging.info("Updater : à jour (v%s).", APP_VERSION)
            self._emit(); return

        exe_asset = next(
            (a for a in data.get("assets", []) if str(a.get("name", "")).lower().endswith(".exe")),
            None,
        )
        if not exe_asset:
            logging.warning("Updater : aucun .exe trouvé dans la release.")
            self.status = "error"; self._emit(); return

        download_url = exe_asset["browser_download_url"]
        self.status = "downloading"; self.latest_version = tag; self._emit()
        try:
            tmp = Path(tempfile.gettempdir()) / f"ScannerListener-update-{tag}.exe"
            urllib.request.urlretrieve(download_url, tmp)
            self.download_path = tmp
            self.status = "ready"
            logging.info("Updater : v%s téléchargée dans %s.", tag, tmp)
            self._emit()
        except Exception as e:
            logging.error("Updater : download échoué (%s)", e)
            self.status = "error"; self._emit()

    def apply_and_restart(self) -> None:
        """Lance un script batch qui attend 2s, remplace l'exe, puis relance."""
        if self.status != "ready" or self.download_path is None:
            return
        if not getattr(sys, "frozen", False):
            return  # devrait pas arriver (is_enabled filtre)
        current = Path(sys.executable)
        update = self.download_path
        cmd_file = Path(tempfile.gettempdir()) / "ScannerListener-apply-update.cmd"
        script = (
            "@echo off\r\n"
            "timeout /t 2 /nobreak >nul\r\n"
            f'move /y "{update}" "{current}"\r\n'
            f'start "" "{current}"\r\n'
            'del "%~f0"\r\n'
        )
        cmd_file.write_text(script, encoding="utf-8")
        CREATE_NO_WINDOW = 0x08000000
        subprocess.Popen(
            ["cmd", "/c", str(cmd_file)],
            creationflags=CREATE_NO_WINDOW,
            close_fds=True,
        )
        # Quit l'app : le batch prendra le relais pendant les 2 secondes.
        os._exit(0)


def _version_cmp(a: str, b: str) -> int:
    """Compare 2 versions `x.y.z[-suffix]`. Suffixe ignoré."""
    def parts(s: str):
        base = s.split("-", 1)[0]
        return [int(x) for x in base.split(".") if x.isdigit()]
    pa, pb = parts(a), parts(b)
    for x, y in zip(pa, pb):
        if x != y: return 1 if x > y else -1
    return (len(pa) > len(pb)) - (len(pa) < len(pb))


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
    cfg = load_config()
    cfg["autostart"] = cfg.get("autostart", False) and get_autostart()
    if cfg["autostart"] and not get_autostart():
        set_autostart(True)

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

    icon = build_tray(server, settings, updater, open_output_dir)
    threading.Thread(target=icon.run, name="pystray", daemon=True).start()

    # Vérification silencieuse 4 secondes après démarrage.
    threading.Timer(4.0, updater.check_in_background).start()

    try:
        settings.run_mainloop()
    finally:
        server.stop(); icon.stop()


if __name__ == "__main__":
    main()
