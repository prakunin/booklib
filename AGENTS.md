# Repository Guidelines

Grimmory is a self-hosted digital library (a community fork of Booklore): an Angular 21 frontend and a
Spring Boot 4 / Java 25 backend, shipped as a **single all-in-one container** — the `Dockerfile` builds
the Angular bundle first and passes it into the backend jar via `-PfrontendDistDir`, so Spring Boot
serves the SPA. `ffprobe` (audiobooks) and `kepubify` (Kobo) are baked in as native binaries.

## Agent Workflow

- Start from the root `Justfile` unless you have a clear reason to drop into a subproject.
- Prefer `just api ...` and `just ui ...` over ad hoc commands.
- Keep changes scoped to the relevant project instead of mixing backend, frontend, deployment, and release edits in one pass.
- Target `develop`, not `main`.
- First prove the change with targeted tests around the edited surface, then run the wider suite for that surface before handing off.
- If you cannot run the wider suite, say exactly what you ran, what you skipped, and why.

## Project Structure

- **Backend (`backend/`)**
  - Application code: `src/main/java`
  - Resources and Flyway migrations: `src/main/resources`
  - Tests: `src/test/java`
- **Frontend (`frontend/`)**
  - Application code: `src/app/{core,features,shared}`
  - Translations: `src/i18n/`
  - Assets: `src/assets/`

## Ownership Boundaries

- `deploy/`, `packaging/`, `tools/`, `docs/`, and `assets/` are support surfaces. Do not change them unless the task actually touches deployment, packaging, release automation, or shared docs/assets.
- Keep backend, frontend, deployment, and release work separated unless the task genuinely crosses those boundaries.
- When a change spans multiple surfaces, validate each one explicitly.

## Command Surface

Use these first:

```bash
just check          # run backend + frontend verification
just test           # run backend + frontend tests
just api run        # start Spring Boot with the dev profile
just api test       # run backend tests
just ui dev         # start the Angular dev server
just ui check       # run frontend verification
just dev-up         # Docker dev stack (UI 4200, API 6060, MariaDB 3366, debug 5005)
just db-up          # database only
```

### Running a single test

```bash
# Backend — by test class
just api test-class test_class=org.booklore.service.BookServiceTest

# Frontend — by spec file, or by test-name regex
cd frontend && pnpm exec ng test --include src/app/path/to/foo.spec.ts --watch=false
cd frontend && pnpm exec ng test --filter "^BookService" --watch=false

# Frontend e2e (Playwright, specs in frontend/playwright/)
just ui e2e-file spec=playwright/login-and-books.spec.ts
```

Note: a single frontend spec still triggers a **full Angular application build first (~2 min)** before
Vitest runs. That is expected, not a hang.

## Architecture

### Backend (`backend/`, root package `org.booklore`)

Organized **layer-first with feature sub-packages inside each layer** (`service/kobo`, `service/metadata`,
…), not vertical slices. `model/enums` is architecturally central: `BookFileType`, `MetadataProvider`,
`TaskType`, and `UserPermission` are the dispatch keys for nearly every registry in the app.

- **Ingestion** — two independent paths, both `WatchService` + in-memory `BlockingQueue` (no external
  broker). Library folder watching (`LibraryWatchService` → `LibraryFileEventProcessor` →
  `LibraryProcessingService`) auto-ingests; **BookDrop** (`BookdropMonitoringService`) stages files as
  `PENDING_REVIEW` for human approval before they move into a library.
- **Per-format handling — three parallel registries**, all keyed on `BookFileType`. Their extension
  mechanisms are inconsistent, which is the most common trap:
  - `BookFileProcessor` → `BookFileProcessorRegistry` — **auto-discovers** beans.
  - `MetadataWriter` → `MetadataWriterFactory` — **auto-discovers** beans; deliberately has **no
    MOBI/AZW3/FB2 writer**.
  - `FileMetadataExtractor` → `MetadataExtractorFactory` — a **hand-written `switch`**. Adding a format
    means editing it by hand.
