"use client";

import { create } from "zustand";
import { persist } from "zustand/middleware";
import { v4 as uuidv4 } from "uuid";
import { DEFAULT_SETTINGS } from "./types";
import type {
  AppSettings,
  Artifact,
  Conversation,
  ConversationMessage,
  EnvVar,
  ProviderConfig,
  RoleKey,
} from "./types";

interface AppState {
  settings: AppSettings;
  setProviderConfig: (role: RoleKey, patch: Partial<ProviderConfig>) => void;
  setVercelToken: (token: string) => void;
  setCloudTerminalUrl: (url: string) => void;
  setMaxAuditLoops: (n: number) => void;
  setEnvVars: (vars: EnvVar[]) => void;
  addEnvVar: () => void;
  updateEnvVar: (index: number, patch: Partial<EnvVar>) => void;
  removeEnvVar: (index: number) => void;

  conversations: Record<string, Conversation>;
  activeConversationId: string | null;
  startNewConversation: () => string;
  setActiveConversationId: (id: string) => void;
  deleteConversation: (id: string) => void;
  renameConversation: (id: string, title: string) => void;
  addMessage: (m: ConversationMessage) => void;
  updateMessage: (id: string, patch: Partial<ConversationMessage>) => void;

  artifacts: Record<string, Artifact>;
  upsertArtifact: (a: Artifact) => void;
  activeArtifactId: string | null;
  setActiveArtifactId: (id: string | null) => void;
  canvasMode: boolean;
  setCanvasMode: (v: boolean) => void;

  mobileSidebarOpen: boolean;
  setMobileSidebarOpen: (v: boolean) => void;
}

function titleFromMessages(messages: ConversationMessage[]) {
  const firstUser = messages.find((m) => m.role === "user");
  if (!firstUser) return "New chat";
  const trimmed = firstUser.content.trim().slice(0, 48);
  return trimmed.length < firstUser.content.trim().length ? `${trimmed}…` : trimmed || "New chat";
}

export const useAppStore = create<AppState>()(
  persist(
    (set, get) => ({
      settings: DEFAULT_SETTINGS,
      setProviderConfig: (role, patch) =>
        set((s) => ({
          settings: {
            ...s.settings,
            providers: {
              ...s.settings.providers,
              [role]: { ...s.settings.providers[role], ...patch },
            },
          },
        })),
      setVercelToken: (token) =>
        set((s) => ({ settings: { ...s.settings, vercelToken: token } })),
      setCloudTerminalUrl: (url) =>
        set((s) => ({ settings: { ...s.settings, cloudTerminalUrl: url } })),
      setMaxAuditLoops: (n) =>
        set((s) => ({ settings: { ...s.settings, maxAuditLoops: n } })),
      setEnvVars: (vars) => set((s) => ({ settings: { ...s.settings, envVars: vars } })),
      addEnvVar: () =>
        set((s) => ({
          settings: { ...s.settings, envVars: [...s.settings.envVars, { key: "", value: "" }] },
        })),
      updateEnvVar: (index, patch) =>
        set((s) => ({
          settings: {
            ...s.settings,
            envVars: s.settings.envVars.map((v, i) => (i === index ? { ...v, ...patch } : v)),
          },
        })),
      removeEnvVar: (index) =>
        set((s) => ({
          settings: { ...s.settings, envVars: s.settings.envVars.filter((_, i) => i !== index) },
        })),

      conversations: {},
      activeConversationId: null,
      startNewConversation: () => {
        const id = uuidv4();
        const now = Date.now();
        set((s) => ({
          conversations: {
            ...s.conversations,
            [id]: { id, title: "New chat", messages: [], createdAt: now, updatedAt: now },
          },
          activeConversationId: id,
          activeArtifactId: null,
          mobileSidebarOpen: false,
        }));
        return id;
      },
      setActiveConversationId: (id) =>
        set({ activeConversationId: id, activeArtifactId: null, mobileSidebarOpen: false }),
      deleteConversation: (id) =>
        set((s) => {
          const conversations = { ...s.conversations };
          delete conversations[id];
          const activeConversationId = s.activeConversationId === id ? null : s.activeConversationId;
          return { conversations, activeConversationId };
        }),
      renameConversation: (id, title) =>
        set((s) => {
          const convo = s.conversations[id];
          if (!convo) return s;
          return { conversations: { ...s.conversations, [id]: { ...convo, title } } };
        }),
      addMessage: (m) => {
        let id = get().activeConversationId;
        if (!id || !get().conversations[id]) id = get().startNewConversation();
        set((s) => {
          const convo = s.conversations[id!];
          const messages = [...convo.messages, m];
          return {
            conversations: {
              ...s.conversations,
              [id!]: {
                ...convo,
                messages,
                title: convo.title === "New chat" ? titleFromMessages(messages) : convo.title,
                updatedAt: Date.now(),
              },
            },
          };
        });
      },
      updateMessage: (id, patch) =>
        set((s) => {
          const convoId = s.activeConversationId;
          if (!convoId || !s.conversations[convoId]) return s;
          const convo = s.conversations[convoId];
          return {
            conversations: {
              ...s.conversations,
              [convoId]: {
                ...convo,
                messages: convo.messages.map((m) => (m.id === id ? { ...m, ...patch } : m)),
              },
            },
          };
        }),

      artifacts: {},
      upsertArtifact: (a) =>
        set((s) => ({ artifacts: { ...s.artifacts, [a.id]: a } })),
      activeArtifactId: null,
      setActiveArtifactId: (id) => set({ activeArtifactId: id, mobileSidebarOpen: false, canvasMode: false }),
      canvasMode: false,
      setCanvasMode: (v) => set({ canvasMode: v }),

      mobileSidebarOpen: false,
      setMobileSidebarOpen: (v) => set({ mobileSidebarOpen: v }),
    }),
    {
      name: "chomugiri-store",
      version: 3,
      partialize: (s) => ({
        settings: s.settings,
        conversations: s.conversations,
        activeConversationId: s.activeConversationId,
        artifacts: s.artifacts,
      }),
      merge: (persisted, current) => {
        const persistedState = (persisted ?? {}) as Partial<AppState> & {
          messages?: ConversationMessage[];
        };

        let conversations = persistedState.conversations ?? {};
        let activeConversationId = persistedState.activeConversationId ?? null;

        // Migrate the old single-thread `messages` shape (store v2 and earlier) into one conversation.
        if (persistedState.messages?.length && Object.keys(conversations).length === 0) {
          const id = uuidv4();
          const now = Date.now();
          conversations = {
            [id]: {
              id,
              title: titleFromMessages(persistedState.messages),
              messages: persistedState.messages,
              createdAt: now,
              updatedAt: now,
            },
          };
          activeConversationId = id;
        }

        return {
          ...current,
          ...persistedState,
          conversations,
          activeConversationId,
          settings: {
            ...DEFAULT_SETTINGS,
            ...current.settings,
            ...persistedState.settings,
            providers: {
              ...DEFAULT_SETTINGS.providers,
              ...persistedState.settings?.providers,
            },
          },
        };
      },
    },
  ),
);
