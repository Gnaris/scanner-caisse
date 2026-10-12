# Scanner Listener — service Windows

Petit logiciel système qui tourne dans la zone de notification de Windows et
réceptionne les exports (CSV / JSON / Sage 50) envoyés par l'app Scanner Caisse
(Android) via Wi-Fi.

- **Mise à jour auto** depuis GitHub Releases (silencieuse au démarrage,
  l'utilisateur voit juste « Redémarrer et installer v… » quand c'est prêt)
- **Démarrage auto** avec Windows (option)
- Multi-poste : chaque PC a sa config dans `%APPDATA%\ScannerListener\config.json`

## Installation (pour l'utilisateur final)

1. Récupère `ScannerListener-Setup-x.y.z.exe` sur la page [Releases](../../releases).
2. Double-clic. Installeur dans `%LOCALAPPDATA%\ScannerListener` (pas d'admin).
3. Coche « Démarrer avec Windows ».
4. L'icône bleue "S" apparaît en bas à droite près de l'horloge.
5. Clic → **Paramètres** : nom entreprise, port, dossier d'export.
6. Note l'URL en bleu (ex. `http://192.168.1.42:8080/export`) → à coller dans
   l'app PDA → Paramètres → "URL export PC".

Dès qu'une nouvelle version est publiée sur GitHub, l'app la télécharge en
tâche de fond. Au prochain démarrage (ou via Paramètres → "Redémarrer et
installer"), elle s'applique en ~2 secondes.

## Pour le dev : publier une nouvelle version

Le workflow CI est au niveau du repo : `.github/workflows/pc-listener-release.yml`.

1. Bump les dépendances si nécessaire, commit.
2. Tag : `git tag pc-listener-v1.2.3 && git push origin pc-listener-v1.2.3`
3. Attendre ~3 min que le workflow build et publie la release.
4. Vérifier : page Releases du repo. L'asset doit s'appeler
   `ScannerListener-1.2.3.exe` et finir par `.exe` (sinon l'updater ne détecte pas).

Les installations existantes se mettront à jour au prochain démarrage ou lors
d'un check manuel via le menu tray.

**Avant le premier release**, remplace dans `listener.py` :

```python
GITHUB_REPO = "REPLACE_WITH_OWNER/REPLACE_WITH_REPO"
```

par ton `owner/repo`. Le workflow CI le fera automatiquement au build mais il
faut aussi mettre à jour la copie **locale** si tu testes l'updater en dev
(bien que `is_enabled()` le désactive sauf en build frozen).

## Build local (sans CI)

Prérequis :
- Python 3.10+ sur le PATH
- [Inno Setup 6](https://jrsoftware.org/isdl.php) (facultatif, pour l'installeur)

```powershell
cd pc-listener
.\build.ps1                 # exe + installeur
.\build.ps1 -NoInstaller    # juste l'exe
.\build.ps1 -Version 1.2.3  # injecte la version dans l'exe
```

Produit :
- `dist\ScannerListener.exe` — exe autonome
- `dist\Installer\ScannerListener-Setup-1.0.0.exe` — installeur

## Développement sans build

```powershell
cd pc-listener
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python listener.py
```

L'updater est **désactivé** en dev (`APP_VERSION = "0.0.0-dev"` + pas frozen).

Logs : `%APPDATA%\ScannerListener\listener.log`.

## Protocole HTTP

```
POST /export?filename=<nom.csv>  HTTP/1.1
Content-Type: text/csv; charset=utf-8
Content-Length: <N>

<N octets>
```

Réponse `200 OK` avec `OK <nom-sanitize>`.

## Désinstallation

Panneau de configuration → Programmes → **Scanner Listener** → Désinstaller.
La config utilisateur (`%APPDATA%\ScannerListener\`) est conservée (décommente
`UninstallDelete` dans `installer.iss` pour tout effacer).

## Signature code

Pour que Windows SmartScreen ne hurle pas à l'installation chez les clients,
il faut un **certificat Authenticode** (~200 €/an chez Sectigo/DigiCert/
etc.). Non inclus pour l'instant — les premiers utilisateurs devront cliquer
"Infos complémentaires → Exécuter quand même" une seule fois.

Le workflow CI est prêt à signer : ajoute les secrets `WIN_CERT_PFX_BASE64` et
`WIN_CERT_PASSWORD`, puis une étape `signtool sign` avant `action-gh-release`.
