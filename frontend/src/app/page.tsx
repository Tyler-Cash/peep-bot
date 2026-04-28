import { AppShell } from "@/components/AppShell";
import { EventsFeed } from "@/components/feed/EventsFeed";

export default function Home() {
  return (
    <AppShell>
      <EventsFeed />
    </AppShell>
  );
}
