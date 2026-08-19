"use client";

import Link from "next/link";
import { FileCode2, LayoutGrid, Code, Maximize2, PlayCircle, Smartphone, TerminalSquare } from "lucide-react";
import { useAppStore } from "@/lib/store";

const TOOLS = [
  { icon: Code, label: "Code" },
  { icon: Maximize2, label: "Canvas" },
  { icon: PlayCircle, label: "Run" },
  { icon: Smartphone, label: "Android APK" },
  { icon: TerminalSquare, label: "My Terminal" },
];

export default function ArtifactsListView() {
  const artifacts = useAppStore((s) => s.artifacts);
  const setActiveArtifactId = useAppStore((s) => s.setActiveArtifactId);
  const list = Object.values(artifacts).sort((a, b) => b.createdAt - a.createdAt);

  if (list.length === 0) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 px-4 text-center">
        <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-bg-elevated text-fg-muted">
          <LayoutGrid size={20} />
        </div>
        <div>
          <h1 className="font-display text-xl font-medium">No artifacts yet</h1>
          <p className="mt-1 max-w-sm text-sm text-fg-muted">
            Go to <Link href="/" className="text-accent underline">Chat</Link> and ask ChomuGirI
            to build something — an app, a site, a script. The generated project shows up here
            with five tools attached:
          </p>
        </div>
        <div className="flex flex-wrap items-center justify-center gap-2">
          {TOOLS.map(({ icon: Icon, label }) => (
            <div
              key={label}
              className="flex items-center gap-1.5 rounded-full border border-border bg-bg-elevated px-3 py-1.5 text-xs text-fg-muted"
            >
              <Icon size={13} /> {label}
            </div>
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-3xl px-3 py-6 sm:px-4 sm:py-8">
      <h1 className="text-xl font-semibold">Artifacts</h1>
      <p className="mt-1 text-sm text-fg-muted">
        Every project the swarm has generated, across every chat.
      </p>

      <div className="mt-6 grid gap-2.5 sm:grid-cols-2">
        {list.map((a) => (
          <button
            key={a.id}
            onClick={() => setActiveArtifactId(a.id)}
            className="flex items-start gap-2.5 rounded-xl border border-border bg-bg-elevated p-3.5 text-left transition-colors hover:border-accent-2/60"
          >
            <FileCode2 size={16} className="mt-0.5 shrink-0 text-accent-2" />
            <div className="min-w-0">
              <p className="truncate text-sm font-medium">{a.title}</p>
              <p className="mt-0.5 text-xs text-fg-muted">
                {a.files.length} file{a.files.length === 1 ? "" : "s"} ·{" "}
                {new Date(a.createdAt).toLocaleDateString()}
              </p>
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}
