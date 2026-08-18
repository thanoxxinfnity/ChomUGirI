const HEARTBEAT_MS = 15000;

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Turns an async generator of JSON-serializable events into an SSE ReadableStream. Model calls
 * in the pipeline can take well over a minute with no output in between (a single non-streaming
 * completion from a loaded model), so while waiting on the generator we also emit periodic
 * ": keep-alive" comment lines — otherwise idle proxies/load balancers can drop the connection
 * before the next real event arrives.
 */
export function streamFromGenerator<T>(gen: AsyncGenerator<T>): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      try {
        const nextPromise = gen.next();
        while (true) {
          const race = await Promise.race([
            nextPromise.then((r) => ({ kind: "value" as const, r })),
            sleep(HEARTBEAT_MS).then(() => ({ kind: "timeout" as const })),
          ]);
          if (race.kind === "timeout") {
            controller.enqueue(encoder.encode(`: keep-alive\n\n`));
            continue;
          }
          const { value, done } = race.r;
          if (done) {
            controller.close();
            return;
          }
          controller.enqueue(encoder.encode(`data: ${JSON.stringify(value)}\n\n`));
          return;
        }
      } catch (err) {
        const message = err instanceof Error ? err.message : "Stream error.";
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ type: "error", message })}\n\n`));
        controller.close();
      }
    },
    cancel() {
      gen.return?.(undefined as never);
    },
  });
}

export const SSE_HEADERS = {
  "Content-Type": "text/event-stream",
  "Cache-Control": "no-cache, no-transform",
  Connection: "keep-alive",
  "X-Accel-Buffering": "no",
};
