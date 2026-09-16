import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "@tanstack/react-router";
import "@roboco/theme/fonts.css";
import "./styles/app.css";
import { installThemeVariant } from "./theme";
import { router } from "./router";

installThemeVariant();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
);
