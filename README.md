# SchemaGuard

SchemaGuard is a MySQL migration-risk analyzer designed to answer one question before a schema change reaches production: "What can break, where, and how risky is it?"

The project combines a Java + Spring Boot backend with a React + Vite frontend. It parses schema definitions, compares old and new database structures, classifies the migration risk, discovers likely application dependencies from manual registration and static repository scanning, and presents a migration plan that helps teams review deployment risk before running a change.

This project is intentionally conservative: unsupported SQL is rejected clearly instead of being silently interpreted, and dependency analysis is evidence-backed but not claimed to be perfect.

## Why SchemaGuard exists

Database schema changes are often deceptively simple. A column rename, type change, new constraint, or dropped table can be harmless in one context and breaking in another. SchemaGuard provides a structured, reviewable answer for:

- what changed between two schema versions
- whether the change is low, medium, or high risk
- which applications may depend on the affected tables or columns
- which migration steps must be validated before production rollout
- where the evidence for dependency impact came from

## Project architecture

The system is structured as layered services, each responsible for a narrow part of the workflow:

- Parser layer: converts SQL scripts into a structured schema model
- Diff engine: compares old and new schemas and emits schema changes
- Risk classifier: assigns a risk level and rationale to each change
- Dependency registry: stores manually known application-to-table relationships
- Repository scanner: looks for static SQL references in supported source files
- Dependency graph: links applications, tables, columns, and evidence together
- Impact analysis: maps a schema change to dependent applications
- API layer: exposes comparison and impact-aware endpoints
- Frontend: visualizes schema differences, warnings, impacts, and migration plans

## High-level flow

```text
+---------------------------+
| User or API client        |
| submits old/new schema    |
+-------------+-------------+
              |
              v
+---------------------------+
| MySqlSchemaParser         |
| Parses CREATE/ALTER SQL   |
| into DatabaseSchema       |
+-------------+-------------+
              |
              v
+---------------------------+
| SchemaDiffEngine          |
| Compares schemas          |
| Finds ADDED/MODIFIED/REMOVED objects |
+-------------+-------------+
              |
              v
+---------------------------+
| SchemaRiskClassifier      |
| Assigns risk levels and  |
| recommendation rules     |
+-------------+-------------+
              |
              v
+---------------------------+
| Dependency registry +     |
| static repository scan    |
| + dependency graph        |
+-------------+-------------+
              |
              v
+---------------------------+
| ApplicationImpactAnalysisService |
| Maps impacted apps to schema changes |
+-------------+-------------+
              |
              v
+---------------------------+
| API response / frontend  |
| risk summary / impact /  |
| migration plan           |
+---------------------------+
```

## Detailed design

### 1. Backend

The backend lives under [backend](backend). It is a Spring Boot application using Java 21 and Maven.

Key backend packages:

- [backend/src/main/java/com/schemaguard/controller](backend/src/main/java/com/schemaguard/controller)
  - REST controllers for schema parsing, diffing, impact analysis, registry operations, and scanning
- [backend/src/main/java/com/schemaguard/parser](backend/src/main/java/com/schemaguard/parser)
  - SQL parsing and supported DDL handling
- [backend/src/main/java/com/schemaguard/diff](backend/src/main/java/com/schemaguard/diff)
  - schema comparison, schema change generation, risk classification
- [backend/src/main/java/com/schemaguard/model](backend/src/main/java/com/schemaguard/model)
  - schema model, diff request/response DTOs, dependency evidence, graph models, impact models
- [backend/src/main/java/com/schemaguard/service](backend/src/main/java/com/schemaguard/service)
  - registry logic, scanning, graph traversal, impact evaluation

### 2. Frontend

The frontend is under [frontend](frontend) and is built with React + Vite.

It provides:

- SQL editor inputs for old and new schema
- schema comparison request execution
- risk summary display
- filtering and sorting of changes by risk level
- dependency impact panels
- migration plan recommendations
- graceful fallback if the impact-aware endpoint is unavailable

### 3. Supported SQL safety model

SchemaGuard is intentionally explicit about the boundaries of support.

It supports the SQL patterns it can safely model and rejects unsupported statements loudly instead of pretending to understand them. This is important because false confidence is worse than a clear error in migration review.

Examples of supported patterns include:

- CREATE TABLE
- ALTER TABLE ADD COLUMN
- ALTER TABLE DROP COLUMN
- ALTER TABLE MODIFY COLUMN
- column and table rename flows where representable
- index and constraint handling in supported cases
- drop-table flows

Unsupported statements are rejected with an IllegalArgumentException or HTTP 400-style validation error.

## Core workflow

### Parse step

The parser reads a SQL script and produces a final `DatabaseSchema` object containing:

- tables
- columns
- primary keys
- indexes
- constraints

The parser preserves statement order for supported migration operations, so the final schema reflects the actual sequence of valid DDL changes.

### Diff step

The diff engine compares two schema models and emits a list of `SchemaChange` objects. Each object contains:

- object type (TABLE, COLUMN, INDEX, CONSTRAINT, PRIMARY_KEY)
- operation type (ADDED, REMOVED, MODIFIED)
- table name and object name
- old/new values
- risk level
- human-readable change description

### Risk classification

Risk classification is centralized in the risk classifier so that changes are scored consistently and recommendations remain aligned with actual diff results.

Risk examples include:

