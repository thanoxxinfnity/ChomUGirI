"use client";

import { useEffect, useRef, useState } from "react";
import { v4 as uuidv4 } from "uuid";
import { useAppStore } from "@/lib/store";
import { readSseStream } from "@/lib/sse-client";
import type { ChatMessage, ConversationMessage } from "@/lib/types";
import Composer from "./Composer";
import MessageBubble from "./MessageBubble";
import Logomark from "./Logomark";

// Stable reference: a selector must never return a fresh literal like `[]` on every call, or
// zustand's useSyncExternalStore sees "the snapshot changed" on every render and loops forever.
const EMPTY_MESSAGES: ConversationMessage[] = [];

export default function ChatView() {
  const activeConversationId = useAppStore((s) => s.activeConversationId);
  const messages = useAppStore((s) =>
    s.activeConversationId ? (s.conversations[s.activeConversationId]?.messages ?? EMPTY_MESSAGES) : EMPTY_MESSAGES,
  );
  const addMessage = useAppStore((s) => s.addMessage);
  const updateMessage = useAppStore((s) => s.updateMessage);
  const upsertArtifact = useAppStore((s) => s.upsertArtifact);
  const setActiveArtifactId = useAppStore((s) => s.setActiveArtifactId);
  const renameConversation = useAppStore((s) => s.renameConversation);
  const settings = useAppStore((s) => s.settings);

  const [busy, setBusy] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    scrollRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  async function handleSend(text: string) {
    const userMsg: ConversationMessage = {
      id: uuidv4(),
      role: "user",
      content: text,
      kind: "chat",
      createdAt: Date.now(),
    };
    const assistantId = uuidv4();
    const assistantMsg: ConversationMessage = {
      id: assistantId,
      role: "assistant",
      content: "",
      kind: "chat",
      createdAt: Date.now(),
      streaming: true,
      pipelineEvents: [],
    };

    addMessage(userMsg);
    addMessage(assistantMsg);
    setBusy(true);

    const history: ChatMessage[] = messages
      .filter((m) => m.kind === "chat")
      .map((m) => ({ role: m.role, content: m.content }));

    function currentAssistantMessage() {
      const convoId = useAppStore.getState().activeConversationId;
      const convo = convoId ? useAppStore.getState().conversations[convoId] : undefined;
      return convo?.messages.find((m) => m.id === assistantId);
    }

    try {
      const res = await fetch("/api/router", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          prompt: text,
          history,
          providers: settings.providers,
          maxAuditLoops: settings.maxAuditLoops,
          forceCodeMode: settings.forceCodeMode,
        }),
      });

      if (!res.ok) {
        const errBody = await res.json().catch(() => null);
        throw new Error(errBody?.error ?? `Router request failed (${res.status})`);
      }

      let artifactCreated = false;

      for await (const ev of readSseStream(res)) {
        if (ev.type === "route") {
          updateMessage(assistantId, {
            kind: ev.mode === "pipeline" ? "pipeline" : "chat",
            pipelineEvents: [ev],
          });
        } else if (ev.type === "stage" || ev.type === "log") {
          const current = currentAssistantMessage();
          updateMessage(assistantId, { pipelineEvents: [...(current?.pipelineEvents ?? []), ev] });
        } else if (ev.type === "chat-chunk") {
          const current = currentAssistantMessage();
          updateMessage(assistantId, { content: (current?.content ?? "") + (ev.message ?? "") });
        } else if (ev.type === "files" && ev.files) {
          const existingArtifactId = currentAssistantMessage()?.artifactId;
          const artifactId = artifactCreated && existingArtifactId ? existingArtifactId : uuidv4();
          artifactCreated = true;
          const projectTitle = guessTitle(text);
          upsertArtifact({
            id: artifactId,
            title: projectTitle,
            files: ev.files,
            createdAt: Date.now(),
          });
          updateMessage(assistantId, { artifactId, content: summarize(ev.files.length) });
          setActiveArtifactId(artifactId);
          const convoId = useAppStore.getState().activeConversationId;
          if (convoId) renameConversation(convoId, projectTitle);
        } else if (ev.type === "done") {
          const current = currentAssistantMessage();
          updateMessage(assistantId, {
            streaming: false,
            content: current?.kind === "pipeline" ? current.content || summarize(0) : current?.content,
          });
        } else if (ev.type === "error") {
          updateMessage(assistantId, { streaming: false, error: ev.message });
        }
      }

      updateMessage(assistantId, { streaming: false });
    } catch (err) {
      updateMessage(assistantId, {
        streaming: false,
        error: err instanceof Error ? err.message : "Something went wrong.",
      });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex h-full min-h-0 flex-1 flex-col">
      <div className="min-h-0 flex-1 overflow-y-auto">
        {messages.length === 0 ? (
          <EmptyState />
        ) : (
          <div key={activeConversationId} className="mx-auto flex max-w-3xl flex-col gap-6 px-3 py-6 sm:px-4 sm:py-8">
            {messages.map((m) => (
              <MessageBubble key={m.id} message={m} />
            ))}
            <div ref={scrollRef} />
          </div>
        )}
      </div>
      <Composer onSend={handleSend} disabled={busy} />
    </div>
  );
}

function guessTitle(prompt: string) {
  const trimmed = prompt.trim().slice(0, 60);
  return trimmed.length < prompt.trim().length ? `${trimmed}…` : trimmed || "Generated project";
}

function summarize(fileCount: number) {
  return fileCount > 0
    ? `Done — ${fileCount} file${fileCount === 1 ? "" : "s"} ready. Open the panel to view, run, or download.`
    : "Done. Open the panel to view, run, or download.";
}

function EmptyState() {
  const stages = [
    { label: "Fast Chat", note: "casual talk" },
    { label: "Kimi K3", note: "coder" },
    { label: "GLM 5.2", note: "auditor" },
    { label: "DeepSeek R1", note: "fallback" },
    { label: "Nemotron", note: "safety net" },
  ];

  return (
    <div className="flex h-full flex-col items-center justify-center gap-6 px-4 text-center sm:gap-7">
      <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-accent sm:h-12 sm:w-12">
        <Logomark size={22} />
      </div>
      <div>
        <h1 className="font-display text-xl font-medium sm:text-2xl">Welcome to ChomuGirI</h1>
        <p className="mt-2 max-w-md text-sm text-fg-muted">
          Casual conversation gets a fast reply. Ask for an app, website, or script and the full
          AI swarm takes over automatically.
        </p>
      </div>

      <div className="flex flex-wrap items-center justify-center gap-1">
        {stages.map((s, i) => (
          <div key={s.label} className="flex items-center gap-1">
            <div className="flex flex-col items-center gap-1 rounded-xl border border-border bg-bg-elevated/70 px-2.5 py-2 sm:px-3">
              <span className="font-mono text-[11px] text-fg">{s.label}</span>
              <span className="text-[10px] text-fg-muted">{s.note}</span>
            </div>
            {i < stages.length - 1 && <div className="h-px w-3 bg-border-strong" />}
          </div>
        ))}
      </div>
    </div>
  );
}
