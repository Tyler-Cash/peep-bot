// @vitest-environment jsdom
import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import useSWR, { SWRConfig } from "swr";
import { mutate, SwrMutateBridge } from "@/lib/swrMutate";

function Value() {
  const { data } = useSWR<string>("swr-mutate-test", () => Promise.resolve("initial"));
  return <p>value: {data ?? "loading"}</p>;
}

describe("swrMutate", () => {
  afterEach(() => cleanup());

  it("updates the custom provider cache from outside a component", async () => {
    render(
      <SWRConfig value={{ provider: () => new Map(), dedupingInterval: 0 }}>
        <SwrMutateBridge />
        <Value />
      </SWRConfig>,
    );
    expect(await screen.findByText("value: initial")).toBeInTheDocument();

    await act(async () => {
      await mutate("swr-mutate-test", "updated", { revalidate: false });
    });

    expect(screen.getByText("value: updated")).toBeInTheDocument();
  });
});
