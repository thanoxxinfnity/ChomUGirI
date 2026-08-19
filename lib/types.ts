export type RoleKey = "fast" | "kimi" | "glm" | "deepseek" | "nemotron";

export interface ProviderConfig {
  apiKey: string;
  baseUrl: string;
  model: string;
}

export type ProviderSettings = Record<RoleKey, ProviderConfig>;

export interface EnvVar {
  key: string;
  value: string;
}

export interface AppSettings {
  providers: ProviderSettings;
  vercelToken: string;
  maxAuditLoops: number;
  envVars: EnvVar[];
  /** URL of a terminal the user hosts and exposes themselves (e.g. ttyd behind their own
   *  ngrok/cloudflare tunnel). Never set by this app — purely user-supplied, embedded as-is. */
  cloudTerminalUrl: string;
}

export const ROLE_LABELS: Record<RoleKey, string> = {
  fast: "Fast Chat Model",
  kimi: "Kimi K3 (Coder)",
  glm: "GLM 5.2 (Auditor)",
  deepseek: "DeepSeek R1 (Deep Logic)",
  nemotron: "Nemotron 3 Ultra 550B (Safety Net)",
};

export const ROLE_ORDER: RoleKey[] = ["fast", "kimi", "glm", "deepseek", "nemotron"];

/** Base URLs a provider preset dropdown can snap a role to in Settings. */
export const PROVIDER_PRESETS = {
  nim: { label: "NVIDIA NIM", baseUrl: "https://integrate.api.nvidia.com/v1" },
  openrouter: { label: "OpenRouter", baseUrl: "https://openrouter.ai/api/v1" },
  custom: { label: "Custom", baseUrl: "" },
} as const;

export type ProviderPresetKey = keyof typeof PROVIDER_PRESETS;

/**
 * Defaults point at NVIDIA NIM (integrate.api.nvidia.com) — one API key covers every role.
 * Model ids below were confirmed live against the NIM catalog: z-ai/glm-5.2 and
 * nvidia/nemotron-3-ultra-550b-a55b are exact matches for "GLM 5.2" / "Nemotron 3 Ultra 550B".
 * NIM doesn't currently serve Kimi K3 on every account, so the coder role defaults to a strong
 * general model instead — swap the model id here any time your account gets access to it.
 * None of this ships with a real key baked in: every user pastes their own in Settings.
 */
export const DEFAULT_SETTINGS: AppSettings = {
  providers: {
    fast: {
      apiKey: "",
      baseUrl: PROVIDER_PRESETS.nim.baseUrl,
      model: "meta/llama-3.1-8b-instruct",
    },
    kimi: {
      apiKey: "",
      baseUrl: PROVIDER_PRESETS.nim.baseUrl,
      model: "meta/llama-3.1-70b-instruct",
    },
    glm: {
      apiKey: "",
      baseUrl: PROVIDER_PRESETS.nim.baseUrl,
      model: "z-ai/glm-5.2",
    },
    deepseek: {
      apiKey: "",
      baseUrl: PROVIDER_PRESETS.nim.baseUrl,
      model: "deepseek-ai/deepseek-v4-flash-0731",
    },
    nemotron: {
      apiKey: "",
      baseUrl: PROVIDER_PRESETS.nim.baseUrl,
      model: "nvidia/nemotron-3-ultra-550b-a55b",
    },
  },
  vercelToken: "",
  maxAuditLoops: 3,
  envVars: [],
  cloudTerminalUrl: "",
};

export interface ChatMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

export interface ConversationMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  kind: "chat" | "pipeline";
  artifactId?: string;
  createdAt: number;
  pipelineEvents?: PipelineEvent[];
  streaming?: boolean;
  error?: string;
}

export interface GeneratedFile {
  path: string;
  content: string;
}

export interface Artifact {
  id: string;
  title: string;
  files: GeneratedFile[];
  createdAt: number;
}

export interface Conversation {
  id: string;
  title: string;
  messages: ConversationMessage[];
  createdAt: number;
  updatedAt: number;
}

export type PipelineStage = "router" | "kimi" | "glm" | "deepseek" | "nemotron" | "done" | "error";

export interface PipelineEvent {
  type: "route" | "stage" | "log" | "files" | "chat-chunk" | "done" | "error";
  stage?: PipelineStage;
  status?: "start" | "end";
  iteration?: number;
  message?: string;
  files?: GeneratedFile[];
  mode?: "chat" | "pipeline";
}
