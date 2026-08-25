#!/usr/bin/env node
/**
 * Publishes the documentation site.
 *
 *   npm run release:docs        build, check the links, then deploy
 *   npm run release:docs:dry    build and check the links only
 *
 * The docs have no version and no artifact: they are a static site rebuilt from
 * `zetaforge-doc/` and pushed to GitHub Pages. Every push to main that touches
 * that directory already deploys them, so this command exists for the case the
 * workflow does not cover - publishing a fix without waiting for a push, or
 * redeploying after a Pages outage.
 *
 * The build and the link check run here first regardless. Asking a workflow to
 * find out whether the site compiles is a slow way to learn it does not.
 */
import path from "node:path";
import { banner, fail, info, ok, parseArgs, requireCommand, root, run, spawnTool, step } from "./release-lib.mjs";

const args = parseArgs(process.argv.slice(2));
const dryRun = args["dry-run"] === true;
const docs = path.join(root, "zetaforge-doc");

main().catch((error) => {
  console.error(`\n  x ${error.message}\n`);
  process.exit(1);
});

async function main() {
  banner("docs", dryRun);

  step("building the site");
  run("node", [path.join(docs, "build.mjs")], { cwd: docs });

  step("checking every internal link resolves");
  run("node", [path.join(docs, "scripts", "check-links.mjs")], { cwd: docs });

  if (dryRun) {
    console.log("\n  + dry run: the site builds and every link resolves; nothing deployed\n");
    return;
  }

  step("asking GitHub to deploy");
  requireCommand("gh", ["--version"], "The GitHub CLI triggers the deployment. Install it, then: gh auth login");
  if (spawnTool("gh", ["auth", "status"], { capture: true }).status !== 0) {
    fail("gh is not authenticated.", "Run: gh auth login");
  }
  run("gh", ["workflow", "run", "docs.yml"]);

  ok("deployment requested");
  info("watch it:  gh run watch");
  console.log("    site      https://zetawave.github.io/ZetaForge/\n");
}
