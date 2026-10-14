# Multi-Repository Dependency Discovery

SchemaGuard can scan registered application repositories, persist source evidence,
and match the discovered table and column references to proposed schema changes.
The scanner analyzes source and configuration files; it does not connect to
application databases, read business data, execute SQL, or run repository code.

## Workflow

1. Register each application with `POST /api/v1/dependencies/applications`.
2. Submit local checkout paths or supported GitHub repository URLs to
   `POST /api/v1/dependencies/scan/multiple`.
3. Review each application's evidence, scan limits, and coverage.
4. Submit the current and proposed schemas to
   `POST /api/v1/schema/diff-with-impact`, including the same `databaseName`
   used when registering the application.
5. Review affected applications/components and the confidence-backed risk score.

When no dependency was found, the report identifies the application as
unscanned or says that no reference was found in the analyzed source. It does
not claim that an application is unaffected.

## Register an application

`POST /api/v1/dependencies/applications`

```json
{
  "applicationId": "user-service",
  "name": "User Service",
  "databaseName": "customer_db",
  "repository": "https://github.com/example/user-service",
  "description": "User profile APIs and persistence"
}
```

## Scan repositories

`POST /api/v1/dependencies/scan/multiple`

```json
{
  "repositories": [
    {
      "applicationId": "user-service",
      "repositoryPath": "C:\\projects\\user-service"
    },
    {
      "applicationId": "order-service",
      "repositoryPath": "C:\\projects\\order-service"
    }
  ]
}
```

Every application ID must already be registered. A request returns repository,
file, byte, and reference totals; per-application results; and warnings for
truncated scans. The original `POST /api/v1/dependencies/scan` endpoint remains
available for one repository.

The current scanner detects common SQL table references, simple single-table
`SELECT column-list FROM table` projections, and explicit JPA
`@Table(name = ...)` mappings. Static SQL references are heuristic; exact API
attribution is included only when the scanner has supporting source structure.
Dynamic SQL, joins' column ownership, implicit ORM mappings, and framework-
specific query builders may not be resolved.

## Limits and persistence

- Up to 500 supported source files per repository scan; files larger than 1 MiB
  are skipped.
- GitHub archives are limited to 25 MiB compressed, 100 MiB extracted, 20,000
  archive entries, and 501 extracted source files.
- Common generated/build directories are skipped; code is never executed.
- Application records, manually registered dependencies, scanner evidence,
  and scan coverage are stored in MySQL and migrated by Flyway.
- A successful rescan replaces that application's previous scanner-generated
  evidence and coverage while retaining manually registered dependencies.

Set `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` to configure SchemaGuard's
registry database. The registry stores dependency metadata, not application
business data.

Optional `TARGET_DB_URL`, `TARGET_DB_USERNAME`, and `TARGET_DB_PASSWORD`
configure read-only MySQL metadata lookup for affected table sizes. Use a
separate database account with read-only privileges. The score adds up to 10
points based on the largest affected table (100 MiB: 2; 1 GiB: 4; 10 GiB: 7;
100 GiB: 10). Without this configuration the response reports
`NOT_CONFIGURED` and adds no size points. Exact `INSTANT`/`INPLACE`/`COPY`
selection is not inferred from schema snapshots; validate the exact migration
against the target MySQL version on staging.

## Validation

```powershell
mvn -f backend\pom.xml clean test
```
