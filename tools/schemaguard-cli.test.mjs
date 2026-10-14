import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { createServer } from "node:http";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";

import { formatReport, normalizeApiBase } from "./schemaguard-cli.mjs";

test("normalizes a SchemaGuard API base without duplicating its version path", () => {
    assert.equal(
        normalizeApiBase("https://guard.example.test"),
        "https://guard.example.test/api/v1"
    );
    assert.equal(
        normalizeApiBase("https://guard.example.test/api/v1/"),
        "https://guard.example.test/api/v1"
    );
});

test("renders changed objects and evidence-backed impacted APIs", () => {
    const report = formatReport({
        diffResult: {
            changes: [{
                riskLevel: "HIGH",
                changeType: "REMOVED",
                objectType: "COLUMN",
                tableName: "users",
                objectName: "email",
                description: "Column removed"
            }]
        },
        impacts: [{
            applicationId: "users-api",
            applicationName: "Users API",
            apiEndpoint: "GET /users",
            tableName: "users",
            columnName: "email",
            source: "src/UserRepository.java",
            confidence: "HIGH"
        }],
        applicationSummaries: [],
        riskScore: {
            score: 90,
            riskLevel: "CRITICAL",
            affectedApplicationCount: 1,
            affectedApiCount: 1,
            affectedComponentCount: 1
        }
    });

    assert.match(report, /Risk: CRITICAL — 90\/100/);
    assert.match(report, /users\.email/);
    assert.match(report, /GET \/users/);
    assert.match(report, /src\/UserRepository\.java/);
});

test("reads baseline and ordered Flyway migrations, then posts an impact request",
    async () => {
        let requestBody;
        const server = createServer(async (request, response) => {
            const chunks = [];
            for await (const chunk of request) chunks.push(chunk);
            requestBody = JSON.parse(Buffer.concat(chunks).toString("utf8"));
            response.writeHead(200, { "Content-Type": "application/json" });
            response.end(JSON.stringify({
                diffResult: { changes: [] },
                impacts: [],
                riskScore: {
                    score: 0,
                    riskLevel: "NONE",
                    affectedApplicationCount: 0,
                    affectedApiCount: 0,
                    affectedComponentCount: 0,
                    targetMetadataStatus: "NOT_CONFIGURED",
                    tableSizeRiskPoints: 0
                }
            }));
        });
        server.listen(0, "127.0.0.1");
        await once(server, "listening");

        const directory = await mkdtemp(join(tmpdir(), "schemaguard-cli-"));
        try {
            const migrationDirectory = join(directory, "migrations");
            await mkdir(migrationDirectory);
            const baseline = "CREATE TABLE users (id INT);";
            await writeFile(join(directory, "baseline.sql"), baseline);
            await writeFile(
                join(migrationDirectory, "V10__add_name.sql"),
                "ALTER TABLE users ADD COLUMN name VARCHAR(50);"
            );
            await writeFile(
                join(migrationDirectory, "V2__add_email.sql"),
                "ALTER TABLE users ADD COLUMN email VARCHAR(255);"
            );

            const cliPath = resolve("tools/schemaguard-cli.mjs");
            const child = spawn(process.execPath, [cliPath], {
                env: {
                    ...process.env,
                    SCHEMAGUARD_API_URL:
                        `http://127.0.0.1:${server.address().port}/api/v1`,
                    SCHEMAGUARD_DATABASE_NAME: "commerce",
                    SCHEMAGUARD_BASE_SCHEMA_FILE:
                        join(directory, "baseline.sql"),
                    SCHEMAGUARD_MIGRATIONS_DIR: migrationDirectory
                },
                stdio: ["ignore", "pipe", "pipe"]
            });
            const [exitCode, stdout, stderr] = await Promise.all([
                once(child, "close"),
                collect(child.stdout),
                collect(child.stderr)
            ]);

            assert.equal(exitCode[0], 0, stderr);
            assert.equal(requestBody.databaseName, "commerce");
            assert.equal(requestBody.oldSql, baseline);
            assert.equal(
                requestBody.newSql,
                `${baseline}\nALTER TABLE users ADD COLUMN email VARCHAR(255);\n`
                    + "ALTER TABLE users ADD COLUMN name VARCHAR(50);"
            );
            assert.equal(
                requestBody.migrationSql,
                "ALTER TABLE users ADD COLUMN email VARCHAR(255);\n"
                    + "ALTER TABLE users ADD COLUMN name VARCHAR(50);"
            );
            assert.match(stdout, /SchemaGuard migration review/);
        } finally {
            await new Promise(resolvePromise => server.close(resolvePromise));
            await rm(directory, { recursive: true, force: true });
        }
    });

function collect(stream) {
    return new Promise((resolvePromise, reject) => {
        const chunks = [];
        stream.on("data", chunk => chunks.push(chunk));
        stream.on("error", reject);
        stream.on("end", () =>
            resolvePromise(Buffer.concat(chunks).toString("utf8")));
    });
}
