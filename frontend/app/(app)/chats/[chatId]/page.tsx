import { ChatView } from "@/components/chat-view";
import { requireSession } from "@/lib/api/session";
import { me } from "@/lib/api/me";

/**
 * One conversation.
 *
 * The list stays mounted beside this route so switching chats does not refetch it, which is why
 * the chat page renders only the right-hand pane. Session and identity are resolved on the
 * server — a bad cookie redirects here instead of rendering a shell that then fails every query.
 */
export default async function ChatPage({ params }: PageProps<"/chats/[chatId]">) {
  const { chatId } = await params;
  const token = await requireSession();
  const user = await me(token);

  return <ChatView chatId={chatId} selfId={user.id} />;
}