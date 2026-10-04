/**
 * The empty right pane. The conversation list beside this is rendered by the `/chats` layout.
 */
export default function ChatsPage() {
  return (
    <main className="flex flex-1 items-center justify-center bg-msg-surface">
      <p className="text-msg-base text-msg-ink-muted">Pick a conversation to start reading.</p>
    </main>
  );
}