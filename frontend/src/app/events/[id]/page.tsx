import { AppShell } from "@/components/AppShell";
import { EventDetail } from "@/components/event/EventDetail";

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <AppShell>
      <EventDetail id={id} />
    </AppShell>
  );
}
