# Multi-Repository Dependency Discovery

## Goal

SchemaGuard scans several checked-out application repositories for evidence that their source code uses database tables and columns. The scanner analyzes repository files only. It does not connect to application databases, read application rows, execute SQL, or run repository code.

Dependency discovery supplements the existing schema parser and schema-diff engine. It does not replace either component.

## Initial supported workflow

1. Check out each application repository locally.
2. Start the SchemaGuard backend.
3. Register each checked-out repository with `POST /api/v1/dependencies/applications`.
4. Run `POST /api/v1/dependencies/scan` with selected repository IDs, or with an empty body to scan all registered repositories.
5. Review the table/column evidence and scanner warnings.
6. In a subsequent step, match the discovered evidence against schema diff results to produce cross-application migration impact reports.

The first version scans local repository directories. The repository URL is metadata; it does not automatically clone or download a remote repository.

## Scan roots and filesystem safety

The scanner only accepts paths beneath configured trusted scan roots. By default, the current working directory is allowed; when the current directory is named `backend`, its parent project directory is also allowed.

Configure additional trusted roots through Spring configuration using `schemaguard.scan.allowed-roots`. Separate multiple roots with the operating system's path separator.

Example Windows PowerShell session:

```powershell
$env:SCHEMAGUARD_SCAN_ALLOWEDROOTS = "C:\Users\harsh\projects;D:\company-repositories"
cd C:\Users\harsh\SchemaGuard\backend
mvn spring-boot:run
```

Only point this setting at directories that are trusted for source analysis. Do not expose a local SchemaGuard instance to untrusted network clients.

The scanner:
- resolves registered paths to canonical paths and checks that they remain within allowed roots
- does not follow symbolic links
- skips common build, IDE, and dependency directories
- scans recognized source/configuration extensions only
- limits the scan to 10,000 files, 1 MiB per file, and 50 MiB total input
- skips oversized or binary-like files and reports warnings
- never executes code or SQL from a repository

## API

### Register a repository

`POST /api/v1/dependencies/applications`

Example request:

```json
{
  "repositoryId": "user-service",
  "repositoryName": "User Service",
  "repositoryUrl": "https://github.com/example/user-service",
  "localPath": "C:/Users/harsh/projects/user-service",
  "primaryLanguage": "Java"
}
```

`repositoryId` can be omitted to have the backend generate an identifier. `repositoryName` and `localPath` are required. The local path must exist and be within an allowed scan root. The registry currently lives in process memory and resets when the backend restarts.

### List registered repositories

`GET /api/v1/dependencies/applications`

Returns the repository registrations currently held by the running backend.

### Scan multiple repositories

`POST /api/v1/dependencies/scan`

Scan selected repositories:

```json
{
  "repositoryIds": ["user-service", "order-service"]
}
```

Scan every currently registered repository by sending an empty JSON object:

```json
{}
```

The response includes per-repository results, counts, dependency evidence, and warnings.

## Evidence model

Each detected reference contains:
- repository ID and display name
- path relative to the repository root
- line number
- matched database object type
- table and optional column name
- detection method
- confidence label
- a short matched source pattern

The initial scanner recognizes heuristic SQL table references such as `FROM`, `JOIN`, `UPDATE`, `INTO`, and `REFERENCES`; simple `SELECT column-list FROM table` projections; and explicit JPA `@Table(name = ...)` / `@Column(name = ...)` annotations.

The confidence label communicates the source of the evidence; it is not a statistical probability. Static SQL references are labelled medium confidence and explicit JPA mappings high confidence. This is an initial heuristic scanner, not a full parser or call-graph engine.

## Interpretation and limitations

- A matching source reference is evidence that the repository may depend on the named table or column.
- A source file match does not automatically prove which HTTP API endpoint executes the query.
- Dynamically constructed SQL, stored procedures, generated code, indirect ORM mappings, SQL using unusual syntax, and other framework patterns may be missed.
- A missing result does not prove that the application has no dependency.
- The registry is currently in-memory; registrations must be submitted again after backend restart.
- Repository URL is metadata only in this version; the scanner reads the supplied local path.
- Results should be reviewed before a migration is approved.

## Verification

Run the backend tests:

```powershell
cd C:\Users\harsh\SchemaGuard\backend
mvn clean test
```

The added unit tests cover multi-repository scans, static SQL references, JPA table/column annotations, duplicate identifiers, and scan-root enforcement.
