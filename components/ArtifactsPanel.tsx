"use client";

import { useMemo, useState } from "react";
import JSZip from "jszip";
import { Download, Rocket, X, Code, PlayCircle, Loader2, CheckCircle2 } from "lucide-react";
import { useAppStore } from "@/lib/store";
import CodeRunner from "./CodeRunner";

type Tab = "code" | "run";

export default function ArtifactsPanel() {
  const activeArtifactId = useAppStore((s) => s.activeArtifactId);
  const artifact = useAppStore((s) => (activeArtifactId ? s.artifacts[activeArtifactId] : undefined));
  const setActiveArtifactId = useAppStore((s) => s.setActiveArtifactId);
  const vercelToken = useAppStore((s) => s.settings.vercelToken);

  const [tab, setTab] = useState<Tab>("code");
  const [activeFile, setActiveFile] = useState<string | null>(null);
  const [deployState, setDeployState] = useState<"idle" | "deploying" | "done" | "error">("idle");
  const [deployUrl, setDeployUrl] = useState<string | null>(null);
  const [deployError, setDeployError] = useState<string | null>(null);

  const currentFile = useMemo(() => {
    if (!artifact) return null;
    return artifact.files.find((f) => f.path === activeFile) ?? artifact.files[0] ?? null;
  }, [artifact, activeFile]);

  if (!artifact) return null;

  async function downloadZip() {
    if (!artifact) return;
    const zip = new JSZip();
    for (const f of artifact.files) zip.file(f.path, f.content);
    const blob = await zip.generateAsync({ type: "blob" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${artifact.title.replace(/[^a-z0-9-_]+/gi, "-") || "chomugiri-project"}.zip`;
    a.click();
    URL.revokeObjectURL(url);
  }

  async function deploy() {
    if (!artifact) return;
    if (!vercelToken) {
      setDeployState("error");
      setDeployError("Settings me Vercel token pehle daalo.");
      return;
    }
    setDeployState("deploying");
    setDeployError(null);
    try {
      const res = await fetch("/api/vercel-deploy", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          vercelToken,
          projectName: artifact.title,
          files: artifact.files,
        }),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data?.error ?? "Deploy fail hua");
      setDeployUrl(data.url);
      setDeployState("done");
    } catch (err) {
      setDeployState("error");
      setDeployError(err instanceof Error ? err.message : "Deploy fail hua");
    }
  }

  return (
    <div className="flex h-full w-full flex-col border-l border-border bg-bg">
      <div className="flex items-center justify-between border-b border-border px-3 py-2">
        <div className="min-w-0">
          <p className="truncate text-sm font-medium">{artifact.title}</p>
          <p className="text-xs text-fg-muted">{artifact.files.length} files</p>
        </div>
        <div className="flex items-center gap-1.5">
          <button
            onClick={downloadZip}
            title="Download as zip"
            className="flex items-center gap-1 rounded-md border border-border px-2 py-1.5 text-xs text-fg-muted hover:text-fg"
          >
            <Download size={13} /> Download
          </button>
          <button
            onClick={deploy}
            disabled={deployState === "deploying"}
            title="Deploy to Vercel"
            className="flex items-center gap-1 rounded-md border border-accent/50 bg-accent/10 px-2 py-1.5 text-xs text-accent hover:bg-accent/20 disabled:opacity-50"
          >
            {deployState === "deploying" ? (
              <Loader2 size={13} className="animate-spin" />
            ) : deployState === "done" ? (
              <CheckCircle2 size={13} />
            ) : (
              <Rocket size={13} />
            )}
            Deploy
          </button>
          <button
            onClick={() => setActiveArtifactId(null)}
            className="rounded-md p-1.5 text-fg-muted hover:text-fg"
          >
            <X size={15} />
          </button>
        </div>
      </div>

      {(deployUrl || deployError) && (
        <div className="border-b border-border px-3 py-2 text-xs">
          {deployUrl && (
            <a href={deployUrl} target="_blank" rel="noreferrer" className="text-accent underline">
              Deployed: {deployUrl}
            </a>
          )}
          {deployError && <span className="text-danger">{deployError}</span>}
        </div>
      )}

      <div className="flex border-b border-border px-2">
        <button
          onClick={() => setTab("code")}
          className={`flex items-center gap-1.5 border-b-2 px-3 py-2 text-xs ${
            tab === "code" ? "border-accent text-fg" : "border-transparent text-fg-muted"
          }`}
        >
          <Code size={13} /> Code
        </button>
        <button
          onClick={() => setTab("run")}
          className={`flex items-center gap-1.5 border-b-2 px-3 py-2 text-xs ${
            tab === "run" ? "border-accent text-fg" : "border-transparent text-fg-muted"
          }`}
        >
          <PlayCircle size={13} /> Run &amp; Terminal
        </button>
      </div>

      <div className="min-h-0 flex-1">
        {tab === "code" ? (
          <div className="flex h-full">
            <div className="w-44 shrink-0 overflow-y-auto border-r border-border py-2">
              {artifact.files.map((f) => (
                <button
                  key={f.path}
                  onClick={() => setActiveFile(f.path)}
                  className={`block w-full truncate px-3 py-1.5 text-left text-xs ${
                    (currentFile?.path ?? artifact.files[0]?.path) === f.path
                      ? "bg-bg-elevated text-fg"
                      : "text-fg-muted hover:text-fg"
                  }`}
                  title={f.path}
                >
                  {f.path}
                </button>
              ))}
            </div>
            <div className="min-w-0 flex-1 overflow-auto p-3">
              <pre className="whitespace-pre-wrap break-words font-mono text-[13px] leading-relaxed text-fg">
                <code>{currentFile?.content}</code>
              </pre>
            </div>
          </div>
        ) : (
          <CodeRunner files={artifact.files} />
        )}
      </div>
    </div>
  );
}
