import { BookmarkCheck, BookmarkPlus, Check, PenLine, Plus } from "lucide-react";
import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { termKey, type Sentence, type VocabEntry } from "../api/types";
import { showToast } from "../lib/toast";
import { sentenceWords } from "../lib/vocab";
import { studyStore } from "../state/store";
import { BottomSheet } from "./BottomSheet";

interface Props {
  open: boolean;
  onClose: () => void;
  sentence: Sentence | null;
  /** 지금 단어장(오버레이 반영). 아직 못 받았으면 null. */
  vocab: VocabEntry[] | null;
  onOpenVocab: () => void;
}

/**
 * 단어 담기 시트. 위에는 이 문장의 미리 만든 표현(뜻 포함), 아래에는 나머지 단어 칩, 맨 아래에 직접 입력.
 * 누르면 담기/빼기가 바로 바뀐다(오프라인에서도 이벤트로 쌓였다가 전송된다).
 */
export function VocabPickSheet({ open, onClose, sentence, vocab, onOpenVocab }: Props) {
  return (
    <BottomSheet open={open} onClose={onClose} title="단어 담기">
      {sentence && <Picker key={sentence.id} sentence={sentence} vocab={vocab ?? []} onOpenVocab={onOpenVocab} />}
    </BottomSheet>
  );
}

