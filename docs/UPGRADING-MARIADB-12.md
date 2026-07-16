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

**Know your database name.** The commands below use `$MYSQL_DATABASE`. This repository's samples do
not agree on it — `deploy/compose/docker-compose.yml` uses `grimmory`, while the README `.env` sample
uses `booklib` — so substitute the value **your** deployment actually uses. If you are unsure:

```bash
docker compose exec mariadb sh -c 'echo "$MYSQL_DATABASE"'
```

**Back up first.** Both paths assume you can stop the stack.

Note that the commands wrap the password in `sh -c '...'` with single quotes. This is deliberate:
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
   **new, empty** directory. Do not reuse the old linuxserver path, and do not pick a directory that
   contains it: mounting a parent of your old tree puts the stale data inside the live datadir.

4. Start **only** the database so it initialises the new datadir:

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

3. Install the new unit files (including the new `grimmory-db-data.volume`) and reload. The old
   `grimmory-db.volume` unit is gone from this repository, so delete your copy — otherwise it keeps
   recreating an empty `grimmory-db` volume:

   ```bash
   cp ./*grimmory* ~/.config/containers/systemd/
   rm -f ~/.config/containers/systemd/grimmory-db.volume
   systemctl --user daemon-reload
   ```

4. Start the pod so the database initialises its new volume, and wait for it to report healthy:

   ```bash
   systemctl --user start grimmory-pod.service
   podman healthcheck run grimmory-db
   ```

5. Restore:

   ```bash
   podman exec -i grimmory-db sh -c \
     'exec mariadb -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < grimmory-backup.sql
   ```

Once you have verified the library, remove the old volume with
`podman volume rm grimmory-db`.

## Path 2 — reuse the data directory in place (expert)

Faster and avoids downtime proportional to database size, but you are responsible for the details.
Use only if you understand the risks, and **only with a backup you have tested**.

The linuxserver image keeps the real datadir in `config/databases`, so it can be handed to the
official image directly:

```yaml
mariadb:
  image: mariadb:12.3.2
  environment:
    - MARIADB_AUTO_UPGRADE=1
  volumes:
    - ./config/databases:/var/lib/mysql
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
  sudo chown -R "$uid:$gid" ./config/databases
  ```

- **Your `custom.cnf` is not carried over.** Anything you set in `/config/custom.cnf` must be moved
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
