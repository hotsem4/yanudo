import { useSyncExternalStore } from "react";

export type ToastTone = "info" | "success";

export interface ToastAction {
  label: string;
  run: () => void;
}

export interface Toast {
  id: number;
  message: string;
  tone: ToastTone;
  /** 되돌리기처럼 토스트가 떠 있는 동안만 할 수 있는 행동 하나. */
  action?: ToastAction;
}

let toasts: Toast[] = [];
let nextId = 1;
const listeners = new Set<() => void>();
const emit = () => listeners.forEach((l) => l());

export function showToast(message: string, tone: ToastTone = "info", ms = 2600, action?: ToastAction): void {
  const toast: Toast = { id: nextId++, message, tone, action };
  toasts = [...toasts.slice(-1), toast];
  emit();
  window.setTimeout(() => {
    toasts = toasts.filter((t) => t.id !== toast.id);
    emit();
  }, ms);
}

export function dismissToast(id: number): void {
  toasts = toasts.filter((t) => t.id !== id);
  emit();
}

export function useToasts(): Toast[] {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => toasts,
  );
}
