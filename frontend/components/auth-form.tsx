"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Field } from "@/components/field";
import {
  E164,
  PASSWORD_MAX,
  PASSWORD_MIN,
  USERNAME_MAX,
  USERNAME_MIN,
  USERNAME_RE,
} from "@/lib/api/types";

/**
 * The failure shape our own proxy routes return: `{code, message, details?}`.
 * `code` is what we branch on. The backend distinguishes a wrong password
 * (INVALID_CREDENTIALS) from a malformed payload (VALIDATION_ERROR), and both
 * from a taken username or phone number, so the field-level messages below can be
 * precise instead of guessing.
 */
type FieldName = "phoneNumber" | "username" | "password";

const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: "That phone number and password do not match an account.",
  PHONE_ALREADY_REGISTERED: "That phone number is already registered.",
  USERNAME_ALREADY_TAKEN: "That username is taken.",
  VALIDATION_ERROR: "Check the highlighted fields.",
};

export function AuthForm({ mode }: { mode: "login" | "register" }) {
  const router = useRouter();
  const isRegister = mode === "register";

  const [phoneNumber, setPhoneNumber] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [invalid, setInvalid] = useState<FieldName[]>([]);
  const [pending, setPending] = useState(false);
  const [passwordVisible, setPasswordVisible] = useState(false);

  /**
   * Mirrors the server's constraints so an obviously-invalid payload never
   * leaves the browser. The server still validates — this only saves a round trip
   * and gives an immediate answer.
   */
  function localValidation(): FieldName[] {
    const bad: FieldName[] = [];
    if (!E164.test(phoneNumber)) bad.push("phoneNumber");
    if (isRegister && (username.length < USERNAME_MIN || username.length > USERNAME_MAX || !USERNAME_RE.test(username))) {
      bad.push("username");
    }
    if (password.length < PASSWORD_MIN || password.length > PASSWORD_MAX) bad.push("password");
    return bad;
  }

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);

    const bad = localValidation();
    setInvalid(bad);
    if (bad.length > 0) {
      setError(MESSAGES.VALIDATION_ERROR);
      return;
    }

    setPending(true);
    try {
      const response = await fetch(`/api/auth/${mode}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(
          isRegister ? { phoneNumber, username, password } : { phoneNumber, password },
        ),
      });

      if (!response.ok) {
        const body = (await response.json().catch(() => null)) as
          | { code?: string; message?: string; details?: Record<string, string> }
          | null;

        if (body?.details) {
          setInvalid(
            Object.keys(body.details).filter((key): key is FieldName =>
              key === "phoneNumber" || key === "username" || key === "password",
            ),
          );
        }
        setError(
          (body?.code && MESSAGES[body.code]) ||
            body?.message ||
            "Something went wrong. Try again.",
        );
        return;
      }

      // The session cookie is already set by the route handler.
      router.replace("/chats");
      router.refresh();
    } catch {
      setError("Something went wrong. Try again.");
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={onSubmit} noValidate className="flex w-full max-w-[400px] flex-col gap-8">
      <div className="flex flex-col gap-2">
        <h1 className="text-msg-3xl font-extrabold text-msg-ink">
          {isRegister ? "Create your account" : "Welcome back"}
        </h1>
        <p className="text-msg-base text-msg-ink-muted">
          {isRegister
            ? "Your phone number is how people find you on Lagoon."
            : "Sign in with the phone number you registered."}
        </p>
      </div>

      <div className="flex flex-col gap-6">
        <Field
          label="Phone number"
          type="tel"
          inputMode="tel"
          autoComplete="tel"
          placeholder="+1 415 555 0100"
          value={phoneNumber}
          onChange={setPhoneNumber}
          invalid={invalid.includes("phoneNumber")}
          hint="Include your country code, like +14155550100."
        />

        {isRegister ? (
          <Field
            label="Username"
            autoComplete="username"
            placeholder="Letters, numbers, dot or underscore"
            maxLength={USERNAME_MAX}
            value={username}
            onChange={setUsername}
            invalid={invalid.includes("username")}
            hint="3 to 32 characters. This is what people see."
          />
        ) : null}

        <Field
          label="Password"
          type={passwordVisible ? "text" : "password"}
          autoComplete={isRegister ? "new-password" : "current-password"}
          placeholder="At least 8 characters"
          value={password}
          onChange={setPassword}
          invalid={invalid.includes("password")}
          trailing={
            <PasswordToggle visible={passwordVisible} onToggle={() => setPasswordVisible((v) => !v)} />
          }
        />
      </div>

      {error ? (
        <p role="alert" className="text-msg-sm leading-[18px] text-msg-live-text">
          {error}
        </p>
      ) : null}

      <button
        type="submit"
        disabled={pending}
        className="flex h-[46px] items-center justify-center gap-2 rounded-msg-sm bg-msg-accent text-msg-base font-bold text-msg-on-accent transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-60"
      >
        {pending ? "Working…" : isRegister ? "Create account" : "Sign in"}
      </button>

      <div className="flex items-center gap-1.5 border-t border-msg-hairline pt-6">
        <span className="text-msg-sm text-msg-ink-muted">
          {isRegister ? "Already on Lagoon?" : "New to Lagoon?"}
        </span>
        <a
          href={isRegister ? "/login" : "/register"}
          className="text-msg-sm font-bold text-msg-accent-text hover:underline"
        >
          {isRegister ? "Sign in" : "Create an account"}
        </a>
      </div>
    </form>
  );
}

function PasswordToggle({ visible, onToggle }: { visible: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-label={visible ? "Hide password" : "Show password"}
      aria-pressed={visible}
      className="-mr-1 shrink-0 cursor-pointer p-1 text-msg-ink-muted hover:text-msg-ink"
    >
      <svg
        width="20"
        height="20"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        {visible ? (
          <>
            <path d="M3 3l18 18" />
            <path d="M10.6 5.2A9.8 9.8 0 0 1 12 5c6.4 0 10 7 10 7a17.7 17.7 0 0 1-3.2 4.1M6.2 6.2A17.6 17.6 0 0 0 2 12s3.6 7 10 7a9.7 9.7 0 0 0 4.2-.9" />
            <path d="M9.9 9.9a3 3 0 0 0 4.2 4.2" />
          </>
        ) : (
          <>
            <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7-10-7-10-7Z" />
            <circle cx="12" cy="12" r="3" />
          </>
        )}
      </svg>
    </button>
  );
}
