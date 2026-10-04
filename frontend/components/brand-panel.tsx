/**
 * The left half of the sign-in and create-account screens: a single flat panel of
 * `--msg-brand` carrying the wordmark and one line of promise.
 *
 * Deliberately no decorative shapes. An earlier pass had two radial glows and
 * they read as hard-edged blobs rather than light, which is exactly the kind of
 * noise the system is trying to avoid — the DS itself says the canvas stays
 * quiet. The scale contrast between 52px display type and 15px body is doing the
 * work instead.
 */
export function BrandPanel() {
  return (
    <aside className="hidden w-[720px] shrink-0 flex-col justify-between bg-msg-brand p-16 lg:flex">
      <div className="flex items-center gap-3">
        <div className="flex size-10 shrink-0 items-center justify-center rounded-msg-sm bg-msg-accent">
          <span className="text-msg-xl leading-none font-extrabold text-msg-on-accent">
            L
          </span>
        </div>
        <span className="text-msg-xl leading-7 font-extrabold tracking-tight text-msg-ground">
          Lagoon
        </span>
      </div>

      <div className="flex flex-col gap-5">
        <h2 className="max-w-[520px] text-[52px] leading-[58px] font-extrabold tracking-tight text-msg-ground">
          Fast, warm conversation.
        </h2>
        <p className="max-w-[420px] text-msg-md leading-6 text-msg-accent-tint">
          Messages arrive the moment they are sent, and stay readable years later.
        </p>
      </div>
    </aside>
  );
}
