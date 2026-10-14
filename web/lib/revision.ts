import { execFileSync } from "node:child_process";
import { escapeHtml } from "./http";

let cached: string | undefined;

export function gitRevision(): string {
  if (cached !== undefined) return cached;
  const fromEnv = (process.env.VERCEL_GIT_COMMIT_SHA || "").trim();
  if (/^[0-9a-f]{7,40}$/i.test(fromEnv)) {
    cached = fromEnv.slice(0, 7).toLowerCase();
    return cached;
  }
  try {
    const hash = execFileSync("git", ["rev-parse", "--short=7", "HEAD"], {
      encoding: "utf8",
      timeout: 1500,
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
    cached = /^[0-9a-f]{7,40}$/i.test(hash) ? hash.slice(0, 7).toLowerCase() : "";
  } catch {
    cached = "";
  }
  return cached;
}

export function revisionMark(): string {
  const hash = gitRevision();
  if (!hash) return "";
  return `<style>.rev{position:fixed;top:8px;right:12px;z-index:30;font:11px/1 ui-monospace,SFMono-Regular,Menlo,monospace;color:#666;letter-spacing:.03em}header.top{padding-right:78px}body.app-embed .rev{display:none}</style><span class="rev">${escapeHtml(hash)}</span>`;
}

/** Hash rendered inline (e.g. in the header under the profile name). */
export function revisionInline(): string {
  const hash = gitRevision();
  if (!hash) return "";
  return `<code class="rev-inline">${escapeHtml(hash)}</code>`;
}
