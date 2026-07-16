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

Your database passwords and database name are unaffected: the official image accepts the same
`MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `MYSQL_USER`, and `MYSQL_PASSWORD` variables.

**Back up before you start.** Both paths below assume you can afford to stop the stack.

## Path 1 — dump and restore (recommended)

Works regardless of layout differences, file ownership, or the 11.4 → 12.3 version jump. Downtime
scales with database size.

1. With the **old** stack still running, dump the database:

   ```bash
   docker compose exec mariadb \
     mariadb-dump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction \
     --routines --triggers --events grimmory > grimmory-backup.sql
   ```

   Check the file is not empty before continuing:

   ```bash
   ls -lh grimmory-backup.sql && head -5 grimmory-backup.sql
   ```

2. Stop the stack:

   ```bash
   docker compose down
   ```

3. Update your `docker-compose.yml` to the new image and volume:

   ```yaml
   mariadb:
     image: mariadb:12.3.2
     volumes:
       - ./mariadb:/var/lib/mysql
   ```

   Remove the `PUID` and `PGID` environment variables — the official image ignores them.

4. Start **only** the database so it initialises an empty 12.3.2 data directory:

   ```bash
   docker compose up -d mariadb
   ```

5. Restore:

   ```bash
   docker compose exec -T mariadb \
     mariadb -u root -p"$MYSQL_ROOT_PASSWORD" grimmory < grimmory-backup.sql
   ```

6. Start the rest of the stack:

   ```bash
   docker compose up -d
   ```

Your old `./config` directory is left untouched. Keep it until you have confirmed the application
works, then delete it.

## Path 2 — re-point the data directory (expert)

Faster, no dump, but you are responsible for the details. Use only if you understand the risks.

The linuxserver image keeps the actual data directory in `config/databases`, so it can be handed
straight to the official image:

```yaml
mariadb:
  image: mariadb:12.3.2
  volumes:
    - ./config/databases:/var/lib/mysql
```

Caveats:

- **Ownership is handled for you — as long as the container starts as root.** linuxserver wrote
  those files as your `PUID`/`PGID` (typically `1000:1000`). The official entrypoint starts as root,
  chowns anything under the data directory that is not already owned by `mysql`, and only then drops
  to the `mysql` user, so a linuxserver-owned tree is adopted automatically.

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
- **podman named volumes.** If your data lives in the `grimmory-db` named volume rather than a bind
  mount, this path does not apply cleanly — the volume root is `/config`, not the data directory.
  Use Path 1.

MariaDB upgrades across major versions in place, so `mariadb-upgrade` handles 11.4 → 12.3 without
stepping through intermediate versions.

## Verifying

```bash
docker compose exec mariadb mariadb -u root -p"$MYSQL_ROOT_PASSWORD" \
  -e "SELECT VERSION(); SELECT COUNT(*) FROM grimmory.book;"
```

Expect a `12.3.2-MariaDB` version string and your real book count.
