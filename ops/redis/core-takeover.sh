#!/usr/bin/env bash
set -euo pipefail

CORE_MAXMEMORY="${CORE_MAXMEMORY:-1000mb}"
STOP_SUPPORT=1
COMPOSE=(docker compose)

usage() {
  cat <<'USAGE'
Usage: ops/redis/core-takeover.sh [--no-stop-support]

Expands Core Redis to the full local Redis memory budget and, by default,
stops the auxiliary support Redis container. This script does not flush data.

Options:
  --no-stop-support  Keep support-redis running when the application has
                     already bypassed auxiliary Redis traffic.

Environment:
  CORE_MAXMEMORY     Core Redis maxmemory during takeover. Default: 1000mb.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --no-stop-support)
      STOP_SUPPORT=0
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
  shift
done

cd "$(dirname "$0")/../.."

echo "Setting core-redis-master maxmemory to ${CORE_MAXMEMORY}..."
"${COMPOSE[@]}" exec -T core-redis-master redis-cli CONFIG SET maxmemory "${CORE_MAXMEMORY}"

if "${COMPOSE[@]}" ps --status running --services | grep -qx 'core-redis-replica'; then
  echo "Setting core-redis-replica maxmemory to ${CORE_MAXMEMORY}..."
  "${COMPOSE[@]}" exec -T core-redis-replica redis-cli CONFIG SET maxmemory "${CORE_MAXMEMORY}"
else
  echo "core-redis-replica is not running; skipped replica maxmemory update."
fi

if [ "${STOP_SUPPORT}" -eq 1 ]; then
  echo "Stopping support-redis to free auxiliary Redis capacity..."
  "${COMPOSE[@]}" stop support-redis
else
  echo "Leaving support-redis running; ensure application traffic is bypassing auxiliary Redis."
fi

echo
echo "Current Redis memory limits:"
"${COMPOSE[@]}" exec -T core-redis-master redis-cli CONFIG GET maxmemory
if "${COMPOSE[@]}" ps --status running --services | grep -qx 'core-redis-replica'; then
  "${COMPOSE[@]}" exec -T core-redis-replica redis-cli CONFIG GET maxmemory
fi
if "${COMPOSE[@]}" ps --status running --services | grep -qx 'support-redis'; then
  "${COMPOSE[@]}" exec -T support-redis redis-cli CONFIG GET maxmemory
else
  echo "support-redis stopped"
fi
