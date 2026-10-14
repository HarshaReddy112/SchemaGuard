import { useEffect, useMemo, useState } from "react";
import "./DependencyDiscovery.css";

const API_BASE = "http://localhost:8080/api/v1/dependencies";

const initialForm = {
    applicationId: "",
    name: "",
    databaseName: "",
    repository: "",
    description: "",
    localPath: ""
};

async function readApiResponse(response, fallbackMessage) {
    if (!response.ok) {
        const body = await response.text();
        throw new Error(body || fallbackMessage);
    }

    if (response.status === 204) {
        return null;
    }

    return response.json();
}

function DependencyDiscovery() {
    const [form, setForm] = useState(initialForm);
    const [applications, setApplications] = useState([]);
    const [repositoryPaths, setRepositoryPaths] = useState({});
    const [scanResult, setScanResult] = useState(null);
    const [loadingApplications, setLoadingApplications] = useState(true);
    const [registering, setRegistering] = useState(false);
    const [scanning, setScanning] = useState(false);
    const [error, setError] = useState("");
    const [notice, setNotice] = useState("");

    async function refreshApplications() {
        setLoadingApplications(true);
        try {
            const response = await fetch(API_BASE + "/applications");
            const data = await readApiResponse(response, "Could not load registered applications.");
            setApplications(Object.entries(data || {}).map(([applicationId, name]) => ({
                applicationId,
                name
            })));
        } catch (requestError) {
            setError(requestError.message.includes("fetch")
                ? "Cannot reach SchemaGuard. Start the Spring Boot backend on port 8080."
                : requestError.message);
        } finally {
            setLoadingApplications(false);
        }
    }

    useEffect(() => {
        refreshApplications();
    }, []);

    const configuredRepositories = useMemo(
        () => applications.filter(application => (repositoryPaths[application.applicationId] || "").trim()),
        [applications, repositoryPaths]
    );

    const totalEvidence = scanResult?.referencesFound ?? 0;
    const allEvidence = (scanResult?.results || []).flatMap(result =>
        (result.scanResult?.evidence || []).map((evidence, index) => ({
            ...evidence,
            applicationId: result.applicationId,
            applicationName: result.applicationName,
            resultKey: result.applicationId + "-" + evidence.sourceLocation + "-" + evidence.detector + "-" + index
        }))
    );

    function updateForm(event) {
        const { name, value } = event.target;
        setForm(current => ({ ...current, [name]: value }));
    }

    async function registerApplication(event) {
        event.preventDefault();
        setError("");
        setNotice("");
        setScanResult(null);

        const applicationId = form.applicationId.trim();
        const name = form.name.trim();
        const databaseName = form.databaseName.trim();
        const localPath = form.localPath.trim();

        if (!applicationId || !name || !databaseName || !localPath) {
            setError("Application ID, application name, logical database name, and local checkout path are required.");
            return;
        }

        setRegistering(true);
        try {
            const response = await fetch(API_BASE + "/applications", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    applicationId,
                    name,
                    databaseName,
                    repository: form.repository.trim(),
                    description: form.description.trim()
                })
            });

            const data = await readApiResponse(response, "Could not register this application.");
            setApplications(Object.entries(data || {}).map(([id, applicationName]) => ({
                applicationId: id,
                name: applicationName
            })));
            setRepositoryPaths(current => ({ ...current, [applicationId]: localPath }));
            setForm(initialForm);
            setNotice("Registered " + name + ". Its local source path is ready to scan.");
        } catch (requestError) {
            setError(requestError.message.includes("fetch")
                ? "Cannot reach SchemaGuard. Start the Spring Boot backend on port 8080."
                : requestError.message);
        } finally {
            setRegistering(false);
        }
    }

    async function scanRepositories() {
        setError("");
        setNotice("");
        setScanResult(null);

        if (configuredRepositories.length === 0) {
            setError("Enter a local checkout path for at least one registered application.");
            return;
        }

        setScanning(true);
        try {
            const response = await fetch(API_BASE + "/scan/multiple", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    repositories: configuredRepositories.map(application => ({
                        applicationId: application.applicationId,
                        repositoryPath: repositoryPaths[application.applicationId].trim()
                    }))
                })
            });

            const data = await readApiResponse(response, "Dependency scanning failed.");
            setScanResult(data);
            const skippedCount = applications.length - configuredRepositories.length;
            setNotice(skippedCount > 0
                ? "Scanned " + data.repositoriesScanned + " repositories. " + skippedCount + " registered application(s) had no local path and were not scanned."
                : "Scanned " + data.repositoriesScanned + " repositories.");
        } catch (requestError) {
            setError(requestError.message.includes("fetch")
                ? "Cannot reach SchemaGuard. Start the Spring Boot backend on port 8080."
                : requestError.message);
        } finally {
            setScanning(false);
        }
    }

    return (
        <section className="dependency-discovery-section">
            <div className="section-heading">
                <div>
                    <span className="eyebrow">APPLICATION DEPENDENCY DISCOVERY</span>
                    <h2>Find which repositories use your database</h2>
                    <p>
                        Register multiple application repositories, scan their source code,
                        and collect evidence of table and column references.
                    </p>
                </div>
            </div>

            <div className="dependency-info-strip">
                <span className="dependency-info-icon" aria-hidden="true">i</span>
                <p>
                    SchemaGuard scans source files in local repository checkouts. It does not
                    connect to your applications&apos; databases or read business data. The local
                    checkout path is sent to the backend for scanning and is not stored in the
                    application registration record.
                </p>
            </div>

            <div className="dependency-workspace">
                <form className="dependency-form-card" onSubmit={registerApplication}>
                    <div className="dependency-card-heading">
                        <div>
                            <h3>Register an application</h3>
                            <p>Repeat this for each service or application.</p>
                        </div>
                        <span className="dependency-step">01</span>
                    </div>

                    <div className="dependency-form-grid">
                        <label>
                            Application ID
                            <input
                                name="applicationId"
                                value={form.applicationId}
                                onChange={updateForm}
                                placeholder="user-service"
                                autoComplete="off"
                                required
                            />
                        </label>
                        <label>
                            Application name
                            <input
                                name="name"
                                value={form.name}
                                onChange={updateForm}
                                placeholder="User Service"
                                required
                            />
                        </label>
                        <label>
                            Logical database/schema
                            <input
                                name="databaseName"
                                value={form.databaseName}
                                onChange={updateForm}
                                placeholder="customer_db"
                                required
                            />
                        </label>
                        <label>
                            Repository URL (optional)
                            <input
                                name="repository"
                                value={form.repository}
                                onChange={updateForm}
                                placeholder="https://github.com/org/user-service"
                                type="url"
                            />
                        </label>
                        <label className="dependency-field-full">
                            Local repository checkout path
                            <input
                                name="localPath"
                                value={form.localPath}
                                onChange={updateForm}
                                placeholder={"C:\\Users\\you\\projects\\user-service"}
                                autoComplete="off"
                                required
                            />
                            <small>Use the full path visible to the computer running the backend.</small>
                        </label>
                        <label className="dependency-field-full">
                            Description (optional)
                            <input
                                name="description"
                                value={form.description}
                                onChange={updateForm}
                                placeholder="User profile and account APIs"
                            />
                        </label>
                    </div>

                    <button className="dependency-primary-button" type="submit" disabled={registering}>
                        {registering ? "Registering..." : "Register application"}
                        {!registering && <span aria-hidden="true">+</span>}
                    </button>
                </form>

                <div className="dependency-repositories-card">
                    <div className="dependency-card-heading">
                        <div>
                            <h3>Registered repositories</h3>
                            <p>{applications.length} application(s) currently registered</p>
                        </div>
                        <span className="dependency-step">02</span>
                    </div>

                    {loadingApplications ? (
                        <div className="dependency-empty-state">Loading registered applications...</div>
                    ) : applications.length === 0 ? (
                        <div className="dependency-empty-state">
                            <strong>No applications registered yet</strong>
                            <span>Use the form to register your first checked-out repository.</span>
                        </div>
                    ) : (
                        <div className="dependency-repository-list">
                            {applications.map(application => (
                                <div className="dependency-repository-row" key={application.applicationId}>
                                    <div className="dependency-repository-title">
                                        <span className="dependency-repository-mark" aria-hidden="true">
                                            {application.name.slice(0, 1).toUpperCase()}
                                        </span>
                                        <div>
                                            <strong>{application.name}</strong>
                                            <code>{application.applicationId}</code>
                                        </div>
                                    </div>
                                    <label>
                                        Local checkout path
                                        <input
                                            value={repositoryPaths[application.applicationId] || ""}
                                            onChange={event => setRepositoryPaths(current => ({
                                                ...current,
                                                [application.applicationId]: event.target.value
                                            }))}
                                            placeholder={"C:\\projects\\application"}
                                            autoComplete="off"
                                        />
                                    </label>
                                    <span className={"dependency-path-status " + ((repositoryPaths[application.applicationId] || "").trim() ? "path-ready" : "path-missing")}>
                                        {(repositoryPaths[application.applicationId] || "").trim() ? "Path configured" : "Path required"}
                                    </span>
                                </div>
                            ))}
                        </div>
                    )}

                    <div className="dependency-scan-footer">
                        <span>
                            {configuredRepositories.length} of {applications.length} application(s) ready
                        </span>
                        <button
                            className="dependency-primary-button"
                            type="button"
                            onClick={scanRepositories}
                            disabled={scanning || loadingApplications || configuredRepositories.length === 0}
                        >
                            {scanning ? "Scanning..." : "Scan repositories"}
                            {!scanning && <span aria-hidden="true">→</span>}
                        </button>
                    </div>
                </div>
            </div>

            {error && <div className="error-message dependency-feedback" role="alert">{error}</div>}
            {notice && <div className="dependency-success-message" role="status">{notice}</div>}

            {scanResult && (
                <div className="dependency-scan-results">
                    <div className="section-heading dependency-results-heading">
                        <div>
                            <span className="eyebrow">SCAN RESULTS</span>
                            <h2>Discovered database references</h2>
                            <p>Evidence detected in the repositories included in this scan.</p>
                        </div>
                    </div>

                    <div className="dependency-metrics-grid">
                        <div><span>Repositories</span><strong>{scanResult.repositoriesScanned}</strong></div>
                        <div><span>Files scanned</span><strong>{scanResult.scannedFiles}</strong></div>
                        <div><span>References found</span><strong>{totalEvidence}</strong></div>
                    </div>

                    <div className="dependency-warning-list">
                        {(scanResult.warnings || []).map((warning, index) => (
                            <p key={warning + "-" + index}>{warning}</p>
                        ))}
                    </div>

                    {allEvidence.length === 0 ? (
                        <div className="dependency-empty-state">
                            <strong>No references were detected</strong>
                            <span>This may mean the scanner did not recognize the code patterns; it does not prove that the applications have no database dependencies.</span>
                        </div>
                    ) : (
                        <div className="dependency-evidence-list">
                            {allEvidence.map(evidence => (
                                <article className="dependency-evidence-card" key={evidence.resultKey}>
                                    <div className="dependency-evidence-title">
                                        <div>
                                            <strong>{evidence.applicationName}</strong>
                                            <span>{evidence.columnName ? "Column reference" : "Table reference"}</span>
                                        </div>
                                        <span className={"dependency-confidence confidence-" + (evidence.confidence || "MEDIUM").toLowerCase()}>
                                            {evidence.confidence || "UNKNOWN"} confidence
                                        </span>
                                    </div>
                                    <div className="dependency-object-name">
                                        <code>{evidence.tableName}{evidence.columnName ? "." + evidence.columnName : ""}</code>
                                        <span>{evidence.detector}</span>
                                    </div>
                                    <p className="dependency-source-location">{evidence.sourceLocation}</p>
                                    <pre>{evidence.snippet}</pre>
                                </article>
                            ))}
                        </div>
                    )}

                    <p className="dependency-disclaimer">
                        Discovery is static and heuristic. Source references can indicate likely dependencies,
                        but they do not by themselves prove which HTTP API executes the query. Review the evidence
                        before treating an application as impacted or unaffected.
                    </p>
                </div>
            )}
        </section>
    );
}

export default DependencyDiscovery;
