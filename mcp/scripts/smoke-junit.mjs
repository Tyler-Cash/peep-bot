#!/usr/bin/env node
import { readReports, formatSummary } from "../dist/junit.js";
import { resolve } from "node:path";

const dir = resolve(process.cwd(), "../backend/build/test-results/test");
const summary = readReports(dir);
console.log(formatSummary(summary, { maxFailures: 5 }));
