import type { GeneratedFile } from "./types";

const FILE_BLOCK_RE = /###\s*FILE:\s*(.+?)\s*\n```[a-zA-Z0-9_+-]*\n([\s\S]*?)```/g;

/** Parses the "### FILE: path\n```lang\n...\n```" convention Kimi is prompted to emit. */
export function parseFileBlocks(text: string): GeneratedFile[] {
  const files: GeneratedFile[] = [];
  let match: RegExpExecArray | null;
  FILE_BLOCK_RE.lastIndex = 0;
  while ((match = FILE_BLOCK_RE.exec(text)) !== null) {
    const path = match[1].trim().replace(/^\/+/, "");
    const content = match[2].replace(/\n$/, "");
    if (path) files.push({ path, content });
  }
  return files;
}

export function filesToPromptBlock(files: GeneratedFile[]): string {
  return files
    .map((f) => `### FILE: ${f.path}\n\`\`\`\n${f.content}\n\`\`\``)
    .join("\n\n");
}

export function mergeFiles(base: GeneratedFile[], updates: GeneratedFile[]): GeneratedFile[] {
  const map = new Map(base.map((f) => [f.path, f]));
  for (const u of updates) map.set(u.path, u);
  return Array.from(map.values());
}

/** Best-effort JSON extraction from a model response that may wrap JSON in prose or fences. */
export function extractJson<T>(text: string): T | null {
  const fenced = text.match(/```json\s*([\s\S]*?)```/i) ?? text.match(/```\s*([\s\S]*?)```/);
  const candidate = fenced ? fenced[1] : text;
  const start = candidate.indexOf("{");
  const end = candidate.lastIndexOf("}");
  if (start === -1 || end === -1 || end < start) return null;
  try {
    return JSON.parse(candidate.slice(start, end + 1)) as T;
  } catch {
    return null;
  }
}
