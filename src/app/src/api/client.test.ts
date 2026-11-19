import { describe, expect, it, vi } from "vitest";
import { ApiError, apiUrl, createClient, queryString, TOKEN_HEADER } from "./client";

const connection = { url: "http://127.0.0.1:8787/", token: "secret" };

describe("apiUrl", () => {
  it("joins base URL, version prefix and path", () => {
    expect(apiUrl(connection, "/health")).toBe("http://127.0.0.1:8787/api/v1/health");
    expect(apiUrl(connection, "health")).toBe("http://127.0.0.1:8787/api/v1/health");
  });
});

describe("createClient", () => {
  it("sends the per-launch token", async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response(JSON.stringify({ status: "UP", version: "dev" })));
    const health = await createClient(connection, fetchMock).health();

    expect(health.status).toBe("UP");
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit | undefined;
    expect(new Headers(init?.headers).get(TOKEN_HEADER)).toBe("secret");
  });

  it("throws ApiError on non-2xx responses", async () => {
    const fetchMock = vi.fn(async () => new Response("", { status: 401 }));
    await expect(createClient(connection, fetchMock).health()).rejects.toBeInstanceOf(ApiError);
  });
});

describe("flip requests", () => {
  it("builds query strings from defined values only", () => {
    expect(queryString({})).toBe("");
    expect(queryString({ sort: "roi", budget: undefined, includeLocked: false, nextSessionIn: "" })).toBe(
      "?sort=roi&includeLocked=false",
    );
  });

  it("posts JSON and surfaces problem-detail messages", async () => {
    const fetchMock = vi.fn(
      async (_input: RequestInfo | URL, _init?: RequestInit) =>
        new Response(JSON.stringify({ status: 409, detail: "Slot 1 is busy" }), { status: 409 }),
    );
    const client = createClient(connection, fetchMock);
    await expect(client.startForgeJob({ slot: 1, recipeId: "REFINED_MITHRIL" })).rejects.toThrow("Slot 1 is busy");
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit | undefined;
    expect(init?.method).toBe("POST");
    expect(new Headers(init?.headers).get("Content-Type")).toBe("application/json");
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe("http://127.0.0.1:8787/api/v1/forge/jobs");
  });

  it("accepts empty 204 responses", async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }));
    await expect(createClient(connection, fetchMock).deleteForgeJob(3)).resolves.toBeUndefined();
  });
});
