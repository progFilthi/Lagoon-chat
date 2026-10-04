"use client";

import { useId } from "react";
import type { ReactNode } from "react";

/**
 * The one input in the system: 46px tall, 10px radius, 1px hairline, and a jade
 * ring on focus. The label sits above the control at 13px/600, so the field never
 * relies on a placeholder that disappears the moment you type — the placeholder
 * only shows the expected *format*, and the hint below carries the constraint.
 */
export function Field({
  label,
  hint,
  type = "text",
  value,
  onChange,
  autoComplete,
  inputMode,
  placeholder,
  maxLength,
  invalid,
  trailing,
}: {
  label: string;
  hint?: string;
  type?: string;
  value: string;
  onChange: (value: string) => void;
  autoComplete?: string;
  inputMode?: "text" | "tel" | "email" | "numeric";
  placeholder?: string;
  maxLength?: number;
  invalid?: boolean;
  trailing?: ReactNode;
}) {
  const id = useId();
  const hintId = `${id}-hint`;

  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={id} className="text-msg-sm leading-[18px] font-semibold text-msg-ink">
        {label}
      </label>

      <div
        className={[
          "flex h-[46px] items-center justify-between rounded-msg-sm border bg-msg-ground px-[14px]",
          "focus-within:border-msg-accent",
          invalid ? "border-msg-live" : "border-msg-hairline",
        ].join(" ")}
      >
        <input
          id={id}
          type={type}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          autoComplete={autoComplete}
          inputMode={inputMode}
          placeholder={placeholder}
          maxLength={maxLength}
          aria-invalid={invalid || undefined}
          aria-describedby={hint ? hintId : undefined}
          className="min-w-0 flex-1 bg-transparent text-msg-base text-msg-ink outline-none placeholder:text-msg-ink-muted"
        />
        {trailing}
      </div>

      {hint ? (
        <p id={hintId} className="text-msg-xs leading-4 text-msg-ink-muted">
          {hint}
        </p>
      ) : null}
    </div>
  );
}
