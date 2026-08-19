"use client";

import { useState } from "react";
import {
  Eye,
  EyeOff,
  Save,
  Terminal,
  Rocket,
  LayoutPanelLeft,
  Zap,
  Plus,
  Trash2,
} from "lucide-react";
import { useAppStore } from "@/lib/store";
import { PROVIDER_PRESETS, ROLE_LABELS, ROLE_ORDER } from "@/lib/types";
import type { ProviderPresetKey, RoleKey } from "@/lib/types";

function presetForBaseUrl(baseUrl: string): ProviderPresetKey {
  const entry = (Object.entries(PROVIDER_PRESETS) as [ProviderPresetKey, { baseUrl: string }][]).find(
    ([key, v]) => key !== "custom" && v.baseUrl === baseUrl,
  );
  return entry ? entry[0] : "custom";
}

function ProviderRow({ role }: { role: RoleKey }) {
  const config = useAppStore((s) => s.settings.providers[role]);
  const setProviderConfig = useAppStore((s) => s.setProviderConfig);
  const [showKey, setShowKey] = useState(false);
  const preset = presetForBaseUrl(config.baseUrl);

  return (
    <div className="rounded-xl border border-border bg-bg-elevated p-3.5 sm:p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm font-medium">{ROLE_LABELS[role]}</p>
        <select
          value={preset}
          onChange={(e) => {
            const key = e.target.value as ProviderPresetKey;
            if (key !== "custom") setProviderConfig(role, { baseUrl: PROVIDER_PRESETS[key].baseUrl });
          }}
          className="rounded-md border border-border bg-bg px-2 py-1 text-xs text-fg-muted outline-none focus:border-accent"
        >
          {Object.entries(PROVIDER_PRESETS).map(([key, v]) => (
            <option key={key} value={key}>
              {v.label}
            </option>
          ))}
        </select>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="sm:col-span-2">
          <label className="mb-1 block text-xs text-fg-muted">API Key</label>
          <div className="flex items-center gap-2">
            <input
              type={showKey ? "text" : "password"}
              value={config.apiKey}
              onChange={(e) => setProviderConfig(role, { apiKey: e.target.value })}
              placeholder="Paste your API key..."
              className="w-full min-w-0 rounded-lg border border-border bg-bg px-3 py-2 text-sm outline-none focus:border-accent"
            />
            <button
              type="button"
              onClick={() => setShowKey((v) => !v)}
              className="shrink-0 rounded-lg border border-border p-2 text-fg-muted hover:text-fg"
            >
              {showKey ? <EyeOff size={14} /> : <Eye size={14} />}
            </button>
          </div>
        </div>
        <div>
          <label className="mb-1 block text-xs text-fg-muted">Base URL</label>
          <input
            value={config.baseUrl}
            onChange={(e) => setProviderConfig(role, { baseUrl: e.target.value })}
            placeholder="https://integrate.api.nvidia.com/v1"
            className="w-full rounded-lg border border-border bg-bg px-3 py-2 text-sm outline-none focus:border-accent"
          />
        </div>
        <div>
          <label className="mb-1 block text-xs text-fg-muted">Model ID</label>
          <input
            value={config.model}
            onChange={(e) => setProviderConfig(role, { model: e.target.value })}
            placeholder="provider/model-name"
            className="w-full rounded-lg border border-border bg-bg px-3 py-2 text-sm outline-none focus:border-accent"
          />
        </div>
      </div>
    </div>
  );
}

function QuickFillNim() {
  const setProviderConfig = useAppStore((s) => s.setProviderConfig);
  const [key, setKey] = useState("");

  function apply() {
    if (!key.trim()) return;
    for (const role of ROLE_ORDER) {
      setProviderConfig(role, { apiKey: key.trim(), baseUrl: PROVIDER_PRESETS.nim.baseUrl });
    }
    setKey("");
  }

  return (
    <div className="rounded-xl border border-accent/40 bg-accent/5 p-3.5 sm:p-4">
      <p className="flex items-center gap-1.5 text-sm font-medium text-accent">
        <Zap size={14} /> Fast setup: one NVIDIA NIM key for all 5 roles
      </p>
      <p className="mt-1 text-xs text-fg-muted">
        The NIM catalog has exact matches for GLM 5.2 and Nemotron 3 Ultra 550B, plus solid
        defaults for the other roles already filled in. Paste your NIM key once and it applies
        to every provider below (edit any model id per row afterwards).
      </p>
      <div className="mt-2 flex flex-col gap-2 sm:flex-row sm:items-center">
        <input
          type="password"
          value={key}
          onChange={(e) => setKey(e.target.value)}
          placeholder="nvapi-..."
          className="w-full min-w-0 rounded-lg border border-border bg-bg px-3 py-2 text-sm outline-none focus:border-accent"
        />
        <button
          type="button"
          onClick={apply}
          className="shrink-0 rounded-lg bg-accent px-3 py-2 text-xs font-medium text-accent-fg hover:bg-accent-hover"
        >
          Apply to all
        </button>
      </div>
    </div>
  );
}

