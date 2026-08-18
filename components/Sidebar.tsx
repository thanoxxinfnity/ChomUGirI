"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { MessageSquarePlus, Settings, Sparkles } from "lucide-react";
import { useAppStore } from "@/lib/store";

export default function Sidebar() {
  const pathname = usePathname();
  const clearMessages = useAppStore((s) => s.clearMessages);

  return (
    <aside className="flex h-full w-64 shrink-0 flex-col border-r border-border bg-bg-sidebar">
      <div className="flex items-center gap-2 px-4 py-4">
        <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-accent text-accent-fg">
          <Sparkles size={18} />
        </div>
        <span className="text-lg font-semibold tracking-tight">ChomuGirI</span>
      </div>

      <div className="px-3">
        <button
          onClick={clearMessages}
          className="flex w-full items-center gap-2 rounded-lg border border-border px-3 py-2 text-sm text-fg-muted transition-colors hover:border-accent hover:text-fg"
        >
          <MessageSquarePlus size={16} />
          New chat
        </button>
      </div>

      <nav className="mt-2 flex-1 space-y-1 overflow-y-auto px-3">
        <Link
          href="/"
          className={`block rounded-lg px-3 py-2 text-sm transition-colors ${
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
          className={`flex items-center gap-2 rounded-lg px-3 py-2 text-sm transition-colors ${
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
