"use client";

import { useState } from "react";
import { ChevronRight, Loader2, CheckCircle2, XCircle } from "lucide-react";
import type { PipelineEvent } from "@/lib/types";

/**
 * Deliberately minimal: a single status line while the swarm works, one line when it's done,
 * and a collapsed (opt-in) log underneath — no per-model "thinking" chips or live stage
 * timeline cluttering the chat. Build details are always available a click away, never forced.
 */
export default function PipelineStatus({ events }: { events: PipelineEvent[] }) {
  const [expanded, setExpanded] = useState(false);
  if (!events.length) return null;

  const isDone = events.some((e) => e.type === "done");
  const errorEvent = events.find((e) => e.type === "error");
  const lines = events
    .filter((e) => (e.type === "stage" || e.type === "log" || e.type === "route") && e.message)
    .map((e) => e.message as string);
  const current = lines[lines.length - 1];

  return (
    <div className="mb-2">
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        className="flex w-full items-center gap-2 rounded-lg py-1 text-left text-xs text-fg-muted transition-colors hover:text-fg"
      >
        <ChevronRight size={12} className={`shrink-0 transition-transform ${expanded ? "rotate-90" : ""}`} />
        {errorEvent ? (
          <XCircle size={13} className="shrink-0 text-danger" />
        ) : isDone ? (
          <CheckCircle2 size={13} className="shrink-0 text-success" />
        ) : (
          <Loader2 size={13} className="shrink-0 animate-spin text-accent" />
        )}
        <span className="truncate font-mono">
          {errorEvent ? "Build failed" : isDone ? "Build complete" : (current ?? "Working...")}
        </span>
      </button>

      {expanded && (
        <div className="ml-5 mt-1 space-y-0.5 border-l border-border pl-3 font-mono text-[11px] text-fg-muted">
          {lines.map((line, i) => (
            <p key={i}>{line}</p>
          ))}
          {errorEvent && <p className="text-danger">{errorEvent.message}</p>}
        </div>
      )}
    </div>
  );
}
