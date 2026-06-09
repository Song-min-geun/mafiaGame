# Redis Core Takeover Runbook

This runbook covers the emergency path for moving the local Redis memory budget
from the normal Core/Aux split to Core-only capacity.

Normal allocation:

- Core Redis: `800mb` (`core-redis-master`, `core-redis-replica`)
- Aux/support Redis: `200mb` (`support-redis`)

Takeover allocation:

- Core Redis: `1000mb`
- Aux/support Redis: stopped, or application traffic bypassed before it is left running

The scripts in this directory use Docker Compose and Redis `CONFIG SET`. They do
not run `FLUSHDB`, `FLUSHALL`, delete volumes, or remove containers.

## When to Use

Use takeover when Core Redis is under memory pressure and auxiliary Redis-backed
features can be temporarily disabled, bypassed, or allowed to degrade.

Check pressure before changing capacity:

```sh
docker compose exec -T core-redis-master redis-cli INFO memory
docker compose exec -T support-redis redis-cli INFO memory
docker compose ps
```

## Core Takeover

Default takeover expands Core Redis to `1000mb` and stops `support-redis`:

```sh
ops/redis/core-takeover.sh
```

If the application has already bypassed auxiliary Redis traffic and support
Redis must stay online, keep it running:

```sh
ops/redis/core-takeover.sh --no-stop-support
```

Expected effects:

- `core-redis-master` runtime `maxmemory` becomes `1000mb`.
- `core-redis-replica` runtime `maxmemory` becomes `1000mb` when it is running.
- `support-redis` is stopped unless `--no-stop-support` is used.
- Existing Redis data is preserved.

Validate after takeover:

```sh
docker compose exec -T core-redis-master redis-cli CONFIG GET maxmemory
docker compose exec -T core-redis-master redis-cli INFO memory
docker compose ps support-redis
```

Application note: if `support-redis` is stopped, any code path that requires it
must be disabled, bypassed, or expected to fail closed until split mode is
restored.

## Restore Normal Split

Restore the normal 80/20 split:

```sh
ops/redis/restore-split.sh
```

Expected effects:

- `support-redis` is started.
- `core-redis-master` runtime `maxmemory` returns to `800mb`.
- `core-redis-replica` runtime `maxmemory` returns to `800mb` when it is running.
- `support-redis` runtime `maxmemory` is set to `200mb`.
- Existing Redis data is preserved.

Validate after restore:

```sh
docker compose exec -T core-redis-master redis-cli CONFIG GET maxmemory
docker compose exec -T support-redis redis-cli CONFIG GET maxmemory
docker compose ps
```

## Notes and Rollback

- These changes are runtime changes. The checked-in Redis config files remain at
  the normal split (`800mb` Core, `200mb` support), so restarting Redis
  containers also returns memory limits to the normal config.
- If takeover worsens application behavior, run `ops/redis/restore-split.sh`.
- Do not run destructive Redis or Docker volume commands as part of this
  procedure.
