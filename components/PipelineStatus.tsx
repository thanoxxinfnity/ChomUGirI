"use client";

import { useState } from "react";
import { Sparkles, Loader2, CheckCircle2, XCircle, X } from "lucide-react";
import type { PipelineEvent } from "@/lib/types";

interface LogLine {
  message: string;
  status: "active" | "done" | "info";
}

function reduceLog(events: PipelineEvent[]): LogLine[] {
  const lines: LogLine[] = [];
  for (const ev of events) {
    if ((ev.type === "route" || ev.type === "log") && ev.message) {
      lines.push({ message: ev.message, status: "info" });
    }
    if (ev.type === "stage" && ev.message) {
      lines.push({ message: ev.message, status: ev.status === "end" ? "done" : "active" });
    }
  }
  return lines;
}

/**
 * Collapsed by default — a small "thinking" bubble (Gemini-style shimmer while active) that
 * pops open a modal with the full step log (Claude-style), instead of cluttering the chat
 * with a live stage-by-stage timeline.
 */
export default function PipelineStatus({ events }: { events: PipelineEvent[] }) {
  const [open, setOpen] = useState(false);
  if (!events.length) return null;

  const isDone = events.some((e) => e.type === "done");
  const errorEvent = events.find((e) => e.type === "error");
  const lines = reduceLog(events);
  const current = lines[lines.length - 1]?.message;

  return (
    <div className="mb-2">
      <button
        type="button"
        onClick={() => setOpen(true)}
        className={`inline-flex max-w-full items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs transition-colors ${
          errorEvent
            ? "border-danger/40 bg-danger/10 text-danger"
            : isDone
              ? "border-success/40 bg-success/10 text-success hover:border-success/60"
              : "border-accent/40 bg-accent/10 text-accent hover:border-accent/60"
        }`}
      >
        {errorEvent ? (
          <XCircle size={13} className="shrink-0" />
        ) : isDone ? (
          <CheckCircle2 size={13} className="shrink-0" />
        ) : (
          <Sparkles size={13} className="pulse-dot shrink-0" />
        )}
        <span className={`truncate ${!isDone && !errorEvent ? "shimmer-text" : ""}`}>
          {errorEvent ? "Build failed — tap for details" : isDone ? "Thought for a moment" : (current ?? "Thinking...")}
        </span>
      </button>

      {open && (
        <div
          className="fixed inset-0 z-[60] flex items-center justify-center bg-black/60 p-4"
          onClick={() => setOpen(false)}
        >
          <div
            className="bubble-in max-h-[70vh] w-full max-w-md overflow-hidden rounded-2xl border border-border bg-bg-elevated shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between border-b border-border px-4 py-3">
              <p className="flex items-center gap-1.5 text-sm font-medium">
                <Sparkles size={14} className="text-accent" /> Thinking
              </p>
              <button
                onClick={() => setOpen(false)}
                className="rounded-md p-1 text-fg-muted hover:text-fg"
              >
                <X size={16} />
              </button>
            </div>
            <div className="max-h-[calc(70vh-52px)] overflow-y-auto p-4">
              <ol className="space-y-2.5">
                {lines.map((line, i) => (
                  <li key={i} className="flex items-start gap-2 font-mono text-[12px] leading-relaxed">
                    {line.status === "active" ? (
                      <Loader2 size={12} className="mt-0.5 shrink-0 animate-spin text-accent" />
                    ) : line.status === "done" ? (
                      <CheckCircle2 size={12} className="mt-0.5 shrink-0 text-success" />
                    ) : (
                      <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-fg-muted" />
                    )}
                    <span className={line.status === "info" ? "text-fg-muted" : "text-fg"}>
                      {line.message}
                    </span>
                  </li>
                ))}
                {errorEvent && (
                  <li className="flex items-start gap-2 font-mono text-[12px] leading-relaxed text-danger">
                    <XCircle size={12} className="mt-0.5 shrink-0" />
                    {errorEvent.message}
                  </li>
                )}
              </ol>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
