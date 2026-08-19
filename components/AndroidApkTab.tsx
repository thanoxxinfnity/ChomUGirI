"use client";

import { useState } from "react";
import { Download, Copy, Check, Smartphone } from "lucide-react";
import { useAppStore } from "@/lib/store";
import { buildAndroidProjectZip, ANDROID_BUILD_COMMANDS } from "@/lib/androidProject";
import type { Artifact } from "@/lib/types";

export default function AndroidApkTab({ artifact }: { artifact: Artifact }) {
  const cloudTerminalUrl = useAppStore((s) => s.settings.cloudTerminalUrl);
  const [copied, setCopied] = useState(false);

  async function downloadProject() {
    const blob = await buildAndroidProjectZip(artifact);
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${artifact.title.replace(/[^a-z0-9-_]+/gi, "-") || "chomugiri-app"}-android.zip`;
    a.click();
    URL.revokeObjectURL(url);
  }

  async function copyCommands() {
    await navigator.clipboard.writeText(ANDROID_BUILD_COMMANDS);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  }

  return (
    <div className="mx-auto max-w-lg p-4 sm:p-6">
      <div className="flex items-center gap-2.5">
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-accent-2/15 text-accent-2">
          <Smartphone size={18} />
        </div>
        <div>
          <p className="text-sm font-medium">Android APK</p>
          <p className="text-xs text-fg-muted">Real build, no automation pretending to be magic</p>
        </div>
      </div>

      <p className="mt-4 text-sm text-fg-muted">
        This downloads the project as a ready-to-build{" "}
        <a
          className="text-accent-2 underline"
          href="https://capacitorjs.com/"
          target="_blank"
          rel="noreferrer"
        >
          Capacitor
        </a>{" "}
        Android app — it bundles these files locally, so the APK works offline. Nothing gets
        built automatically here: you run the real Android build yourself, in a real terminal
        with the Android SDK and a JDK installed.
      </p>

      <button
        onClick={downloadProject}
        className="mt-4 flex w-full items-center justify-center gap-2 rounded-lg bg-accent px-4 py-2.5 text-sm font-medium text-accent-fg hover:bg-accent-hover"
      >
        <Download size={15} /> Download Android project (.zip)
      </button>

      <div className="mt-5 rounded-xl border border-border bg-bg-elevated p-3.5">
        <p className="text-xs font-medium text-fg-muted">Then, in a terminal:</p>
        <div className="mt-2 flex items-center gap-2">
          <code className="min-w-0 flex-1 overflow-x-auto whitespace-nowrap rounded-lg bg-bg px-3 py-2 font-mono text-[11px] text-fg">
            {ANDROID_BUILD_COMMANDS}
          </code>
          <button
            onClick={copyCommands}
            className="shrink-0 rounded-lg border border-border p-2 text-fg-muted hover:text-fg"
            title="Copy"
          >
            {copied ? <Check size={14} className="text-success" /> : <Copy size={14} />}
          </button>
        </div>
        <p className="mt-2 text-xs text-fg-muted">
          The finished APK lands at{" "}
          <code className="rounded bg-bg-elevated-2 px-1 py-0.5">
            android/app/build/outputs/apk/debug/app-debug.apk
          </code>
          .
        </p>
      </div>

      {cloudTerminalUrl ? (
        <p className="mt-3 text-xs text-fg-muted">
          You&apos;ve set a Cloud Terminal URL in Settings — open the{" "}
          <strong className="text-fg">&quot;My Terminal&quot;</strong> tab, extract the
          downloaded project there, and run the commands above for a real APK.
        </p>
      ) : (
        <p className="mt-3 text-xs text-fg-muted">
          No terminal with the Android SDK yet? Add your own in{" "}
          <strong className="text-fg">Settings → Cloud Terminal URL</strong> and it shows up
          here as a &quot;My Terminal&quot; tab.
        </p>
      )}
    </div>
  );
}
