"use client";

import { useState, type KeyboardEvent } from "react";
import { ArrowUp, Code2 } from "lucide-react";
import { useAppStore } from "@/lib/store";

export default function Composer({
  onSend,
  disabled,
}: {
  onSend: (text: string) => void;
  disabled?: boolean;
}) {
  const [value, setValue] = useState("");
  const forceCodeMode = useAppStore((s) => s.settings.forceCodeMode);
  const setForceCodeMode = useAppStore((s) => s.setForceCodeMode);

  function submit() {
    const text = value.trim();
    if (!text || disabled) return;
    onSend(text);
    setValue("");
  }

  function handleKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  }

  return (
    <div className="mx-auto w-full max-w-3xl px-4 pb-4">
      <div className="rounded-2xl border border-border bg-bg-elevated p-2 shadow-lg">
        <textarea
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={handleKeyDown}
          rows={1}
          placeholder="Message ChomuGirI... (app/code banane ko bolo to swarm khud activate ho jayega)"
          className="max-h-48 min-h-[44px] w-full resize-none bg-transparent px-3 py-2 text-[15px] outline-none placeholder:text-fg-muted"
          style={{ height: "auto" }}
          onInput={(e) => {
            const el = e.currentTarget;
            el.style.height = "auto";
            el.style.height = `${Math.min(el.scrollHeight, 192)}px`;
          }}
        />
        <div className="flex items-center justify-between px-1 pb-1 pt-1">
          <button
            type="button"
            onClick={() => setForceCodeMode(!forceCodeMode)}
            title="Force code pipeline mode (Kimi/GLM/DeepSeek/Nemotron) chahe message casual lage"
            className={`flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs transition-colors ${
              forceCodeMode
                ? "border-accent bg-accent/15 text-accent"
                : "border-border text-fg-muted hover:text-fg"
            }`}
          >
            <Code2 size={13} />
            Code mode {forceCodeMode ? "ON" : "OFF"}
          </button>

          <button
            type="button"
            onClick={submit}
            disabled={disabled || !value.trim()}
            className="flex h-8 w-8 items-center justify-center rounded-full bg-accent text-accent-fg transition-opacity disabled:opacity-30"
          >
            <ArrowUp size={16} />
          </button>
        </div>
      </div>
      <p className="mt-2 text-center text-xs text-fg-muted">
        Casual baat = fast reply. App/code request = poora AI swarm (Kimi → GLM → DeepSeek → Nemotron).
      </p>
    </div>
  );
}
