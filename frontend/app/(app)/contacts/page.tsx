import { ContactsView } from "@/components/contacts-view";
import { requireSession } from "@/lib/api/session";
import { me } from "@/lib/api/me";

export default async function ContactsPage() {
  const token = await requireSession();
  const user = await me(token);

  return <ContactsView selfId={user.id} />;
}
