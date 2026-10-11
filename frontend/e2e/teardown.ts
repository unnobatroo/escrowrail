import { execSync } from "node:child_process";

export default async function globalTeardown() {
  try {
    execSync("docker rm -f wise-e2e-pg", { stdio: "ignore" });
  } catch {
    // Container already gone — fine.
  }
}
