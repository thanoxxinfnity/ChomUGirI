import type { FileSystemTree } from "@webcontainer/api";
import type { GeneratedFile } from "./types";

let bootPromise: Promise<import("@webcontainer/api").WebContainer> | null = null;

/** WebContainer only allows a single booted instance per page — reuse it across tabs/panels. */
export async function getWebContainer() {
  if (!bootPromise) {
    bootPromise = import("@webcontainer/api").then(({ WebContainer }) => WebContainer.boot());
  }
  return bootPromise;
}

export function filesToTree(files: GeneratedFile[]): FileSystemTree {
  const tree: FileSystemTree = {};

  for (const file of files) {
    const parts = file.path.split("/").filter(Boolean);
    let cursor = tree;
    for (let i = 0; i < parts.length - 1; i++) {
      const part = parts[i];
      const existing = cursor[part];
      if (!existing || !("directory" in existing)) {
        cursor[part] = { directory: {} };
      }
      cursor = (cursor[part] as { directory: FileSystemTree }).directory;
    }
    const fileName = parts[parts.length - 1];
    if (fileName) {
      cursor[fileName] = { file: { contents: file.content } };
    }
  }

  return tree;
}

export function hasPackageJson(files: GeneratedFile[]) {
  return files.some((f) => f.path === "package.json" || f.path === "/package.json");
}

export function pickStartScript(files: GeneratedFile[]): "dev" | "start" | null {
  const pkg = files.find((f) => f.path === "package.json" || f.path === "/package.json");
  if (!pkg) return null;
  try {
    const parsed = JSON.parse(pkg.content);
    if (parsed?.scripts?.dev) return "dev";
    if (parsed?.scripts?.start) return "start";
  } catch {
    // ignore parse failure, caller falls back to static serve
  }
  return null;
}