function Picker({ sentence, vocab, onOpenVocab }: { sentence: Sentence; vocab: VocabEntry[]; onOpenVocab: () => void }) {
  const [writing, setWriting] = useState(false);
  const terms = sentence.terms ?? [];
  const words = sentenceWords(sentence.en_text, terms);
  const saved = new Map(vocab.map((v) => [v.term_key, v]));
  const making = words.some((w) => saved.get(w.key)?.status === "PENDING");
  const hereKeys = new Set([...terms.map((t) => t.term_key), ...words.map((w) => w.key)]);
  const savedHere = vocab.filter((v) => hereKeys.has(v.term_key) || v.sentence?.id === sentence.id).length;

  const toggle = (key: string, text: string) => {
    if (saved.has(key)) studyStore.record({ type: "VOCAB_REMOVE", term_key: key });
    else studyStore.record({ type: "VOCAB_ADD", text, sentence_id: sentence.id, meaning: null, note: null });
  };

  return (
    <div className="flex flex-col gap-6">
      <p lang="en" translate="no" className="break-words rounded-2xl bg-canvas px-4 py-3 text-[15px] font-semibold leading-snug text-ink">
        {sentence.en_text}
      </p>

      {terms.length > 0 && (
        <section>
          <h3 className="mb-2 text-[15px] font-semibold text-ink">이 문장의 표현</h3>
          <ul className="flex flex-col gap-2">
            {terms.map((t) => {
              const on = saved.has(t.term_key);
              return (
                <li key={t.term_key}>
                  <button
                    type="button"
                    aria-pressed={on}
                    onClick={() => toggle(t.term_key, t.text)}
                    className={`press flex min-h-14 w-full items-center gap-3 rounded-2xl px-4 py-2.5 text-left ${on ? "bg-royal text-surface" : "bg-tint text-ink"}`}
                  >
                    <span className="min-w-0 flex-1">
                      <span lang="en" translate="no" className="block break-words text-[15.5px] font-bold">
                        {t.text}
                      </span>
                      {t.meaning && <span className={`mt-0.5 block break-keep text-[13px] ${on ? "text-surface/85" : "text-muted-strong"}`}>{t.meaning}</span>}
                    </span>
                    {on ? (
                      <BookmarkCheck size={20} strokeWidth={2} aria-hidden className="shrink-0" />
                    ) : (
                      <BookmarkPlus size={20} strokeWidth={2} aria-hidden className="shrink-0 text-royal" />
                    )}
                    <span className="sr-only">{on ? "담김. 누르면 빼요" : "누르면 담아요"}</span>
                  </button>
                </li>
              );
            })}
          </ul>
        </section>
      )}

      {words.length > 0 && (
        <section>
          <h3 className="mb-2 text-[15px] font-semibold text-ink">{terms.length > 0 ? "나머지 단어" : "이 문장의 단어"}</h3>
          <ul className="flex flex-wrap gap-2">
            {words.map((w) => {
              const on = saved.has(w.key);
              return (
                <li key={w.key}>
                  <button
                    type="button"
                    aria-pressed={on}
                    onClick={() => toggle(w.key, w.text)}
                    className={`press flex h-12 items-center gap-1.5 rounded-full pl-3.5 pr-4 text-[15px] font-semibold ${on ? "bg-royal text-surface" : "bg-tint text-ink"}`}
                  >
                    {on ? <Check size={16} strokeWidth={2} aria-hidden /> : <Plus size={16} strokeWidth={2} aria-hidden className="text-royal" />}
                    <span lang="en" translate="no">
                      {w.text}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
          <p className="mt-3 break-keep text-[12.5px] leading-relaxed text-muted" aria-live="polite">
            {making ? "뜻을 만드는 중… 잠시 뒤 단어장에서 뜻과 예문을 볼 수 있어요." : "단어를 담으면 뜻과 예문 3개를 만들어 단어장에 넣어 둬요."}
          </p>
        </section>
      )}

      {writing && <ManualForm sentence={sentence} saved={saved} onDone={() => setWriting(false)} />}

      <div className="flex gap-2">
        {!writing && (
          <button
            type="button"
            onClick={() => setWriting(true)}
            className="press flex h-12 flex-1 items-center justify-center gap-1.5 rounded-full bg-tint px-4 text-[14px] font-semibold text-royal"
          >
            <PenLine size={16} strokeWidth={2} aria-hidden />
            직접 입력
          </button>
        )}
        <button
          type="button"
          onClick={onOpenVocab}
          aria-label={savedHere > 0 ? `단어장 보기. 이 문장에서 ${savedHere}개 담음` : "단어장 보기"}
          className="press flex h-12 flex-1 items-center justify-center gap-2 rounded-full bg-tint px-4 text-[14px] font-semibold text-royal"
        >
          단어장 보기
          {savedHere > 0 && (
            <span aria-hidden className="flex h-6 min-w-[24px] items-center justify-center rounded-full bg-royal px-1.5 text-[12px] font-bold tabular-nums text-surface">
              {savedHere}
            </span>
          )}
        </button>
      </div>
    </div>
  );
}

/**
 * 문장 칩에 없는 표현(숙어, 다른 형태)을 직접 적어 담는다.
 * 뜻을 적으면 그 뜻으로 바로 쓰고(MANUAL), 비우면 서버가 뜻과 예문을 만든다.
 */
function ManualForm({ sentence, saved, onDone }: { sentence: Sentence; saved: Map<string, VocabEntry>; onDone: () => void }) {
  const textId = useId();
  const meaningId = useId();
  const noteId = useId();
  const hintId = useId();
  const headId = useId();
  const [text, setText] = useState("");
  const [meaning, setMeaning] = useState("");
  const [note, setNote] = useState("");
  const first = useRef<HTMLInputElement>(null);

  useEffect(() => {
    first.current?.focus();
  }, []);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const t = text.trim().replace(/\s+/g, " ");
    if (!t) {
      first.current?.focus();
      return;
    }
    const m = meaning.trim() || null;
    const n = note.trim() || null;
    const existing = saved.get(termKey(t));
    if (existing) {
      // 이미 담긴 표현은 담기가 무시되므로(서버 멱등) 적은 뜻·메모만 고친다.
      if (m || n) {
        studyStore.record({ type: "VOCAB_EDIT", term_key: existing.term_key, meaning: m, note: n ?? existing.note });
        showToast(`이미 담긴 표현이라 뜻과 메모를 고쳤어요: ${existing.text}`, "success");
      } else {
        showToast(`이미 단어장에 있어요: ${existing.text}`, "info");
      }
    } else {
      studyStore.record({ type: "VOCAB_ADD", text: t, sentence_id: sentence.id, meaning: m, note: n });
      showToast(m ? `담았어요: ${t}` : `담았어요. 뜻과 예문을 만드는 중이에요: ${t}`, "success");
    }
    onDone();
  };

  const field = "h-12 w-full rounded-2xl bg-surface px-4 text-[16px] text-ink shadow-card placeholder:text-muted";
  const label = "mb-1.5 block text-[12.5px] font-semibold text-muted-strong";
  return (
    <form onSubmit={submit} aria-labelledby={headId} className="flex flex-col gap-3 rounded-3xl bg-canvas p-4">
      <h3 id={headId} className="text-[15px] font-semibold text-ink">직접 입력해서 담기</h3>
      <div>
        <label htmlFor={textId} className={label}>
          영어 표현
        </label>
        <input
          ref={first}
          id={textId}
          name="text"
          lang="en"
          translate="no"
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="예: grab a bite"
          autoComplete="off"
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          enterKeyHint="next"
          maxLength={80}
          required
          className={field}
        />
      </div>
      <div>
        <label htmlFor={meaningId} className={label}>
          뜻 (선택)
        </label>
        <input
          id={meaningId}
          name="meaning"
          value={meaning}
          onChange={(e) => setMeaning(e.target.value)}
          placeholder="예: 간단히 먹다"
          aria-describedby={hintId}
          autoComplete="off"
          maxLength={200}
          className={field}
        />
        <p id={hintId} className="mt-1.5 break-keep text-[12.5px] leading-relaxed text-muted-strong">
          비워 두면 뜻과 예문 3개를 만들어 드려요.
        </p>
      </div>
      <div>
        <label htmlFor={noteId} className={label}>
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
          담기
        </button>
        <button type="button" onClick={onDone} className="press h-12 rounded-full bg-surface px-5 text-[14px] font-semibold text-royal shadow-card">
          취소
        </button>
      </div>
    </form>
  );
}
