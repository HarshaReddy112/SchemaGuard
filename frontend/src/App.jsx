import { useState } from "react";
import "./App.css";

const API_BASE = import.meta.env.VITE_API_BASE_URL || "/api/v1";

const initialOldSql = `CREATE TABLE users (
    id BIGINT NOT NULL,
    name VARCHAR(100),
    email VARCHAR(255),
    PRIMARY KEY (id)
);`;

const initialNewSql = `CREATE TABLE users (
    id BIGINT NOT NULL,
    name VARCHAR(50),
    PRIMARY KEY (id)
);`;

function formatBytes(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    const units = ["KB", "MB", "GB", "TB"];
    let value = bytes;
    let unit = -1;
    do {
        value /= 1024;
        unit += 1;
    } while (value >= 1024 && unit < units.length - 1);
    return `${value.toFixed(1)} ${units[unit]}`;
}

function App() {
    const [oldSql, setOldSql] = useState(initialOldSql);
    const [newSql, setNewSql] = useState(initialNewSql);
    const [report, setReport] = useState(null);
    const [impactReport, setImpactReport] = useState(null);
    const [loading, setLoading] = useState(false);
    const [registering, setRegistering] = useState(false);
    const [error, setError] = useState("");
    const [registryMessage, setRegistryMessage] = useState("");
    const [applicationId, setApplicationId] = useState("");
    const [applicationName, setApplicationName] = useState("");
    const [databaseName, setDatabaseName] = useState("application_db");
    const [tableName, setTableName] = useState("");
    const [columnName, setColumnName] = useState("");
    const [dependencyType, setDependencyType] = useState("READ_WRITE");
    const [scanning, setScanning] = useState(false);
    const [scanMessage, setScanMessage] = useState("");
    const [repositoryScans, setRepositoryScans] = useState([
        { id: 1, applicationId: "", name: "", repositoryPath: "" }
    ]);
    const [repositoryScanResults, setRepositoryScanResults] = useState({});
    const [riskFilter, setRiskFilter] = useState("ALL");
    const [sortByRisk, setSortByRisk] = useState(true);

    async function analyzeMigration() {
        if (!oldSql.trim() || !newSql.trim()) {
            setError("Enter both the old and new SQL schemas.");
            return;
        }

        setLoading(true);
        setError("");
        setReport(null);
        setImpactReport(null);

        try {
            const response = await fetch(`${API_BASE}/schema/diff-with-impact`, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify({ oldSql, newSql, databaseName })
            });

            if (!response.ok) {
                const message = await response.text();
                throw new Error(message || "Migration analysis failed.");
            }

            const data = await response.json();
            setReport(data.diffResult ?? data);
            setImpactReport(data.riskScore ? data : null);
        } catch (err) {
            setError(
                err.message.includes("fetch")
                    ? "Cannot connect to the backend. Make sure Spring Boot is running on port 8080."
                    : err.message
            );
        } finally {
            setLoading(false);
        }
    }

    function clearReport() {
        setReport(null);
        setImpactReport(null);
        setError("");
    }

    async function registerDependency() {
        if (!applicationId.trim() || !applicationName.trim()
                || !databaseName.trim() || !tableName.trim()) {
            setRegistryMessage("Application ID, name, database, and table are required.");
            return;
        }

        setRegistering(true);
        setRegistryMessage("");
        try {
            let response = await fetch(`${API_BASE}/dependencies/applications`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    applicationId,
                    name: applicationName,
                    databaseName
                })
            });
            if (!response.ok) {
                throw new Error(await response.text());
            }

            response = await fetch(`${API_BASE}/dependencies/entries`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    applicationId,
                    tableName,
                    columnName: columnName.trim() || null,
                    dependencyType,
                    source: "manual-registry",
                    evidence: "Registered through the SchemaGuard UI",
                    confidence: "HIGH"
                })
            });
            if (!response.ok) {
                throw new Error(await response.text());
            }
            setRegistryMessage(`Dependency saved for ${applicationName}. Run analysis to see its impact.`);
        } catch (err) {
            setRegistryMessage(err.message || "Could not register dependency.");
        } finally {
            setRegistering(false);
        }
    }

    function updateRepositoryScan(id, field, value) {
        setRepositoryScans(scans => scans.map(scan =>
            scan.id === id ? { ...scan, [field]: value } : scan
        ));
    }

    function addRepositoryScan() {
        const id = Math.max(0, ...repositoryScans.map(scan => scan.id)) + 1;
        setRepositoryScans(scans => [
            ...scans,
            { id, applicationId: "", name: "", repositoryPath: "" }
        ]);
    }

    function removeRepositoryScan(id) {
        setRepositoryScans(scans => scans.filter(scan => scan.id !== id));
        setRepositoryScanResults(results => {
            const nextResults = { ...results };
            delete nextResults[id];
            return nextResults;
        });
    }

    async function scanApplicationRepositories() {
        if (!databaseName.trim() || repositoryScans.length === 0) {
            setScanMessage("Enter a database and at least one application repository.");
            return;
        }
        const incompleteScan = repositoryScans.find(scan =>
            !scan.applicationId.trim() || !scan.name.trim()
                || !scan.repositoryPath.trim()
        );
        if (incompleteScan) {
            setScanMessage("Complete the application ID, name, and repository for every row.");
            return;
        }
        const applicationIds = repositoryScans.map(scan =>
            scan.applicationId.trim().toLowerCase()
        );
        if (new Set(applicationIds).size !== applicationIds.length) {
            setScanMessage("Each application must have a unique application ID.");
            return;
        }

        setScanning(true);
        setScanMessage("");
        let successfulScans = 0;
        for (const scan of repositoryScans) {
            setRepositoryScanResults(results => ({
                ...results,
                [scan.id]: { status: "SCANNING" }
            }));
            try {
                const registrationResponse = await fetch(
                    `${API_BASE}/dependencies/applications`, {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({
                            applicationId: scan.applicationId.trim(),
                            name: scan.name.trim(),
                            databaseName: databaseName.trim()
                        })
                    });
                if (!registrationResponse.ok) {
                    throw new Error(await registrationResponse.text());
                }
                const response = await fetch(`${API_BASE}/dependencies/scan`, {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        applicationId: scan.applicationId.trim(),
                        repositoryPath: scan.repositoryPath.trim()
                    })
                });
                if (!response.ok) {
                    throw new Error(await response.text());
                }
                const result = await response.json();
                successfulScans += 1;
                const tables = [...new Set(
                    result.dependencies.map(dependency => dependency.tableName)
                )].sort((left, right) => left.localeCompare(right));
                const componentEvidence = new Map();
                result.dependencies.forEach(dependency => {
                    const name = dependency.apiEndpoint
                        ? `${dependency.apiEndpoint} · ${dependency.source}`
                        : dependency.source || "API/component not resolved";
                    const entry = componentEvidence.get(name) || {
                        name,
                        tables: new Set(),
                        dependencyTypes: new Set(),
                        evidence: new Set()
                    };
                    entry.tables.add(dependency.tableName);
                    entry.dependencyTypes.add(dependency.dependencyType);
                    if (dependency.evidence) {
                        entry.evidence.add(dependency.evidence);
                    }
                    componentEvidence.set(name, entry);
                });
                setRepositoryScanResults(results => ({
                    ...results,
                    [scan.id]: {
                        status: "SCANNED",
                        message: `Scanned ${result.scannedFiles} files${result.truncated ? " (scan limit reached; results are incomplete)" : ""}.`,
                        tables,
                        components: [...componentEvidence.values()].map(component => ({
                            ...component,
                            tables: [...component.tables].sort((left, right) =>
                                left.localeCompare(right)
                            ),
                            dependencyTypes: [...component.dependencyTypes].sort(),
                            evidence: [...component.evidence].sort()
                        }))
                    }
                }));
            } catch (err) {
                setRepositoryScanResults(results => ({
                    ...results,
                    [scan.id]: {
                        status: "ERROR",
                        message: err.message || "Could not scan this repository."
                    }
                }));
            }
        }
        setScanMessage(
            `Scanned ${successfulScans} of ${repositoryScans.length} application repositories. Run schema analysis to see impact and scan coverage.`
        );
        setScanning(false);
    }

    const riskClass = report?.overallRisk?.toLowerCase() || "none";
    
    const riskOrder = {
    HIGH: 0,
    MEDIUM: 1,
    LOW: 2
    };

    const visibleChanges = (report?.changes || []).filter(change =>
        riskFilter === "ALL" || change.riskLevel === riskFilter)
    .sort((a, b) =>
        sortByRisk
            ? (riskOrder[a.riskLevel] ?? 3) -
              (riskOrder[b.riskLevel] ?? 3)
            : 0
    );
    const applicationGroups = impactReport?.applicationSummaries || [];
    const untrackedImpacts = (impactReport?.impacts || [])
        .filter(impact => !impact.applicationId);

    return (
        <div className="app-shell">
            <header className="topbar">
                <a className="brand" href="#">
                    <span className="brand-mark">S</span>
                    <span>SchemaGuard</span>
                </a>
                <span className="backend-status">
                    <span className="status-dot" />
                    Migration Analyzer
                </span>
            </header>

            <main className="main-content">
                <section className="hero">
                    <span className="eyebrow">DATABASE CHANGE INTELLIGENCE</span>
                    <h1>Know the risk before you migrate.</h1>
                    <p>
                        Compare database schemas, detect breaking changes,
                        and review migration risks before deployment.
                    </p>
                </section>

                <section className="schema-section">
                    <div className="section-heading">
                        <div>
                            <h2>Compare schemas</h2>
                            <p>Paste the current and proposed SQL definitions.</p>
                        </div>
                        <button
                            className="text-button"
                            onClick={() => {
                                setOldSql("");
                                setNewSql("");
                                clearReport();
                            }}
                        >
                            Clear inputs
                        </button>
                    </div>

                    <div className="editor-grid">
                        <div className="editor-card">
                            <div className="editor-header">
                                <div className="editor-title">
                                    <span className="editor-dot old-dot" />
                                    <span>Old schema</span>
                                </div>
                                <span className="editor-label">CURRENT</span>
                            </div>
                            <textarea
                                value={oldSql}
                                onChange={event => setOldSql(event.target.value)}
                                placeholder="Paste your current CREATE TABLE statements..."
                                spellCheck="false"
                                aria-label="Old SQL schema"
                            />
                        </div>

                        <div className="editor-card">
                            <div className="editor-header">
                                <div className="editor-title">
                                    <span className="editor-dot new-dot" />
                                    <span>New schema</span>
                                </div>
                                <span className="editor-label">PROPOSED</span>
                            </div>
                            <textarea
                                value={newSql}
                                onChange={event => setNewSql(event.target.value)}
                                placeholder="Paste your proposed CREATE TABLE statements..."
                                spellCheck="false"
                                aria-label="New SQL schema"
                            />
                        </div>
                    </div>

                    <div className="analyze-row">
                        <p>Analysis runs against your local SchemaGuard API.</p>
                        <button
                            className="analyze-button"
                            onClick={analyzeMigration}
                            disabled={loading}
                        >
                            {loading ? "Analyzing..." : "Analyze Migration"}
                            {!loading && <span aria-hidden="true">→</span>}
                        </button>
                    </div>

                    {error && (
                        <div className="error-message" role="alert">
                            {error}
                        </div>
                    )}
                </section>

                <section className="dependency-setup">
                    <details>
                        <summary>Register and scan application repositories</summary>
                        <p>
                            Register and scan multiple application repositories. A missing match means
                            no reference was found in analyzed files, not that the application is unaffected.
                        </p>
                        <label className="database-scope-field">
                            Database being analyzed
                            <input value={databaseName} onChange={event => setDatabaseName(event.target.value)} />
                        </label>
                        <div className="repository-scan-list">
                            {repositoryScans.map((scan, index) => (
                                <div className="repository-scan-row" key={scan.id}>
                                    <div className="repository-scan-heading">
                                        <strong>Application {index + 1}</strong>
                                        {repositoryScans.length > 1 && (
                                            <button
                                                type="button"
                                                className="text-button"
                                                onClick={() => removeRepositoryScan(scan.id)}
                                                disabled={scanning}
                                            >
                                                Remove
                                            </button>
                                        )}
                                    </div>
                                    <label>
                                        Application ID
                                        <input
                                            value={scan.applicationId}
                                            onChange={event => updateRepositoryScan(scan.id, "applicationId", event.target.value)}
                                            placeholder="orders-api"
                                        />
                                    </label>
                                    <label>
                                        Application name
                                        <input
                                            value={scan.name}
                                            onChange={event => updateRepositoryScan(scan.id, "name", event.target.value)}
                                            placeholder="Order Service"
                                        />
                                    </label>
                                    <label>
                                        Repository folder or public GitHub URL
                                        <input
                                            value={scan.repositoryPath}
                                            onChange={event => updateRepositoryScan(scan.id, "repositoryPath", event.target.value)}
                                            placeholder="https://github.com/owner/repository"
                                        />
                                    </label>
                                    {repositoryScanResults[scan.id] && (
                                        <div className={`repository-scan-result status-${repositoryScanResults[scan.id].status.toLowerCase()}`}>
                                            <p className="repository-scan-status" role="status">
                                                {repositoryScanResults[scan.id].status === "SCANNING"
                                                    ? "Scanning..."
                                                    : repositoryScanResults[scan.id].message}
                                            </p>
                                            {repositoryScanResults[scan.id].status === "SCANNED" && (
                                                <>
                                                    <div className="scan-detail-section">
                                                        <strong>Database tables found</strong>
                                                        {repositoryScanResults[scan.id].tables.length > 0 ? (
                                                            <ul className="scan-detail-list">
                                                                {repositoryScanResults[scan.id].tables.map(table => (
                                                                    <li key={table}>{table}</li>
                                                                ))}
                                                            </ul>
                                                        ) : (
                                                            <p>No table references were found in the analyzed files.</p>
                                                        )}
                                                    </div>
                                                    <div className="scan-detail-section">
                                                        <strong>APIs and source components found</strong>
                                                        {repositoryScanResults[scan.id].components.length > 0 ? (
                                                            <ul className="scan-detail-list scan-component-list">
                                                                {repositoryScanResults[scan.id].components.map(component => (
                                                                    <li key={component.name}>
                                                                        <span>{component.name}</span>
                                                                        <small>
                                                                            Uses: {component.tables.join(", ")}
                                                                            {" · "}
                                                                            {component.dependencyTypes.join(", ")}
                                                                        </small>
                                                                        {component.evidence.map(evidence => (
                                                                            <small key={evidence}>{evidence}</small>
                                                                        ))}
                                                                    </li>
                                                                ))}
                                                            </ul>
                                                        ) : (
                                                            <p>No API or source component references were found in the analyzed files.</p>
                                                        )}
                                                    </div>
                                                </>
                                            )}
                                        </div>
                                    )}
                                </div>
                            ))}
                        </div>
                        <p className="repository-help">
                            Public GitHub repository URLs and local folder paths are supported.
                            Private repositories and links to subfolders are not supported.
                        </p>
                        <button className="text-button" onClick={addRepositoryScan} disabled={scanning}>
                            + Add another application repository
                        </button>
                        <button className="scan-button" onClick={scanApplicationRepositories} disabled={scanning}>
                            {scanning ? "Scanning applications..." : "Register and scan all repositories"}
                        </button>
                        {scanMessage && <p className="registry-message" role="status">{scanMessage}</p>}
                        <details className="manual-dependency">
                            <summary>Register one dependency manually</summary>
                            <div className="dependency-form">
                                <label>
                                    Application ID
                                    <input value={applicationId} onChange={event => setApplicationId(event.target.value)} placeholder="orders-api" />
                                </label>
                                <label>
                                    Application name
                                    <input value={applicationName} onChange={event => setApplicationName(event.target.value)} placeholder="Order Service" />
                                </label>
                                <label>
                                    Table
                                    <input value={tableName} onChange={event => setTableName(event.target.value)} placeholder="orders" />
                                </label>
                                <label>
                                    Column (optional)
                                    <input value={columnName} onChange={event => setColumnName(event.target.value)} placeholder="status" />
                                </label>
                                <label>
                                    Usage
                                    <select value={dependencyType} onChange={event => setDependencyType(event.target.value)}>
                                        <option value="READ_WRITE">Reads and writes</option>
                                        <option value="READ">Reads</option>
                                        <option value="WRITE">Writes</option>
                                    </select>
                                </label>
                            </div>
                            <button className="text-button" onClick={registerDependency} disabled={registering}>
                                {registering ? "Saving..." : "Save dependency"}
                            </button>
                            {registryMessage && <p className="registry-message" role="status">{registryMessage}</p>}
                        </details>
                    </details>
                </section>

                {report && (
                    <section className="results-section">
                        <div className="section-heading">
                            <div>
                                <span className="eyebrow">ANALYSIS RESULTS</span>
                                <h2>Migration risk report</h2>
                                <p>
                                    {report.changes.length} change
                                    {report.changes.length === 1 ? "" : "s"} detected
                                </p>
                            </div>
                            <button
                                className="text-button"
                                onClick={() => window.print()}
                            >
                                Export report
                            </button>
                        </div>

                        <div className={`overall-card risk-${riskClass}`}>
                            <div>
                                <span className="result-label">OVERALL RISK</span>
                                <h3>{report.overallRisk}</h3>
                                <p>
                                    {report.overallRisk === "NONE"
                                        ? "No schema changes were detected."
                                        : report.overallRisk === "HIGH"
                                            ? "Review these changes carefully before deployment."
                                            : report.overallRisk === "MEDIUM"
                                                ? "Review compatibility and operational impact."
                                                : "Review the changes before deployment."}
                                </p>
                            </div>
                            <div className="risk-symbol" aria-hidden="true">
                                {report.overallRisk === "NONE" ? "✓" : "!"}
                            </div>
                        </div>

                        {impactReport?.riskScore ? (
                            <>
                                <div className="numeric-risk-card">
                                    <div>
                                        <span className="result-label">DEPENDENCY-AWARE RISK</span>
                                        <strong>{impactReport.riskScore.score}<small>/100</small></strong>
                                        <span className={`score-risk-level risk-${impactReport.riskScore.riskLevel.toLowerCase()}`}>
                                            {impactReport.riskScore.riskLevel} RISK
                                        </span>
                                    </div>
                                    <div className="risk-score-breakdown">
                                        <strong>Impact factors</strong>
                                        <ul>
                                            <li>{impactReport.riskScore.affectedApplicationCount} affected application(s)</li>
                                            <li>{impactReport.riskScore.affectedApiCount} affected API(s)</li>
                                            <li>{impactReport.riskScore.affectedComponentCount} affected API/source component(s)</li>
                                            <li>
                                                Target metadata: {impactReport.riskScore.targetMetadataStatus}
                                                {impactReport.riskScore.targetDatabaseVersion
                                                    ? ` (MySQL ${impactReport.riskScore.targetDatabaseVersion})`
                                                    : ""}
                                            </li>
                                            <li>
                                                Table-size risk: {impactReport.riskScore.tableSizeRiskPoints} points
                                            </li>
                                            <li>
                                                Explicit DDL algorithm risk: {impactReport.riskScore.ddlAlgorithmRiskPoints} points
                                            </li>
                                        </ul>
                                        {Object.entries(
                                            impactReport.riskScore.affectedTableSizesBytes || {}
                                        ).length > 0 && (
                                            <p>
                                                Affected table sizes: {Object.entries(
                                                    impactReport.riskScore.affectedTableSizesBytes
                                                ).map(([table, bytes]) =>
                                                    `${table} (${formatBytes(bytes)})`
                                                ).join(", ")}
                                            </p>
                                        )}
                                        <small>
                                            DDL algorithm assessment: {impactReport.riskScore.ddlAlgorithmAssessment}
                                        </small>
                                        <p>
                                            Schema severity: {impactReport.riskScore.schemaSeverityPoints} ·
                                            {" "}Application exposure: {impactReport.riskScore.applicationExposurePoints} ·
                                            {" "}API/component exposure: {impactReport.riskScore.componentExposurePoints} ·
                                            {" "}Evidence confidence: {impactReport.riskScore.dependencyConfidencePoints}
                                        </p>
                                        <small>{impactReport.riskScore.explanation}</small>
                                    </div>
                                </div>
                                <div className="result-panel">
                                    <div className="panel-heading">
                                        <div>
                                            <h3>Potentially affected applications</h3>
                                            <p>Shows matching evidence and distinguishes scanned applications from apps whose dependencies remain unknown</p>
                                        </div>
                                    </div>
                                    {applicationGroups.length === 0 && untrackedImpacts.length === 0 ? (
                                        <div className="empty-state">
                                            <strong>No affected applications found</strong>
                                            <p>Register an application and scan its repository before running the impact analysis.</p>
                                        </div>
                                    ) : (
                                        <div className="impact-list">
                                            {applicationGroups.map(application => {
                                                const affectedTables = [...new Set(
                                                    application.impacts.map(impact =>
                                                        `${impact.tableName}${impact.columnName ? `.${impact.columnName}` : ""}`
                                                    )
                                                )];
                                                const apiEvidenceByLabel = new Map();
                                                application.impacts.forEach(impact => {
                                                    const label = impact.apiEndpoint
                                                        ? `${impact.apiEndpoint} · ${impact.source}`
                                                        : impact.source || "API route not resolved";
                                                    const entry = apiEvidenceByLabel.get(label) || {
                                                        label,
                                                        tables: [],
                                                        dependencyTypes: [],
                                                        confidences: [],
                                                        evidence: []
                                                    };
                                                    const tableName = `${impact.tableName}${impact.columnName ? `.${impact.columnName}` : ""}`;
                                                    if (!entry.tables.includes(tableName)) {
                                                        entry.tables.push(tableName);
                                                    }
                                                    if (impact.dependencyType
                                                            && !entry.dependencyTypes.includes(impact.dependencyType)) {
                                                        entry.dependencyTypes.push(impact.dependencyType);
                                                    }
                                                    if (impact.confidence
                                                            && !entry.confidences.includes(impact.confidence)) {
                                                        entry.confidences.push(impact.confidence);
                                                    }
                                                    if (impact.evidence
                                                            && !entry.evidence.includes(impact.evidence)) {
                                                        entry.evidence.push(impact.evidence);
                                                    }
                                                    apiEvidenceByLabel.set(label, entry);
                                                });
                                                const apiEvidence = [...apiEvidenceByLabel.values()];
                                                return (
                                                    <article className="impact-item application-impact" key={application.applicationId}>
                                                        <div>
                                                            <strong>{application.applicationName}</strong>
                                                            <span>{application.databaseName || databaseName}</span>
                                                        </div>
                                                        <span className={`application-status status-${application.status.toLowerCase()}`}>
                                                            {application.status === "POTENTIALLY_AFFECTED"
                                                                ? "Potentially affected"
                                                                : application.status === "NO_REFERENCE_FOUND"
                                                                    ? "No matching reference found"
                                                                    : application.status === "NOT_SCANNED"
                                                                        ? "Not scanned"
                                                                        : "No changes to evaluate"}
                                                        </span>
                                                        {application.status === "POTENTIALLY_AFFECTED" ? (
                                                            <>
                                                                <p>Affected schema objects: {affectedTables.join(", ")}</p>
                                                                <strong className="api-list-heading">Potentially affected components / APIs</strong>
                                                                <ul className="api-evidence-list">
                                                                    {apiEvidence.map(api => (
                                                                        <li key={api.label}>
                                                                            <span>{api.label}</span>
                                                                            <small>References: {api.tables.join(", ")}</small>
                                                                            <small>
                                                                                {api.dependencyTypes.join(", ")}
                                                                                {api.confidences.length > 0
                                                                                    ? ` · ${api.confidences.join(", ")} confidence`
                                                                                    : ""}
                                                                            </small>
                                                                            {api.evidence.map(evidence => (
                                                                                <small key={evidence}>{evidence}</small>
                                                                            ))}
                                                                        </li>
                                                                    ))}
                                                                </ul>
                                                            </>
                                                        ) : application.status === "NO_REFERENCE_FOUND" ? (
                                                            <p>
                                                                No matching reference was found in {application.analyzedFileCount} analyzed file(s)
                                                                {application.repository ? ` from ${application.repository}` : ""}. This does not confirm
                                                                the application is unaffected.
                                                                {application.scanTruncated && " The scan hit its file limit; some files were not analyzed."}
                                                            </p>
                                                        ) : application.status === "NOT_SCANNED" ? (
                                                            <p>No repository scan has been completed for this registered application. Its impact is unknown.</p>
                                                        ) : (
                                                            <p>No schema changes were detected for this analysis.</p>
                                                        )}
                                                    </article>
                                                );
                                            })}
                                            {untrackedImpacts.map((impact, index) => (
                                                <article className="impact-item" key={`untracked-${impact.tableName}-${index}`}>
                                                    <div>
                                                        <strong>{impact.applicationName}</strong>
                                                        <span>{impact.databaseName} · {impact.tableName}</span>
                                                    </div>
                                                    <p>{impact.evidence}</p>
                                                </article>
                                            ))}
                                        </div>
                                    )}
                                </div>
                            </>
                        ) : (
                            <div className="score-unavailable" role="status">
                                <strong>Dependency risk score was not returned</strong>
                                <p>
                                    This analysis endpoint should include a risk score. Restart the
                                    SchemaGuard backend and run the analysis again.
                                </p>
                            </div>
                        )}

                        <div className="risk-grid">
                            <div className="risk-card high-card">
                                <span>High risk</span>
                                <strong>{report.highRiskCount}</strong>
                                <small>Requires careful review</small>
                            </div>
                            <div className="risk-card medium-card">
                                <span>Medium risk</span>
                                <strong>{report.mediumRiskCount}</strong>
                                <small>Check compatibility</small>
                            </div>
                            <div className="risk-card low-card">
                                <span>Low risk</span>
                                <strong>{report.lowRiskCount}</strong>
                                <small>Lower estimated impact</small>
                            </div>
                        </div>

                        <div className="result-panel">
                            <div className="panel-heading">
                                <div>
                                    <h3>Detected changes</h3>
                                    <p>Changes identified by the schema diff engine</p>
                                </div>
                                <span className="count-pill">
                                    {report.changes.length}
                                </span>
                            </div>

                            {report.changes.length === 0 ? (
    <div className="empty-state">
        <span>✓</span>
        <strong>Schemas match</strong>
        <p>No changes were detected between these definitions.</p>
    </div>
) : (
    <>
        <div className="change-controls">
            <label>
                Risk level
                <select
                    value={riskFilter}
                    onChange={event => setRiskFilter(event.target.value)}
                >
                    <option value="ALL">All risks</option>
                    <option value="HIGH">High</option>
                    <option value="MEDIUM">Medium</option>
                    <option value="LOW">Low</option>
                </select>
            </label>

            <label className="sort-control">
                <input
                    type="checkbox"
                    checked={sortByRisk}
                    onChange={event => setSortByRisk(event.target.checked)}
                />
                High risk first
            </label>
        </div>

        {visibleChanges.length === 0 ? (
            <div className="empty-state">
                <strong>No matching changes</strong>
                <p>Try selecting a different risk level.</p>
            </div>
        ) : (
            <div className="change-list">
                {visibleChanges.map((change, index) => (
                    <article
                        className="change-item"
                        key={`${change.objectType}-${change.tableName}-${change.objectName}-${index}`}
                    >
                        <div className="change-main">
                            <div className="change-title">
                                <strong>{change.objectName}</strong>
                                <span className={`risk-tag tag-${change.riskLevel.toLowerCase()}`}>
                                    {change.riskLevel}
                                </span>
                            </div>

                            <p>
                                {change.tableName} · {change.objectType} · {change.changeType}
                            </p>

                            <span className="change-message">
                                {change.message}
                            </span>

                            <div className="value-comparison">
                                <div>
                                    <span>OLD</span>
                                    <code>{change.oldValue ?? "—"}</code>
                                </div>

                                <span className="change-arrow">→</span>

                                <div>
                                    <span>NEW</span>
                                    <code>{change.newValue ?? "—"}</code>
                                </div>
                            </div>
                        </div>
                    </article>
                ))}
            </div>
        )}
    </>
)}
                        </div>

                        <div className="result-panel recommendations-panel">
                            <div className="panel-heading">
                                <div>
                                    <h3>Recommendations</h3>
                                    <p>Suggested next steps based on detected changes</p>
                                </div>
                                <span className="recommendation-icon">✦</span>
                            </div>
                            <ul className="recommendation-list">
                                {report.recommendations.map((recommendation, index) => (
                                    <li key={index}>
                                        <span className="recommendation-check">✓</span>
                                        <span>{recommendation}</span>
                                    </li>
                                ))}
                            </ul>
                            <p className="report-disclaimer">
                                Risk levels are heuristic estimates. Validate migrations
                                against real data and in a staging environment before deployment.
                            </p>
                        </div>
                    </section>
                )}

                <footer className="footer">
                    <span>SchemaGuard</span>
                    <span>Analyze early. Migrate confidently.</span>
                </footer>
            </main>
        </div>
    );
}

export default App;
