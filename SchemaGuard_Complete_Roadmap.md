# SchemaGuard --- Complete Development Roadmap

## Purpose

This document is the end-to-end implementation plan for SchemaGuard,
from the current working state through a complete dependency-aware
database migration risk analyzer. Work incrementally: inspect existing
code, make the smallest necessary change, add tests, run the test suite,
and only then continue.

## Non-Negotiable Repository Safety Rules

**Do not delete any existing project files. Do not remove, overwrite, or
replace existing functionality as a shortcut.**

1.  Do not delete current source files, test files, configuration,
    documentation, assets, or other project files.
2.  Do not run destructive commands such as `git clean`,
    `git reset --hard`, or recursive deletion commands.
3.  Do not remove existing classes, endpoints, models, tests, frontend
    components, or features merely to add a new implementation.
4.  Prefer additive changes. When an existing file must be updated,
    preserve its responsibilities and behavior except for the specific
    change required and tested.
5.  Inspect an existing file before editing it and identify the smallest
    safe change.
6.  Before significant edits, inspect `git status` and create a Git
    commit or another recoverable checkpoint when appropriate. Never
    discard uncommitted user work.
7.  Add new classes and tests where practical instead of replacing large
    existing classes.
8.  If a major redesign is necessary, preserve the old implementation in
    a separately named backup or legacy file before changing it. Never
    overwrite an existing backup.
9.  Do not remove files from Git tracking or add broad ignore rules that
    hide current files without explicit approval.
10. After each milestone, run relevant tests and builds. If something
    fails, fix it without deleting existing files or reverting unrelated
    work.
11. Do not claim support for SQL syntax or dependency relationships that
    have not been implemented and tested.
12. Keep secrets out of Git and logs.

## Current Project State

### Known structure

``` text
SchemaGuard/
├── backend/
│   ├── src/main/java/com/schemaguard/
│   │   ├── config/
│   │   ├── controller/
│   │   ├── diff/
│   │   ├── model/
│   │   ├── parser/
│   │   ├── service/
│   │   └── SchemaGuardApplication.java
│   ├── src/main/resources/
│   ├── src/test/java/com/schemaguard/
│   │   ├── parser/
│   │   └── diff/
│   └── pom.xml
└── frontend/
    ├── src/
    ├── public/
    └── package.json
```

Inspect the actual repository before relying on any path or adding a
class.

### Existing technologies and interfaces

-   Backend: Java and Spring Boot.
-   Build/test tool: Maven.
-   Frontend: React and Vite.
-   Database focus: MySQL.
-   Existing parser: `MySqlSchemaParser`.
-   Existing schema models include `ColumnSchema`, `TableSchema`, and
    `DatabaseSchema`.
-   Existing diff logic includes `SchemaDiffEngine`.
-   Existing tests include `MySqlSchemaParserTest` and
    `SchemaDiffEngineTest`.
-   Existing API endpoint: `POST /api/v1/schema/diff`.
-   The frontend already supports comparing SQL inputs and displaying
    changes, risk counts, filters/sorting, recommendations, and a
    printable report.

### Current verified checkpoint

The user reported that `mvn clean test` completed with `BUILD SUCCESS`
after correcting the test source folder layout.

The parser has been extended and tested for: -
`ALTER TABLE ... ADD COLUMN` - `ALTER TABLE ... DROP COLUMN` -
`ALTER TABLE ... MODIFY COLUMN`

The parser processes supported statements in order. This does **not**
mean the full MySQL DDL grammar is supported. Maintain an explicit list
of supported operations and reject unsupported operations clearly rather
than silently misinterpreting them.

Before further work, check `git status`, confirm the branch, inspect the
current code, and rerun tests if the working tree or environment has
changed.

------------------------------------------------------------------------

# Milestone 1 --- Reliable Migration-Aware DDL Parsing

## Goal

Parse supported schema creation and migration statements in order,
producing an accurate final schema representation without pretending to
support the full MySQL grammar.

### Step 1.1 --- Establish a safe baseline

1.  Run `git status`.
2.  Confirm the active feature branch.
3.  Review `MySqlSchemaParser.java`, schema model classes, and parser
    tests.
4.  Run `mvn clean test` from `backend`.
5.  Record the baseline result.
6.  Do not overwrite or delete files to make the baseline pass.

**Acceptance criteria:** the existing test suite passes before new
changes begin.

### Step 1.2 --- Document supported SQL syntax

