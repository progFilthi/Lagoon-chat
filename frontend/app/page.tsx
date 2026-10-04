import { redirect } from "next/navigation";
import { getSessionToken } from "@/lib/api/session";

/**
 * The root is only a router: there is no landing page, because there is nothing
 * to show a signed-out visitor that the sign-in screen does not say better.
 */
export default async function Home() {
  redirect((await getSessionToken()) ? "/chats" : "/login");
}
