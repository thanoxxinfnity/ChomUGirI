"use client";

import { Menu } from "lucide-react";
import { useAppStore } from "@/lib/store";
import Logomark from "./Logomark";

export default function MobileTopBar() {
  const setMobileSidebarOpen = useAppStore((s) => s.setMobileSidebarOpen);

  return (
    <div className="flex items-center gap-3 border-b border-border px-3 py-3 md:hidden">
      <button
        onClick={() => setMobileSidebarOpen(true)}
        className="rounded-lg p-1.5 text-fg-muted hover:text-fg"
        aria-label="Open menu"
      >
        <Menu size={20} />
      </button>
      <div className="flex items-center gap-2">
        <div className="flex h-6 w-6 items-center justify-center rounded-lg bg-accent">
          <Logomark size={14} />
        </div>
        <span className="font-display text-sm font-medium">ChomuGirI</span>
      </div>
    </div>
  );
}
