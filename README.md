# ChomuGirI

A Claude-Code-style assistant with a smart AI router, a multi-model coder/auditor swarm,
in-browser cloud terminal + code runner, artifacts/canvas, and one-click Vercel deploy — all
configured from a single Settings page, no `.env` editing required.

## How it works

1. **Smart AI Router** (`lib/router.ts`, `app/api/router/route.ts`) — classifies every message.
   Casual chat gets an instant streamed reply from the Fast Chat Model. A code/app request wakes
   the heavy pipeline instead. There's also a manual "Code mode" toggle in the composer to force
   pipeline mode.
2. **Kimi K3 (coder) → GLM 5.2 (auditor) loop** (`lib/pipeline.ts`) — Kimi writes the full source,
   GLM audits it and lists concrete bugs, Kimi fixes them, GLM rechecks — repeats up to
   `maxAuditLoops` rounds (configurable in Settings).
3. **DeepSeek R1** — if the audit loop is still unclean after the max rounds, DeepSeek gets the
   unresolved issues and does a deep-reasoning fix pass, then GLM rechecks once more.
4. **Nemotron 3 Ultra 550B** — final safety pass over the whole file set before anything reaches
   the user; can still patch files if it finds something.
5. **Artifacts panel** — generated files show up Claude-artifacts-style on the right: a Code tab
   with per-file browsing, and a Run & Terminal tab that boots the project in an in-browser
   [WebContainer](https://webcontainers.io/) sandbox (real `npm install` / dev server / live
   preview iframe + a fully interactive terminal — no download needed to test it), plus
   Download-as-zip and one-click Deploy to Vercel buttons.

### Bring your own models

Every role (Fast Chat, Kimi K3, GLM 5.2, DeepSeek R1, Nemotron 3 Ultra) is just an
**API key + base URL + model id** set in **Settings** — `lib/providers.ts` speaks the generic
OpenAI-compatible `/chat/completions` protocol, so point each role at whatever host actually
serves that model for you (OpenRouter, Groq, Together, a self-hosted vLLM/Ollama proxy, etc).
Defaults are pre-filled with OpenRouter-style slugs as a starting point — swap them for whatever
is current/available to you. Keys are stored client-side in `localStorage` only.

### Vercel deploy

Paste a [Vercel access token](https://vercel.com/account/tokens) into Settings; the artifact
panel's Deploy button ships the generated files straight to Vercel via the REST API.

## Development

```bash
npm install
npm run dev
```

Open http://localhost:3000. WebContainers require the page to be cross-origin isolated, which
`next.config.ts` already sets (COOP/COEP headers).

## Project layout

- `lib/` — provider client, router heuristic, pipeline orchestration, prompts, file parsing,
  WebContainer helpers, zustand store.
- `app/api/router/route.ts` — single SSE endpoint the UI talks to; decides chat vs pipeline.
- `app/api/vercel-deploy/route.ts` — ships an artifact to Vercel.
- `components/` — chat UI, composer, pipeline status, artifacts panel, code runner/terminal,
  settings form.