- dropping a column or table -> high risk
- changing the primary key -> high risk
- adding or removing foreign key constraints -> high risk / medium depending on style
- changing nullability to NOT NULL -> high risk
- widening integer types -> low risk
- index removal -> medium risk
- index addition -> low risk

### Recommendation generation

Recommendation text is conditional and practical. It does not assume every change is safe or unsafe without evidence. Recommendations are based on:

- destructive data operations
- nullability enforcement
- type conversion risk
- primary key / foreign key changes
- index build or lock implications

### Dependency discovery

SchemaGuard adds dependency discovery in layers:

1. Manual registration
   - applications can be registered
   - table and column dependency entries can be stored
2. Static repository scanning
   - scans Java, SQL, XML, properties, and common source files for SQL patterns
   - captures file paths and snippets as evidence
3. Graph construction
   - builds nodes and edges between applications, tables, and columns
   - supports bounded traversal and cycle protection
4. Impact analysis
   - traces a schema change to likely affected applications

This design keeps discovery conservative and explainable rather than pretending to infer every runtime relation.

## API surface

The project exposes several API routes.

### Schema APIs

- POST /api/v1/schema/parse
  - Parse an SQL script into a `DatabaseSchema`
- POST /api/v1/schema/diff
  - Compare old and new SQL and return risk summary and schema changes
- POST /api/v1/schema/diff-with-impact
  - Returns diff results plus application impact and migration plan

### Dependency APIs

- POST /api/v1/dependencies/applications
  - Register an application
- GET /api/v1/dependencies/applications
  - List registered applications
- POST /api/v1/dependencies/entries
  - Register known dependencies
- GET /api/v1/dependencies/entries
  - List dependency entries
- POST /api/v1/dependencies/scan
  - Scan a repository path for likely SQL references

## Example data flow

A typical migration review can look like this:

1. User pastes the old schema and proposed new schema into the frontend
2. Frontend sends JSON to `/api/v1/schema/diff-with-impact`
3. Backend parses both SQL inputs
4. Diff engine compares the schema objects and identifies changes
5. Risk classifier applies risk and recommendation rules
6. Dependency graph and repository scanner identify likely dependent apps
7. Impact service connects schema changes to applications and evidence
8. API returns:
   - diff result
   - impacted applications
   - migration plan
9. frontend renders these results for review

## Frontend user experience

The frontend is designed to keep the original comparison use case intact while exposing the dependency-aware layer. It does not replace the core schema-diff experience; it augments it.

It currently provides:

- old schema editor
- new schema editor
- analyze button
- overall risk banner
- risk filters and sorting
- detailed change list
- recommendations panel
- impacted app summary
- migration plan
- graceful fallback to the original diff when needed

## Testing and validation

The project validates behavior with Maven for the backend and Vite build for the frontend.

### Backend

```powershell
cd C:\Users\harsh\SchemaGuard\backend
mvn test
```

### Frontend

```powershell
cd C:\Users\harsh\SchemaGuard\frontend
npm run build
```

The project intentionally emphasizes focused tests over broad, unconstrained changes. Each milestone adds targeted coverage for the behavior introduced.

## Security and reliability assumptions

SchemaGuard is designed with practical safety boundaries:

- unsupported SQL is rejected
- scanning is bounded and file-size-limited
- path traversal is blocked
- dependency directories are skipped
- arbitrary repository execution is avoided
- static analysis remains conservative and evidence-based

The system does not execute user-provided SQL and does not claim to fully understand every dynamic ORM pattern or framework-specific behavior.

## Known limitations

SchemaGuard is strong for reviewable, explicit DDL differences, but it still has realistic limitations:

- repository scanning is heuristic, not full semantic code analysis
- dynamic SQL generation is not fully resolved by static detection
- framework-specific patterns may be missed unless explicitly supported
- graph confidence is evidence-based and not definitive
- production deployment safety still requires human review and validation

These limitations are treated as explicit constraints rather than hidden assumptions.

## Repository layout

```text
SchemaGuard/
├── README.md
├── SchemaGuard_Complete_Roadmap.md
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/schemaguard/
│       └── test/java/com/schemaguard/
├── frontend/
│   ├── package.json
│   └── src/
└── .gitignore
```

## Local setup

### 1. Backend

```powershell
cd C:\Users\harsh\SchemaGuard\backend
mvn spring-boot:run
```

Then open the API at:

- http://localhost:8080

### 2. Frontend

```powershell
cd C:\Users\harsh\SchemaGuard\frontend
npm install
npm run dev
```

Then open the Vite app in a browser and use the schema comparison workflow.

## Release / contribution workflow

This project is structured for incremental delivery. The recommended pattern is:

1. inspect current code and Git state
2. add a focused change for one milestone
3. add or update tests
4. run targeted validation
5. review the diff and confirm no unrelated files were touched
6. commit the work with a descriptive message
7. push to the current branch

## Git commands

To commit and push from the repository root:

```powershell
cd C:\Users\harsh\SchemaGuard
git add README.md .gitignore
git commit -m "docs: update project documentation"
git push origin HEAD
```

## Commit status

This README is intended to describe the current project as implemented, not as a future idealized version. It preserves the conservative design decisions and the release-safe migration review model that define the project.

## Final summary

SchemaGuard is best understood as a migration-review assistant for database schema changes. It is not a migration executor, and it does not pretend that static analysis can replace database and application validation. Instead, it gives a structured, explainable, and reviewable answer to the core migration question: what can change, how risky is it, who may be affected, and what should be checked before rollout.
