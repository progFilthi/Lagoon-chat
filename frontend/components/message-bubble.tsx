"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api/client";
import { formatBubbleTime } from "@/lib/format";
import { tickOf, type DisplayMessage } from "@/lib/chat/types";
import type { MessageStatus } from "@/lib/api/types";

/**
 * Message bubbles, matched to the "Lagoon Web — Chats" artboard.
 *
 * The values here are transcribed from the design's computed styles rather than eyeballed, and
 * a few of them are load-bearing enough to be worth naming:
 *
 * - Outgoing bubbles are **tinted mint** (`--msg-accent-tint` on a `--msg-accent-tint-border`
 *   hairline), not the solid accent. Solid accent is reserved for small filled controls — the
 *   send button, the play button, the nav-rail logo — so a filled bubble would flatten that
 *   hierarchy.
 * - Both bubble faces carry a **1px border**. There is no drop shadow on a bubble in this design;
 *   the shadow in earlier code was invented and made the thread look heavier than the comp.
 * - The tail corner is 6px on the sender's side and 14px everywhere else, and it is dropped on
 *   runs of consecutive messages so a group reads as one block.
 * - Max width is 62%, not the 78% the `--msg-bubble-max-width` token claims. The artboard is the
 *   authority here; the token is unused by the design itself.
 */

/** Static waveform. Bars are decorative, so a fixed pattern beats fetching real peaks. */
const WAVEFORM = [6, 12, 18, 10, 22, 14, 8, 20, 12, 16, 9, 13];

export function MessageBubble({
  message,
  own,
  showTail,
  onReact,
}: {
  message: DisplayMessage;
  own: boolean;
  /** False when the next message is from the same sender, which suppresses the tail. */
  showTail: boolean;
  onReact?: () => void;
}) {
  const tail = own ? "rounded-br-msg-xs" : "rounded-bl-msg-xs";
  const flat = own ? "rounded-br-msg-md" : "rounded-bl-msg-md";

  return (
    <div className={["flex w-full items-end", own ? "justify-end" : "justify-start"].join(" ")}>
      {/* Surface and border are chosen per-direction, never both: emitting `bg-msg-ground` in the
          base and `bg-msg-accent-tint` for outgoing puts two competing utilities on one element,
          and since they have equal specificity the stylesheet order decides — silently painting
          every outgoing bubble white. */}
      <div
        className={[
          "relative flex max-w-[62%] flex-col rounded-msg-md border",
          own ? "border-msg-accent-tint-border bg-msg-accent-tint" : "border-msg-hairline bg-msg-ground",
          own ? "rounded-br-msg-xs" : "rounded-bl-msg-xs",
          showTail ? tail : flat,
          message.failed ? "!border-msg-live" : "",
        ].join(" ")}
      >
        {/* IMAGE and VIDEO are a fixed-width card: media on top, caption bar beneath, matching
            the artboard's 300px column. */}
        {message.type === "IMAGE" || message.type === "VIDEO" ? (
          <MediaCard message={message} own={own} onReact={onReact} />
        ) : null}

        {message.type === "AUDIO" ? <AudioBody message={message} own={own} /> : null}

        {message.type === "TEXT" ? (
          <div className="px-3 py-2.5">
            {message.content ? (
              <p className="text-msg-base leading-5 whitespace-pre-wrap break-words text-msg-ink">
                {message.content}
              </p>
            ) : null}
            <Meta own={own} message={message} />
          </div>
        ) : null}
      </div>
    </div>
  );
}

/**
 * Text body plus the time/tick row. The design puts the timestamp on the same baseline as the
 * text with a 14px gutter (12px outgoing), so it is a sibling of the text inside one padded box
 * rather than a separate stacked element.
 */
function Meta({ own, message }: { own: boolean; message: DisplayMessage }) {
  return (
    <span className={["mt-0.5 flex items-center justify-end", own ? "gap-2.5" : "gap-3.5"].join(" ")}>
      {message.failed ? (
        <span className="text-msg-2xs leading-5 font-medium text-msg-live-text">Not sent</span>
      ) : null}
      <time
        dateTime={message.createdAt}
        className="shrink-0 text-msg-2xs leading-5 font-medium text-[#5B6E69]"
      >
        {formatBubbleTime(message.createdAt)}
      </time>
      {own ? <Tick status={tickOf(message)} pending={Boolean(message.pending)} /> : null}
    </span>
  );
}

