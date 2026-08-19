import JSZip from "jszip";
import type { Artifact } from "./types";

function sanitizeAppId(title: string) {
  const slug = title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "")
    .slice(0, 40);
  return `com.chomugiri.${slug || "app"}`;
}

const README = (appName: string, appId: string) => `# ${appName} — Android project

This is a ready-to-build Capacitor Android project wrapping the files ChomuGirI generated.
It bundles the app locally (no live server needed) — the WebView loads straight from the
\`www/\` folder, so the resulting APK works offline.

## Build (run these in a real terminal with Node.js + a JDK + the Android SDK — e.g. your
## own Cloud Terminal from ChomuGirI Settings, Termux, or a desktop shell):

\`\`\`sh
npm install
npx cap add android
cd android
./gradlew assembleDebug
\`\`\`

The finished APK lands at \`android/app/build/outputs/apk/debug/app-debug.apk\` — copy it to
your phone and install it (enable "Install from unknown sources" first).

App id: \`${appId}\`
`;

const CAPACITOR_CONFIG = (appId: string, appName: string) =>
  JSON.stringify({ appId, appName, webDir: "www" }, null, 2);

const PACKAGE_JSON = (appName: string) =>
  JSON.stringify(
    {
      name: appName.toLowerCase().replace(/[^a-z0-9-]+/g, "-") || "chomugiri-app",
      version: "1.0.0",
      private: true,
      dependencies: {
        "@capacitor/core": "^8.0.0",
        "@capacitor/android": "^8.0.0",
        "@capacitor/cli": "^8.0.0",
      },
    },
    null,
    2,
  );

/** Packages an artifact's files as a self-contained, offline-capable Capacitor Android
 *  project — no build automation, no server dependency: the user runs the real build
 *  themselves (in their own terminal, e.g. the Cloud Terminal URL from Settings). */
export async function buildAndroidProjectZip(artifact: Artifact): Promise<Blob> {
  const appId = sanitizeAppId(artifact.title);
  const zip = new JSZip();

  for (const f of artifact.files) {
    zip.file(`www/${f.path}`, f.content);
  }

  zip.file("capacitor.config.json", CAPACITOR_CONFIG(appId, artifact.title));
  zip.file("package.json", PACKAGE_JSON(artifact.title));
  zip.file("README.md", README(artifact.title, appId));

  return zip.generateAsync({ type: "blob" });
}

export const ANDROID_BUILD_COMMANDS = "npm install && npx cap add android && cd android && ./gradlew assembleDebug";
