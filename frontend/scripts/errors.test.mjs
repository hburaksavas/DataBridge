import { test } from "node:test";
import assert from "node:assert/strict";
import { failureMessage, localGuidance, networkFailure } from "../src/errors.ts";

test("structured server failures preserve the explanation, steps and code", () => {
  const message = failureMessage({ message: "Account locked", code: "ORA-28000", steps: ["Contact DBA", "Test again"] }, 422);
  assert.equal(message, "Account locked\n• Contact DBA\n• Test again\nReference: ORA-28000");
  assert.deepEqual(localGuidance(message), []);
});

test("invalid payload fields and non-JSON errors retain actionable fallbacks", () => {
  assert.match(failureMessage({message: {}, steps: [null, 5], code: 12}, 401), /reopen DataBridge/);
  assert.match(failureMessage({}, 413), /smaller file/);
  assert.match(failureMessage({}, 500), /Execution\/History/);
  assert.match(networkFailure, /before retrying/);
});

test("local design errors point to the relevant controls", () => {
  assert.match(localGuidance("Tables disconnected from the root: ORDERS")[0], /connection port/);
  assert.match(localGuidance("Complete every column mapping.")[0], /mapping row/);
  assert.match(localGuidance("A current preview is required.")[0], /Start/);
  assert.match(localGuidance("This scenario uses APP. Create a new scenario for tables in OTHER.")[0], /other owner/);
});