/**
 * The receipt tick. Only ever drawn on your own messages: the backend zeroes `receipts` for
 * everyone else's messages so a group cannot see who has read what, and a tick drawn from an
 * all-zero summary would be a lie.
 */
function Tick({ status, pending }: { status: MessageStatus; pending: boolean }) {
  if (pending) {
    return (
      <svg
        width="14"
        height="14"
        viewBox="0 0 20 20"
        fill="none"
        stroke="#5B6E69"
        strokeWidth="1.9"
        strokeLinecap="round"
        aria-label="Sending"
      >
        <circle cx="10" cy="10" r="7" />
        <path d="M10 6v4l2.5 2" />
      </svg>
    );
  }
  if (status === "SENT") {
    return (
      <svg
        width="16"
        height="16"
        viewBox="0 0 20 20"
        fill="none"
        stroke="#5B6E69"
        strokeWidth="1.9"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-label="Sent"
      >
        <path d="m3.5 10.5 3.4 3.4L16 5.5" />
      </svg>
    );
  }
  return (
    <svg
      width="18"
      height="16"
      viewBox="0 0 22 20"
      fill="none"
      stroke="var(--msg-accent-text)"
      strokeWidth="1.9"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-label={status === "READ" ? "Read" : "Delivered"}
    >
      <path d="m1.5 10.5 3.4 3.4L11 5.5" />
      <path d="m7.5 10.5 3.4 3.4L20 5.5" />
    </svg>
  );
}

/**
 * Image and video card: 300px wide, 168px media, then a caption bar holding the text and a
 * reaction affordance. The caption bar is what carries the timestamp for media, since the media
 * block itself has no room for it.
 */
function MediaCard({
  message,
  own,
  onReact,
}: {
  message: DisplayMessage;
  own: boolean;
  onReact?: () => void;
}) {
  const url = useAttachmentUrl(message.id, Boolean(message.pending));
  const hasCaption = Boolean(message.content);

  return (
    // rounded-t matches the bubble's own top corners: whichever side carries the tail is always a
    // bottom corner, so the media's top pair is always the full 14px. Without it the image sits
    // square over the bubble's rounded corners and over its border.
    <div className="w-[300px] rounded-t-msg-md">
      <div className="h-[168px] w-full overflow-hidden bg-msg-surface-muted">
        {url === undefined ? (
          <div className="size-full animate-pulse bg-msg-surface-muted" />
        ) : url === null ? (
          <span className="flex size-full items-center justify-center text-msg-sm text-msg-ink-muted">
            {message.type === "VIDEO" ? "Video unavailable" : "Photo unavailable"}
          </span>
        ) : message.type === "VIDEO" ? (
          <video src={url} controls preload="metadata" className="size-full object-cover" />
        ) : (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={url} alt={message.content || "Attached image"} className="size-full object-cover" />
        )}
      </div>

      {hasCaption || onReact ? (
        <div className="flex items-center justify-between gap-3 px-3 py-2">
          <p className="min-w-0 flex-1 text-msg-base leading-5 text-msg-ink">{message.content}</p>
          <Meta own={own} message={message} />
          {onReact ? (
            <button
              type="button"
              onClick={onReact}
              aria-label="React to message"
              className="flex size-[18px] shrink-0 cursor-pointer items-center justify-center text-msg-ink-muted hover:text-msg-ink"
            >
              <SmileIcon />
            </button>
          ) : null}
        </div>
      ) : (
        <div className="px-3 py-2">
          <Meta own={own} message={message} />
        </div>
      )}
    </div>
  );
}

/**
 * Audio bubble: a fixed 250px pill with a filled play button, a decorative waveform and the
 * elapsed time. A native `<audio controls>` element was the earlier approach and it cannot be
 * styled to this design at all, since its chrome is browser-owned.
 */
