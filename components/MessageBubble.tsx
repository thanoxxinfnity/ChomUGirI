"use client";

import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { User, FileCode2 } from "lucide-react";
import type { ConversationMessage } from "@/lib/types";
import { useAppStore } from "@/lib/store";
import PipelineStatus from "./PipelineStatus";
import Logomark from "./Logomark";

export default function MessageBubble({ message }: { message: ConversationMessage }) {
  const artifact = useAppStore((s) => (message.artifactId ? s.artifacts[message.artifactId] : undefined));
  const setActiveArtifactId = useAppStore((s) => s.setActiveArtifactId);
  const isUser = message.role === "user";

  return (
    <div className={`flex gap-3 ${isUser ? "flex-row-reverse" : ""}`}>
      <div
        className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full ${
          isUser ? "bg-bg-elevated-2 text-fg" : "bg-accent"
        }`}
      >
        {isUser ? <User size={14} /> : <Logomark size={14} />}
      </div>

      <div className={`min-w-0 max-w-[75ch] flex-1 ${isUser ? "flex justify-end" : ""}`}>
        <div
          className={`inline-block w-full rounded-2xl px-4 py-3 ${
            isUser ? "bg-bg-elevated text-fg" : "bg-transparent"
          }`}
        >
          {message.pipelineEvents && message.pipelineEvents.length > 0 && (
            <PipelineStatus events={message.pipelineEvents} />
          )}

          {message.error && (
            <p className="mb-2 rounded-lg border border-danger/40 bg-danger/10 px-3 py-2 text-sm text-danger">
              {message.error}
            </p>
          )}

          <div className="prose-chat text-[15px] leading-relaxed">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content || " "}</ReactMarkdown>
            {message.streaming && <span className="pulse-dot ml-0.5 inline-block">▍</span>}
          </div>

          {artifact && (
            <button
              onClick={() => setActiveArtifactId(artifact.id)}
              className="mt-3 flex w-full items-center gap-2 rounded-xl border border-border bg-bg-elevated px-3 py-2.5 text-left text-sm transition-colors hover:border-accent-2/60"
            >
              <FileCode2 size={16} className="text-accent-2" />
              <span className="flex-1 truncate">
                {artifact.title} · {artifact.files.length} file{artifact.files.length === 1 ? "" : "s"}
              </span>
              <span className="text-xs text-fg-muted">Open</span>
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
