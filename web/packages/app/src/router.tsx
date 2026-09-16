import { createRootRoute, createRoute, createRouter } from "@tanstack/react-router";
import { RootLayout } from "./routes/root-layout";
import { AppShell } from "./components/app-shell";
import { IndexPage } from "./routes/index-page";
import { ChatPage } from "./routes/chat-page";
import { PairPage } from "./routes/pair-page";

const rootRoute = createRootRoute({ component: RootLayout });
const shellRoute = createRoute({ getParentRoute: () => rootRoute, id: "shell", component: AppShell });
const indexRoute = createRoute({ getParentRoute: () => shellRoute, path: "/", component: IndexPage });
const chatRoute = createRoute({ getParentRoute: () => shellRoute, path: "/chat/$chatId", component: ChatPage });
const pairRoute = createRoute({ getParentRoute: () => rootRoute, path: "/pair", component: PairPage });

const routeTree = rootRoute.addChildren([pairRoute, shellRoute.addChildren([indexRoute, chatRoute])]);

export { chatRoute, indexRoute, pairRoute, rootRoute, shellRoute };

export const router = createRouter({ routeTree, defaultPreload: "intent" });

declare module "@tanstack/react-router" {
  interface Register {
    readonly router: typeof router;
  }
}