1.  List currently supported `CREATE TABLE` features.
2.  List supported `ALTER TABLE` operations.
3.  Record limitations based on the actual parser implementation.
4.  Report unsupported statements clearly rather than silently ignoring
    meaningful schema changes.
5.  Add tests for unsupported syntax and malformed statements.

**Acceptance criteria:** supported behavior and limitations are explicit
and tested.

### Step 1.3 --- Preserve statement order

1.  Split a SQL script into statements without splitting semicolons
    inside quoted strings.
2.  Respect quoted identifiers and backticks where implemented.
3.  Parse supported `CREATE TABLE` statements.
4.  Apply supported `ALTER TABLE` statements in their original order.
5.  Reject ambiguous or unsupported statements explicitly.
6.  Test several migrations affecting the same table in sequence.

**Acceptance criteria:** the resulting schema reflects the order of
supported statements.

### Step 1.4 --- Expand ALTER TABLE support incrementally

Add one operation at a time, with tests for each:

1.  `ADD COLUMN`.
2.  `DROP COLUMN`.
3.  `MODIFY COLUMN`.
4.  `CHANGE COLUMN` and column rename, once safely representable.
5.  Table rename.
6.  Add and drop indexes.
7.  Add and drop foreign-key constraints.
8.  Add and drop primary-key constraints.
9.  Drop table.
10. Other explicitly prioritized operations.

For every operation: - Test valid input. - Test missing tables or
columns. - Test duplicate names. - Test quoted identifiers and supported
type syntax. - Test interactions with indexes, keys, and constraints. -
Ensure unsupported clauses are not silently ignored.

Do not implement every operation in one large, untested replacement.

### Step 1.5 --- Validate schema model consistency

1.  Ensure column changes update the column map.
2.  Ensure removed or renamed columns do not leave invalid index or
    constraint references.
3.  Check primary-key consistency.
4.  Check foreign-key references and table relationships.
5.  Preserve existing model constructors and callers where practical.
6.  Add model fields only when needed and update tests and serialization
    compatibility.

### Step 1.6 --- Parser test coverage

1.  Retain every existing parser test.
2.  Add tests for each newly supported operation.
3.  Add tests for multiple statements in order.
4.  Add malformed SQL tests.
5.  Add tests for quoted identifiers and semicolons in literals where
    supported.
6.  Add regression tests for existing `CREATE TABLE` behavior.

**Milestone 1 exit criteria:** parser tests pass, supported statement
ordering is correct, limitations are documented, and existing parser
behavior remains covered.

------------------------------------------------------------------------

# Milestone 2 --- Risk Intelligence and Safer Recommendations

## Goal

Explain how each schema change may affect stored data and existing
applications, classify risk consistently, and recommend safer migration
steps.

### Step 2.1 --- Inspect the current diff engine

1.  Read `SchemaDiffEngine.java` and related change, risk, and
    recommendation classes.
2.  Identify existing risk rules and avoid duplicating them.
3.  Record how changes, risk counts, overall risk, and recommendations
    are represented.
4.  Review tests before modifying the implementation.
5.  Preserve the current API response shape unless a backward-compatible
    extension is needed.

### Step 2.2 --- Establish consistent risk rules

Define documented rules for: - Dropping a table. - Dropping a column. -
Narrowing a column's type or length. - Widening a column's type or
length. - Changing nullability. - Adding a non-null column to a
populated table. - Changing primary keys. - Adding or removing foreign
keys. - Adding or removing indexes. - Changing defaults. - Signedness
changes. - Renaming columns or tables, when supported.

For each rule, define: 1. What changed. 2. Why it matters. 3. Default
risk level. 4. Conditions that increase or reduce risk. 5. A recommended
mitigation. 6. Assumptions that cannot be confirmed from schema text
alone.

Do not assume a change is safe solely from its syntax. Making a nullable
column `NOT NULL` may fail if existing rows contain `NULL`. Dropping a
column may destroy data. Widening can still have operational costs
depending on MySQL version, table size, and storage engine.

### Step 2.3 --- Separate detection, classification, and explanation

1.  Keep schema-diff detection responsible for identifying structural
    changes.
2.  Place risk classification in a clear rule layer or service,
    following the existing architecture.
3.  Keep recommendation generation separately testable.
4.  Avoid scattering inconsistent risk strings across parser and
    controller code.
5.  Ensure each reported risk has a reason and, where useful,
    mitigation.

### Step 2.4 --- Improve recommendations

