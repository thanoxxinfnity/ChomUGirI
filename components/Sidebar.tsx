"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { MessageSquarePlus, Settings, X, LayoutGrid, MessageSquare } from "lucide-react";
import { useAppStore } from "@/lib/store";
import Logomark from "./Logomark";

export default function Sidebar() {
  const pathname = usePathname();
  const router = useRouter();
  const conversations = useAppStore((s) => s.conversations);
  const activeConversationId = useAppStore((s) => s.activeConversationId);
  const startNewConversation = useAppStore((s) => s.startNewConversation);
  const setActiveConversationId = useAppStore((s) => s.setActiveConversationId);
  const activeConversation = activeConversationId ? conversations[activeConversationId] : undefined;
  const mobileSidebarOpen = useAppStore((s) => s.mobileSidebarOpen);
  const setMobileSidebarOpen = useAppStore((s) => s.setMobileSidebarOpen);

  const conversationList = Object.values(conversations).sort((a, b) => b.updatedAt - a.updatedAt);

  function goToChat() {
    router.push("/");
    setMobileSidebarOpen(false);
  }

  return (
    <>
      {mobileSidebarOpen && (
        <div
          className="fixed inset-0 z-40 bg-black/60 md:hidden"
          onClick={() => setMobileSidebarOpen(false)}
        />
      )}

      <aside
        className={`fixed inset-y-0 left-0 z-50 flex w-72 shrink-0 -translate-x-full flex-col border-r border-border bg-bg-sidebar transition-transform duration-200 md:static md:w-64 md:translate-x-0 ${
          mobileSidebarOpen ? "translate-x-0" : ""
        }`}
      >
        <div className="flex items-center justify-between gap-2.5 px-4 py-5">
          <div className="flex items-center gap-2.5">
            <div className="flex h-8 w-8 items-center justify-center rounded-xl bg-accent">
              <Logomark size={18} />
            </div>
            <span className="font-display text-[1.05rem] font-medium tracking-tight">ChomuGirI</span>
          </div>
          <button
            onClick={() => setMobileSidebarOpen(false)}
            className="rounded-lg p-1.5 text-fg-muted hover:text-fg md:hidden"
            aria-label="Close menu"
          >
            <X size={18} />
          </button>
        </div>

        <div className="space-y-1 px-3">
          <button
            onClick={() => {
              if (!activeConversation || activeConversation.messages.length > 0) {
                startNewConversation();
              }
              goToChat();
            }}
            className="flex w-full items-center gap-2 rounded-xl border border-border px-3 py-2 text-sm text-fg-muted transition-colors hover:border-border-strong hover:text-fg"
          >
            <MessageSquarePlus size={16} />
            New chat
          </button>
          <Link
            href="/artifacts"
            onClick={() => setMobileSidebarOpen(false)}
            className={`flex items-center gap-2 rounded-xl px-3 py-2 text-sm transition-colors ${
              pathname === "/artifacts"
                ? "bg-bg-elevated text-fg"
                : "text-fg-muted hover:bg-bg-elevated hover:text-fg"
            }`}
          >
            <LayoutGrid size={16} />
            Artifacts
          </Link>
        </div>

        <nav className="mt-3 flex-1 space-y-0.5 overflow-y-auto px-3">
          {conversationList.length > 0 && (
            <p className="px-3 pb-1 pt-2 text-xs font-medium text-fg-muted">Chats</p>
          )}
          {conversationList.map((c) => (
            <button
              key={c.id}
              onClick={() => {
                setActiveConversationId(c.id);
                goToChat();
              }}
              className={`flex w-full items-center gap-2 rounded-xl px-3 py-2 text-left text-sm transition-colors ${
                pathname === "/" && activeConversationId === c.id
                  ? "bg-bg-elevated text-fg"
                  : "text-fg-muted hover:bg-bg-elevated hover:text-fg"
              }`}
            >
              <MessageSquare size={14} className="shrink-0" />
              <span className="truncate">{c.title}</span>
            </button>
          ))}
        </nav>

        <div className="border-t border-border px-3 py-3">
          <Link
            href="/settings"
            onClick={() => setMobileSidebarOpen(false)}
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
    </>
  );
}
