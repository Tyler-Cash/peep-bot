// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { RolesPermissionsCard } from "@/components/guild/settings/RolesPermissionsCard";

const roles = [
  { id: "events", name: "events" },
  { id: "organisers", name: "organisers" },
];

afterEach(() => cleanup());

describe("RolesPermissionsCard", () => {
  it("calls onAnyoneCanCreateChange when toggling who-can-create", () => {
    const onAnyone = vi.fn();
    render(
      <RolesPermissionsCard
        notifRole="events"
        organiserRole="organisers"
        anyoneCanCreate={true}
        roles={roles}
        ready
        onNotifRoleChange={vi.fn()}
        onOrganiserRoleChange={vi.fn()}
        onAnyoneCanCreateChange={onAnyone}
      />,
    );
    fireEvent.click(screen.getByRole("radio", { name: /organisers/i }));
    expect(onAnyone).toHaveBeenCalledWith(false);
  });
});
