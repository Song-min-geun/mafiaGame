#!/usr/bin/env bash
set -euo pipefail

CORE_MAXMEMORY="${CORE_MAXMEMORY:-800mb}"
SUPPORT_MAXMEMORY="${SUPPORT_MAXMEMORY:-200mb}"
COMPOSE=(docker compose)

usage() {
  cat <<'USAGE'
Usage: ops/redis/restore-split.sh

Restores the normal Core/Aux Redis split by starting support-redis and setting
Core Redis to 80% and support Redis to 20% of the local Redis memory budget.
This script does not flush data.

Environment:
  CORE_MAXMEMORY     Core Redis maxmemory after restore. Default: 800mb.
  SUPPORT_MAXMEMORY  Support Redis maxmemory after restore. Default: 200mb.
USAGE
}

case "${1:-}" in
  -h|--help)
    usage
    exit 0
    ;;
  "")
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

cd "$(dirname "$0")/../.."

echo "Starting support-redis..."
"${COMPOSE[@]}" up -d support-redis

echo "Setting core-redis-master maxmemory to ${CORE_MAXMEMORY}..."
"${COMPOSE[@]}" exec -T core-redis-master redis-cli CONFIG SET maxmemory "${CORE_MAXMEMORY}"

if "${COMPOSE[@]}" ps --status running --services | grep -qx 'core-redis-replica'; then
  echo "Setting core-redis-replica maxmemory to ${CORE_MAXMEMORY}..."
  "${COMPOSE[@]}" exec -T core-redis-replica redis-cli CONFIG SET maxmemory "${CORE_MAXMEMORY}"
else
  echo "core-redis-replica is not running; skipped replica maxmemory update."
fi

echo "Setting support-redis maxmemory to ${SUPPORT_MAXMEMORY}..."
"${COMPOSE[@]}" exec -T support-redis redis-cli CONFIG SET maxmemory "${SUPPORT_MAXMEMORY}"

echo
echo "Current Redis memory limits:"
"${COMPOSE[@]}" exec -T core-redis-master redis-cli CONFIG GET maxmemory
if "${COMPOSE[@]}" ps --status running --services | grep -qx 'core-redis-replica'; then
  "${COMPOSE[@]}" exec -T core-redis-replica redis-cli CONFIG GET maxmemory
fi
"${COMPOSE[@]}" exec -T support-redis redis-cli CONFIG GET maxmemory
