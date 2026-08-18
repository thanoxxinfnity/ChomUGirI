"use client";

import { useEffect, useRef, useState } from "react";
import { v4 as uuidv4 } from "uuid";
import { useAppStore } from "@/lib/store";
import { readSseStream } from "@/lib/sse-client";
import type { ChatMessage, ConversationMessage } from "@/lib/types";
import Composer from "./Composer";
import MessageBubble from "./MessageBubble";
import ArtifactsPanel from "./ArtifactsPanel";
import { Sparkles } from "lucide-react";

export default function ChatView() {
  const messages = useAppStore((s) => s.messages);
  const addMessage = useAppStore((s) => s.addMessage);
  const updateMessage = useAppStore((s) => s.updateMessage);
  const upsertArtifact = useAppStore((s) => s.upsertArtifact);
  const setActiveArtifactId = useAppStore((s) => s.setActiveArtifactId);
  const activeArtifactId = useAppStore((s) => s.activeArtifactId);
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
        throw new Error(errBody?.error ?? `Router request fail hui (${res.status})`);
      }

      let artifactCreated = false;

      for await (const ev of readSseStream(res)) {
        if (ev.type === "route") {
          updateMessage(assistantId, {
            kind: ev.mode === "pipeline" ? "pipeline" : "chat",
            pipelineEvents: [ev],
          });
        } else if (ev.type === "stage" || ev.type === "log") {
          updateMessage(assistantId, (() => {
            const current = useAppStore.getState().messages.find((m) => m.id === assistantId);
            return { pipelineEvents: [...(current?.pipelineEvents ?? []), ev] };
          })());
        } else if (ev.type === "chat-chunk") {
          const current = useAppStore.getState().messages.find((m) => m.id === assistantId);
          updateMessage(assistantId, { content: (current?.content ?? "") + (ev.message ?? "") });
        } else if (ev.type === "files" && ev.files) {
          const existingArtifactId = useAppStore.getState().messages.find(
            (m) => m.id === assistantId,
          )?.artifactId;
          const artifactId = artifactCreated && existingArtifactId ? existingArtifactId : uuidv4();
          artifactCreated = true;
          upsertArtifact({
            id: artifactId,
            title: guessTitle(text),
            files: ev.files,
            createdAt: Date.now(),
          });
          updateMessage(assistantId, { artifactId });
          setActiveArtifactId(artifactId);
        } else if (ev.type === "done") {
          const current = useAppStore.getState().messages.find((m) => m.id === assistantId);
          updateMessage(assistantId, {
            streaming: false,
            content:
              current?.kind === "pipeline"
                ? current.content || ev.message || "Code ready — right panel me dekho."
                : current?.content,
          });
        } else if (ev.type === "error") {
          updateMessage(assistantId, { streaming: false, error: ev.message });
        }
      }

      updateMessage(assistantId, { streaming: false });
    } catch (err) {
      updateMessage(assistantId, {
        streaming: false,
        error: err instanceof Error ? err.message : "Kuch galat ho gaya.",
      });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex h-full min-h-0 flex-1">
      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        <div className="min-h-0 flex-1 overflow-y-auto">
          {messages.length === 0 ? (
            <EmptyState />
          ) : (
            <div className="mx-auto flex max-w-3xl flex-col gap-6 px-4 py-8">
              {messages.map((m) => (
                <MessageBubble key={m.id} message={m} />
              ))}
              <div ref={scrollRef} />
            </div>
          )}
        </div>
        <Composer onSend={handleSend} disabled={busy} />
      </div>

      {activeArtifactId && (
        <div className="hidden w-[46%] shrink-0 md:block">
          <ArtifactsPanel />
        </div>
      )}
    </div>
  );
}

function guessTitle(prompt: string) {
  const trimmed = prompt.trim().slice(0, 60);
  return trimmed.length < prompt.trim().length ? `${trimmed}…` : trimmed || "Generated project";
}

function EmptyState() {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-4 px-4 text-center">
      <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-accent text-accent-fg">
        <Sparkles size={22} />
      </div>
      <div>
        <h1 className="text-xl font-semibold">ChomuGirI mein aapka swagat hai</h1>
        <p className="mt-1 max-w-md text-sm text-fg-muted">
          Casual baat karo to fast reply milega. Koi app, website ya script banane ko bolo to
          poora AI swarm (Kimi K3 → GLM 5.2 → DeepSeek R1 → Nemotron Ultra) khud kaam pe lag jayega.
        </p>
      </div>
    </div>
  );
}
