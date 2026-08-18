"use client";

import { useState } from "react";
import { Eye, EyeOff, Save, Terminal, Rocket, LayoutPanelLeft } from "lucide-react";
import { useAppStore } from "@/lib/store";
import { ROLE_LABELS, ROLE_ORDER } from "@/lib/types";
import type { RoleKey } from "@/lib/types";

function ProviderRow({ role }: { role: RoleKey }) {
  const config = useAppStore((s) => s.settings.providers[role]);
  const setProviderConfig = useAppStore((s) => s.setProviderConfig);
  const [showKey, setShowKey] = useState(false);

  return (
    <div className="rounded-xl border border-border bg-bg-elevated p-4">
      <p className="mb-3 text-sm font-medium">{ROLE_LABELS[role]}</p>
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="sm:col-span-2">
          <label className="mb-1 block text-xs text-fg-muted">API Key</label>
          <div className="flex items-center gap-2">
            <input
              type={showKey ? "text" : "password"}
              value={config.apiKey}
              onChange={(e) => setProviderConfig(role, { apiKey: e.target.value })}
              placeholder="sk-..."
              className="w-full rounded-lg border border-border bg-bg px-3 py-2 text-sm outline-none focus:border-accent"
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
            placeholder="https://openrouter.ai/api/v1"
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

export default function SettingsForm() {
  const vercelToken = useAppStore((s) => s.settings.vercelToken);
  const setVercelToken = useAppStore((s) => s.setVercelToken);
  const maxAuditLoops = useAppStore((s) => s.settings.maxAuditLoops);
  const setMaxAuditLoops = useAppStore((s) => s.setMaxAuditLoops);
  const [showVercel, setShowVercel] = useState(false);
  const [savedFlash, setSavedFlash] = useState(false);

  return (
    <div className="mx-auto max-w-3xl px-4 py-8">
      <h1 className="text-xl font-semibold">Settings</h1>
      <p className="mt-1 text-sm text-fg-muted">
        Har key browser me (localStorage) save hoti hai aur sirf tab bheji jaati hai jab tum
        khud message ya code-gen request bhejte ho — kisi third-party server pe store nahi hoti.
      </p>

      <section className="mt-6 space-y-3">
        <h2 className="text-sm font-semibold text-fg-muted">AI Router / Swarm Models</h2>
        {ROLE_ORDER.map((role) => (
          <ProviderRow key={role} role={role} />
        ))}

        <div className="rounded-xl border border-border bg-bg-elevated p-4">
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
        <div className="rounded-xl border border-border bg-bg-elevated p-4">
          <label className="mb-1 block text-xs text-fg-muted">Vercel Access Token</label>
          <div className="flex items-center gap-2">
            <input
              type={showVercel ? "text" : "password"}
              value={vercelToken}
              onChange={(e) => setVercelToken(e.target.value)}
              placeholder="vercel token..."
              className="w-full rounded-lg border border-border bg-bg px-3 py-2 text-sm outline-none focus:border-accent"
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
            Artifact panel ke &quot;Deploy&quot; button se seedha Vercel pe production deployment
            ban jayega. Token{" "}
            <a
              className="text-accent underline"
              href="https://vercel.com/account/tokens"
              target="_blank"
              rel="noreferrer"
            >
              vercel.com/account/tokens
            </a>{" "}
            se banao.
          </p>
        </div>
      </section>

      <section className="mt-8 space-y-3">
        <h2 className="flex items-center gap-2 text-sm font-semibold text-fg-muted">
          <Terminal size={14} /> Cloud Terminal &amp; Code Runner
        </h2>
        <div className="rounded-xl border border-border bg-bg-elevated p-4 text-sm text-fg-muted">
          <p>
            Har artifact ke saath ek in-browser sandbox (WebContainers) attach hota hai — koi
            alag connect/setup nahi karna, jaise hi code generate hota hai, artifact ke
            <strong className="text-fg"> &quot;Run &amp; Terminal&quot;</strong> tab me jaake seedha
            <code className="mx-1 rounded bg-bg-elevated-2 px-1.5 py-0.5">npm install</code> /
            <code className="mx-1 rounded bg-bg-elevated-2 px-1.5 py-0.5">npm run dev</code> khud
            chal jaata hai aur live preview mil jaati hai — download kiye bina. Terminal fully
            interactive hai, tum khud bhi commands type kar sakte ho.
          </p>
        </div>
      </section>

      <section className="mt-8 space-y-3">
        <h2 className="flex items-center gap-2 text-sm font-semibold text-fg-muted">
          <LayoutPanelLeft size={14} /> Canvas &amp; Artifacts
        </h2>
        <div className="rounded-xl border border-border bg-bg-elevated p-4 text-sm text-fg-muted">
          Generated code hamesha right-side artifact panel me open hota hai (Claude-style) — code
          view, live run/preview, download-as-zip aur deploy sab wahin se milta hai. Purane
          artifacts conversation history ke saath localStorage me save rehte hain.
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
        {savedFlash ? "Saved!" : "Settings pehle se hi auto-save hoti hain"}
      </button>
    </div>
  );
}
