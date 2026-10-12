import { useState } from "react";
import "./App.css";
import SchemaComparison from "./SchemaComparison";

const API_URL = "http://localhost:8080/api/v1/schema/diff";

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

function App() {
    const [oldSql, setOldSql] = useState(initialOldSql);
    const [newSql, setNewSql] = useState(initialNewSql);
    const [report, setReport] = useState(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");
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

        try {
            const response = await fetch(API_URL, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify({ oldSql, newSql })
            });

            if (!response.ok) {
                const message = await response.text();
                throw new Error(message || "Migration analysis failed.");
            }

            const data = await response.json();
            setReport(data);
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
        setError("");
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

                        <SchemaComparison changes={visibleChanges} />

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
