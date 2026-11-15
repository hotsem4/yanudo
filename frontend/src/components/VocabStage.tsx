import { AnimatePresence, motion, useDragControls, useReducedMotion, type PanInfo } from "framer-motion";
import { Check, CheckCheck, ChevronLeft, ChevronRight, CloudOff, Pencil, Trash2, Volume2 } from "lucide-react";
import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import type { VocabEntry, VocabExample } from "../api/types";
import { audioEngine } from "../audio/useEngine";
import type { MascotState } from "../lib/mascot";
import { startsInField } from "../lib/pointer";
import { showToast } from "../lib/toast";
import { exampleItemId, needsMeaning, vocabCounts, type VocabFilter } from "../lib/vocab";
import type { StudyState } from "../state/store";
import { studyStore } from "../state/store";
import { useStudy } from "../state/useStudy";
import { Mascot } from "./Mascot";
import { Segmented, type SegmentOption } from "./Segmented";

interface Props {
  study: StudyState;
  /** 담은 표현 전체(최근 먼저). null 이면 아직 못 받았다. */
  all: VocabEntry[] | null;
  /** 지금 필터에 걸린 표현. */
  deck: VocabEntry[];
  index: number;
  filter: VocabFilter;
  onFilter: (f: VocabFilter) => void;
  onSelect: (key: string) => void;
  onGoStudy: () => void;
  mascot: MascotState | null;
}

/** PC 의 Claude 로그인이 끊겨 새 단어의 뜻을 못 만드는 중인가. 뜻을 기다리는 단어의 뒷면 문구만 바꾼다(SPEC F-08). */
function useLoginLost(): boolean {
  const study = useStudy();
  return study.kind === "ready" && study.view.vocabGenerator?.state === "LOGIN_REQUIRED";
}

/** 단어장 화면(SPEC F-08). 학습 카드 자리를 대신하고, 데이터 4상태를 모두 그린다. */
export function VocabStage({ study, all, deck, index, filter, onFilter, onSelect, onGoStudy, mascot }: Props) {
  const counts = vocabCounts(all ?? []);
  const filters: SegmentOption<VocabFilter>[] = [
    { value: "learning", label: `외우는 중 ${counts.learning}` },
    { value: "mastered", label: `외운 단어 ${counts.mastered}` },
    { value: "all", label: `전체 ${counts.all}` },
  ];
  const entry = deck[index];

  return (
    <section aria-label="단어장" className="relative flex min-h-0 flex-1 flex-col overflow-hidden rounded-stage bg-surface p-6 shadow-card [@media(max-height:599px)]:min-h-[340px]">
      <Segmented variant="tab" semantics="radio" label="단어장 필터" options={filters} value={filter} onChange={onFilter} className="tabular-nums" />

      {study.kind === "booting" ? (
        <Skeleton />
      ) : study.kind === "error" || all === null ? (
        <Notice
          icon={<CloudOff size={22} strokeWidth={2} aria-hidden />}
          title="단어장을 아직 받지 못했어요"
          body="연결되면 담아 둔 단어를 불러올게요. 그동안 학습 카드에서 담은 단어는 기기에 모아 둡니다."
          action={{ label: "다시 받기", run: () => void studyStore.refresh() }}
        />
      ) : all.length === 0 ? (
        <Notice
          icon={mascot ? <Mascot stage={mascot.stage} mood="wait" size={76} /> : null}
          bare
          title="아직 담은 단어가 없어요"
          body="학습 카드의 단어 버튼으로 모르는 단어를 담아 보세요."
          action={{ label: "학습 카드로 가기", run: onGoStudy }}
        />
      ) : !entry ? (
        <Notice
          icon={<Check size={22} strokeWidth={2} aria-hidden />}
          title={filter === "mastered" ? "아직 외운 단어가 없어요" : "외우는 중인 단어가 없어요"}
          body={filter === "mastered" ? "단어 카드에서 외웠어요를 누르면 여기로 옮겨 와요." : "담아 둔 단어를 모두 외웠어요."}
          action={{ label: "전체 보기", run: () => onFilter("all") }}
        />
      ) : (
        <VocabCard
          key={entry.term_key}
          entry={entry}
          index={index}
          total={deck.length}
          filter={filter}
          onPrev={() => index > 0 && onSelect(deck[index - 1].term_key)}
          onNext={() => index < deck.length - 1 && onSelect(deck[index + 1].term_key)}
        />
      )}
    </section>
  );
}

