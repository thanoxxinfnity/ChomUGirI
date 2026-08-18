export type RoleKey = "fast" | "kimi" | "glm" | "deepseek" | "nemotron";

export interface ProviderConfig {
  apiKey: string;
  baseUrl: string;
  model: string;
}

export type ProviderSettings = Record<RoleKey, ProviderConfig>;

export interface AppSettings {
  providers: ProviderSettings;
  vercelToken: string;
  forceCodeMode: boolean;
  maxAuditLoops: number;
}

export const ROLE_LABELS: Record<RoleKey, string> = {
  fast: "Fast Chat Model",
  kimi: "Kimi K3 (Coder)",
  glm: "GLM 5.2 (Auditor)",
  deepseek: "DeepSeek R1 (Deep Logic)",
  nemotron: "Nemotron 3 Ultra 550B (Safety Net)",
};

export const ROLE_ORDER: RoleKey[] = ["fast", "kimi", "glm", "deepseek", "nemotron"];

export const DEFAULT_SETTINGS: AppSettings = {
  providers: {
    fast: {
      apiKey: "",
      baseUrl: "https://openrouter.ai/api/v1",
      model: "google/gemini-2.0-flash-001",
    },
    kimi: {
      apiKey: "",
      baseUrl: "https://openrouter.ai/api/v1",
      model: "moonshotai/kimi-k2",
    },
    glm: {
      apiKey: "",
      baseUrl: "https://openrouter.ai/api/v1",
      model: "z-ai/glm-4.6",
    },
    deepseek: {
      apiKey: "",
      baseUrl: "https://openrouter.ai/api/v1",
      model: "deepseek/deepseek-r1",
    },
    nemotron: {
      apiKey: "",
      baseUrl: "https://openrouter.ai/api/v1",
      model: "nvidia/llama-3.1-nemotron-70b-instruct",
    },
  },
  vercelToken: "",
  forceCodeMode: false,
  maxAuditLoops: 3,
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
