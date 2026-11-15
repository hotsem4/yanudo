import type { ReactNode } from "react";
import type { MascotMood, MascotStage } from "../lib/mascot";

interface Props {
  stage: MascotStage;
  mood: MascotMood;
  /** 한 변 길이(px). */
  size: number;
  /** 뜻을 전해야 하는 자리에서만 준다. 없으면 장식으로 보고 보조 기술에서 숨긴다. */
  label?: string;
  /** 자주 보는 자리(히어로 배지, 성장 띠)는 움직이지 않는다. */
  still?: boolean;
  className?: string;
}

/**
 * 포롱: 알에서 깨어 66일 동안 파랑새로 자란다. 색은 토큰 클래스만 쓰고(fill-royal 등),
 * 움직임은 transform·opacity 뿐이다(index.css 의 mascot-*; 감소 모션이면 전역 규칙이 멈춘다).
 */
export function Mascot({ stage, mood, size, label, still = false, className = "" }: Props) {
  const bob = still ? "" : mood === "done" || mood === "party" ? "mascot-hop" : mood === "sleepy" ? "mascot-bob mascot-slow" : "mascot-bob";
  const twinkle = still ? "" : "mascot-twinkle";
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 120 120"
      role={label ? "img" : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      className={`shrink-0 overflow-visible ${className}`}
    >
      <ellipse className="fill-line" cx="60" cy="108" rx="30" ry="4.5" />
      {stage === 1 && <Egg mood={mood} bob={bob} />}
      {stage === 2 && <CrackedEgg mood={mood} bob={bob} />}
      {stage === 3 && <Chick mood={mood} bob={bob} />}
      {stage >= 4 && <Bird stage={stage} mood={mood} bob={bob} twinkle={twinkle} />}
      <Extras mood={mood} twinkle={twinkle} big={stage >= 4} />
    </svg>
  );
}

/* ── 얼굴 ───────────────────────────────────────────────── */

function Eye({ x, y, mood, r = 4.4 }: { x: number; y: number; mood: MascotMood; r?: number }) {
  if (mood === "done" || mood === "party") {
    return <path className="stroke-ink" d={`M${x - r} ${y + 1.5} q${r} ${-r * 1.7} ${r * 2} 0`} fill="none" strokeWidth="2.8" strokeLinecap="round" />;
  }
  if (mood === "sleepy") {
    return <path className="stroke-ink" d={`M${x - r} ${y} q${r} ${r * 0.9} ${r * 2} 0`} fill="none" strokeWidth="2.6" strokeLinecap="round" />;
  }
  return (
    <>
      <circle className="fill-ink" cx={x} cy={y} r={r} />
      <circle className="fill-surface" cx={x - r * 0.32} cy={y - r * 0.36} r={r * 0.34} />
    </>
  );
}

function Cheeks({ x1, x2, y, mood, onShell = false }: { x1: number; x2: number; y: number; mood: MascotMood; onShell?: boolean }) {
  if (mood === "sleepy") return null;
  const cls = onShell ? "fill-royal-soft/50" : "fill-surface/35";
  return (
    <>
      <ellipse className={cls} cx={x1} cy={y} rx="4.6" ry="3" />
      <ellipse className={cls} cx={x2} cy={y} rx="4.6" ry="3" />
    </>
  );
}

function Beak({ x, y, mood }: { x: number; y: number; mood: MascotMood }) {
  if (mood === "done" || mood === "party") {
    return <path className="fill-surface" d={`M${x - 5.5} ${y - 1} L${x + 5.5} ${y - 1} L${x} ${y + 7.5} Z`} />;
  }
  return <path className="fill-surface" d={`M${x - 5} ${y} Q${x} ${y - 3.5} ${x + 5} ${y} Q${x} ${y + 6} ${x - 5} ${y} Z`} />;
}

function Star({ x, y, s, className }: { x: number; y: number; s: number; className: string }) {
  const b = s * 0.34;
  return (
    <path
      className={className}
      d={`M${x} ${y - s} Q${x + b} ${y - b} ${x + s} ${y} Q${x + b} ${y + b} ${x} ${y + s} Q${x - b} ${y + b} ${x - s} ${y} Q${x - b} ${y - b} ${x} ${y - s} Z`}
    />
  );
}

