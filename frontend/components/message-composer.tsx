"use client";

import { useRef, useState } from "react";
import { uploadAttachment } from "@/lib/api/client";
import { MEDIA_ALLOWED_CONTENT_TYPES, MEDIA_MAX_BYTES, type MessageType } from "@/lib/api/types";
import { useRealtime } from "@/lib/realtime/realtime-provider";

/**
 * The composer, matched to the design: a 44px fully-rounded input pill with an emoji control
 * inside it on the right, a bare 40px attach button on the left, and a 44px filled accent send
 * button. The send button is deliberately larger than the attach button — it is the primary
 * action on the row.
 *
 * Two behaviours worth naming:
 *
 * - **Typing is announced at most every 3s.** The backend contract has no "stopped typing" frame,
 *   so throttling announcement is the only cost control available; announcing per keystroke
 *   would put a frame in the broker for every character typed.
 * - **The upload completes before the message is published.** `mediaKey` must reference an
 *   object that already exists, and `/media/complete` re-reads the stored bytes and deletes the
 *   object if the real size or type contradicts what was declared — so sending first could
 *   reference media the server is about to destroy.
 */
export function MessageComposer({
  chatId,
  /** Newest real message; the backend resolves the chat for a typing frame from an id. */
  latestMessageId,
  disabled,
}: {
  chatId: string;
  latestMessageId: string | null;
  disabled?: boolean;
}) {
  const { sendMessage, sendTyping, status } = useRealtime();
  const [text, setText] = useState("");
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const lastTypingAt = useRef(0);

  const connected = status === "connected";

  function announceTyping() {
    if (!latestMessageId) return;
    const now = Date.now();
    if (now - lastTypingAt.current < 3000) return;
    lastTypingAt.current = now;
    sendTyping(latestMessageId);
  }

  function submit() {
    const content = text.trim();
    if (!content || !connected) return;
    sendMessage({ chatId, content, type: "TEXT" });
    setText("");
    setError(null);
  }

  async function pick(file: File | undefined) {
    if (!file) return;
    setError(null);

    // Checked here so an oversized file fails instantly instead of uploading 25MB to be told no.
    if (file.size > MEDIA_MAX_BYTES) {
      setError(`That file is larger than ${Math.round(MEDIA_MAX_BYTES / 1024 / 1024)}MB.`);
      return;
    }
    if (!(MEDIA_ALLOWED_CONTENT_TYPES as readonly string[]).includes(file.type)) {
      setError("That file type is not supported.");
      return;
    }

    const kind: MessageType = file.type.startsWith("image/")
      ? "IMAGE"
      : file.type.startsWith("video/")
        ? "VIDEO"
        : "AUDIO";

    setUploading(true);
    try {
      const mediaKey = await uploadAttachment(file);
      // Sent as a caption-only message; `mediaKey` is what makes it an attachment.
      sendMessage({ chatId, content: text.trim(), type: kind, mediaKey });
      setText("");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Upload failed.");
    } finally {
      setUploading(false);
      if (fileInput.current) fileInput.current.value = "";
    }
  }

  return (
    <div className="flex shrink-0 items-center gap-2.5 border-t border-msg-hairline bg-msg-ground px-5 py-3.5">
      {error ? (
        <p role="alert" className="absolute bottom-full mb-2 text-msg-sm text-msg-live-text">
          {error}
        </p>
      ) : null}

      <input
        ref={fileInput}
        type="file"
        accept="image/*,video/*,audio/*"
        className="hidden"
        onChange={(event) => void pick(event.target.files?.[0])}
      />

      <button
        type="button"
        aria-label="Attach a file"
        disabled={disabled || uploading || !connected}
        onClick={() => fileInput.current?.click()}
        className="flex size-10 shrink-0 cursor-pointer items-center justify-center rounded-full text-msg-ink-muted transition-colors hover:bg-msg-surface disabled:cursor-not-allowed disabled:opacity-40"
      >
        <svg
          width="19"
          height="19"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
        >
          <path d="M21 11.5 12.5 20a5 5 0 0 1-7-7l8-8a3.5 3.5 0 1 1 5 5l-8 8a2 2 0 0 1-3-3l7.5-7.5" />
        </svg>
      </button>

      <div className="flex h-11 min-w-0 flex-1 items-center gap-2.5 rounded-full bg-msg-surface px-4">
        <input
          value={text}
          disabled={disabled || uploading}
          placeholder={connected ? "Message" : "Reconnecting…"}
          aria-label="Message"
          onChange={(event) => {
            setText(event.target.value);
            announceTyping();
          }}
          onKeyDown={(event) => {
            // Enter sends, Shift+Enter would be a newline in a textarea but has no meaning
            // here, so the input is a single-line field rather than an auto-growing one.
            if (event.key === "Enter") {
              event.preventDefault();
              submit();
            }
          }}
          className="min-w-0 flex-1 bg-transparent text-msg-base leading-5 text-msg-ink outline-none placeholder:text-msg-ink-muted disabled:opacity-60"
        />

        <button
          type="button"
          aria-label="Add emoji"
          disabled={disabled || uploading}
          onClick={() => setText((value) => `${value}🙂`)}
          className="flex size-[18px] shrink-0 cursor-pointer items-center justify-center text-msg-ink-muted transition-colors hover:text-msg-ink disabled:opacity-40"
        >
          <svg
            width="18"
            height="18"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
          >
            <circle cx="12" cy="12" r="9" />
            <path d="M8.5 14.5a4.5 4.5 0 0 0 7 0" />
            <path d="M9 9.5h.01M15 9.5h.01" />
          </svg>
        </button>
      </div>

      <button
        type="button"
        aria-label="Send message"
        disabled={disabled || uploading || !text.trim() || !connected}
        onClick={submit}
        className="flex size-11 shrink-0 cursor-pointer items-center justify-center rounded-full bg-msg-accent text-msg-on-accent transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
      >
        {uploading ? (
          <svg width="18" height="18" viewBox="0 0 24 24" className="animate-spin" aria-label="Uploading">
            <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="2.5" fill="none" opacity="0.3" />
            <path
              d="M21 12a9 9 0 0 0-9-9"
              stroke="currentColor"
              strokeWidth="2.5"
              fill="none"
              strokeLinecap="round"
            />
          </svg>
        ) : (
          <svg
            width="19"
            height="19"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M4 12h15M13 6l6 6-6 6" />
          </svg>
        )}
      </button>
    </div>
  );
}