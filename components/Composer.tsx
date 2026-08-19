"use client";

import { useState, type KeyboardEvent } from "react";
import { ArrowUp } from "lucide-react";

export default function Composer({
  onSend,
  disabled,
}: {
  onSend: (text: string) => void;
  disabled?: boolean;
}) {
  const [value, setValue] = useState("");

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
    <div className="mx-auto w-full max-w-3xl px-3 pb-3 sm:px-4 sm:pb-4">
      <div className="rounded-[1.4rem] border border-border bg-bg-elevated p-2 shadow-[0_8px_30px_-12px_rgba(0,0,0,0.6)] transition-shadow focus-within:border-border-strong focus-within:shadow-[0_0_0_3px_var(--accent-soft)]">
        <textarea
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={handleKeyDown}
          rows={1}
          placeholder="Message ChomuGirI... ask for an app or script to wake the swarm"
          className="max-h-48 min-h-[44px] w-full resize-none bg-transparent px-3 py-2 text-[15px] outline-none placeholder:text-fg-muted"
          style={{ height: "auto" }}
          onInput={(e) => {
            const el = e.currentTarget;
            el.style.height = "auto";
            el.style.height = `${Math.min(el.scrollHeight, 192)}px`;
          }}
        />
        <div className="flex items-center justify-end px-1 pb-1 pt-1">
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
      <p className="mt-2 hidden text-center text-xs text-fg-muted sm:block">
        Casual chat gets a fast reply. App/code requests wake the full swarm (Kimi → GLM → DeepSeek → Nemotron) — automatically.
      </p>
    </div>
  );
}
