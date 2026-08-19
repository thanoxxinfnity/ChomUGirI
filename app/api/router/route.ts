import type { NextRequest } from "next/server";
import { classifyIntent } from "@/lib/router";
import { runPipeline } from "@/lib/pipeline";
import { streamChatCompletion } from "@/lib/providers";
import { streamFromGenerator, SSE_HEADERS } from "@/lib/sse";
import { FAST_CHAT_SYSTEM_PROMPT } from "@/lib/prompts";
import type { ChatMessage, PipelineEvent, ProviderSettings } from "@/lib/types";

export const runtime = "nodejs";

interface RouterRequestBody {
  prompt: string;
  history?: ChatMessage[];
  providers: ProviderSettings;
  maxAuditLoops?: number;
}

async function* chatGenerator(
  prompt: string,
  history: ChatMessage[],
  providers: ProviderSettings,
): AsyncGenerator<PipelineEvent> {
  yield { type: "route", mode: "chat", message: "Fast Chat Model responding..." };
  try {
    const messages: ChatMessage[] = [
      { role: "system", content: FAST_CHAT_SYSTEM_PROMPT },
      ...history.slice(-10),
      { role: "user", content: prompt },
    ];
    for await (const chunk of streamChatCompletion(providers.fast, "Fast Chat Model", messages)) {
      yield { type: "chat-chunk", message: chunk };
    }
    yield { type: "done" };
  } catch (err) {
    const message = err instanceof Error ? err.message : "Something went wrong in fast chat.";
    yield { type: "error", message };
  }
}

async function* routedGenerator(body: RouterRequestBody): AsyncGenerator<PipelineEvent> {
  const mode = classifyIntent(body.prompt);

  if (mode === "chat") {
    yield* chatGenerator(body.prompt, body.history ?? [], body.providers);
    return;
  }

  yield {
    type: "route",
    mode: "pipeline",
    message: "Code request detected — the swarm (Kimi -> GLM -> DeepSeek -> Nemotron) is on it.",
  };
  yield* runPipeline(body.prompt, body.providers, body.maxAuditLoops ?? 3);
}

export async function POST(req: NextRequest) {
  let body: RouterRequestBody;
  try {
    body = await req.json();
  } catch {
    return new Response(JSON.stringify({ error: "Invalid JSON body" }), { status: 400 });
  }

  if (!body?.prompt || !body?.providers) {
    return new Response(JSON.stringify({ error: "prompt and providers are required" }), {
      status: 400,
    });
  }

  const stream = streamFromGenerator(routedGenerator(body));
  return new Response(stream, { headers: SSE_HEADERS });
}