Recommendations should be actionable, for example: - Back up data before
destructive changes. - Check existing values before enforcing
`NOT NULL`. - Use staged expand-and-contract migrations for potentially
breaking changes. - Validate foreign-key data before adding
constraints. - Evaluate index build and lock implications for large
tables. - Use a data migration or backfill when a new column needs
meaningful values for existing rows.

Recommendations must be conditional and honest. Schema text alone cannot
prove table size, production traffic, application usage, or incompatible
data.

### Step 2.5 --- Test the risk engine

1.  Preserve all existing `SchemaDiffEngineTest` tests.
2.  Add a focused test for each risk rule.
3.  Test risk level and explanation/recommendation.
4.  Test overall risk aggregation.
5.  Test that identical schemas produce no changes and no false risk.
6.  Test edge cases such as missing defaults and multiple changed
    properties.
7.  Avoid brittle tests tied to incidental wording unless wording is an
    intentional contract.

**Milestone 2 exit criteria:** supported changes have predictable risk
classification, useful reasoning, tested recommendations, and no
regressions.

------------------------------------------------------------------------

# Milestone 3 --- Application Dependency Discovery

## Goal

Discover which applications, services, and repositories may depend on
database tables and columns.

### Step 3.1 --- Define the dependency data model

Design additive models for: - Application or service. - Repository and
repository metadata. - Logical database reference, without storing
secrets in plain text. - Table and column reference. - Dependency
evidence. - Confidence level. - Source location and last scan time.

Reuse existing models when suitable. Do not remove or replace current
schema models merely to introduce dependency discovery.

### Step 3.2 --- Build a manual dependency registry first

1.  Define an input format for registering applications and known
    database dependencies.
2.  Validate required fields and identifiers.
3.  Add service operations to create, list, and update registry entries.
4.  Test invalid references and duplicate entries.
5.  Distinguish manually registered dependencies from automatically
    inferred dependencies.

This creates a useful foundation before repository scanning.

### Step 3.3 --- Scan source code for SQL references

1.  Define supported repository input methods and file types.
2.  Start with conservative detection of static SQL strings.
3.  Detect table and column references where reliable.
4.  Record file path and line/location evidence when available.
5.  Handle false positives explicitly.
6.  Never execute scanned code.
7.  Treat repository contents as untrusted input.
8.  Add tests with known positive and negative examples.

### Step 3.4 --- Support common application access patterns

Extend detection incrementally for selected patterns: - SQL embedded in
Java source. - JDBC queries. - Spring JDBC usage. - JPA entity/table
mappings. - ORM annotations and explicit table names. - SQL migration
files. - Configuration metadata that safely identifies logical
databases.

Do not claim every framework or dynamically constructed query is
supported. Record the detector used and its limitations.

### Step 3.5 --- Secure repository ingestion

1.  Set limits for file size, file count, and scan duration.
2.  Exclude generated and dependency directories where appropriate.
3.  Prevent path traversal when processing archives or uploaded
    repositories.
4.  Do not run build scripts or repository code during static scanning.
5.  Do not log credentials or tokens.
6.  Test invalid inputs and oversized scans.

**Milestone 3 exit criteria:** users can register applications, scan
supported source patterns, and inspect evidence-backed dependencies with
explicit confidence and limitations.

------------------------------------------------------------------------

# Milestone 4 --- Dependency Graph

## Goal

Represent relationships between applications, tables, columns, and
database constraints so impact can be traced.

### Step 4.1 --- Define graph nodes and edges

Potential nodes: - Applications and services. - Databases and schemas. -
Tables and columns. - Foreign-key relationships. - Repositories or
source files, where useful for evidence.

Potential edges: - Application reads table. - Application writes
table. - Application references column. - Table references another table
through a foreign key. - Repository contains a detected reference.

Use only relationship types supported by evidence. Distinguish inferred
relationships from explicitly registered ones.

### Step 4.2 --- Implement graph construction

1.  Convert registry records and scan results into graph nodes and
    edges.
2.  Assign stable identifiers.
3.  Prevent duplicate edges.
4.  Preserve evidence, confidence, and timestamps.
5.  Handle unresolved table or column references.
6.  Add graph-construction unit tests.

### Step 4.3 --- Implement traversal

1.  Find applications directly dependent on a table or column.
2.  Trace downstream table relationships.
3.  Support bounded traversal.
4.  Prevent cycles from causing infinite traversal.
5.  Preserve the path explaining why a dependency was found.
6.  Test cycles, disconnected nodes, duplicate edges, and multi-hop
    relationships.

