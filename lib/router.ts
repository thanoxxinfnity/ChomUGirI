const CODE_KEYWORDS = [
  "app",
  "website",
  "web app",
  "webapp",
  "build",
  "banao",
  "bana do",
  "bana ke do",
  "bana kar do",
  "banaye",
  "banaiye",
  "banwa do",
  "likh do",
  "code likho",
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
  "convert",
  "generate a",
  "make me a",
  "create a",
  "crud",
  "endpoint",
  "dashboard",
  "chatbot",
  "calculator",
  "to-do",
  "todo list",
  "portfolio site",
  "e-commerce",
  "ecommerce",
  "html",
  "css",
  "sql",
  "python",
  "javascript",
  "typescript",
  "react",
  "vue",
  "angular",
  "flutter",
  "kotlin",
  "swift",
  "golang",
  "rust",
  "next.js",
  "nodejs",
  "node.js",
];

const GREETING_PATTERNS = /^(hi|hii+|hey|hello|salam|assalam|kya haal|kaise ho|good\s?(morning|evening|night))\b/i;

/**
 * Cheap heuristic classifier so trivial chat ("hi", "kya haal hai") never wakes the heavy
 * coder/auditor swarm. This is the only signal the router acts on — there's no manual
 * "code mode" switch, so ambiguous asks that clearly describe something to build (long,
 * specific requests) lean toward "pipeline" rather than making the user repeat themselves.
 */
export function classifyIntent(message: string): "chat" | "pipeline" {
  const trimmed = message.trim();
  if (trimmed.length === 0) return "chat";
  if (GREETING_PATTERNS.test(trimmed) && trimmed.length < 40) return "chat";

  const lower = trimmed.toLowerCase();
  const hasCodeKeyword = CODE_KEYWORDS.some((kw) => lower.includes(kw));
  const isLongRequest = trimmed.length > 180;

  if (hasCodeKeyword || isLongRequest) return "pipeline";
  return "chat";
}