function EnvVarsSection() {
  const envVars = useAppStore((s) => s.settings.envVars);
  const addEnvVar = useAppStore((s) => s.addEnvVar);
  const updateEnvVar = useAppStore((s) => s.updateEnvVar);
  const removeEnvVar = useAppStore((s) => s.removeEnvVar);
  const [showValues, setShowValues] = useState(false);

  return (
    <div className="rounded-xl border border-border bg-bg-elevated p-3.5 sm:p-4">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs text-fg-muted">
          Like Replit &quot;Secrets&quot; — these get injected into the generated project&apos;s
          cloud terminal shell (and into Vercel deploys) automatically.
        </p>
        <button
          type="button"
          onClick={() => setShowValues((v) => !v)}
          className="flex shrink-0 items-center gap-1 rounded-md border border-border px-2 py-1 text-xs text-fg-muted hover:text-fg"
        >
          {showValues ? <EyeOff size={12} /> : <Eye size={12} />}
          {showValues ? "Hide values" : "Show values"}
        </button>
      </div>

      <div className="space-y-2">
        {envVars.map((v, i) => (
          <div key={i} className="flex items-center gap-2">
            <input
              value={v.key}
              onChange={(e) => updateEnvVar(i, { key: e.target.value })}
              placeholder="KEY"
              className="w-2/5 min-w-0 rounded-lg border border-border bg-bg px-3 py-2 font-mono text-xs outline-none focus:border-accent"
            />
            <input
              type={showValues ? "text" : "password"}
              value={v.value}
              onChange={(e) => updateEnvVar(i, { value: e.target.value })}
              placeholder="value"
              className="min-w-0 flex-1 rounded-lg border border-border bg-bg px-3 py-2 font-mono text-xs outline-none focus:border-accent"
            />
            <button
              type="button"
              onClick={() => removeEnvVar(i)}
              className="shrink-0 rounded-lg border border-border p-2 text-fg-muted hover:border-danger/50 hover:text-danger"
            >
              <Trash2 size={13} />
            </button>
          </div>
        ))}
      </div>

      <button
        type="button"
        onClick={addEnvVar}
        className="mt-3 flex items-center gap-1.5 rounded-lg border border-border px-3 py-1.5 text-xs text-fg-muted hover:text-fg"
      >
        <Plus size={13} /> Add variable
      </button>
    </div>
  );
}

function CloudTerminalSection() {
  const cloudTerminalUrl = useAppStore((s) => s.settings.cloudTerminalUrl);
  const setCloudTerminalUrl = useAppStore((s) => s.setCloudTerminalUrl);

  return (
    <div className="rounded-xl border border-border bg-bg-elevated p-3.5 sm:p-4">
      <label className="mb-1 block text-xs text-fg-muted">Your terminal URL (optional)</label>
      <input
        value={cloudTerminalUrl}
        onChange={(e) => setCloudTerminalUrl(e.target.value)}
        placeholder="https://your-tunnel.ngrok-free.dev"
        className="w-full min-w-0 rounded-lg border border-border bg-bg px-3 py-2 text-sm outline-none focus:border-accent"
      />
      <p className="mt-2 text-xs text-fg-muted">
        Nothing is hardcoded here — this app never connects to any terminal on its own. If you
        run your own web terminal (e.g.{" "}
        <code className="rounded bg-bg-elevated-2 px-1.5 py-0.5">ttyd</code>) and expose it with
        your own tunnel (ngrok, Cloudflare Tunnel, etc.), paste that URL here. It shows up as a
        real, fully interactive &quot;My Terminal&quot; tab on every artifact — useful for things
        the in-browser sandbox can&apos;t do, like a real Android SDK / Flutter build. Leave it
        empty to only use the built-in sandbox.
        <br />
        <span className="text-fg-muted/80">
          Using the ChomuGirI Android app? It has its own separate storage from any browser —
          set this URL again from inside the app itself, it won&apos;t carry over automatically.
        </span>
      </p>
    </div>
  );
}

