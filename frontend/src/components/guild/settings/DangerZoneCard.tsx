"use client";

import { useState } from "react";
import { Chunky } from "@/components/ui/Chunky";
import { KickBotConfirmModal } from "./KickBotConfirmModal";

export function DangerZoneCard({
  guildName,
  onKick,
}: {
  guildName: string;
  onKick: (confirmGuildName: string) => Promise<void> | void;
}) {
  const [showModal, setShowModal] = useState(false);
  return (
    <section className="bg-white border-[1.5px] border-danger rounded-[16px] shadow-[4px_4px_0_#DC2626] p-[18px]">
      <Chunky
        type="button"
        variant="danger"
        size="md"
        onClick={() => setShowModal(true)}
        className="w-full"
      >
        kick peepbot
      </Chunky>
      {showModal && (
        <KickBotConfirmModal
          guildName={guildName}
          onClose={() => setShowModal(false)}
          onConfirm={async () => {
            await onKick(guildName);
            setShowModal(false);
          }}
        />
      )}
    </section>
  );
}
