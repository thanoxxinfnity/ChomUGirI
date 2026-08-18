"use client";

import { useAppStore } from "@/lib/store";
import ArtifactsPanel from "./ArtifactsPanel";

/** Renders the artifact panel as a global overlay so it can be opened from any page — the
 *  chat thread, the artifacts list, or settings — not just from inside the chat view. */
export default function ArtifactOverlay() {
  const activeArtifactId = useAppStore((s) => s.activeArtifactId);
  if (!activeArtifactId) return null;

  return (
    <div className="fixed inset-0 z-40 flex justify-end md:bg-black/40">
      <div className="h-full w-full bg-bg md:w-[46%] md:min-w-[420px]">
        <ArtifactsPanel />
      </div>
    </div>
  );
}
