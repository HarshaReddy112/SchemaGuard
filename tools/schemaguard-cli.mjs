import { readFile, readdir } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";

const MAX_SQL_CHARS = 500_000;
const MAX_REPORTED_IMPACTS = 50;

export function normalizeApiBase(value) {
    const url = new URL(value);
    if (!["http:", "https:"].includes(url.protocol)
            || url.search || url.hash) {
        throw new Error("SchemaGuard API URL must be an HTTP(S) base URL.");
    }
    const path = url.pathname.replace(/\/+$/, "");
    url.pathname = path.endsWith("/api/v1") ? path : `${path}/api/v1`;
    return url.toString().replace(/\/$/, "");
}

export function formatReport(report) {
    if (!report?.riskScore || !Array.isArray(report.diffResult?.changes)
            || !Array.isArray(report.impacts)) {
        throw new Error("SchemaGuard API returned an incomplete impact report.");
    }

    const risk = report.riskScore;
    const tableSizes = Object.entries(risk.affectedTableSizesBytes || {})
        .map(([table, bytes]) => `${inline(table)}: ${bytes} bytes`)
        .join(", ") || "unavailable";
    const lines = [
        "<!-- schemaguard-migration-review -->",
        "# SchemaGuard migration review",
        "",
        `**Risk: ${inline(risk.riskLevel)} — ${risk.score}/100**`,
        "",
        `Affected applications: ${risk.affectedApplicationCount}; `
            + `APIs: ${risk.affectedApiCount}; `
            + `components: ${risk.affectedComponentCount}.`,
        `Target metadata: ${inline(risk.targetMetadataStatus || "UNKNOWN")}; `
            + `table-size risk: ${risk.tableSizeRiskPoints ?? 0} points; `
            + `table sizes: ${tableSizes}.`,
        `Explicit DDL algorithm risk: ${risk.ddlAlgorithmRiskPoints ?? 0} points.`,
        `DDL algorithm assessment: ${inline(risk.ddlAlgorithmAssessment || "Not provided")}`,
        "",
        "## Schema changes"
    ];

    if (report.diffResult.changes.length === 0) {
        lines.push("- No schema changes detected.");
    } else {
        for (const change of report.diffResult.changes) {
            lines.push(`- **${inline(change.riskLevel)}** `
                + `${inline(change.changeType)} ${inline(change.objectType)} `
                + `\`${inline(change.tableName)}.${inline(change.objectName)}\`: `
                + `${inline(change.description || change.reason || "")}`);
        }
    }

    lines.push("", "## Potentially affected components");
    const impacts = report.impacts.filter(impact => impact.applicationId);
    if (impacts.length === 0) {
        lines.push("- No direct dependency evidence was found. This does not "
            + "prove that applications are unaffected; review unscanned or "
            + "dynamic dependencies manually.");
    } else {
        for (const impact of impacts.slice(0, MAX_REPORTED_IMPACTS)) {
            const endpoint = impact.apiEndpoint
                ? ` — API \`${inline(impact.apiEndpoint)}\``
                : "";
            const source = impact.source
                ? ` — \`${inline(impact.source)}\``
                : "";
            lines.push(`- **${inline(impact.applicationName)}**${endpoint}`
                + `${source} — \`${inline(impact.tableName)}`
                + `${impact.columnName ? `.${inline(impact.columnName)}` : ""}\``
                + ` (${inline(impact.confidence || "UNKNOWN")} confidence)`);
        }
        if (impacts.length > MAX_REPORTED_IMPACTS) {
            lines.push(`- …and ${impacts.length - MAX_REPORTED_IMPACTS} more `
                + "impact record(s). See the SchemaGuard report for details.");
        }
    }

    if (report.applicationSummaries?.length) {
        const uncertain = report.applicationSummaries.filter(summary =>
            summary.status === "NOT_SCANNED"
            || summary.status === "NO_REFERENCE_FOUND");
        if (uncertain.length) {
            lines.push("", "## Coverage requiring review");
            for (const summary of uncertain) {
                lines.push(`- **${inline(summary.applicationName)}**: `
                    + `${inline(summary.status)} in the analyzed source.`);
            }
        }
    }

    if (report.migrationPlan?.length) {
        lines.push("", "## Validation recommendations");
        for (const recommendation of report.migrationPlan) {
            lines.push(`- ${inline(recommendation)}`);
        }
    }

    lines.push("", "_Static dependency analysis is evidence-based and may not "
        + "resolve dynamic SQL or every framework pattern. Review before merge._");
    return lines.join("\n");
}

