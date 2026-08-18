"use client";

import { Check, Loader2 } from "lucide-react";
import type { PipelineEvent, PipelineStage } from "@/lib/types";
import { ROLE_LABELS } from "@/lib/types";

const STAGE_ROLE: Record<Exclude<PipelineStage, "router" | "done" | "error">, keyof typeof ROLE_LABELS> = {
  kimi: "kimi",
  glm: "glm",
  deepseek: "deepseek",
  nemotron: "nemotron",
};

interface StageState {
  stage: PipelineStage;
  label: string;
  status: "active" | "done" | "pending";
  detail?: string;
}

function reduceEvents(events: PipelineEvent[]): { stages: StageState[]; log: string[] } {
  const order: PipelineStage[] = ["kimi", "glm", "deepseek", "nemotron"];
  const seen = new Map<PipelineStage, StageState>();
  const log: string[] = [];

  for (const ev of events) {
    if (ev.type === "route" && ev.message) log.push(ev.message);
    if (ev.type === "log" && ev.message) log.push(ev.message);
    if (ev.type === "stage" && ev.stage && ev.stage in STAGE_ROLE) {
      const role = STAGE_ROLE[ev.stage as keyof typeof STAGE_ROLE];
      seen.set(ev.stage, {
        stage: ev.stage,
        label: ROLE_LABELS[role] + (ev.iteration ? ` · round ${ev.iteration}` : ""),
        status: ev.status === "end" ? "done" : "active",
        detail: ev.message,
      });
    }
  }

  const stages = order
    .filter((s) => seen.has(s))
    .map((s) => seen.get(s)!);

  return { stages, log };
}

export default function PipelineStatus({ events }: { events: PipelineEvent[] }) {
  if (!events.length) return null;
  const { stages } = reduceEvents(events);
  const routeMsg = events.find((e) => e.type === "route")?.message;
  const isDone = events.some((e) => e.type === "done");
  const errorMsg = events.find((e) => e.type === "error")?.message;

  return (
    <div className="mb-3 rounded-2xl border border-border bg-bg-elevated/60 p-3">
      {routeMsg && (
        <p className="mb-2 font-mono text-[11px] uppercase tracking-wide text-fg-muted">{routeMsg}</p>
      )}
      <div className="flex flex-wrap gap-2">
        {stages.map((s) => (
          <div
            key={s.label}
            className={`flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs ${
              s.status === "done"
                ? "border-success/40 bg-success/10 text-success"
                : "stage-active border-accent/50 bg-accent/10 text-accent"
            }`}
            title={s.detail}
          >
            {s.status === "done" ? (
              <Check size={12} />
            ) : (
              <Loader2 size={12} className="animate-spin" />
            )}
            {s.label}
          </div>
        ))}
        {isDone && (
          <div className="flex items-center gap-1.5 rounded-full border border-success/40 bg-success/10 px-2.5 py-1 text-xs text-success">
            <Check size={12} />
            Final output ready
          </div>
        )}
        {errorMsg && (
          <div className="flex items-center gap-1.5 rounded-full border border-danger/40 bg-danger/10 px-2.5 py-1 text-xs text-danger">
            Error
          </div>
        )}
      </div>
    </div>
  );
}
