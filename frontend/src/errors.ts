export type FailurePayload = {
  message?: unknown;
  code?: unknown;
  steps?: unknown;
};

export function failureMessage(
  payload: FailurePayload,
  status: number,
): string {
  const defaults: Record<number, [string, string]> = {
    401: [
      "The application session is no longer authorized.",
      "Close and reopen DataBridge to create a new session.",
    ],
    403: [
      "This operation is not permitted.",
      "Check the connection role and target environment, then test the connection.",
    ],
    404: [
      "The requested connection, scenario or transfer was not found.",
      "Refresh the list and select an existing item before trying again.",
    ],
    413: [
      "The import file is too large.",
      "Choose a smaller file or split the import into smaller groups.",
    ],
    500: [
      "The application could not complete the operation.",
      "Check Execution/History before retrying a transfer. Note the operation and contact support if the problem persists.",
    ],
  };
  const fallback = defaults[status] || [
    "The operation could not be completed.",
    "Check the selected connection, scenario and table, then retry. Check Execution/History first if a transfer was running.",
  ];
  const message =
    typeof payload.message === "string" && payload.message.trim()
      ? payload.message
      : fallback[0];
  const steps = Array.isArray(payload.steps)
    ? payload.steps.filter(
        (s): s is string => typeof s === "string" && !!s.trim(),
      )
    : [];
  const code =
    typeof payload.code === "string" ? payload.code : `HTTP-${status}`;
  return [
    message,
    ...(steps.length ? steps : [fallback[1]]).map((s) => `• ${s}`),
    `Reference: ${code}`,
  ].join("\n");
}

export function localGuidance(message: string): string[] {
  const text = message.toLowerCase();
  if (text.includes("• ") || text.includes("reference:")) return [];
  if (text.includes("disconnected") || text.includes("isolated"))
    return [
      "Connect every table to the root by dragging its connection port to another table, or remove tables outside the transfer scope.",
    ];
  if (
    text.includes("source") &&
    (text.includes("connection") || text.includes("owner.table_name"))
  )
    return [
      "Select Source and Target, load Source tables and choose the root table. To add a table by name, enter OWNER.TABLE_NAME.",
    ];
  if (text.includes("column mapping") || text.includes("two different tables"))
    return [
      "Choose different parent and child tables. Complete both columns in every mapping row or remove unused rows.",
    ];
  if (
    text.includes("scenario") &&
    (text.includes("name") || text.includes("json"))
  )
    return [
      "Enter a unique scenario name and select a root table. For imports, choose a JSON file exported by DataBridge.",
    ];
  if (
    text.includes("preview") ||
    text.includes("design changed") ||
    text.includes("source changed")
  )
    return [
      "Close this window and select Start to validate the current design and create a fresh preview.",
    ];
  if (text.includes("this scenario uses"))
    return [
      "Create a new scenario for the other owner, or choose a table owned by the current scenario's schema.",
    ];
  if (text.includes("authentication cancelled"))
    return [
      "Retry the operation and enter the existing password when prompted, or update and test the saved connection in Connections.",
    ];
  if (text.includes("json") || text.includes("unexpected token"))
    return [
      "Choose a valid DataBridge JSON export. If this occurred without importing a file, reopen the application and retry.",
    ];
  return [];
}

export const networkFailure =
  "The application server could not be reached.\n• Check that DataBridge is still running. Reopen the application if necessary.\n• If a transfer was starting or running, inspect Execution/History and the target data before retrying to avoid duplicate writes.\nReference: APP_CONNECTION";
