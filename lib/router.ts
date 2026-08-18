const CODE_KEYWORDS = [
  "app",
  "website",
  "web app",
  "webapp",
  "build",
  "banao",
  "bana do",
  "banaye",
  "banaiye",
  "code",
  "script",
  "program",
  "component",
  "api",
  "backend",
  "frontend",
  "database",
  "function",
  "class ",
  "bug",
  "fix kar",
  "error",
  "deploy",
  "landing page",
  "game",
  "clone",
  "feature add",
  "refactor",
  "python",
  "javascript",
  "typescript",
  "react",
  "next.js",
  "nodejs",
  "node.js",
];

const GREETING_PATTERNS = /^(hi|hii+|hey|hello|salam|assalam|kya haal|kaise ho|good\s?(morning|evening|night))\b/i;

/**
 * Cheap heuristic classifier so trivial chat ("hi", "kya haal hai") never wakes
 * the heavy coder/auditor swarm. Real ambiguous cases lean toward "chat" — the
 * user can always force pipeline mode from the composer.
 */
export function classifyIntent(message: string): "chat" | "pipeline" {
  const trimmed = message.trim();
  if (trimmed.length === 0) return "chat";
  if (GREETING_PATTERNS.test(trimmed) && trimmed.length < 40) return "chat";

  const lower = trimmed.toLowerCase();
  const hasCodeKeyword = CODE_KEYWORDS.some((kw) => lower.includes(kw));
  const isLongRequest = trimmed.length > 220;

  if (hasCodeKeyword || isLongRequest) return "pipeline";
  return "chat";
}
