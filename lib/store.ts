"use client";

import { create } from "zustand";
import { persist } from "zustand/middleware";
import { DEFAULT_SETTINGS } from "./types";
import type { AppSettings, Artifact, ConversationMessage, EnvVar, ProviderConfig, RoleKey } from "./types";

interface AppState {
  settings: AppSettings;
  setProviderConfig: (role: RoleKey, patch: Partial<ProviderConfig>) => void;
  setVercelToken: (token: string) => void;
  setForceCodeMode: (v: boolean) => void;
  setMaxAuditLoops: (n: number) => void;
  setEnvVars: (vars: EnvVar[]) => void;
  addEnvVar: () => void;
  updateEnvVar: (index: number, patch: Partial<EnvVar>) => void;
  removeEnvVar: (index: number) => void;

  messages: ConversationMessage[];
  addMessage: (m: ConversationMessage) => void;
  updateMessage: (id: string, patch: Partial<ConversationMessage>) => void;
  clearMessages: () => void;

  artifacts: Record<string, Artifact>;
  upsertArtifact: (a: Artifact) => void;
  activeArtifactId: string | null;
  setActiveArtifactId: (id: string | null) => void;
}

export const useAppStore = create<AppState>()(
  persist(
    (set) => ({
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
      setForceCodeMode: (v) =>
        set((s) => ({ settings: { ...s.settings, forceCodeMode: v } })),
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

      messages: [],
      addMessage: (m) => set((s) => ({ messages: [...s.messages, m] })),
      updateMessage: (id, patch) =>
        set((s) => ({
          messages: s.messages.map((m) => (m.id === id ? { ...m, ...patch } : m)),
        })),
      clearMessages: () => set({ messages: [], artifacts: {}, activeArtifactId: null }),

      artifacts: {},
      upsertArtifact: (a) =>
        set((s) => ({ artifacts: { ...s.artifacts, [a.id]: a } })),
      activeArtifactId: null,
      setActiveArtifactId: (id) => set({ activeArtifactId: id }),
    }),
    {
      name: "chomugiri-store",
      version: 2,
      partialize: (s) => ({
        settings: s.settings,
        messages: s.messages,
        artifacts: s.artifacts,
      }),
      merge: (persisted, current) => {
        const persistedState = (persisted ?? {}) as Partial<AppState>;
        return {
          ...current,
          ...persistedState,
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
