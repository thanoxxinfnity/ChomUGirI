"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { MessageSquarePlus, Settings } from "lucide-react";
import { useAppStore } from "@/lib/store";
import Logomark from "./Logomark";

export default function Sidebar() {
  const pathname = usePathname();
  const clearMessages = useAppStore((s) => s.clearMessages);

  return (
    <aside className="flex h-full w-64 shrink-0 flex-col border-r border-border bg-bg-sidebar">
      <div className="flex items-center gap-2.5 px-4 py-5">
        <div className="flex h-8 w-8 items-center justify-center rounded-xl bg-accent">
          <Logomark size={18} />
        </div>
        <span className="font-display text-[1.05rem] font-medium tracking-tight">ChomuGirI</span>
      </div>

      <div className="px-3">
        <button
          onClick={clearMessages}
          className="flex w-full items-center gap-2 rounded-xl border border-border px-3 py-2 text-sm text-fg-muted transition-colors hover:border-border-strong hover:text-fg"
        >
          <MessageSquarePlus size={16} />
          New chat
        </button>
      </div>

      <nav className="mt-2 flex-1 space-y-1 overflow-y-auto px-3">
        <Link
          href="/"
          className={`block rounded-xl px-3 py-2 text-sm transition-colors ${
            pathname === "/"
              ? "bg-bg-elevated text-fg"
              : "text-fg-muted hover:bg-bg-elevated hover:text-fg"
          }`}
        >
          Chat
        </Link>
      </nav>

      <div className="border-t border-border px-3 py-3">
        <Link
          href="/settings"
          className={`flex items-center gap-2 rounded-xl px-3 py-2 text-sm transition-colors ${
            pathname === "/settings"
              ? "bg-bg-elevated text-fg"
              : "text-fg-muted hover:bg-bg-elevated hover:text-fg"
          }`}
        >
          <Settings size={16} />
          Settings
        </Link>
      </div>
    </aside>
  );
}
