import type { NextRequest } from "next/server";
import type { EnvVar, GeneratedFile } from "@/lib/types";

export const runtime = "nodejs";

interface DeployRequestBody {
  vercelToken: string;
  projectName: string;
  files: GeneratedFile[];
  teamId?: string;
  envVars?: EnvVar[];
}

/**
 * Ships the artifact's files as a single Vercel deployment using the inline-file form of the
 * v13 deployments API. Fine for small generated projects; very large artifacts (many/huge
 * files) would need the chunked file-upload flow instead, which isn't implemented here.
 */
export async function POST(req: NextRequest) {
  let body: DeployRequestBody;
  try {
    body = await req.json();
  } catch {
    return Response.json({ error: "Invalid JSON body" }, { status: 400 });
  }

  const { vercelToken, projectName, files, teamId, envVars } = body;

  if (!vercelToken) {
    return Response.json({ error: "No Vercel token set in Settings." }, { status: 400 });
  }
  if (!files?.length) {
    return Response.json({ error: "No files to deploy." }, { status: 400 });
  }

  const safeName = (projectName || "chomugiri-app")
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 100) || "chomugiri-app";

  const url = new URL("https://api.vercel.com/v13/deployments");
  if (teamId) url.searchParams.set("teamId", teamId);

  const envObject = Object.fromEntries(
    (envVars ?? []).filter((v) => v.key.trim()).map((v) => [v.key.trim(), v.value]),
  );

  const res = await fetch(url.toString(), {
    method: "POST",
    headers: {
      Authorization: `Bearer ${vercelToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      name: safeName,
      target: "production",
      files: files.map((f) => ({ file: f.path, data: f.content })),
      projectSettings: { framework: null },
      ...(Object.keys(envObject).length > 0 ? { env: envObject, build: { env: envObject } } : {}),
    }),
  });

  const data = await res.json().catch(() => null);

  if (!res.ok) {
    return Response.json(
      { error: data?.error?.message ?? `Vercel deploy failed (${res.status})`, details: data },
      { status: res.status },
    );
  }

  return Response.json({
    url: data?.url ? `https://${data.url}` : null,
    id: data?.id,
    raw: data,
  });
}
