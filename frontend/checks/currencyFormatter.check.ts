import assert from "node:assert/strict";
import { deriveReceivesFromDeposit, toCents } from "../src/shared/utilities/currencyFormatter.ts";

// toCents: invalid non-empty input is null, never a silent zero.
assert.equal(toCents("not-a-number"), null);
assert.equal(toCents("12.3x"), null);
assert.equal(toCents(""), 0n);
assert.equal(toCents("0.00"), 0n);
assert.equal(toCents("9305.79"), 930579n);
assert.equal(toCents("-1.23"), -123n);
// Sub-cent precision is rejected, not truncated — the backend refuses scale > 2.
assert.equal(toCents("1.999"), null);
assert.equal(toCents("1.005"), null);
assert.equal(toCents("1.2"), 120n);

// Pay-driven forward conversion at rate 1.08: deposit 9305.79 → fee 46.53
// (HALF_EVEN of 46.52895), principal 9259.26 → 925926 * 1.08 = 1000000.08 →
// "10000.00" exactly.
assert.equal(deriveReceivesFromDeposit("9305.79", "1.08"), "10000.00");
// Overcoverage: one cent more deposit converts to "10000.01" — the forward
// conversion can overshoot the requested target by a cent, so it is not an
// exact inverse of the backend's minimum-deposit quote.
assert.equal(deriveReceivesFromDeposit("9305.80", "1.08"), "10000.01");

console.log("currencyFormatter checks passed");
