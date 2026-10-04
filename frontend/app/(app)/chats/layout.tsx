import { ConversationList } from "@/components/conversation-list";
import { requireSession } from "@/lib/api/session";
import { me } from "@/lib/api/me";

/**
 * The two-pane frame shared by `/chats` and `/chats/[chatId]`.
 *
 * The list lives here rather than in either page so that navigating into a conversation does not
 * unmount it. That matters beyond aesthetics: it keeps the list's query cache warm, so moving
 * between chats never refetches the conversation list, and it preserves the scroll position and
 * the active filter you had set.
 */
export default async function ChatsLayout({ children }: LayoutProps<"/chats">) {
  const token = await requireSession();
  const user = await me(token);

  return (
    <>
      <ConversationList selfId={user.id} />
      {children}
    </>
  );
}