import type { Message, MessageStatus } from "@/lib/api/types";

/**
 * A message as the UI needs it: the wire shape, plus the local-only state that
 * exists only while a send is in flight.
 *
 * `pending` and `failed` are what make optimistic sending honest. A bubble is
 * rendered as "sending" the moment it is published and stays that way until the
 * broadcast echoes it back — it is never silently assumed to have succeeded,
 * because on this backend the only evidence of success is the echo itself.
 *
 * `tick` is deliberately separate from `receipts`. The counts in `receipts` are a
 * per-recipient summary the server computes, and a live receipt frame carries no
 * counts at all — only a status. Rather than invent a number, the UI tracks the
 * monotonic status it has actually observed and lets history supply the counts.
 */
export interface DisplayMessage extends Message {
  pending?: boolean;
  failed?: boolean;
  failureReason?: string | null;
  tick?: MessageStatus;
}

const TICK_ORDER: Record<MessageStatus, number> = { SENT: 0, DELIVERED: 1, READ: 2 };

/** Receipt counts are the authoritative signal when present; a live `tick` overrides. */
export function tickOf(message: DisplayMessage): MessageStatus {
  if (message.tick) return message.tick;
  if (message.receipts.read > 0) return "READ";
  if (message.receipts.delivered > 0) return "DELIVERED";
  return "SENT";
}

/** Returns whichever status is further along, so a late DELIVERED cannot undo a READ. */
export function laterTick(current: MessageStatus, next: MessageStatus): MessageStatus {
  return TICK_ORDER[next] > TICK_ORDER[current] ? next : current;
}