interface CardProps {
  entry: VocabEntry;
  index: number;
  total: number;
  filter: VocabFilter;
  onPrev: () => void;
  onNext: () => void;
}

const SWIPE_DISTANCE = 80;
const SWIPE_VELOCITY = 110;

function VocabCard({ entry, index, total, filter, onPrev, onNext }: CardProps) {
  const reduce = useReducedMotion();
  const [back, setBack] = useState(false);
  const [editing, setEditing] = useState(false);
  const drag = useDragControls();

  const onDragEnd = (_: unknown, info: PanInfo) => {
    const { offset, velocity } = info;
    if (offset.x < -SWIPE_DISTANCE || (velocity.x < -SWIPE_VELOCITY && offset.x < -24)) onNext();
    else if (offset.x > SWIPE_DISTANCE || (velocity.x > SWIPE_VELOCITY && offset.x > 24)) onPrev();
  };

  const remove = () => {
    studyStore.record({ type: "VOCAB_REMOVE", term_key: entry.term_key });
    showToast(`단어장에서 뺐어요: ${entry.text}`, "info", 5000, {
      label: "되돌리기",
      run: () => {
        // 직접 적은 뜻은 함께 되살린다. 나머지는 서버가 다시 채운다.
        studyStore.record({
          type: "VOCAB_ADD",
          text: entry.text,
          sentence_id: entry.sentence?.id ?? null,
          meaning: entry.source === "MANUAL" ? entry.meaning : null,
          note: entry.note,
        });
        if (entry.mastered) studyStore.record({ type: "VOCAB_MASTER", term_key: entry.term_key, mastered: true });
      },
    });
  };

  const toggleMastered = () => {
    const mastered = !entry.mastered;
    studyStore.record({ type: "VOCAB_MASTER", term_key: entry.term_key, mastered });
    // 필터 밖으로 나가 카드가 사라지는 경우에만 어디로 갔는지 알리고 되돌릴 길을 준다.
    if (filter === "all") return;
    showToast(mastered ? `외운 단어로 옮겼어요: ${entry.text}` : `외우는 중으로 옮겼어요: ${entry.text}`, "success", 4000, {
      label: "되돌리기",
      run: () => studyStore.record({ type: "VOCAB_MASTER", term_key: entry.term_key, mastered: !mastered }),
    });
  };

  return (
    <>
      <div className="mt-4 flex items-center justify-between gap-2">
        <span className="whitespace-nowrap text-[12.5px] font-semibold tabular-nums text-muted">
          {index + 1} / {total}
        </span>
        <button
          type="button"
          onClick={remove}
          aria-label={`${entry.text} 단어장에서 빼기`}
          className="press hit flex h-9 w-9 items-center justify-center rounded-full text-muted [@media(hover:hover)]:hover:bg-tint [@media(hover:hover)]:hover:text-royal"
        >
          <Trash2 size={18} strokeWidth={1.75} aria-hidden />
        </button>
      </div>

      <motion.div
        drag="x"
        dragControls={drag}
        dragListener={false}
        onPointerDown={(e) => !startsInField(e) && drag.start(e)}
        dragSnapToOrigin
        dragElastic={0.18}
        dragMomentum={false}
        onDragEnd={onDragEnd}
        onTap={back ? undefined : () => setBack(true)}
        role={back ? undefined : "button"}
        tabIndex={0}
        aria-label={back ? undefined : `${entry.text}. 눌러서 뜻 보기`}
        onKeyDown={(e) => {
          if (e.target !== e.currentTarget) return;
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            setBack((b) => !b);
          } else if (e.key === "ArrowRight") onNext();
          else if (e.key === "ArrowLeft") onPrev();
        }}
        initial={reduce ? { opacity: 0 } : { opacity: 0, transform: "translateY(8px)" }}
        animate={{ opacity: 1, transform: "translateY(0px)" }}
        transition={{ duration: 0.2, ease: [0.23, 1, 0.32, 1] }}
        className={`mt-2 flex min-h-0 flex-1 touch-pan-y flex-col rounded-2xl ${back ? "" : "cursor-pointer select-none"}`}
      >
        <AnimatePresence initial={false} mode="wait">
          {back ? (
            <motion.div
              key="back"
              initial={{ opacity: 0, filter: "blur(2px)" }}
              animate={{ opacity: 1, filter: "blur(0px)" }}
              exit={{ opacity: 0, filter: "blur(2px)" }}
              transition={{ duration: 0.16 }}
              className="thin-scroll -mx-3 -mt-1 min-h-0 flex-1 overflow-y-auto overscroll-contain px-3 pt-1"
            >
              <Back entry={entry} editing={editing} onEdit={setEditing} onFront={() => setBack(false)} />
            </motion.div>
          ) : (
            <motion.div
              key="front"
              initial={{ opacity: 0, filter: "blur(2px)" }}
              animate={{ opacity: 1, filter: "blur(0px)" }}
              exit={{ opacity: 0, filter: "blur(2px)" }}
              transition={{ duration: 0.16 }}
              className="my-auto py-6"
            >
              <p lang="en" translate="no" className="break-words text-balance text-[34px] font-bold leading-[1.2] tracking-tight text-ink">
                {entry.text}
              </p>
              <p className="mt-4 text-[13.5px] font-medium text-muted">
                {entry.status === "PENDING" ? "뜻을 만드는 중…" : needsMeaning(entry) ? "눌러서 뜻을 적어 보세요" : "눌러서 뜻 보기"}
              </p>
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>

      <div className="mt-4 flex items-center gap-2">
        <button
          type="button"
          onClick={onPrev}
          disabled={index === 0}
          aria-label="이전 단어"
          className="press flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-tint text-royal disabled:opacity-35"
        >
          <ChevronLeft size={22} strokeWidth={2} aria-hidden />
        </button>
        <button
          type="button"
          onClick={toggleMastered}
          aria-pressed={entry.mastered}
          className={`press flex h-12 min-w-0 flex-1 items-center justify-center gap-1.5 rounded-full text-[14px] font-semibold ${
            entry.mastered ? "bg-royal text-surface" : "bg-tint text-royal"
          }`}
        >
          {entry.mastered ? <CheckCheck size={17} strokeWidth={2} aria-hidden /> : <Check size={17} strokeWidth={2} aria-hidden />}
          {entry.mastered ? "외운 단어" : "외웠어요"}
        </button>
        <button
          type="button"
          onClick={onNext}
          disabled={index >= total - 1}
          aria-label="다음 단어"
          className="press flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-tint text-royal disabled:opacity-35"
        >
          <ChevronRight size={22} strokeWidth={2} aria-hidden />
        </button>
      </div>
    </>
  );
}

/** 뒷면: 뜻, 쓰임 느낌, 예문 3개, 처음 본 학습 문장. */
function Back({ entry, editing, onEdit, onFront }: { entry: VocabEntry; editing: boolean; onEdit: (v: boolean) => void; onFront: () => void }) {
  const pending = entry.status === "PENDING";
  const missing = needsMeaning(entry);
  const loginLost = useLoginLost();
  const showForm = editing || missing;
  return (
    <div className="flex flex-col gap-4 pb-1">
      <button
        type="button"
        onClick={onFront}
        aria-label={`${entry.text}. 눌러서 앞면으로`}
        className="press -mx-2 self-start rounded-xl px-2 py-1 text-left"
      >
        <span lang="en" translate="no" className="break-words text-[22px] font-bold leading-tight tracking-tight text-ink">
          {entry.text}
        </span>
      </button>

      {showForm ? (
        <EditForm entry={entry} missing={missing} onDone={() => onEdit(false)} />
      ) : pending && !entry.meaning ? (
        <div>
          <div aria-hidden className="flex flex-col gap-2">
            <span className="skeleton h-5 w-[52%] rounded-lg bg-tint" />
            <span className="skeleton h-4 w-[84%] rounded-lg bg-line" />
            <span className="skeleton h-4 w-[68%] rounded-lg bg-line" />
          </div>
          <p className="mt-3 break-keep text-[13.5px] leading-relaxed text-muted">
            {loginLost ? "PC의 Claude 로그인이 끊겨 기다리는 중이에요. 다시 로그인하면 뜻과 예문이 채워져요." : "뜻을 만드는 중… 잠시 뒤 뜻과 예문이 채워져요."}
          </p>
          <button type="button" onClick={() => onEdit(true)} className="press mt-2 h-11 rounded-full bg-tint px-4 text-[13.5px] font-semibold text-royal">
            직접 적기
          </button>
        </div>
      ) : (
        <div>
          <div className="flex items-start justify-between gap-2">
            <p className="break-keep text-[18px] font-semibold leading-snug text-royal">{entry.meaning}</p>
            <button
              type="button"
              onClick={() => onEdit(true)}
              aria-label="뜻과 메모 고치기"
              className="press hit flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-muted [@media(hover:hover)]:hover:bg-tint [@media(hover:hover)]:hover:text-royal"
            >
              <Pencil size={16} strokeWidth={1.75} aria-hidden />
            </button>
          </div>
          {entry.nuance && <p className="mt-1.5 break-keep text-pretty text-[14px] leading-relaxed text-muted">{entry.nuance}</p>}
          {entry.note && (
            <p className="mt-2 break-keep rounded-2xl bg-canvas px-3 py-2 text-[13.5px] leading-relaxed text-muted-strong">
              <span className="font-semibold">내 메모</span> {entry.note}
            </p>
          )}
          {pending && <p className="mt-2 text-[13px] text-muted">예문을 만드는 중…</p>}
        </div>
      )}

      {entry.examples.length > 0 && (
        <ol className="flex flex-col gap-1" aria-label="예문">
          {entry.examples.map((ex, i) => (
            <Example key={i} example={ex} id={exampleItemId(entry.term_key, i)} day={entry.sentence?.day ?? 0} />
          ))}
        </ol>
      )}

      {entry.sentence && (
        <div className="rounded-2xl bg-tint px-4 py-3">
          <p className="text-[12px] font-medium tabular-nums text-muted-strong">Day {entry.sentence.day}에서 처음 만난 문장</p>
          <p lang="en" translate="no" className="mt-1 break-words text-[14.5px] font-semibold leading-snug text-ink">
            {entry.sentence.en_text}
          </p>
          <p className="mt-0.5 break-keep text-[13px] leading-relaxed text-muted-strong">{entry.sentence.ko_natural}</p>
        </div>
      )}
    </div>
  );
}

function Example({ example, id, day }: { example: VocabExample; id: number; day: number }) {
  return (
    <li className="flex items-start gap-2">
      <button
        type="button"
        onClick={() =>
          void audioEngine.audition({ id, day, speaker: "B", en_text: example.en, audio_url: example.audio_url, duration_ms: example.duration_ms })
        }
        aria-label={`예문 듣기: ${example.en}`}
        className="press mt-0.5 flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-tint text-royal"
      >
        <Volume2 size={18} strokeWidth={2} aria-hidden />
      </button>
      <div className="min-w-0 py-1.5">
        <p lang="en" translate="no" className="break-words text-[15px] font-semibold leading-snug text-ink">
          {example.en}
        </p>
        <p className="mt-0.5 break-keep text-[13px] leading-relaxed text-muted">{example.ko}</p>
      </div>
    </li>
  );
}

/** 뜻·메모 직접 입력. 직접 적은 값이 생성값보다 우선한다(SPEC F-08). */
function EditForm({ entry, missing, onDone }: { entry: VocabEntry; missing: boolean; onDone: () => void }) {
  const loginLost = useLoginLost();
  const meaningId = useId();
  const noteId = useId();
  const [meaning, setMeaning] = useState(entry.meaning ?? "");
  const [note, setNote] = useState(entry.note ?? "");
  const first = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!missing) first.current?.focus();
  }, [missing]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const m = meaning.trim();
    if (!m) {
      first.current?.focus();
      return;
    }
    studyStore.record({ type: "VOCAB_EDIT", term_key: entry.term_key, meaning: m, note: note.trim() || null });
    showToast("뜻을 저장했어요", "success");
    onDone();
  };

  const field = "h-12 w-full rounded-2xl bg-canvas px-4 text-[16px] text-ink placeholder:text-muted";
  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      {missing && (
        <p className="break-keep text-[13.5px] leading-relaxed text-muted">
          {entry.status !== "FAILED"
            ? "아직 뜻이 없어요. 직접 적어 두세요."
            : loginLost
              ? "PC의 Claude 로그인이 끊겨 뜻을 만들지 못했어요. 다시 로그인하면 자동으로 만들고, 지금 직접 적어도 돼요."
              : "뜻을 만들지 못했어요. 직접 적어 두면 바로 쓸 수 있어요."}
        </p>
      )}
      <div>
        <label htmlFor={meaningId} className="mb-1.5 block text-[12.5px] font-semibold text-muted-strong">
          뜻
        </label>
        <input
          ref={first}
          id={meaningId}
          name="meaning"
          value={meaning}
          onChange={(e) => setMeaning(e.target.value)}
          placeholder="예: 가볍게 먹다"
          autoComplete="off"
          maxLength={80}
          required
          className={field}
        />
      </div>
      <div>
        <label htmlFor={noteId} className="mb-1.5 block text-[12.5px] font-semibold text-muted-strong">
          메모 (선택)
        </label>
        <input
          id={noteId}
          name="note"
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder="예: 친구 사이에서만 쓰기"
          autoComplete="off"
          maxLength={120}
          className={field}
        />
      </div>
      <div className="flex gap-2">
        <button type="submit" className="press h-12 flex-1 rounded-full bg-royal px-5 text-[14px] font-semibold text-surface">
          저장
        </button>
        {!missing && (
          <button type="button" onClick={onDone} className="press h-12 rounded-full bg-tint px-5 text-[14px] font-semibold text-royal">
            취소
          </button>
        )}
      </div>
    </form>
  );
}

function Notice({
  icon,
  title,
  body,
  action,
  bare = false,
}: {
  icon: React.ReactNode;
  title: string;
  body: string;
  action: { label: string; run: () => void };
  bare?: boolean;
}) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-3 py-8 text-center">
      {bare ? icon : <span className="flex h-12 w-12 items-center justify-center rounded-full bg-tint text-royal">{icon}</span>}
      <h2 className="text-balance text-[17px] font-bold text-ink">{title}</h2>
      <p className="break-keep text-pretty text-[14px] leading-relaxed text-muted">{body}</p>
      <button type="button" onClick={action.run} className="press mt-1 h-12 rounded-full bg-tint px-5 text-[14px] font-semibold text-royal">
        {action.label}
      </button>
    </div>
  );
}

function Skeleton() {
  return (
    <>
      <div aria-hidden className="flex flex-1 flex-col justify-center gap-3 py-8">
        <span className="skeleton h-9 w-[58%] rounded-lg bg-line" />
        <span className="skeleton h-4 w-[34%] rounded-lg bg-tint" />
      </div>
      <span className="sr-only">단어장을 불러오는 중이에요</span>
    </>
  );
}
