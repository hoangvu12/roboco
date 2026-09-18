import { createRootRoute, createRoute, createRouter, redirect } from "@tanstack/react-router";
import { RootLayout } from "./routes/root-layout";
import { AppShell } from "./components/app-shell";
import { ConversationPage } from "./routes/chat-page";
import { PairPage } from "./routes/pair-page";
import { SettingsLayout } from "./components/settings-layout";
import { RemoteAccessSettingsPage } from "./routes/settings-remote-access";
import { AccountsSettingsPage } from "./routes/settings-accounts";
import { AppearanceSettingsPage } from "./routes/settings-appearance";

const rootRoute = createRootRoute({ component: RootLayout });
const shellRoute = createRoute({ getParentRoute: () => rootRoute, id: "shell", component: AppShell });
/*
 * BOTH conversation routes render the SAME component reference
 * (`ConversationPage`, ticket 15): TanStack's `Match` memoizes the route
 * element on `route.options.component`, so the shared reference keeps ONE
 * fiber alive across the `/` ↔ `/chat/$chatId` boundary — the composer is
 * one persistent entity, re-anchored by the dock, never remounted. The
 * page re-renders on navigation through its router-state subscription.
 */
const indexRoute = createRoute({ getParentRoute: () => shellRoute, path: "/", component: ConversationPage });
const chatRoute = createRoute({ getParentRoute: () => shellRoute, path: "/chat/$chatId", component: ConversationPage });
// Changes and Files have no routes: they are right-pane surfaces on the
// desktop, and a route for either took the chat off `/chat/$chatId`, which is
// the only path that owns a pane — the column, its tabs and its toggle all
// disappeared. `rightPaneStore.show(chatId, "changes" | "files")` opens them.
const pairRoute = createRoute({ getParentRoute: () => rootRoute, path: "/pair", component: PairPage });

const settingsRoute = createRoute({ getParentRoute: () => shellRoute, path: "/settings", component: SettingsLayout });
const settingsIndexRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: "/",
  beforeLoad: () => {
    throw redirect({ to: "/settings/remote-access" });
  },
});
const remoteAccessRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: "/remote-access",
  component: RemoteAccessSettingsPage,
});
const accountsRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: "/accounts",
  component: AccountsSettingsPage,
});
const appearanceRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: "/appearance",
  component: AppearanceSettingsPage,
});

const routeTree = rootRoute.addChildren([
  pairRoute,
  shellRoute.addChildren([
    indexRoute,
    chatRoute,
    settingsRoute.addChildren([settingsIndexRoute, remoteAccessRoute, accountsRoute, appearanceRoute]),
  ]),
]);

export {
  accountsRoute,
  appearanceRoute,
  chatRoute,
  indexRoute,
  pairRoute,
  remoteAccessRoute,
  rootRoute,
  settingsRoute,
  shellRoute,
};

export const router = createRouter({ routeTree, defaultPreload: "intent" });

declare module "@tanstack/react-router" {
  interface Register {
    readonly router: typeof router;
  }
}
