
package com.schemaguard.diff;

import java.util.List;

public record SchemaDiffResult(
        List<SchemaChange> changes
) {
}
