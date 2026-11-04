#!/usr/bin/env bash
# SmartEnergy Hub - moniteur du stream Redis en direct (decision D-21)
# Lecture seule : XREAD hors consumer group, aucun ACK, aucun impact sur
# pending des groupes storage/analytics (conforme D-02/D-05).
# Redecouvre stream.sh : exec se.sh stream.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 requis (monitor). Installez-le : sudo apt install python3" >&2
  exit 1
fi
exec python3 "$SCRIPT_DIR/stream-watch.py" "$@"
