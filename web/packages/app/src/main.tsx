import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "@tanstack/react-router";
import "@roboco/theme/fonts.css";
import "./styles/app.css";
import { initAppearance } from "./state/appearance";
import { initBackgroundPauseMonitor } from "./lib/reduced-motion";
import { RootErrorBoundary } from "./components/error-boundary";
import { router } from "./router";

initAppearance();
// The background-pause arm's monitor (wpn-07): carries focus flips to the
// root attr and the effective reduced-motion read's subscribers.
initBackgroundPauseMonitor();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <RootErrorBoundary>
      <RouterProvider router={router} />
    </RootErrorBoundary>
  </StrictMode>,
);