export default function SettingsForm() {
  const vercelToken = useAppStore((s) => s.settings.vercelToken);
  const setVercelToken = useAppStore((s) => s.setVercelToken);
  const maxAuditLoops = useAppStore((s) => s.settings.maxAuditLoops);
  const setMaxAuditLoops = useAppStore((s) => s.setMaxAuditLoops);
  const [showVercel, setShowVercel] = useState(false);
  const [savedFlash, setSavedFlash] = useState(false);

  return (
    <div className="mx-auto max-w-3xl px-3 py-6 sm:px-4 sm:py-8">
      <h1 className="text-xl font-semibold">Settings</h1>
      <p className="mt-1 text-sm text-fg-muted">
        Every key lives in your browser (localStorage) and is only sent out when you send a
        message or code request — nothing is stored on a third-party server.
      </p>

      <section className="mt-6 space-y-3">
        <h2 className="text-sm font-semibold text-fg-muted">AI Router / Swarm Models</h2>
        <QuickFillNim />
        {ROLE_ORDER.map((role) => (
          <ProviderRow key={role} role={role} />
        ))}

        <div className="rounded-xl border border-border bg-bg-elevated p-3.5 sm:p-4">
          <label className="mb-1 block text-sm font-medium">
            GLM audit loop — max rounds before DeepSeek R1 escalation
          </label>
          <input
            type="number"
            min={1}
            max={10}
            value={maxAuditLoops}
            onChange={(e) => setMaxAuditLoops(Number(e.target.value) || 1)}
            className="mt-1 w-24 rounded-lg border border-border bg-bg px-3 py-2 text-sm outline-none focus:border-accent"
          />
        </div>
      </section>

      <section className="mt-8 space-y-3">
        <h2 className="flex items-center gap-2 text-sm font-semibold text-fg-muted">
          <Rocket size={14} /> Vercel Deploy
        </h2>
        <div className="rounded-xl border border-border bg-bg-elevated p-3.5 sm:p-4">
          <label className="mb-1 block text-xs text-fg-muted">Vercel Access Token</label>
          <div className="flex items-center gap-2">
            <input
              type={showVercel ? "text" : "password"}
              value={vercelToken}
              onChange={(e) => setVercelToken(e.target.value)}
              placeholder="Vercel token..."
              className="w-full min-w-0 rounded-lg border border-border bg-bg px-3 py-2 text-sm outline-none focus:border-accent"
            />
            <button
              type="button"
              onClick={() => setShowVercel((v) => !v)}
              className="shrink-0 rounded-lg border border-border p-2 text-fg-muted hover:text-fg"
            >
              {showVercel ? <EyeOff size={14} /> : <Eye size={14} />}
            </button>
          </div>
          <p className="mt-2 text-xs text-fg-muted">
            The artifact panel&apos;s &quot;Deploy&quot; button ships straight to a Vercel
            production deployment, including the environment variables below. Create a token at{" "}
            <a
              className="text-accent underline"
              href="https://vercel.com/account/tokens"
              target="_blank"
              rel="noreferrer"
            >
              vercel.com/account/tokens
            </a>
            .
          </p>
        </div>
      </section>

      <section className="mt-8 space-y-3">
        <h2 className="text-sm font-semibold text-fg-muted">Environment Variables (Secrets)</h2>
        <EnvVarsSection />
      </section>

      <section className="mt-8 space-y-3">
        <h2 className="flex items-center gap-2 text-sm font-semibold text-fg-muted">
          <Terminal size={14} /> Cloud Terminal &amp; Code Runner
        </h2>
        <div className="rounded-xl border border-border bg-bg-elevated p-3.5 text-sm text-fg-muted sm:p-4">
          <p>
            Every artifact ships with an in-browser sandbox (WebContainers) — nothing to connect
            or set up. As soon as code is generated, open the artifact&apos;s{" "}
            <strong className="text-fg">&quot;Run &amp; Terminal&quot;</strong> tab and{" "}
            <code className="mx-1 rounded bg-bg-elevated-2 px-1.5 py-0.5">npm install</code> /
            <code className="mx-1 rounded bg-bg-elevated-2 px-1.5 py-0.5">npm run dev</code> run
            automatically, with a live preview — no download required. The terminal is fully
            interactive, and any environment variables above are already exported into it.
          </p>
          <p className="mt-2 text-xs">
            Note: this is a browser-sandboxed Node.js runtime (WebContainers) — web apps, APIs,
            and scripts all run fine, but native/compiled toolchains (like the Android SDK or
            Gradle) can&apos;t run inside it, since those need a real Linux machine the browser
            sandbox doesn&apos;t provide. For that, use your own terminal below.
          </p>
        </div>
        <CloudTerminalSection />
      </section>

      <section className="mt-8 space-y-3">
        <h2 className="flex items-center gap-2 text-sm font-semibold text-fg-muted">
          <LayoutPanelLeft size={14} /> Canvas &amp; Artifacts
        </h2>
        <div className="rounded-xl border border-border bg-bg-elevated p-3.5 text-sm text-fg-muted sm:p-4">
          Generated code opens in the artifact panel — Code view, Run (live preview/sandbox),
          Android APK, and (when a terminal URL is set) My Terminal all live there, plus a
          &quot;Canvas&quot; button to expand the whole thing edge-to-edge on desktop. Every
          artifact you&apos;ve ever generated is also browsable from the{" "}
          <strong className="text-fg">Artifacts</strong> page in the sidebar.
        </div>
      </section>

      <button
        onClick={() => {
          setSavedFlash(true);
          setTimeout(() => setSavedFlash(false), 1500);
        }}
        className="mt-8 flex items-center gap-2 rounded-lg bg-accent px-4 py-2 text-sm font-medium text-accent-fg hover:bg-accent-hover"
      >
        <Save size={14} />
        {savedFlash ? "Saved!" : "Settings save automatically"}
      </button>
    </div>
  );
}
