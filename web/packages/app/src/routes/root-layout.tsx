import { Outlet } from "@tanstack/react-router";
import { EngineSessionProvider } from "../state/session-provider";

export function RootLayout() {
  return (
    <EngineSessionProvider>
      <Outlet />
    </EngineSessionProvider>
  );
}
