import type { PointerEvent } from "react";

/**
 * 입력 칸에서 시작한 포인터인가. 끌어서 넘기는 카드·끌어서 닫는 시트 안의 입력 칸에서는
 * 커서 이동·글자 선택이 드래그로 잡혀 카드가 넘어가거나 시트가 닫히면(쓰던 글이 사라지면) 안 된다.
 */
export function startsInField(e: PointerEvent): boolean {
  return e.target instanceof Element && e.target.closest("input, textarea, select, [contenteditable='true']") !== null;
}
