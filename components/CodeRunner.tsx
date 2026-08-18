"use client";

import { useEffect, useRef, useState } from "react";
import "@xterm/xterm/css/xterm.css";
import { Loader2, RotateCcw, ExternalLink } from "lucide-react";
import type { GeneratedFile } from "@/lib/types";
import { filesToTree, getWebContainer, hasPackageJson, pickStartScript } from "@/lib/webcontainer";
import type { EnvVar } from "@/lib/types";

type Status = "idle" | "booting" | "installing" | "running" | "error";

function shellQuote(value: string) {
  return `'${value.replace(/'/g, `'\\''`)}'`;
}

/**
 * Boots a WebContainer, mounts the artifact's files, and drives everything through a single
 * interactive shell — the same process backs both the "cloud terminal" and the code runner:
 * install/start commands are typed into it automatically and the user can keep typing after.
 */
export default function CodeRunner({ files, envVars = [] }: { files: GeneratedFile[]; envVars?: EnvVar[] }) {
  const termHostRef = useRef<HTMLDivElement>(null);
  const shellInputRef = useRef<((data: string) => void) | null>(null);
  const [status, setStatus] = useState<Status>("idle");
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [runToken, setRunToken] = useState(0);

  useEffect(() => {
    let cancelled = false;
    let disposeTerm: (() => void) | null = null;

    async function boot() {
      setStatus("booting");
      setErrorMsg(null);
      setPreviewUrl(null);

      try {
        const [{ Terminal }, { FitAddon }] = await Promise.all([
          import("@xterm/xterm"),
          import("@xterm/addon-fit"),
        ]);

        if (cancelled || !termHostRef.current) return;

        const term = new Terminal({
          convertEol: true,
          fontSize: 13,
          fontFamily: "var(--font-geist-mono), monospace",
          theme: {
            background: "#1e1e1c",
            foreground: "#eeece7",
            cursor: "#d97757",
          },
        });
        const fit = new FitAddon();
        term.loadAddon(fit);
        term.open(termHostRef.current);
        fit.fit();

        const resizeObserver = new ResizeObserver(() => {
          try {
            fit.fit();
          } catch {
            // ignore transient resize errors during teardown
          }
        });
        resizeObserver.observe(termHostRef.current);

        disposeTerm = () => {
          resizeObserver.disconnect();
          term.dispose();
        };

        const wc = await getWebContainer();
        if (cancelled) return;

        wc.on("server-ready", (_port, url) => {
          if (!cancelled) setPreviewUrl(url);
        });

        await wc.mount(filesToTree(files));

        const shell = await wc.spawn("jsh", [], {
          terminal: { cols: term.cols, rows: term.rows },
        });

        shell.output.pipeTo(
          new WritableStream({
            write(data) {
              term.write(data);
            },
          }),
        );

        const writer = shell.input.getWriter();
        shellInputRef.current = (data: string) => writer.write(data);
        term.onData((data) => writer.write(data));
        term.onResize(({ cols, rows }) => shell.resize({ cols, rows }));

        setStatus("installing");

        const validEnvVars = envVars.filter((v) => v.key.trim());
        if (validEnvVars.length > 0) {
          const exports = validEnvVars
            .map((v) => `export ${v.key.trim()}=${shellQuote(v.value)}`)
            .join(" && ");
          writer.write(`${exports}\n`);
        }

        const startScript = pickStartScript(files);
        const command = hasPackageJson(files)
          ? `npm install && npm run ${startScript ?? "dev"}\n`
          : `npx --yes serve -l 3000\n`;

        writer.write(command);
        setStatus("running");
      } catch (err) {
        if (!cancelled) {
          setStatus("error");
          setErrorMsg(err instanceof Error ? err.message : "WebContainer boot fail hua.");
        }
      }
    }

    boot();

    return () => {
      cancelled = true;
      disposeTerm?.();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runToken]);

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between border-b border-border bg-bg-elevated px-3 py-2">
        <div className="flex items-center gap-2 text-xs text-fg-muted">
          {status === "booting" && (
            <>
              <Loader2 size={12} className="animate-spin" /> Sandbox boot ho raha hai...
            </>
          )}
          {status === "installing" && (
            <>
              <Loader2 size={12} className="animate-spin" /> Install/start ho raha hai...
            </>
          )}
          {status === "running" && previewUrl && (
            <>
              <span className="h-2 w-2 rounded-full bg-success" /> Live: {previewUrl}
            </>
          )}
          {status === "running" && !previewUrl && (
            <>
              <Loader2 size={12} className="animate-spin" /> Server start ho raha hai, preview ka wait...
            </>
          )}
          {status === "error" && <span className="text-danger">{errorMsg}</span>}
        </div>
        <div className="flex items-center gap-2">
          {previewUrl && (
            <a
              href={previewUrl}
              target="_blank"
              rel="noreferrer"
              className="flex items-center gap-1 rounded-md border border-border px-2 py-1 text-xs text-fg-muted hover:text-fg"
            >
              <ExternalLink size={12} /> New tab
            </a>
          )}
          <button
            onClick={() => {
              shellInputRef.current = null;
              setRunToken((t) => t + 1);
            }}
            className="flex items-center gap-1 rounded-md border border-border px-2 py-1 text-xs text-fg-muted hover:text-fg"
          >
            <RotateCcw size={12} /> Restart
          </button>
        </div>
      </div>

      <div className="grid min-h-0 flex-1 grid-rows-2">
        <div className="min-h-0 border-b border-border bg-white">
          {previewUrl ? (
            <iframe src={previewUrl} className="h-full w-full" title="Live preview" />
          ) : (
            <div className="flex h-full items-center justify-center text-sm text-zinc-400">
              Preview yahan aayega jab dev server ready ho jayega.
            </div>
          )}
        </div>
        <div className="min-h-0 bg-bg-sidebar p-1" ref={termHostRef} />
      </div>
    </div>
  );
}
