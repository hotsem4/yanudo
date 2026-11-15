import { AnimatePresence, motion, useReducedMotion, type PanInfo } from "framer-motion";
import { X } from "lucide-react";
import { useEffect, useId, useRef, type ReactNode } from "react";

interface Props {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
}

const DRAWER = [0.32, 0.72, 0, 1] as const;

/** 바텀시트(clean-blue-ui). 열림 420ms drawer 커브, 닫힘은 더 빠르게. 아래로 끌거나 배경·Esc 로 닫는다. */
export function BottomSheet({ open, onClose, title, children }: Props) {
  const titleId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  const reduce = useReducedMotion();

  useEffect(() => {
    if (!open) return;
    const prev = document.activeElement as HTMLElement | null;
    panelRef.current?.focus();
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      prev?.focus?.();
    };
  }, [open, onClose]);

  const onDragEnd = (_: unknown, info: PanInfo) => {
    if (info.offset.y > 100 || info.velocity.y > 500) onClose();
  };

  return (
    <AnimatePresence>
      {open && (
        <div className="fixed inset-0 z-40">
          <motion.div
            className="absolute inset-0 bg-ink/30 backdrop-blur-[2px]"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0, transition: { duration: 0.2 } }}
            onClick={onClose}
          />
          <motion.div
            ref={panelRef}
            role="dialog"
            aria-modal="true"
            aria-labelledby={titleId}
            tabIndex={-1}
            initial={reduce ? { opacity: 0 } : { y: "100%" }}
            animate={reduce ? { opacity: 1 } : { y: 0, transition: { duration: 0.42, ease: DRAWER } }}
            exit={reduce ? { opacity: 0 } : { y: "100%", transition: { duration: 0.26, ease: DRAWER } }}
            drag={reduce ? false : "y"}
            dragConstraints={{ top: 0, bottom: 0 }}
            dragElastic={{ top: 0, bottom: 0.6 }}
            onDragEnd={onDragEnd}
            className="absolute inset-x-0 bottom-0 mx-auto flex max-h-[88dvh] max-w-md flex-col overflow-hidden rounded-t-[32px] bg-surface shadow-lift outline-none unfolded:max-w-lg"
          >
            <div className="shrink-0 px-6 pt-3">
              <div aria-hidden className="mx-auto mb-4 h-1.5 w-10 rounded-full bg-line" />
              <div className="mb-5 flex items-center justify-between">
                <h2 id={titleId} className="text-[19px] font-bold text-ink">
                  {title}
                </h2>
                <button type="button" onClick={onClose} aria-label="닫기" className="press hit flex h-9 w-9 items-center justify-center rounded-full bg-tint text-royal">
                  <X size={18} strokeWidth={2} aria-hidden />
                </button>
              </div>
            </div>
            {/* 스크롤은 제목 아래 본문만. 스크롤바가 둥근 모서리 아래에서 시작하고 가장자리에서 6px 안쪽에 선다. */}
            <div className="thin-scroll mr-1.5 min-h-0 flex-1 overflow-y-auto overscroll-contain pb-[max(24px,env(safe-area-inset-bottom))] pl-6 pr-[18px]">
              {children}
            </div>
          </motion.div>
        </div>
      )}
    </AnimatePresence>
  );
}
