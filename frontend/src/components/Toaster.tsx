import { AnimatePresence, motion } from "framer-motion";
import { Sparkles } from "lucide-react";
import type { MascotState } from "../lib/mascot";
import { dismissToast, useToasts } from "../lib/toast";
import { Mascot } from "./Mascot";

/** 위에서 내려오고 같은 방향으로 나가는 토스트(emil-design-eng: 400ms ease). */
export function Toaster({ mascot }: { mascot: MascotState | null }) {
  const toasts = useToasts();
  return (
    <div className="top-safe pointer-events-none fixed inset-x-0 z-[60] flex flex-col items-center gap-2 px-5" aria-live="polite" role="status">
      <AnimatePresence initial={false}>
        {toasts.map((t) => (
          <motion.div
            key={t.id}
            initial={{ y: "-120%", opacity: 0 }}
            animate={{ y: 0, opacity: 1 }}
            exit={{ y: "-120%", opacity: 0 }}
            transition={{ duration: 0.4, ease: "easeOut" }}
            className={`flex max-w-sm items-center gap-2 rounded-full bg-surface text-[13.5px] font-semibold text-ink shadow-lift ${
              t.tone === "success" && mascot ? "py-1.5 pl-2 pr-4" : "px-4 py-2.5"
            }`}
          >
            {t.tone === "success" &&
              (mascot ? (
                <Mascot stage={mascot.stage} mood={mascot.mood === "party" ? "party" : "done"} size={32} />
              ) : (
                <Sparkles size={16} strokeWidth={2} className="shrink-0 text-royal" aria-hidden />
              ))}
            {t.message}
            {t.action && (
              <button
                type="button"
                onClick={() => {
                  t.action?.run();
                  dismissToast(t.id);
                }}
                className="press hit pointer-events-auto -mr-2 ml-1 h-9 rounded-full bg-tint px-3 text-[13px] font-semibold text-royal"
              >
                {t.action.label}
              </button>
            )}
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  );
}
