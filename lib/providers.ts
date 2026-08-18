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

/**
 * Generic OpenAI-compatible chat client. Works against any endpoint that speaks
 * the /chat/completions protocol (OpenRouter, Groq, Together, vLLM, Ollama proxy, etc.)
 * so the exact vendor slug behind "Kimi K3" / "GLM 5.2" / "Nemotron 3 Ultra" can be
 * swapped per-user in Settings without code changes.
 */
export async function chatCompletion(
  config: ProviderConfig,
  role: string,
  messages: ChatMessage[],
  opts: { temperature?: number; jsonMode?: boolean; maxTokens?: number } = {},
): Promise<string> {
  assertConfigured(config, role);

  const res = await fetch(`${config.baseUrl.replace(/\/$/, "")}/chat/completions`, {
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
      stream: false,
      ...(opts.jsonMode ? { response_format: { type: "json_object" } } : {}),
    }),
  });

  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new ProviderError(
      `${role} request fail hui (${res.status}): ${text.slice(0, 300)}`,
      role,
    );
  }

  const data = await res.json();
  const content = data?.choices?.[0]?.message?.content;
  if (typeof content !== "string") {
    throw new ProviderError(`${role} ne khali response diya.`, role);
  }
  return content;
}

/**
 * Streaming variant for the fast chat path. Yields incremental text chunks.
 */
export async function* streamChatCompletion(
  config: ProviderConfig,
  role: string,
  messages: ChatMessage[],
  opts: { temperature?: number } = {},
): AsyncGenerator<string> {
  assertConfigured(config, role);

  const res = await fetch(`${config.baseUrl.replace(/\/$/, "")}/chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${config.apiKey}`,
    },
    body: JSON.stringify({
      model: config.model,
      messages,
      temperature: opts.temperature ?? 0.6,
      stream: true,
    }),
  });

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