- **Metadata providers** implement `BookParser` and are wired in a manual `Map` in `config/BookParserConfig`
  (not component-scanned). Several are **Jsoup HTML scrapers**, not APIs, with recorded HTML fixtures under
  `src/test/resources/`.
- **Security** — `config/security/SecurityConfig` defines **12 ordered `SecurityFilterChain` beans** across 11
  `@Order` slots (Kobo and KOReader deliberately share `@Order(3)`, disjoint by `securityMatcher`); read it
  before touching auth. Media/streaming/download endpoints are `permitAll()` at the matcher and authenticated
  by `QueryParameterJwtFilter` (**JWT in a query param**, because `<img>`/`<audio>` can't send headers) — so
  `permitAll` does not mean public. OIDC is hand-rolled (not `spring-security-oauth2-client`). There are
  **four separate credential stores**: main users, OPDS, KOReader, and Kobo (token in the URL path).
- **Authorization** is multi-user, not multi-tenant (one shared DB, no tenant column). Isolation comes from
  per-user library assignment, ~28 permission booleans, and the `@CheckBookAccess` / `@CheckLibraryAccess`
  AOP aspects.
- **Two migration systems.** Schema → Flyway SQL in `src/main/resources/db/migration/` (currently at `V145`).
  Data backfills → Java `Migration` classes in `service/migration/`, which must be **manually registered in
  `AppMigrationStartup.runMigrationsOnce()`**.
- **Most runtime config lives in the database**, not YAML — OIDC, metadata providers, cron schedules, and
  Komga/OPDS toggles are all `AppSettingKey` entries behind `AppSettingService`. Grep that enum before adding
  a YAML property.
- **Tasks** implement the `Task` interface and are registered by `TaskType`; cron schedules are loaded
  dynamically from the DB, so `@Scheduled` is almost absent.
- **WebSocket/STOMP** destinations are centralized in the `model/websocket/Topic` enum, sent through
  `NotificationService`. Its `sendMessage()` resolves the *current authenticated user* and **silently drops
  the message when there is none** — background threads must run on `taskExecutor` (which propagates the
  SecurityContext) or call `sendMessageToUser(...)`.
- `org.booklore.app` is a **second, parallel API surface** (`/api/v1/app/**`) for the mobile client with its
  own controllers/DTOs/mappers. Changing `controller/BookController` does not change `app/controller/AppBookController`.

### Frontend (`frontend/src/app/{core,features,shared}`)

Angular 21, **zoneless**, 100% standalone components and `@if`/`@for` control flow. Signals are pervasive;
the `@Input()`/`input()` migration is still in flight.

- **State = TanStack Query, exposed to components as signals** via `computed()` on `injectQuery()` results.
  There is no NgRx/store library. `QueryClient` is configured with **`staleTime: Infinity`** — nothing
  refetches on its own, so **if data looks stale you forgot to invalidate**. Invalidation is driven by
  WebSocket events.
- **Two parallel book caches** (an in-flight migration): the legacy flat `['books']` list and the paginated
  `['app-books']` infinite query. Every mutator in `features/book/service/book-query-cache.ts` must update
  **both**. Read that file before touching book state.
- **`app.component.ts` is the single global WebSocket dispatcher** — all `/user/queue/*` subscriptions are
  declared there and fan out to feature services. Adding a live-updating feature means editing it.
- **UI: three stacked layers** — PrimeNG 21 (Aura preset), Tailwind v4 (**CSS-first, no `tailwind.config.js`**;
  tokens live in `src/assets/styles/tailwind.css`), and an in-house kit at `shared/ui/`. **Prefer `shared/ui`
  for new UI**; there's a live catalog at the `/design-system` route. Theming is driven by app-owned CSS custom
  properties that PrimeNG is bridged onto — the app's vars are the source of truth, not the Prime preset.
- **Readers** (`features/readers/`) are four full-screen mini-apps routed *outside* the main layout shell.
  EPUB uses **foliate-js vendored as raw JS in `src/assets/foliate/`** (not an npm dep, injected at runtime);
  PDF uses **EmbedPDF in an iframe**, which is why the dev server sets COOP/COEP headers in `angular.json` —
  don't remove them, the PDFium WASM needs them.
- **i18n**: Transloco, but translations are **not fetched over HTTP** — English is statically bundled and other
  languages are lazy `import()`ed from `core/config/transloco-loader.ts`, deep-merged over English (missing keys
  fall back per-key). Adding a language means editing that loader; adding a JSON file means registering it in
  `src/i18n/<lang>/index.ts`.
- **No TypeScript path aliases** — all cross-directory imports are deep relative paths. Note `shared/` has both
  `service/` and `services/`, and both `model/` and `models/`; check both.

## Backend Rules

- Use 4-space indentation and match surrounding Java style.
- Prefer constructor injection via Lombok patterns already used in the codebase. Do not introduce `@Autowired` field injection.
- Use `@Slf4j` for logging. Throw API-facing errors via `ApiError` helpers, not raw `RuntimeException`.
- Use MapStruct for entity/DTO mapping.
- Keep JPA entities on the `*Entity` suffix.
- Add Flyway migrations as new files named `V<number>__<Description>.sql`.
- Do not edit released migrations in place.
- Use Spring Data JPA methods or JPQL. Do not introduce native queries unless a maintainer has approved them.
- Security checks should use existing patterns such as `@PreAuthorize("@securityUtil.isAdmin()")` or `@CheckBookAccess`.
- Prefer focused unit tests (Mockito + AssertJ, heavy `@Nested` use); use `@SpringBootTest` only when the Spring
  context is required — there are only ~11 in a 200+ file suite.

## Frontend Rules

- Use 2-space indentation in TypeScript, HTML, and SCSS.
- Keep Angular code on standalone components. Do not add NgModules.
- Prefer `inject()` over constructor injection.
- Follow `frontend/eslint.config.js`: component selectors use `app-*`, directive selectors use `app*`, and `any` is disallowed.
- Put user-facing strings in Transloco files under `frontend/src/i18n/`.
- Keep responsive behavior intact.
- Use Vitest for tests, with classic `TestBed`. **Reuse the harnesses in `src/app/core/testing/`**
  (`getTranslocoModule()`, `createQueryClientHarness()`, `createAuthServiceStub()`) rather than reinventing
  setup. Specs are co-located next to their source.

## Validation

- Use staged verification: prove the behavior locally with targeted tests first, then run the wider suite for that surface.
- Typical backend path: `just api test` and then `just api check`.
- Typical frontend path: targeted Vitest coverage for the changed area and then `just ui check`.
- If the change crosses frontend and backend boundaries, finish with a repo-level pass such as `just test` or `just check`.
- Do not claim completion from a narrow test when a broader runnable suite exists.

CI gates that fail builds and are easy to miss locally:

- **Zero tolerance for frontend lint *and build* warnings** (thresholds are all `0`).
- Frontend coverage thresholds of **90%** apply only when `COVERAGE_GATE=1` is set (i.e. in CI).
- Backend native-lib tests (pdfium/epub4j) are **conditionally skipped** when the natives can't load — a green
  local run may have skipped real coverage.

## Legacy Naming — Do Not "Fix"

The Booklore → Grimmory rename is deliberately skin-deep, and the old name is **load-bearing**. The Java root
package is still `org.booklore`, along with the Gradle group, `BookloreApplication`, the JWT issuer string
`"booklore"`, `logging.level.org.booklore`, and the `BOOKLORE_PORT` env var. On the frontend, the
`ResetProgressType` wire value is still the literal string `'BOOKLORE'`. Renaming these breaks auth, logging,
and migrations. Leave them alone unless the task is explicitly a rename.

## PR Expectations

- If UI behavior changes, capture screenshots or a short recording for the PR.
- PRs in this repo are expected to link an approved issue and include local test output.
- Follow Conventional Commits with scopes, for example `feat(devex): ...` or `fix(entrypoint): ...`.
- Branch from `develop` using `feat/`, `fix/`, `refactor/`, or `docs/` prefixes.
