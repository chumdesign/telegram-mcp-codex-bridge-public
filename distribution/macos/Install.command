#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v python3 >/dev/null 2>&1; then
  echo 'Установите Python 3.11+ для Apple Silicon, затем запустите Install.command снова.'
  exit 1
fi
python3 -c 'import sys; assert sys.version_info >= (3,11), "Требуется Python 3.11+"'
python3 bridge.py setup --register-codex "$@"
echo 'Откройте README.md и выполните команду auth, показанную установщиком.'
read -r -p 'Нажмите Enter для закрытия окна. '
