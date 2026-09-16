import { createRootRoute, createRoute, createRouter } from "@tanstack/react-router";
import { RootLayout } from "./routes/root-layout";
import { AppShell } from "./components/app-shell";
import { IndexPage } from "./routes/index-page";
import { ChatPage } from "./routes/chat-page";
import { FilesPage } from "./routes/files-page";
import { PairPage } from "./routes/pair-page";

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
const pairRoute = createRoute({ getParentRoute: () => rootRoute, path: "/pair", component: PairPage });

const routeTree = rootRoute.addChildren([pairRoute, shellRoute.addChildren([indexRoute, chatRoute, filesRoute])]);

export { chatRoute, filesRoute, indexRoute, pairRoute, rootRoute, shellRoute };

export const router = createRouter({ routeTree, defaultPreload: "intent" });

declare module "@tanstack/react-router" {
  interface Register {
    readonly router: typeof router;
  }
}
