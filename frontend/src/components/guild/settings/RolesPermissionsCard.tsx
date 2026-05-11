"use client";

import { ChipPicker, type ChipOption } from "@/components/ui/ChipPicker";
import { SegmentedSelector } from "@/components/ui/SegmentedSelector";

export function RolesPermissionsCard({
  notifRole,
  organiserRole,
  anyoneCanCreate,
  roles,
  ready,
  onNotifRoleChange,
  onOrganiserRoleChange,
  onAnyoneCanCreateChange,
}: {
  notifRole: string;
  organiserRole: string;
  anyoneCanCreate: boolean;
  roles: ChipOption[];
  ready: boolean;
  onNotifRoleChange: (name: string) => void;
  onOrganiserRoleChange: (name: string) => void;
  onAnyoneCanCreateChange: (v: boolean) => void;
}) {
  const idForName = (name: string) => roles.find((r) => r.name === name)?.id ?? null;
  const nameForId = (id: string) => roles.find((r) => r.id === id)?.name ?? id;

  return (
    <section className="bg-white border-[1.5px] border-ink rounded-[16px] shadow-[4px_4px_0_#0E100D] p-6">
      <h2 className="text-[24px] font-extrabold tracking-[-0.03em] lowercase">roles &amp; permissions</h2>
      <p className="text-[13.5px] font-semibold text-mute mt-1">who pings, who runs the show</p>

      <div className="flex flex-col gap-[18px] mt-4">
        <div>
          <p className="text-[11.5px] font-extrabold tracking-[0.18em] text-mute uppercase">
            notification role
          </p>
          <ChipPicker
            value={idForName(notifRole)}
            onChange={(id) => onNotifRoleChange(nameForId(id))}
            options={roles}
            ready={ready}
            label="notification role"
          />
          <p className="text-[12.5px] font-semibold text-mute mt-1">
            this role gets pinged on every new event.
          </p>
        </div>

        <div>
          <p className="text-[11.5px] font-extrabold tracking-[0.18em] text-mute uppercase">
            organiser role
          </p>
          <ChipPicker
            value={idForName(organiserRole)}
            onChange={(id) => onOrganiserRoleChange(nameForId(id))}
            options={roles}
            ready={ready}
            label="organiser role"
          />
          <p className="text-[12.5px] font-semibold text-mute mt-1">
            cancel · recategorize · kick attendees · create private channels
          </p>
        </div>

        <div>
          <p className="text-[11.5px] font-extrabold tracking-[0.18em] text-mute uppercase">
            who can create / edit events
          </p>
          <SegmentedSelector
            value={anyoneCanCreate ? "anyone" : "organisers"}
            onChange={(v) => onAnyoneCanCreateChange(v === "anyone")}
            ariaLabel="who can create or edit events"
            options={[
              { value: "anyone", label: "anyone" },
              { value: "organisers", label: "organisers" },
            ]}
          />
          <p className="text-[12.5px] font-semibold text-mute mt-2">
            {anyoneCanCreate
              ? "any server member can create events."
              : `only @${organiserRole} can create or edit events.`}
          </p>
        </div>
      </div>
    </section>
  );
}
