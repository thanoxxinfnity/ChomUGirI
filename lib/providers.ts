import type { ChatMessage, ProviderConfig } from "./types";

export class ProviderError extends Error {
  constructor(
    message: string,
    public role: string,
  ) {
    super(message);
    this.name = "ProviderError";
  }
}

function assertConfigured(config: ProviderConfig, role: string) {
  if (!config.apiKey) {
    throw new ProviderError(
      `${role} ka API key set nahi hai. Settings me jaake key daalo.`,
      role,
    );
  }
  if (!config.baseUrl || !config.model) {
    throw new ProviderError(`${role} ka base URL ya model id missing hai.`, role);
  }
}

interface StreamOpts {
  temperature?: number;
  jsonMode?: boolean;
  maxTokens?: number;
}

/**
 * Raw SSE delta stream from an OpenAI-compatible /chat/completions endpoint. Always requested
 * with stream:true — even for calls whose caller wants one final string (see chatCompletion
 * below) — because a slow model's non-streaming response can sit fully idle for minutes with
 * zero bytes on the wire, and some proxies/CDNs kill idle connections long before the model
 * finishes. Streaming keeps bytes flowing the whole time instead.
 */
async function* rawStream(
  config: ProviderConfig,
  role: string,
  messages: ChatMessage[],
  opts: StreamOpts,
): AsyncGenerator<string> {
  assertConfigured(config, role);

  let res: Response;
  try {
    res = await fetch(`${config.baseUrl.replace(/\/$/, "")}/chat/completions`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${config.apiKey}`,
      },
      body: JSON.stringify({
        model: config.model,
        messages,
        temperature: opts.temperature ?? 0.4,
        max_tokens: opts.maxTokens ?? 4096,
        stream: true,
        ...(opts.jsonMode ? { response_format: { type: "json_object" } } : {}),
      }),
    });
  } catch (err) {
    const cause = err instanceof Error ? err.message : String(err);
    throw new ProviderError(`${role} tak network request nahi pahunch payi: ${cause}`, role);
  }

  if (!res.ok || !res.body) {
    const text = await res.text().catch(() => "");
    throw new ProviderError(
      `${role} request fail hui (${res.status}): ${text.slice(0, 300)}`,
      role,
    );
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    const lines = buffer.split("\n");
    buffer = lines.pop() ?? "";

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed.startsWith("data:")) continue;
      const payload = trimmed.slice(5).trim();
      if (payload === "[DONE]") return;
      try {
        const json = JSON.parse(payload);
        const delta = json?.choices?.[0]?.delta?.content;
        if (typeof delta === "string" && delta.length > 0) {
          yield delta;
        }
      } catch {
        // ignore partial/non-JSON keep-alive lines
      }
    }
  }
}

/**
 * Generic OpenAI-compatible chat client. Works against any endpoint that speaks
 * the /chat/completions protocol (OpenRouter, Groq, Together, NVIDIA NIM, vLLM, Ollama
 * proxy, etc.) so the exact vendor slug behind "Kimi K3" / "GLM 5.2" / "Nemotron 3 Ultra"
 * can be swapped per-user in Settings without code changes. Streams under the hood (see
 * rawStream) and joins the deltas into one string for callers that just want the full text.
 */
export async function chatCompletion(
  config: ProviderConfig,
  role: string,
  messages: ChatMessage[],
  opts: StreamOpts = {},
): Promise<string> {
  let full = "";
  for await (const chunk of rawStream(config, role, messages, opts)) {
    full += chunk;
  }
  if (!full) {
    throw new ProviderError(`${role} ne khali response diya.`, role);
  }
  return full;
}

/** Streaming variant for the fast chat path. Yields incremental text chunks as they arrive. */
export async function* streamChatCompletion(
  config: ProviderConfig,
  role: string,
  messages: ChatMessage[],
  opts: { temperature?: number } = {},
): AsyncGenerator<string> {
  yield* rawStream(config, role, messages, { temperature: opts.temperature ?? 0.6 });
}
