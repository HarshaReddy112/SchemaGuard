# Multi-Repository Dependency Discovery

## Purpose

SchemaGuard can scan several registered application repositories in one request, detect evidence that each application uses database tables and columns, and make that evidence available to the existing schema impact analyzer.

The scanner inspects local source files only. It does not connect to company application databases, read business rows, execute submitted SQL, or run repository code. It does not automatically clone a GitHub URL; each repository must first be checked out locally.

## Workflow

1. Register each application using \`POST /api/v1/dependencies/applications\`.
2. Check out each application's repository into a trusted local directory.
3. Set scan roots if the repositories are outside the default allowed directories.
4. Submit a single \`POST /api/v1/dependencies/scan/multiple\` request with each registered application ID and its local repository path.
5. Review per-repository scan evidence, file locations, detected table/column references, and truncation warnings.
6. Submit the old and proposed SQL to \`POST /api/v1/schema/diff-with-impact\`.
7. The impact report uses registered and scanned evidence. If no direct dependency is found, it asks for manual verification rather than declaring the application safe.

## Register an application

\`POST /api/v1/dependencies/applications\`

Example request:

\`\`\`json
{
  "applicationId": "user-service",
  "name": "User Service",
  "repository": "https://github.com/example/user-service",
  "databaseName": "customer_db",
  "description": "User profile APIs and persistence"
}
\`\`\`

The existing registry uses \`applicationId\` and \`name\` to identify the application. The \`repository\` field remains metadata; the local path used for a scan is provided separately.

## Scan multiple repositories together

\`POST /api/v1/dependencies/scan/multiple\`

Example request:

\`\`\`json
{
  "repositories": [
    {
      "applicationId": "user-service",
      "repositoryPath": "C:/Users/harsh/projects/user-service"
    },
    {
      "applicationId": "order-service",
      "repositoryPath": "C:/Users/harsh/projects/order-service"
    }
  ]
}
\`\`\`

Register all application IDs before scanning. Each application ID must be registered and must appear only once per request. A request can include up to 20 repositories. The original \`POST /api/v1/dependencies/scan\` single-repository endpoint remains available.

The response includes:
- total repositories scanned, files scanned, bytes scanned, and references found
- a result for each application, including its original scan output
- warnings when a scan reaches a configured limit
- evidence containing table, optional column, source location, snippet, detector, and confidence

## Current evidence detectors

The scanner currently recognizes:
- SQL table references using patterns such as \`FROM\`, \`JOIN\`, \`UPDATE\`, \`INTO\`, \`DELETE FROM\`, \`CREATE TABLE\`, and \`REFERENCES\`
- simple single-table \`SELECT column-list FROM table\` projections, which can associate selected columns with the table
- explicit JPA \`@Table(name = ...)\` mappings
- explicit JPA \`@Column(name = ...)\` mappings when exactly one explicit JPA table mapping is present in the source file
- explicit \`@Entity(name = ...)\` names as a lower-confidence heuristic, not proof of the physical table name

Static SQL patterns are heuristic and labelled medium confidence; explicit table/column annotations are labelled high confidence. Confidence labels describe the detector's evidence category, not a statistical probability.

## How impact is calculated

The schema diff engine identifies changed tables/columns. The impact analysis then matches those objects to manual and scanned dependency entries in the registry. Source locations and snippets are returned as evidence.

The analyzer does not invent application names or hard-code example services. A discovered repository dependency is not automatically an HTTP API endpoint. Exact endpoint attribution across controller/service/repository call chains is a later enhancement; this version reports the registered application and source reference. If a relationship is not found, the result says that manual verification is required.

## Scan limits and path safety

- Repositories must be local directories.
- Paths containing parent traversal segments (\`..\`) are rejected.
- Registered scan paths must resolve beneath a configured trusted scan root.
- Symbolic links are not followed.
- Common build, IDE, generated, and dependency directories are skipped.
- Only recognized source/configuration extensions are scanned.
- Maximum per scan: 200 files, 1 MiB per file, 50 MiB total bytes.
- A truncated scan is explicitly marked in the response.
- Code is never executed.

By default, the current working directory, Java's temporary directory, and (when running from a directory named \`backend\`) its parent project directory are allowed. To scan repositories elsewhere, set \`SCHEMAGUARD_SCAN_ALLOWEDROOTS\` before launching Spring Boot. Separate roots with the operating system's path separator.

Example PowerShell configuration:

\`\`\`powershell
$env:SCHEMAGUARD_SCAN_ALLOWEDROOTS = "C:\Users\harsh\projects;D:\company-repositories"
cd C:\Users\harsh\SchemaGuard\backend
mvn spring-boot:run
\`\`\`

Only add trusted source directories. Do not expose a local SchemaGuard instance to untrusted network clients.

## Persistence

The current application/dependency registry is held in process memory. Application registrations and scanned evidence are lost when the backend restarts; re-register and rescan repositories after restart. The stored metadata is dependency evidence only, not business/application data. A later change can add persistence for repository metadata if required.

## Limitations

- Dynamic SQL and runtime-generated queries may not be resolved.
- Column extraction is intentionally limited to simple single-table SELECT projections and explicit JPA annotations.
- Join projections, stored procedures, database views, implicit ORM mappings, and framework-specific patterns may require additional detectors.
- Source file evidence does not prove which HTTP API executes a query.
- An absence of matches never proves an application is unaffected.
- Review dependency evidence and validate application behavior before approving a production migration.

## Test

From the project root:

\`\`\`powershell
mvn -f backend/pom.xml clean test
\`\`\`

Or from the backend directory:

\`\`\`powershell
mvn clean test
\`\`\`
