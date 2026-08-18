import type { PipelineEvent } from "./types";

/** Reads an SSE (data: {...}\n\n) fetch Response body and yields parsed events. */
export async function* readSseStream(res: Response): AsyncGenerator<PipelineEvent> {
  if (!res.body) return;
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    const chunks = buffer.split("\n\n");
    buffer = chunks.pop() ?? "";

    for (const chunk of chunks) {
      const line = chunk.split("\n").find((l) => l.startsWith("data:"));
      if (!line) continue;
      const payload = line.slice(5).trim();
      try {
        yield JSON.parse(payload) as PipelineEvent;
      } catch {
        // ignore malformed chunk
      }
    }
  }
}
