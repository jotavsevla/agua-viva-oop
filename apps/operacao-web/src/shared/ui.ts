import type { DisplayTone } from "../types";
import { escapeHtml } from "./html";

export function renderPill(label: string, tone: DisplayTone): string {
  return `<span class="pill ${tone}">${escapeHtml(label)}</span>`;
}