function AudioBody({ message, own }: { message: DisplayMessage; own: boolean }) {
  const url = useAttachmentUrl(message.id, Boolean(message.pending));
  const audio = useRef<HTMLAudioElement>(null);
  const [playing, setPlaying] = useState(false);
  const [duration, setDuration] = useState<string | null>(null);

  function toggle() {
    const node = audio.current;
    if (!node) return;
    if (node.paused) void node.play();
    else node.pause();
  }

  // Column, not row: the caption belongs under the waveform. As one flex row it sat beside the
  // waveform and fought it for the 250px.
  return (
    <div className="flex w-[250px] flex-col">
      <div className="flex items-center gap-3 px-3 py-2.5">
        <button
          type="button"
          onClick={toggle}
          disabled={!url}
          aria-label={playing ? "Pause audio" : "Play audio"}
          className="flex size-[34px] shrink-0 cursor-pointer items-center justify-center rounded-full bg-msg-accent disabled:opacity-40"
        >
          {playing ? (
            <svg width="12" height="12" viewBox="0 0 12 12" fill="currentColor" aria-hidden>
              <rect x="1" y="1" width="3.5" height="10" rx="1" />
              <rect x="7.5" y="1" width="3.5" height="10" rx="1" />
            </svg>
          ) : (
            <svg width="12" height="12" viewBox="0 0 12 12" fill="currentColor" aria-hidden>
              <path d="M2 1.2 10.4 6 2 10.8z" />
            </svg>
          )}
        </button>

        <div className="flex h-6 flex-1 items-center gap-[3px]" aria-hidden>
          {WAVEFORM.map((height, index) => (
            <span
              key={index}
              className="w-[2px] rounded-full bg-msg-accent-text/70"
              style={{ height: `${height}px` }}
            />
          ))}
        </div>

        {/* Elapsed length, not the caption: `content` is the user's text, which the bubble shows
            on its own line below. */}
        <span className="shrink-0 text-msg-2xs leading-[14px] font-medium text-msg-accent-text opacity-85">
          {duration ?? "0:00"}
        </span>

        {url ? (
          <audio
            ref={audio}
            src={url}
            preload="metadata"
            onPlay={() => setPlaying(true)}
            onPause={() => setPlaying(false)}
            onLoadedMetadata={(event) => setDuration(formatClock(event.currentTarget.duration))}
            className="hidden"
          />
        ) : null}
      </div>

      {message.content ? (
        <div className="px-3 pb-2.5">
          <p className="text-msg-base leading-5 break-words text-msg-ink">{message.content}</p>
          <Meta own={own} message={message} />
        </div>
      ) : null}
    </div>
  );
}

/** `m:ss`, or null when the duration is unknown or not finite (streams report NaN/Infinity). */
function formatClock(seconds: number): string | null {
  if (!Number.isFinite(seconds) || seconds < 0) return null;
  const whole = Math.round(seconds);
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, "0")}`;
}

/**
 * Fetches a signed GET URL for one message's attachment.
 *
 * Requested per bubble and never cached: the URL is short-lived and authorised by chat
 * membership, so holding one on a long-lived `<img>` would both expire and outlive the grant.
 * Nothing is ever rendered from `mediaKey` — that is a storage key, not a URL, and treating it
 * as one is exactly how private media leaks.
 *
 * @returns `undefined` while loading, `null` when the fetch failed.
 */
function useAttachmentUrl(messageId: string, pending: boolean): string | null | undefined {
  // The state records *which* id it belongs to, so a stale entry can never be handed back for a
  // new message — the bubble reads as loading until its own fetch lands.
  const [state, setState] = useState<{ id: string; url: string | null } | null>(null);

  useEffect(() => {
    // An optimistic message carries a `pending-<uuid>` stand-in id, which the backend rejects.
    // Waiting for the real one is not just tidier: this effect re-runs when `messageId` changes,
    // so the bubble resolves as soon as the server echo swaps the id in.
    if (pending) return;

    let cancelled = false;
    api
      .downloadUrl(messageId)
      .then((result) => {
        if (!cancelled) setState({ id: messageId, url: result.downloadUrl });
      })
      .catch(() => {
        if (!cancelled) setState({ id: messageId, url: null });
      });

    return () => {
      cancelled = true;
    };
  }, [messageId, pending]);

  // Derived during render rather than reset in the effect: setting it here would cascade an extra
  // render on every id change.
  if (pending) return undefined;
  return state?.id === messageId ? state.url : undefined;
}

function SmileIcon() {
  return (
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
  );
}