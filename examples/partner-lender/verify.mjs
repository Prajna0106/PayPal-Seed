#!/usr/bin/env node
// Lender check: verify a Seed credit record from a member's share link.
// Usage: node verify.mjs <share-url-or-token>
// Needs Node 20+. No dependencies, no build step.

const BASE_URL = (process.env.SEED_API_URL ?? "https://seed-api-4hui.onrender.com").replace(/\/+$/, "");

/**
 * @typedef {"NEW"|"BUILDING"|"STEADY"|"TRUSTED"|"EXEMPLARY"} RecordLevel
 * @typedef {Object} VerifiedRecord
 * @property {boolean} valid
 * @property {RecordLevel} level
 * @property {string} [since] business date, YYYY-MM-DD
 * @property {number} [loansRepaidOnPlan]
 * @property {number} [loansCompletedWithFundHelp]
 * @property {string} [onTimeBand]
 * @property {string} issuedAt
 * @property {string} expiresAt
 * @typedef {Object} Problem
 * @property {string} code
 * @property {string} [title]
 */

/**
 * The token is the last path segment of the share URL. A bare token is accepted too.
 * @param {string} input
 * @returns {string}
 */
export function extractToken(input) {
  const text = input.trim();
  if (!/^https?:\/\//i.test(text)) return text;
  const parts = new URL(text).pathname.split("/").filter(Boolean);
  const token = parts.at(-1);
  if (!token) throw new Error("That link has no token in it.");
  return decodeURIComponent(token);
}

/** @param {string} value */
function describe(value) {
  return value === undefined ? "not shared" : String(value);
}

/** @param {VerifiedRecord} r */
function printDecision(r) {
  console.log("Seed credit record");
  console.log("------------------");
  console.log(`Still valid:               ${r.valid ? "yes" : "NO - a default happened since this link was made"}`);
  console.log(`Level:                     ${r.level}`);
  console.log(`Record began:              ${describe(r.since)}`);
  console.log(`Loans repaid on plan:      ${describe(r.loansRepaidOnPlan)}`);
  console.log(`Loans completed with help: ${describe(r.loansCompletedWithFundHelp)}`);
  console.log(`On-time band:              ${describe(r.onTimeBand)}`);
  console.log(`Link issued:               ${r.issuedAt}`);
  console.log(`Link expires:              ${r.expiresAt}`);
  console.log("");
  console.log(
    r.valid
      ? "Decision input: the record stands. Use it as one input with your own checks."
      : "Decision input: do not rely on this record. Ask the applicant for a fresh link.",
  );
}

async function main() {
  const arg = process.argv[2];
  if (!arg) {
    console.error("Usage: node verify.mjs <share-url-or-token>");
    process.exit(2);
  }
  const token = extractToken(arg);
  const url = `${BASE_URL}/api/v1/records/verify/${encodeURIComponent(token)}`;

  let res;
  try {
    res = await fetch(url, { headers: { Accept: "application/json" }, signal: AbortSignal.timeout(60_000) });
  } catch {
    console.error("Could not reach Seed. The sandbox may be waking up; wait a minute and try again.");
    process.exit(1);
  }

  if (res.ok) {
    printDecision(/** @type {VerifiedRecord} */ (await res.json()));
    return;
  }

  /** @type {Problem | undefined} */
  const problem = await res.json().catch(() => undefined);
  if (res.status === 404) {
    console.error("This link did not work. It may be wrong, expired or revoked. Ask the applicant for a new one.");
  } else if (res.status === 429) {
    const wait = res.headers.get("Retry-After");
    console.error(`Too many requests. Try again in ${wait ?? "a few"} seconds.`);
  } else if (res.status === 503) {
    console.error("Seed cannot verify records right now. Try again later.");
  } else {
    console.error(`Unexpected answer (HTTP ${res.status}${problem?.code ? `, ${problem.code}` : ""}).`);
  }
  process.exit(1);
}

import { pathToFileURL } from "node:url";
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
