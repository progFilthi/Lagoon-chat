import { SettingsView } from "@/components/settings-view";
import { requireSession } from "@/lib/api/session";
import { me } from "@/lib/api/me";

export default async function SettingsPage() {
  const token = await requireSession();
  const user = await me(token);

  return <SettingsView user={user} />;
}
