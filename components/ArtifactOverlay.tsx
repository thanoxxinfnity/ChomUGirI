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
    return (
      <div className="fixed inset-0 z-40 bg-bg">
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
