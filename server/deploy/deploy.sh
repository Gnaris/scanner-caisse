#!/usr/bin/env bash
# Déploie le serveur de synchro sur le VPS depuis la racine de server/ :
#   bash deploy/deploy.sh
# N'affecte que le process PM2 « scanner-caisse » (jamais « beliandjolie »).
set -euo pipefail
HOST=root@72.61.106.128
DIR=/var/www/scanner-caisse
tar --exclude=node_modules --exclude=.env -czf - package.json package-lock.json .env.example src test deploy \
  | ssh -4 "$HOST" "cd $DIR && tar xzf - && npm ci --omit=dev --no-audit --no-fund && npm test && npm run migrate && pm2 startOrReload deploy/ecosystem.config.cjs && pm2 save"
ssh -4 "$HOST" "sleep 2 && curl -fsS http://127.0.0.1:3100/v1/health"
echo
