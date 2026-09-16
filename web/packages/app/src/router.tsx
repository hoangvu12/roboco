import { createRootRoute, createRoute, createRouter, redirect } from "@tanstack/react-router";
import { RootLayout } from "./routes/root-layout";
import { AppShell } from "./components/app-shell";
import { IndexPage } from "./routes/index-page";
import { ChatPage } from "./routes/chat-page";
import { FilesPage } from "./routes/files-page";
import { ChangesPage } from "./routes/changes-page";
import { PairPage } from "./routes/pair-page";
import { SettingsLayout } from "./components/settings-layout";
import { RemoteAccessSettingsPage } from "./routes/settings-remote-access";
import { AccountsSettingsPage } from "./routes/settings-accounts";
import { AppearanceSettingsPage } from "./routes/settings-appearance";

const rootRoute = createRootRoute({ component: RootLayout });
const shellRoute = createRoute({ getParentRoute: () => rootRoute, id: "shell", component: AppShell });
const indexRoute = createRoute({ getParentRoute: () => shellRoute, path: "/", component: IndexPage });
const chatRoute = createRoute({ getParentRoute: () => shellRoute, path: "/chat/$chatId", component: ChatPage });
const filesRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "/files",
  component: FilesPage,
  validateSearch: (search: Record<string, unknown>): { space?: string; path?: string } => ({
    ...(typeof search.space === "string" && search.space.length > 0 ? { space: search.space } : {}),
    ...(typeof search.path === "string" && search.path.length > 0 ? { path: search.path } : {}),
  }),
});
const changesRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "/chat/$chatId/changes",
  component: ChangesPage,
  validateSearch: (search: Record<string, unknown>): { scope?: string; base?: string } => ({
    ...(typeof search.scope === "string" && search.scope.length > 0 ? { scope: search.scope } : {}),
    ...(typeof search.base === "string" && search.base.length > 0 ? { base: search.base } : {}),
  }),
});
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
    changesRoute,
    filesRoute,
    settingsRoute.addChildren([settingsIndexRoute, remoteAccessRoute, accountsRoute, appearanceRoute]),
  ]),
]);

export {
  accountsRoute,
  appearanceRoute,
  changesRoute,
  chatRoute,
  filesRoute,
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
