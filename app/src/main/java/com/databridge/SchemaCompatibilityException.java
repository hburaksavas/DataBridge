package com.databridge;

import java.util.Set;

final class SchemaCompatibilityException extends IllegalArgumentException {
  SchemaCompatibilityException(Set<String> findings) {
    super("Schema compatibility report. The following issues block the selected transfer:\n"
        + String.join("\n", findings));
  }
}
