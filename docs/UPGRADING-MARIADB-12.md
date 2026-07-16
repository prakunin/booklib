# Upgrading to MariaDB 12.3.2

Grimmory now runs MariaDB **12.3.2 LTS** on the **official `mariadb` image**, replacing
`lscr.io/linuxserver/mariadb` 11.4.

This is a breaking change for existing Docker Compose and podman deployments. The two images store
their data in different places, so **an existing database is not picked up automatically**:

|                | linuxserver (old)    | official (new)       |
| -------------- | -------------------- | -------------------- |
| Data directory | `/config/databases`  | `/var/lib/mysql`     |
| Ownership      | `PUID` / `PGID`      | runs as `mysql`      |
| Custom config  | `/config/custom.cnf` | `/etc/mysql/conf.d/` |

If you upgrade without migrating, the new image finds an empty data directory, initialises a fresh
database, and Grimmory greets you with a first-run setup screen. **Your old data is not deleted** —
the shipped configs deliberately point at a new volume/path so the old one stays intact — but you
must move it across yourself.

Your passwords and database name are unaffected: the official image accepts the same
`MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `MYSQL_USER`, and `MYSQL_PASSWORD` variables.

## Before you start

**Know your database name.** The commands below use `$MYSQL_DATABASE`, read from inside the
container so it always matches your deployment. This repository's samples do not agree on the value
— `deploy/compose/docker-compose.yml` uses `grimmory`, the README `.env` sample uses `booklib` — so
never hardcode it. To see yours:

```bash
docker compose exec mariadb sh -c 'echo "$MYSQL_DATABASE"'
```

**Know where your old data actually lives.** The samples disagree here too. Find your current
linuxserver mapping (the host side of `:/config`) before going further:

| If your compose says | your data is at            |
| -------------------- | -------------------------- |
| `./config:/config`         | `./config/databases`         |
| `./mariadb/config:/config` | `./mariadb/config/databases` |

Everything below writes `<OLD>` for that host path. Substitute your own.

**Back up first.** Both paths assume you can stop the stack.

The commands wrap the password in `sh -c '...'` with single quotes on purpose:
`$MYSQL_ROOT_PASSWORD` lives in the container's environment, not your shell's. Writing it unquoted
would expand to an empty string on the host and pass a bare `-p`.

## Path 1 — dump and restore (recommended)

Works regardless of layout differences, ownership, or the version jump. Downtime scales with
database size.

### Docker Compose

1. With the **old** stack still running, dump the database:

   ```bash
   docker compose exec -T mariadb sh -c \
     'exec mariadb-dump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction \
        --routines --triggers --events "$MYSQL_DATABASE"' > grimmory-backup.sql
   ```

   Confirm it is real before going further — it should end with a `Dump completed` line:

   ```bash
   ls -lh grimmory-backup.sql && tail -1 grimmory-backup.sql
   ```

2. Stop the stack:

   ```bash
   docker compose down
   ```

3. Update your `docker-compose.yml` to the new image and datadir:

   ```yaml
   mariadb:
     image: mariadb:12.3.2
     volumes:
       - ./mariadb/data:/var/lib/mysql
   ```

   Remove the `PUID` and `PGID` variables — the official image ignores them. Point the volume at a
   **new, empty** directory. Do not reuse `<OLD>`, and do not pick a directory that contains it:
   mounting a parent of your old tree puts the stale data inside the live datadir.

4. Start **only** the database, so the app cannot boot against the empty datadir and migrate it:

   ```bash
   docker compose up -d mariadb
   ```

5. Restore:

   ```bash
   docker compose exec -T mariadb sh -c \
     'exec mariadb -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < grimmory-backup.sql
   ```

6. Start the rest of the stack:

   ```bash
   docker compose up -d
   ```

### podman (quadlet)

The shipped units now mount a **new** volume, `grimmory-db-data`. Your existing `grimmory-db` volume
is left alone, which is what makes this recoverable — but it also means a plain
`systemctl --user daemon-reload` gives you an empty library until you migrate.

1. With the **old** unit still running, dump the database:

   ```bash
   podman exec grimmory-db sh -c \
     'exec mariadb-dump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction \
        --routines --triggers --events "$MYSQL_DATABASE"' > grimmory-backup.sql
   ```

   ```bash
   ls -lh grimmory-backup.sql && tail -1 grimmory-backup.sql
   ```

2. Stop the pod:

   ```bash
   systemctl --user stop grimmory-pod.service
   ```

3. Install the new unit files and reload. `grimmory-db.volume` is gone from this repository — it
   declared the old `grimmory-db` volume, which nothing mounts any more. Deleting your copy keeps
   `systemctl` from managing a volume the pod no longer uses; your data in it is untouched either
   way.

   ```bash
   cp ./*grimmory* ~/.config/containers/systemd/
   rm -f ~/.config/containers/systemd/grimmory-db.volume
   systemctl --user daemon-reload
   ```

4. Start **only** the database, so Grimmory cannot boot against the empty datadir and migrate it.
   Then wait for it to actually become healthy — `podman healthcheck run` probes once and exits, so
   poll it:

   ```bash
   systemctl --user start grimmory-db.service
   until podman healthcheck run grimmory-db >/dev/null 2>&1; do sleep 2; done
   ```

5. Restore:

   ```bash
   podman exec -i grimmory-db sh -c \
     'exec mariadb -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < grimmory-backup.sql
   ```

6. Start the rest of the pod:

   ```bash
   systemctl --user start grimmory-pod.service
   ```

Once you have verified the library, remove the old volume with `podman volume rm grimmory-db`.

### Helm

The bundled chart moves from 12.2.2 to 12.3.2, which is also a major jump across an existing PVC.
The chart does not run `mariadb-upgrade` for you. Either take a logical dump and restore it into a
fresh PVC (the equivalent of Path 1), or set `MARIADB_AUTO_UPGRADE=1` on the MariaDB pod for one
release so the entrypoint upgrades the system tables in place — see Path 2 for what that flag does
and why it is required.

## Path 2 — reuse the data directory in place (expert)

Faster and avoids downtime proportional to database size, but you are responsible for the details.
Use only if you understand the risks, and **only with a backup you have tested**.

The linuxserver image keeps the real datadir in `<OLD>` (see *Before you start*), so it can be
handed to the official image directly. Substitute your own path — the example uses the
`deploy/compose` layout:

```yaml
mariadb:
  image: mariadb:12.3.2
  environment:
    - MARIADB_AUTO_UPGRADE=1
  volumes:
    - ./config/databases:/var/lib/mysql   # <OLD>/databases
```

If the host path is wrong, Docker silently creates an empty directory for it and you get a fresh
database rather than an error. Check before starting:

```bash
ls <OLD>/databases/mysql   # must exist and be non-empty
```

`MARIADB_AUTO_UPGRADE=1` is **required, not optional**. The official image does not run
`mariadb-upgrade` on its own: without this variable it starts 12.3 straight onto your 11.4 system
tables and fails on the first privilege or metadata query with `Column count of mysql.<table> is
wrong ... Please use mariadb-upgrade to fix this error`.

Be aware that this jumps from 11.4 straight to 12.3, skipping 11.8, 12.0, 12.1 and 12.2. MariaDB's
upgrade notes are written around stepping through releases, and this combination is not something
this project tests. If your library matters to you, prefer Path 1 — it has a dump to fall back on
and this path does not.

Caveats:

- **Ownership is handled for you — as long as the container starts as root.** linuxserver wrote
  those files as your `PUID`/`PGID` (typically `1000:1000`). The official entrypoint starts as root,
  chowns anything under the datadir not already owned by `mysql`, and only then drops to the `mysql`
  user, so a linuxserver-owned tree is adopted automatically.

  This breaks if you pin a `user:` in your compose file: the entrypoint can no longer chown and the
  server fails with permission errors. If you must run as a fixed user, chown the tree yourself
  first. Do **not** assume the uid is `999` — the image creates its account with `useradd -r`, which
  allocates the id dynamically, so read it from the image:

  ```bash
  uid=$(docker run --rm mariadb:12.3.2 id -u mysql)
  gid=$(docker run --rm mariadb:12.3.2 id -g mysql)
  sudo chown -R "$uid:$gid" <OLD>/databases
  ```

- **Your `custom.cnf` is not carried over.** Anything you set in `<OLD>/custom.cnf` must be moved
  into `/etc/mysql/conf.d/` as a mounted file.
- **podman named volumes.** If your data lives in the `grimmory-db` volume rather than a bind mount,
  this path does not apply — the volume root is `/config`, not the datadir. Use Path 1.

## Verifying

```bash
docker compose exec mariadb sh -c \
  'exec mariadb -u root -p"$MYSQL_ROOT_PASSWORD" -e \
     "SELECT VERSION(); SELECT COUNT(*) FROM $MYSQL_DATABASE.book;"'
```

Expect a `12.3.2-MariaDB` version string and your real book count.