/** 머리 깃. 단계가 오를수록 한 가닥씩 는다. */
function Crest({ n, x, y }: { n: number; x: number; y: number }) {
  return (
    <>
      {n >= 3 && <path className="fill-royal-soft" d={`M${x - 4} ${y + 1} q-8 -9 -15 -6 q5 6 11 9 Z`} />}
      {n >= 2 && <path className="fill-royal-soft" d={`M${x + 5} ${y + 1} q4 -12 13 -11 q-3 8 -9 13 Z`} />}
      <path className="fill-royal" d={`M${x} ${y} q-3 -13 6 -17 q1 9 -1 17 Z`} />
    </>
  );
}

const SHELL_BOTTOM = "M30 72 L39 64 L47 73 L55 64 L63 73 L71 64 L80 73 L90 72 A30 33 0 0 1 30 72 Z";

/* ── 단계별 몸 ──────────────────────────────────────────── */

function Body({ bob, children }: { bob: string; children: ReactNode }) {
  return <g className={bob}>{children}</g>;
}

/** 1단계 알: 아직 안에 있어서 알이 표정을 짓는다. */
function Egg({ mood, bob }: { mood: MascotMood; bob: string }) {
  return (
    <Body bob={bob}>
      <ellipse className="fill-frost" cx="60" cy="66" rx="29" ry="37" />
      <circle className="fill-royal-soft/55" cx="44" cy="46" r="3.4" />
      <circle className="fill-royal-soft/55" cx="76" cy="52" r="2.6" />
      <circle className="fill-royal-soft/55" cx="70" cy="90" r="3" />
      <circle className="fill-royal-soft/55" cx="46" cy="86" r="2.2" />
      <Eye x={50} y={68} mood={mood} r={3.8} />
      <Eye x={70} y={68} mood={mood} r={3.8} />
      <Cheeks x1={43} x2={77} y={75} mood={mood} onShell />
    </Body>
  );
}

/** 2단계 금 간 알: 윗껍질을 모자처럼 쓰고 눈만 내민다. */
function CrackedEgg({ mood, bob }: { mood: MascotMood; bob: string }) {
  return (
    <>
      <Body bob={bob}>
        <circle className="fill-royal" cx="60" cy="60" r="22" />
        <Eye x={52} y={58} mood={mood} r={4} />
        <Eye x={68} y={58} mood={mood} r={4} />
        <path className="fill-frost" d="M38 46 L45 52 L52 45 L60 52 L68 45 L75 52 L82 46 A24 22 0 0 0 38 46 Z" transform="rotate(-9 60 40) translate(0 -5)" />
      </Body>
      <path className="fill-frost" d={SHELL_BOTTOM} />
    </>
  );
}

/** 3단계 아기 새: 아랫껍질에 앉아 부리를 내밀었다. */
function Chick({ mood, bob }: { mood: MascotMood; bob: string }) {
  return (
    <>
      <Body bob={bob}>
        <Crest n={1} x={58} y={30} />
        <circle className="fill-royal" cx="60" cy="54" r="26" />
        <ellipse className="fill-royal-soft" cx="35" cy="62" rx="6" ry="9" transform="rotate(18 35 62)" />
        <ellipse className="fill-royal-soft" cx="85" cy="62" rx="6" ry="9" transform="rotate(-18 85 62)" />
        <Eye x={50} y={50} mood={mood} />
        <Eye x={70} y={50} mood={mood} />
        <Beak x={60} y={59} mood={mood} />
        <Cheeks x1={42} x2={78} y={59} mood={mood} />
      </Body>
      <path className="fill-frost" d={SHELL_BOTTOM} />
    </>
  );
}

