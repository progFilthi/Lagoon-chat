"use client";

import { useEffect, useRef, useState } from "react";
import { useStartConversation } from "@/lib/chat/use-start-conversation";

/**
 * The new-conversation dialog.
 *
 * A native `<dialog>` is used rather than a div overlay so focus trapping, Escape to close and
 * the backdrop click all come from the platform. This was previously a button with no handler at
 * all, which is the kind of dead control that only shows up in review.
 */
export function NewConversationDialog() {
  const [open, setOpen] = useState(false);
  const [phone, setPhone] = useState("");
  const dialog = useRef<HTMLDialogElement>(null);
  const start = useStartConversation();

  useEffect(() => {
    const node = dialog.current;
    if (!node) return;
    if (open && !node.open) node.showModal();
    if (!open && node.open) node.close();
  }, [open]);


  /** Every path out of the dialog resets here, so a failed attempt never leaves its
   *  error text and input behind for the next one. */
  function close() {
    setPhone("");
    start.reset();
    setOpen(false);
  }

  return (
    <>
      <button
        type="button"
        aria-label="New conversation"
        onClick={() => setOpen(true)}
        className="flex size-[38px] shrink-0 cursor-pointer items-center justify-center rounded-msg-sm bg-msg-accent text-msg-on-accent transition-opacity hover:opacity-90"
      >
        <svg
          width="18"
          height="18"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
        >
          <path d="M12 5v14M5 12h14" />
        </svg>
      </button>

      <dialog
        ref={dialog}
        onClose={close}
        onClick={(event) => {
          // A click on the dialog element itself is a click on the backdrop.
          if (event.target === dialog.current) close();
        }}
        className="m-auto w-[380px] rounded-msg-md border border-msg-hairline bg-msg-ground p-0 text-msg-ink shadow-xl backdrop:bg-msg-ink/40"
      >
        <form
          method="dialog"
          onSubmit={(event) => {
            event.preventDefault();
            start.mutate(phone, { onSuccess: close });
          }}
          className="p-5"
        >
          <h2 className="text-msg-lg font-extrabold tracking-tight">New conversation</h2>
          <p className="mt-1 text-msg-sm leading-[18px] text-msg-ink-muted">
            Enter the other person&apos;s number in international form.
          </p>

          <label className="mt-4 block">
            <span className="mb-1.5 block text-msg-sm font-semibold text-msg-ink-muted">Phone number</span>
            <input
              value={phone}
              onChange={(event) => setPhone(event.target.value)}
              placeholder="+14155550123"
              autoFocus
              inputMode="tel"
              aria-invalid={start.isError}
              className="h-10 w-full rounded-msg-sm bg-msg-surface px-3 text-msg-base outline-none placeholder:text-msg-ink-muted"
            />
          </label>

          {start.isError ? (
            <p role="alert" className="mt-2 text-msg-sm text-msg-live-text">
              {start.error instanceof Error ? start.error.message : "Could not start the conversation."}
            </p>
          ) : null}

          <div className="mt-5 flex justify-end gap-2">
            <button
              type="button"
              onClick={close}
              className="h-9 cursor-pointer rounded-full px-4 text-msg-sm font-semibold text-msg-ink-muted hover:bg-msg-surface"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={!phone.trim() || start.isPending}
              className="h-9 cursor-pointer rounded-full bg-msg-accent px-5 text-msg-sm font-semibold text-msg-on-accent disabled:cursor-not-allowed disabled:opacity-50"
            >
              {start.isPending ? "Opening…" : "Start chat"}
            </button>
          </div>
        </form>
      </dialog>
    </>
  );
}