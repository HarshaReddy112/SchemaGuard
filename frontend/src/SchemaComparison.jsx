
function getChangeKind(changeType = "") {
    const type = changeType.toUpperCase();

    if (type.includes("ADD") || type.includes("CREATE")) {
        return "added";
    }

    if (
        type.includes("REMOVE") ||
        type.includes("DROP") ||
        type.includes("DELETE")
    ) {
        return "removed";
    }

    return "modified";
}

function displayValue(value) {
    if (value === null || value === undefined || value === "") {
        return "Not present";
    }

    return String(value);
}

function SchemaComparison({ changes = [] }) {
    if (changes.length === 0) {
        return (
            <section className="visual-comparison result-panel">
                <div className="panel-heading">
                    <div>
                        <h3>Visual Schema Comparison</h3>
                        <p>Side-by-side view of detected schema changes</p>
                    </div>
                </div>
                <div className="comparison-empty">
                    No changes to display for the selected risk filter.
                </div>
            </section>
        );
    }

    return (
        <section className="visual-comparison result-panel">
            <div className="panel-heading">
                <div>
                    <h3>Visual Schema Comparison</h3>
                    <p>Compare the previous and proposed definitions for each detected change.</p>
                </div>
                <span className="count-pill">{changes.length}</span>
            </div>

            <div className="comparison-legend">
                <span><i className="legend-dot added" /> Added</span>
                <span><i className="legend-dot removed" /> Removed</span>
                <span><i className="legend-dot modified" /> Modified</span>
            </div>

            <div className="comparison-column-headings">
                <div className="old-heading">Old schema</div>
                <div className="new-heading">Proposed schema</div>
            </div>

            <div className="comparison-list">
                {changes.map((change, index) => {
                    const kind = getChangeKind(change.changeType);
                    const risk = (change.riskLevel || "LOW").toLowerCase();

                    return (
                        <article
                            className={`comparison-row change-${kind}`}
                            key={`${change.objectType}-${change.tableName}-${change.objectName}-${index}`}
                        >
                            <div className="comparison-object">
                                <div className="comparison-object-title">
                                    <strong>{change.objectName || "Unnamed object"}</strong>
                                    <span className={`risk-tag tag-${risk}`}>
                                        {change.riskLevel || "LOW"}
                                    </span>
                                </div>
                                <p>
                                    {change.tableName || "Database"} · {change.objectType || "Schema object"}
                                </p>
                                <span className={`change-kind kind-${kind}`}>
                                    {kind}
                                </span>
                            </div>

                            <div className="comparison-values">
                                <div className="comparison-value old-value">
                                    <span className="value-label">OLD</span>
                                    <code>{displayValue(change.oldValue)}</code>
                                </div>

                                <div className="comparison-connector" aria-hidden="true">
                                    →
                                </div>

                                <div className="comparison-value new-value">
                                    <span className="value-label">NEW</span>
                                    <code>{displayValue(change.newValue)}</code>
                                </div>
                            </div>

                            <p className="comparison-message">{change.message}</p>
                        </article>
                    );
                })}
            </div>
        </section>
    );
}

export default SchemaComparison;