/** 4~6단계: 껍질을 벗고 날개와 꼬리깃이 펴진다. 6단계는 가슴에 별. */
function Bird({ stage, mood, bob, twinkle }: { stage: MascotStage; mood: MascotMood; bob: string; twinkle: string }) {
  const big = stage >= 5;
  const top = stage === 6;
  const r = top ? 33 : big ? 32 : 29;
  const cy = top ? 62 : big ? 64 : 68;
  return (
    <>
      {top ? (
        <>
          <path className="fill-royal-soft" d="M84 80 q22 2 28 22 q-18 -2 -32 -12 Z" />
          <path className="fill-royal" d="M82 84 q14 10 14 26 q-14 -6 -22 -18 Z" />
          <path className="fill-frost" d="M86 76 q20 -6 30 6 q-16 6 -32 2 Z" />
        </>
      ) : big ? (
        <>
          <path className="fill-royal-soft" d="M84 80 q18 4 22 18 q-14 -1 -26 -10 Z" />
          <path className="fill-royal" d="M82 84 q10 8 10 20 q-10 -4 -16 -14 Z" />
        </>
      ) : (
        <path className="fill-royal-soft" d="M82 84 q12 4 14 14 q-10 0 -18 -8 Z" />
      )}
      <Body bob={bob}>
        {top ? (
          <>
            <path className="fill-royal-soft" d="M30 66 q-22 -14 -22 -38 q16 4 30 22 Z" />
            <path className="fill-royal-soft" d="M90 66 q22 -14 22 -38 q-16 4 -30 22 Z" />
          </>
        ) : big ? (
          <>
            <path className="fill-royal-soft" d="M31 70 q-18 -2 -24 -18 q14 -2 28 6 Z" />
            <path className="fill-royal-soft" d="M89 70 q18 -2 24 -18 q-14 -2 -28 6 Z" />
          </>
        ) : (
          <>
            <ellipse className="fill-royal-soft" cx="33" cy="74" rx="7" ry="12" transform="rotate(16 33 74)" />
            <ellipse className="fill-royal-soft" cx="87" cy="74" rx="7" ry="12" transform="rotate(-16 87 74)" />
          </>
        )}
        <Crest n={top ? 3 : big ? 2 : 1} x={58} y={cy - r + 3} />
        <circle className="fill-royal" cx="60" cy={cy} r={r} />
        <ellipse className="fill-frost" cx="60" cy={cy + r * 0.5} rx={r * 0.58} ry={r * 0.38} />
        {top && <Star x={60} y={cy + r * 0.52} s={6} className={`fill-royal ${twinkle}`} />}
        <Eye x={49} y={cy - 7} mood={mood} />
        <Eye x={71} y={cy - 7} mood={mood} />
        <Beak x={60} y={cy - 1} mood={mood} />
        <Cheeks x1={40} x2={80} y={cy + 3} mood={mood} />
      </Body>
      <path className="stroke-ink" d={`M51 ${cy + r - 1} v7 m-4 0 h8 M69 ${cy + r - 1} v7 m-4 0 h8`} fill="none" strokeWidth="2.6" strokeLinecap="round" />
    </>
  );
}

/** 기분 소품: 달성·완주는 반짝임, 졸릴 때는 z. */
function Extras({ mood, twinkle, big }: { mood: MascotMood; twinkle: string; big: boolean }) {
  if (mood === "done") {
    return (
      <>
        <Star x={100} y={26} s={7} className={`fill-royal-soft ${twinkle}`} />
        <Star x={20} y={40} s={5} className={`fill-royal-soft ${twinkle}`} />
      </>
    );
  }
  if (mood === "party") {
    return (
      <>
        <Star x={102} y={24} s={8} className={`fill-royal-soft ${twinkle}`} />
        <Star x={18} y={34} s={6} className={`fill-royal-soft ${twinkle}`} />
        <Star x={96} y={62} s={4.5} className={`fill-frost ${twinkle}`} />
        <circle className={`fill-royal-soft ${twinkle}`} cx="26" cy="16" r="2.6" />
        <circle className={`fill-frost ${twinkle}`} cx="82" cy="10" r="2.2" />
        {big && <rect className={`fill-royal-soft ${twinkle}`} x="10" y="58" width="6" height="3" rx="1.5" transform="rotate(-24 13 59)" />}
      </>
    );
  }
  if (mood === "sleepy") {
    return (
      <>
        <path className="stroke-royal-soft" d="M92 30 h9 l-9 9 h9" fill="none" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
        <path className="stroke-royal-soft/70" d="M105 16 h6 l-6 6 h6" fill="none" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      </>
    );
  }
  return null;
}
