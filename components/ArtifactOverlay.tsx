"use client";

import { useAppStore } from "@/lib/store";
import ArtifactsPanel from "./ArtifactsPanel";

/** Renders the artifact panel as a global overlay so it can be opened from any page — the
 *  chat thread, the artifacts list, or settings — not just from inside the chat view. Canvas
 *  mode expands it edge-to-edge on every breakpoint instead of the default docked side panel. */
export default function ArtifactOverlay() {
  const activeArtifactId = useAppStore((s) => s.activeArtifactId);
  const canvasMode = useAppStore((s) => s.canvasMode);
  if (!activeArtifactId) return null;

  if (canvasMode) {
    // Stop short of the always-visible desktop sidebar (md:w-64) — it sits in a higher
    // stacking context (a flex item with z-50) and would otherwise intercept clicks/hide
    // content in that strip even though this overlay visually paints underneath it.
    return (
      <div className="fixed inset-y-0 left-0 right-0 z-40 bg-bg md:left-64">
        <ArtifactsPanel />
      </div>
    );
  }

  return (
    <div className="fixed inset-0 z-40 flex justify-end md:bg-black/40">
      <div className="h-full w-full bg-bg md:w-[46%] md:min-w-[420px]">
        <ArtifactsPanel />
      </div>
    </div>
  );
}