### Step 4.4 --- Choose persistence

Inspect the current persistence layer before deciding whether to use
relational tables, graph storage, or an in-memory representation for an
initial version.

1.  Document trade-offs.
2.  Keep the first implementation as simple as product needs allow.
3.  Add database changes through migrations or additive setup changes.
4.  Preserve existing stored data and database structures.
5.  Test persistence behavior.

**Milestone 4 exit criteria:** the system can construct and query a
testable dependency graph and explain the evidence behind each path.

------------------------------------------------------------------------

# Milestone 5 --- Cross-Application Migration Impact Analysis

## Goal

Connect a proposed database migration to the applications and services
that may be affected.

### Step 5.1 --- Match schema changes to dependencies

1.  Identify changed tables and columns from the schema diff.
2.  Find graph nodes matching those identifiers.
3.  Retrieve direct and indirect dependents.
4.  Track unresolved or ambiguous matches.
5.  Avoid treating name-only matches as definitive when schemas or
    databases differ.

### Step 5.2 --- Calculate application impact

For each potentially affected application, report: - Application
identifier. - Changed table or column. - Type of dependency. - Source
evidence. - Confidence. - Associated schema changes. - Risk inherited
from the schema change. - Reasons for the impact classification. -
Recommended validation or mitigation.

Keep dependency confidence separate from migration risk. A high-risk
change with weak dependency evidence differs from a high-confidence
dependency on a low-risk change.

### Step 5.3 --- Aggregate impact

1.  Aggregate impacts by application and schema object.
2.  Avoid double-counting a single underlying change.
3.  Distinguish direct and transitive impacts.
4.  Explain how overall impact was derived.
5.  Identify applications requiring manual verification.
6.  Test missing dependencies, uncertain matches, and multiple affected
    applications.

### Step 5.4 --- Produce an impact report

Add a backward-compatible response or a separate endpoint for detailed
application impact. Keep the existing `/api/v1/schema/diff` endpoint
working.

The report should distinguish: - Confirmed or manually registered
dependencies. - Statically inferred dependencies. - Low-confidence
matches. - Unresolved references. - Changes with no discovered
dependents.

**Milestone 5 exit criteria:** a schema migration can be connected to
likely affected applications with traceable evidence and explicit
uncertainty.

------------------------------------------------------------------------

# Milestone 6 --- Frontend Visualization and Migration Planning

## Goal

Expose risk and dependency information through the existing React/Vite
interface without removing current functionality.

### Step 6.1 --- Audit the current frontend

1.  Inspect `App.jsx`, `App.css`, `index.css`, `main.jsx`, and
    `SchemaComparison.jsx`.
2.  Record existing interactions and API contracts.
3.  Keep current SQL comparison, risk summary, filters, sorting,
    recommendations, and print report.
4.  Add views without replacing the current UI.

### Step 6.2 --- Improve risk presentation

Add where appropriate: - Clear risk explanations. - Risk factors and
assumptions. - Recommended mitigations. - Warnings for unsupported or
uncertain analysis. - Links from overall risk summaries to affected
changes.

Do not label a migration safe when required analysis was unavailable.

### Step 6.3 --- Add dependency visualization

1.  Choose a graph visualization approach compatible with current
    frontend dependencies and maintenance requirements.
2.  Show applications, tables, and columns as distinct node types.
3.  Show edges with relationship labels and confidence.
4.  Let users select a changed schema object and inspect dependents.
5.  Show evidence and source locations where available.
6.  Provide a list/table fallback for large graphs or unavailable
    visualization.
7.  Include loading, empty, error, and partial-results states.

### Step 6.4 --- Add migration planning

1.  Group related changes into a migration plan.
2.  Order steps where dependencies require ordering.
3.  Identify potentially destructive operations.
4.  Recommend staged migrations where applicable.
5.  Add pre-migration validation and post-migration verification
    checklists.
6.  Distinguish generated suggestions from verified execution plans.
7.  Do not execute migrations against a real database automatically.

### Step 6.5 --- Frontend verification

1.  Run the frontend production build.
2.  Verify existing comparison workflows.
3.  Verify filters, sorting, recommendations, and printing.
4.  Test empty and invalid SQL input.
5.  Test backend errors and unsupported SQL responses.
6.  Test large dependency graphs and fallback rendering.
7.  Preserve existing frontend assets and components.

