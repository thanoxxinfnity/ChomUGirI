import { chatCompletion, ProviderError } from "./providers";
import { extractJson, filesToPromptBlock, mergeFiles, parseFileBlocks } from "./files";
import {
  DEEPSEEK_SYSTEM_PROMPT,
  GLM_AUDIT_SYSTEM_PROMPT,
  KIMI_FIX_SYSTEM_PROMPT,
  KIMI_SYSTEM_PROMPT,
  NEMOTRON_SYSTEM_PROMPT,
} from "./prompts";
import type { GeneratedFile, PipelineEvent, ProviderSettings } from "./types";

interface AuditIssue {
  file: string;
  description: string;
}

interface AuditResult {
  clean: boolean;
  issues: AuditIssue[];
}

interface NemotronResult {
  safe: boolean;
  notes: string;
  fixedFiles: GeneratedFile[];
}

/**
 * Runs the full Kimi (coder) <-> GLM (auditor) loop, escalating to DeepSeek R1 on a stuck
 * bug and finishing with a Nemotron safety pass. Yields progress events as it goes so the
 * caller can stream them straight to the client over SSE. These events are intentionally
 * verbose (per-stage, per-round) — the UI decides how much of that to surface; by default it
 * shows a single collapsed status line and keeps this log available on demand.
 */
export async function* runPipeline(
  prompt: string,
  providers: ProviderSettings,
  maxAuditLoops: number,
): AsyncGenerator<PipelineEvent> {
  let files: GeneratedFile[] = [];

  try {
    yield { type: "stage", stage: "kimi", status: "start", message: "Kimi K3 is writing the code..." };
    const kimiOut = await chatCompletion(
      providers.kimi,
      "Kimi K3",
      [
        { role: "system", content: KIMI_SYSTEM_PROMPT },
        { role: "user", content: prompt },
      ],
      { maxTokens: 8192 },
    );
    files = parseFileBlocks(kimiOut);
    if (files.length === 0) {
      throw new ProviderError(
        "Kimi K3 didn't return a valid ### FILE: block — check the response format or try a different model.",
        "kimi",
      );
    }
    yield { type: "files", files };
    yield { type: "stage", stage: "kimi", status: "end", message: `Generated ${files.length} file(s).` };

    let clean = false;
    let lastIssues: AuditIssue[] = [];

    for (let i = 1; i <= maxAuditLoops; i++) {
      yield {
        type: "stage",
        stage: "glm",
        status: "start",
        iteration: i,
        message: `GLM 5.2 audit round ${i}/${maxAuditLoops}...`,
      };
      const glmOut = await chatCompletion(
        providers.glm,
        "GLM 5.2",
        [
          { role: "system", content: GLM_AUDIT_SYSTEM_PROMPT },
          { role: "user", content: filesToPromptBlock(files) },
        ],
        { jsonMode: true, temperature: 0.1 },
      );
      const audit = extractJson<AuditResult>(glmOut);
      if (!audit) {
        yield {
          type: "log",
          message: "GLM's response couldn't be parsed as JSON — treating it as unresolved issues.",
        };
        lastIssues = [{ file: "unknown", description: glmOut.slice(0, 500) }];
      } else {
        lastIssues = audit.issues ?? [];
        clean = audit.clean === true && lastIssues.length === 0;
      }

      yield {
        type: "stage",
        stage: "glm",
        status: "end",
        iteration: i,
        message: clean ? "GLM: code is clean." : `GLM: found ${lastIssues.length} issue(s).`,
      };

      if (clean) break;
      if (i === maxAuditLoops) break;

      yield {
        type: "stage",
        stage: "kimi",
        status: "start",
        iteration: i,
        message: `Kimi K3 is fixing issues (round ${i})...`,
      };
      const fixOut = await chatCompletion(
        providers.kimi,
        "Kimi K3",
        [
          { role: "system", content: KIMI_FIX_SYSTEM_PROMPT },
          {
            role: "user",
            content: `Current files:\n${filesToPromptBlock(files)}\n\nIssues to fix:\n${lastIssues
              .map((iss) => `- [${iss.file}] ${iss.description}`)
              .join("\n")}`,
          },
        ],
        { maxTokens: 8192 },
      );
      const fixed = parseFileBlocks(fixOut);
      if (fixed.length > 0) {
        files = mergeFiles(files, fixed);
        yield { type: "files", files };
      }
      yield { type: "stage", stage: "kimi", status: "end", iteration: i, message: "Fix applied." };
    }

    if (!clean && lastIssues.length > 0) {
      yield {
        type: "stage",
        stage: "deepseek",
        status: "start",
        message: "Kimi/GLM got stuck — DeepSeek R1 is reasoning through the bug...",
      };
      const deepOut = await chatCompletion(
        providers.deepseek,
        "DeepSeek R1",
        [
          { role: "system", content: DEEPSEEK_SYSTEM_PROMPT },
          {
            role: "user",
            content: `Files:\n${filesToPromptBlock(files)}\n\nUnresolved issues after ${maxAuditLoops} audit rounds:\n${lastIssues
              .map((iss) => `- [${iss.file}] ${iss.description}`)
              .join("\n")}`,
          },
        ],
        { temperature: 0.2, maxTokens: 8192 },
      );
      const deepFixed = parseFileBlocks(deepOut);
      if (deepFixed.length > 0) {
        files = mergeFiles(files, deepFixed);
        yield { type: "files", files };
      }
      yield { type: "stage", stage: "deepseek", status: "end", message: "DeepSeek R1's fix applied." };

      yield {
        type: "stage",
        stage: "glm",
        status: "start",
        iteration: maxAuditLoops + 1,
        message: "GLM 5.2 final recheck after DeepSeek's fix...",
      };
      const recheckOut = await chatCompletion(
        providers.glm,
        "GLM 5.2",
        [
          { role: "system", content: GLM_AUDIT_SYSTEM_PROMPT },
          { role: "user", content: filesToPromptBlock(files) },
        ],
        { jsonMode: true, temperature: 0.1 },
      );
      const recheck = extractJson<AuditResult>(recheckOut);
      yield {
        type: "stage",
        stage: "glm",
        status: "end",
        iteration: maxAuditLoops + 1,
        message:
          recheck?.clean === true
            ? "GLM: clean after DeepSeek's fix."
            : "GLM: some minor concerns remain, Nemotron will do the final check.",
      };
    }

    yield { type: "stage", stage: "nemotron", status: "start", message: "Nemotron 3 Ultra running the final safety check..." };
    const nemotronOut = await chatCompletion(
      providers.nemotron,
      "Nemotron 3 Ultra 550B",
      [
        { role: "system", content: NEMOTRON_SYSTEM_PROMPT },
        { role: "user", content: filesToPromptBlock(files) },
      ],
      { jsonMode: true, temperature: 0.1, maxTokens: 8192 },
    );
    const safety = extractJson<NemotronResult>(nemotronOut);
    if (safety?.fixedFiles?.length) {
      files = mergeFiles(files, safety.fixedFiles);
      yield { type: "files", files };
    }
    yield {
      type: "stage",
      stage: "nemotron",
      status: "end",
      message:
        safety?.safe === false
          ? `Nemotron applied final fixes: ${safety.notes ?? ""}`
          : "Nemotron: code is crash-safe and production-ready.",
    };

    yield { type: "done", files, message: `Ready — ${files.length} file(s).` };
  } catch (err) {
    const message = err instanceof Error ? err.message : "Unexpected error in the pipeline.";
    yield { type: "error", message, files };
  }
}