async function run(argv = process.argv.slice(2), env = process.env) {
    const options = parseArguments(argv);
    if (options.help) {
        process.stdout.write(usage());
        return;
    }

    const apiUrl = options.apiUrl || env.SCHEMAGUARD_API_URL;
    const databaseName = options.databaseName || env.SCHEMAGUARD_DATABASE_NAME;
    const baselinePath = options.baseSchema || env.SCHEMAGUARD_BASE_SCHEMA_FILE;
    const migrationsPath = options.migrationsDir || env.SCHEMAGUARD_MIGRATIONS_DIR;
    if (!apiUrl || !databaseName || !baselinePath || !migrationsPath) {
        throw new Error(
            "Provide --api-url, --database-name, --base-schema, and "
            + "--migrations-dir, or set the matching SCHEMAGUARD_* environment variables."
        );
    }

    const migrationFiles = await collectMigrationFiles(resolve(migrationsPath));
    if (migrationFiles.length === 0) {
        throw new Error(`No SQL migration files found in ${migrationsPath}.`);
    }
    const baseline = await readFile(resolve(baselinePath), "utf8");
    const migrations = await Promise.all(migrationFiles.map(path =>
        readFile(path, "utf8")));
    const proposedSchema = `${baseline}\n${migrations.join("\n")}`;
    if (baseline.length > MAX_SQL_CHARS || proposedSchema.length > MAX_SQL_CHARS) {
        throw new Error(`Schema input exceeds ${MAX_SQL_CHARS} characters.`);
    }

    const endpoint = `${normalizeApiBase(apiUrl)}/schema/diff-with-impact`;
    const headers = { "Content-Type": "application/json" };
    if (env.SCHEMAGUARD_API_TOKEN) {
        headers.Authorization = `Bearer ${env.SCHEMAGUARD_API_TOKEN}`;
    }
    const response = await fetch(endpoint, {
        method: "POST",
        headers,
        body: JSON.stringify({
            databaseName,
            oldSql: baseline,
            newSql: proposedSchema,
            migrationSql: migrations.join("\n")
        }),
        signal: AbortSignal.timeout(30_000)
    });
    const responseBody = await response.text();
    if (!response.ok) {
        throw new Error(`SchemaGuard API returned HTTP ${response.status}: `
            + responseBody.slice(0, 2_000));
    }

    let report;
    try {
        report = JSON.parse(responseBody);
    } catch (error) {
        throw new Error("SchemaGuard API returned invalid JSON.", { cause: error });
    }
    process.stdout.write(`${formatReport(report)}\n`);
}

function parseArguments(argv) {
    const options = {};
    const flags = new Map([
        ["--api-url", "apiUrl"],
        ["--database-name", "databaseName"],
        ["--base-schema", "baseSchema"],
        ["--migrations-dir", "migrationsDir"]
    ]);
    for (let index = 0; index < argv.length; index++) {
        const argument = argv[index];
        if (argument === "--help" || argument === "-h") {
            options.help = true;
            continue;
        }
        const option = flags.get(argument);
        if (!option || !argv[index + 1] || argv[index + 1].startsWith("--")) {
            throw new Error(`Invalid or incomplete argument: ${argument}`);
        }
        options[option] = argv[++index];
    }
    return options;
}

async function collectMigrationFiles(directory) {
    const files = [];
    async function visit(currentDirectory) {
        const entries = await readdir(currentDirectory, { withFileTypes: true });
        for (const entry of entries) {
            const path = join(currentDirectory, entry.name);
            if (entry.isDirectory()) {
                await visit(path);
            } else if (entry.isFile()
                    && /^(?:V[0-9][^/]*__|R__).*\.sql$/i.test(entry.name)) {
                files.push(path);
            }
        }
    }
    await visit(directory);
    return files.sort((left, right) => {
        const leftRepeatable = /^R__/i.test(left.split(/[\\/]/).at(-1));
        const rightRepeatable = /^R__/i.test(right.split(/[\\/]/).at(-1));
        if (leftRepeatable !== rightRepeatable) {
            return leftRepeatable ? 1 : -1;
        }
        return left.localeCompare(
            right, "en", { numeric: true, sensitivity: "base" });
    });
}

function inline(value) {
    return String(value ?? "")
        .replaceAll("\\", "\\\\")
        .replaceAll("`", "\\`")
        .replaceAll("|", "\\|")
        .replaceAll("@", "@\u200b")
        .replace(/[\r\n]+/g, " ");
}

function usage() {
    return [
        "SchemaGuard migration review CLI",
        "",
        "node tools/schemaguard-cli.mjs \\",
        "  --api-url https://schemaguard.example.com/api/v1 \\",
        "  --database-name customer_db \\",
        "  --base-schema schema/baseline.sql \\",
        "  --migrations-dir backend/src/main/resources/db/migration",
        "",
        "Use a baseline schema from before the migrations in the configured folder.",
        ""
    ].join("\n");
}

function inlineExecution() {
    return process.argv[1]
        && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
}

if (inlineExecution()) {
    run().catch(error => {
        process.stderr.write(`SchemaGuard CLI: ${error.message}\n`);
        process.exitCode = 1;
    });
}
