import { AppShell } from "@/components/AppShell";
import { AdminGate } from "@/components/admin/AdminGate";

export default function AdminPage() {
  return (
    <AppShell>
      <AdminGate />
    </AppShell>
  );
}