**Milestone 6 exit criteria:** users can inspect schema differences,
understand risk, explore dependencies, and review a proposed migration
plan without losing existing UI capabilities.

------------------------------------------------------------------------

# Milestone 7 --- Integration, Security, Documentation, and Release

## Goal

Make the system coherent, testable, documented, and safe to demonstrate
or deploy.

### Step 7.1 --- Integration tests

Add tests covering the end-to-end path: 1. Submit SQL inputs. 2. Parse
supported DDL. 3. Build the schema diff. 4. Classify risks and generate
recommendations. 5. Resolve dependencies. 6. Calculate application
impact. 7. Serialize the response. 8. Verify the frontend can consume
the response.

Use controlled test data and avoid relying on a production database.

### Step 7.2 --- API compatibility

1.  Document existing endpoints and response fields.
2.  Preserve the existing diff endpoint and its current behavior.
3.  Prefer additive fields or separate endpoints for new capabilities.
4.  Validate request sizes and input types.
5.  Return clear errors for invalid or unsupported SQL.
6.  Test error status codes and response structures.

### Step 7.3 --- Security and reliability

1.  Validate all user inputs.
2.  Limit SQL input size and repository scans.
3.  Avoid executing arbitrary submitted SQL.
4.  Protect secrets and sensitive configuration.
5.  Restrict filesystem access during repository scanning.
6.  Apply timeouts and bounded graph traversal.
7.  Avoid leaking stack traces or credentials in API responses.
8.  Log useful diagnostics without sensitive data.
9.  Review CORS and authentication needs before deployment.

### Step 7.4 --- Performance and correctness

1.  Test large schemas and scripts.
2.  Test repeated or conflicting migration statements.
3.  Test graph traversal at realistic sizes.
4.  Identify expensive operations and add limits or indexes when
    justified.
5.  Measure before optimizing.
6.  Document known performance limits.

### Step 7.5 --- Documentation

Maintain or add documentation for: - Project purpose and architecture. -
Local setup. - Backend and frontend startup. - API usage and examples. -
Supported MySQL syntax. - Risk rules and assumptions. - Dependency
discovery and confidence. - Migration planning workflow. - Test
commands. - Known limitations. - Security and data-handling behavior.

Keep documentation current. Review and preserve existing documentation
before modifying it.

### Step 7.6 --- Final verification

Run from the appropriate directories:

``` powershell
cd C:\Users\harsh\SchemaGuard\backend
mvn clean test
```

``` powershell
cd C:\Users\harsh\SchemaGuard\frontend
npm run build
```

Then: 1. Review `git status`. 2. Inspect the complete diff. 3. Confirm
no existing files were accidentally deleted. 4. Confirm no secrets or
generated build outputs were added unintentionally. 5. Smoke-test the
main workflow manually. 6. Record known limitations and outstanding
issues. 7. Commit completed work in small, descriptive commits.

**Milestone 7 exit criteria:** tests and builds pass, the documented
workflow works, the API remains compatible, limitations are clear, and
no unintended file deletions occurred.

------------------------------------------------------------------------

# Recommended Implementation Order

1.  Establish a safe understanding of current code and Git state.
2.  Complete and document migration parsing.
3.  Improve risk rules and recommendations.
4.  Add the manual application dependency registry.
5.  Add static source scanning incrementally.
6.  Construct and query the dependency graph.
7.  Calculate cross-application impact.
8.  Add frontend risk explanations and dependency visualization.
9.  Add migration planning.
10. Complete integration tests, security review, documentation, and
    release checks.

Each step should be small and reviewable. When changing an existing
file, preserve unrelated code and behavior. If a step fails, investigate
and repair the change; do not delete current files or use destructive
Git commands to force a clean state.

# Definition of Done

SchemaGuard is ready for its planned completion when: - Supported SQL
migrations are parsed in order and unsupported syntax is clearly
reported. - Schema differences are classified with consistent, tested
risk rules. - Recommendations explain their rationale and limitations. -
Applications and repositories can be registered and scanned using
supported methods. - Dependency graph relationships include evidence and
confidence. - Schema changes can be traced to potentially affected
applications. - The frontend displays risks, dependencies, and migration
planning information. - Existing comparison features and endpoints
continue to work. - Backend tests and the frontend production build
pass. - Setup, supported syntax, risk rules, security behavior, and
limitations are documented. - No existing project files have been
deleted as part of implementation.
