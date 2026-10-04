import { backend } from "./backend";
import { requireSession } from "./session";
import type { UserProfile } from "./types";

/**
 * Reads the signed-in user on the server.
 *
 * Only for Server Components and Route Handlers — client code goes through
 * `lib/api/client.ts` so the token stays in the httpOnly cookie. The whole app
 * layout goes through here, so this fetch happens once per navigation rather than
 * once per component that happens to need a name.
 */
export async function me(token?: string): Promise<UserProfile> {
  const jwt = token ?? (await requireSession());
  return backend<UserProfile>("/api/users/me", { token: jwt });
}
