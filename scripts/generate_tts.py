#!/usr/bin/env python3
"""서버의 미생성 오디오 작업을 edge-tts로 합성하고 업로드한다.

사용 예:
    python scripts/generate_tts.py
    python scripts/generate_tts.py --import content/day06.json
    python scripts/generate_tts.py --dry-run

이름·길이 규칙은 서버(Java tts 패키지)에만 있다.
이 워커는 서버가 준 파일 이름을 그대로 사용한다.
"""

from __future__ import annotations

import argparse
import asyncio
import importlib
import json
import math
import os
import sys
from dataclasses import dataclass
from http.client import HTTPException
from pathlib import Path
from types import ModuleType
from typing import Any, Callable, Coroutine, TypeVar
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class _NoRedirect(HTTPRedirectHandler):
    """리다이렉트를 따라가지 않는다. 따라가면 X-Admin-Token 이 다른 주소(또는 평문 HTTP)로 함께 넘어갈 수 있다."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401
        return None


_OPENER = build_opener(_NoRedirect)


T = TypeVar("T")
MAX_ATTEMPTS = 3


class NetworkError(Exception):
    """HTTP 전송 또는 연결 실패."""


class ResponseError(Exception):
    """서버 응답 형식 오류."""


class HTTPFailure(Exception):
    def __init__(self, status: int, detail: str) -> None:
        self.status = status
        super().__init__(f"HTTP {status}: {detail}")


@dataclass(frozen=True)
class Job:
    file: str
    voice: str
    rate: str
    text: str


@dataclass
class Summary:
    created: int = 0
    existing: int = 0
    skipped: int = 0
    failed: int = 0
    network_failed: bool = False


def short_text(text: str) -> str:
    text = " ".join(text.split())
    return text if len(text) <= 50 else text[:49] + "…"


def error_text(error: Exception) -> str:
    return " ".join(str(error).split()) or type(error).__name__


def server_message(base_url: str) -> None:
    print(
        f"서버({base_url})에 연결할 수 없습니다. "
        "build-all.bat 또는 run.bat 으로 서버를 먼저 실행하세요.",
        flush=True,
    )


class Client:
    def __init__(
        self, base_url: str, admin_token: str | None, timeout: float
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.admin_token = admin_token
        self.timeout = timeout

    def request(
        self,
        method: str,
        path: str,
        body: bytes | None = None,
        content_type: str | None = None,
    ) -> dict[str, Any]:
        """동기 HTTP 호출. 호출자는 반드시 asyncio.to_thread를 사용한다."""
        headers = {"Accept": "application/json"}
        if self.admin_token:
            headers["X-Admin-Token"] = self.admin_token
        if content_type:
            headers["Content-Type"] = content_type

        request = Request(
            self.base_url + path,
            data=body,
            headers=headers,
            method=method,
        )
        try:
            with _OPENER.open(request, timeout=self.timeout) as response:
                raw = response.read()
        except HTTPError as exc:
            try:
                raw_error = exc.read()
            except (OSError, HTTPException):
                raw_error = b""
            finally:
                exc.close()

            detail = raw_error.decode("utf-8", errors="replace")
            try:
                payload = json.loads(detail)
                if isinstance(payload, dict):
                    detail = str(
                        payload.get("message") or payload.get("error") or detail
                    )
            except (ValueError, TypeError):
                pass

            detail = " ".join(detail.split())[:500] or "요청이 거절되었습니다."
            raise HTTPFailure(exc.code, detail) from exc
        except (URLError, OSError, HTTPException) as exc:
            raise NetworkError("네트워크 연결 또는 응답 수신에 실패했습니다.") from exc

        try:
            payload = json.loads(raw.decode("utf-8"))
        except (UnicodeError, ValueError) as exc:
            raise ResponseError("서버가 올바른 UTF-8 JSON을 반환하지 않았습니다.") from exc

        if not isinstance(payload, dict):
            raise ResponseError("서버 응답은 JSON 객체여야 합니다.")
        return payload

    async def call(
        self,
        method: str,
        path: str,
        body: bytes | None = None,
        content_type: str | None = None,
    ) -> dict[str, Any]:
        async def send() -> dict[str, Any]:
            return await asyncio.to_thread(
                self.request, method, path, body, content_type
            )

        return await retry(
            send,
            f"{method} {path}",
            (NetworkError,),
        )


async def retry(
    operation: Callable[[], Coroutine[Any, Any, T]],
    label: str,
    retry_errors: tuple[type[Exception], ...],
) -> T:
    """최초 시도를 포함해 최대 3회, 1초·2초 지수 백오프로 시도한다."""
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            return await operation()
        except retry_errors as exc:
            if attempt == MAX_ATTEMPTS:
                raise
            delay = 2 ** (attempt - 1)
            print(
                f"{label}: {error_text(exc)} "
                f"({delay}초 후 재시도 {attempt + 1}/{MAX_ATTEMPTS})",
                flush=True,
            )
            await asyncio.sleep(delay)

    raise AssertionError("재시도 흐름 오류")


async def synthesize(edge_tts: ModuleType, job: Job) -> bytes:
    async def generate() -> bytes:
        communicate = edge_tts.Communicate(
            job.text, job.voice, rate=job.rate
        )
        audio = bytearray()
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                audio.extend(chunk["data"])
        if not audio:
            raise RuntimeError("합성 결과에 오디오 바이트가 없습니다.")
        return bytes(audio)

    return await retry(
        generate,
        f"합성 {job.file}",
        (Exception,),
    )


def parse_jobs(payload: dict[str, Any]) -> list[Job]:
    raw_jobs = payload.get("jobs")
    if not isinstance(raw_jobs, list):
        raise ResponseError("작업 목록 응답에 jobs 배열이 없습니다.")

    jobs: list[Job] = []
    for index, item in enumerate(raw_jobs, start=1):
        if not isinstance(item, dict):
            raise ResponseError(f"{index}번째 작업은 JSON 객체여야 합니다.")
        for key in ("file", "voice", "rate", "text"):
            value = item.get(key)
            if not isinstance(value, str) or not value.strip():
                raise ResponseError(
                    f"{index}번째 작업의 {key} 값이 올바르지 않습니다."
                )
        jobs.append(
            Job(
                file=item["file"],
                voice=item["voice"],
                rate=item["rate"],
                text=item["text"],
            )
        )
    return jobs


async def process_job(
    index: int,
    total: int,
    job: Job,
    client: Client,
    edge_tts: ModuleType,
    semaphore: asyncio.Semaphore,
    summary: Summary,
) -> None:
    async with semaphore:
        prefix = f"[{index}/{total}]"
        print(
            f'{prefix} 생성 {job.voice} "{short_text(job.text)}"',
            flush=True,
        )
        path = "/api/v1/admin/audio/" + quote(job.file, safe="")

        try:
            for synthesis_round in range(2):
                audio = await synthesize(edge_tts, job)
                try:
                    response = await client.call(
                        "PUT", path, audio, "audio/mpeg"
                    )
                except HTTPFailure as exc:
                    if exc.status == 404:
                        summary.skipped += 1
                        print(
                            f"{prefix} 건너뜀(404) {job.file}: "
                            "서버의 콘텐츠가 변경되었습니다.",
                            flush=True,
                        )
                        return
                    if exc.status == 422 and synthesis_round == 0:
                        print(
                            f"{prefix} 검증 실패(422) {job.file}: "
                            "한 번 다시 합성해 업로드합니다.",
                            flush=True,
                        )
                        continue
                    raise

                created = response.get("created")
                if not isinstance(created, bool):
                    raise ResponseError(
                        "업로드 응답에 created 불리언 값이 없습니다."
                    )
                if created:
                    summary.created += 1
                    result = "새로 생성"
                else:
                    summary.existing += 1
                    result = "이미 있음"
                print(
                    f"{prefix} 업로드 완료({result}) {job.file}",
                    flush=True,
                )
                return
        except Exception as exc:
            summary.failed += 1
            if isinstance(exc, NetworkError):
                summary.network_failed = True
            print(
                f"{prefix} 실패 {job.file}: {error_text(exc)}",
                flush=True,
            )


async def import_lessons(
    files: list[str], client: Client, summary: Summary
) -> None:
    for filename in files:
        try:
            # 바이트를 그대로 보내되, UTF-8로 읽을 수 있는지 먼저 확인한다.
            body = await asyncio.to_thread(Path(filename).read_bytes)
            body.decode("utf-8")
            result = await client.call(
                "POST",
                "/api/v1/admin/lessons/import",
                body,
                "application/json; charset=utf-8",
            )
            print(
                f"임포트 완료 {filename}: "
                f"레슨 {result.get('lessons', '?')}개, "
                f"새로 생성 {result.get('created', '?')}개, "
                f"수정 {result.get('updated', '?')}개",
                flush=True,
            )
            for warning in result.get("warnings") or []:
                print(f"  경고: {warning}", flush=True)
        except NetworkError:
            raise
        except Exception as exc:
            summary.failed += 1
            print(
                f"임포트 실패 {filename}: {error_text(exc)}",
                flush=True,
            )


async def run(args: argparse.Namespace, edge_tts: ModuleType) -> int:
    client = Client(args.base_url, args.admin_token, args.timeout)
    summary = Summary()

    try:
        await import_lessons(args.import_files, client, summary)
        payload = await client.call("GET", "/api/v1/admin/tts/jobs")
        if isinstance(payload, dict) and payload.get("engine") == "azure":
            # Azure(48kHz) 파일 이름에 edge-tts(24kHz) 소리를 올리면 품질이 섞인다. 서버가 직접 만든다.
            print("서버가 Azure Speech 로 음성을 직접 만듭니다. 이 스크립트는 할 일이 없습니다(진행은 /api/v1/admin/tts/status).")
            return 0
        jobs = parse_jobs(payload)
    except NetworkError:
        server_message(client.base_url)
        return 2
    except Exception as exc:
        print(f"작업 목록 조회 실패: {error_text(exc)}", flush=True)
        return 1

    available = len(jobs)
    if args.limit is not None:
        jobs = jobs[:args.limit]

    print(
        f"미생성 작업 {available}개 중 {len(jobs)}개"
        f"{' 조회' if args.dry_run else ' 처리'}",
        flush=True,
    )

    if args.dry_run:
        for index, job in enumerate(jobs, start=1):
            print(
                f'[{index}/{len(jobs)}] 작업 {job.voice} '
                f'"{short_text(job.text)}" '
                f"(파일: {job.file}, 속도: {job.rate})",
                flush=True,
            )
    else:
        semaphore = asyncio.Semaphore(args.concurrency)
        await asyncio.gather(
            *(
                process_job(
                    index,
                    len(jobs),
                    job,
                    client,
                    edge_tts,
                    semaphore,
                    summary,
                )
                for index, job in enumerate(jobs, start=1)
            )
        )

    missing: int | str = "조회 실패"
    try:
        status = await client.call("GET", "/api/v1/admin/tts/status")
        value = status.get("clips_missing")
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise ResponseError("서버 상태의 clips_missing 값이 올바르지 않습니다.")
        missing = value
    except Exception as exc:
        summary.failed += 1
        if isinstance(exc, NetworkError):
            summary.network_failed = True
        print(f"서버 상태 조회 실패: {error_text(exc)}", flush=True)

    uploaded = summary.created + summary.existing
    print(
        f"요약: 업로드 {uploaded}개"
        f"(새로 생성 {summary.created}, 이미 있음 {summary.existing}), "
        f"건너뜀(404) {summary.skipped}개, 실패 {summary.failed}개, "
        f"서버 clips_missing: {missing}",
        flush=True,
    )

    if summary.network_failed:
        server_message(client.base_url)
        return 2
    return 1 if summary.failed else 0


def positive_int(value: str) -> int:
    try:
        number = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("양의 정수를 입력하세요.") from exc
    if number <= 0:
        raise argparse.ArgumentTypeError("1 이상의 정수를 입력하세요.")
    return number


def nonnegative_int(value: str) -> int:
    try:
        number = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("0 이상의 정수를 입력하세요.") from exc
    if number < 0:
        raise argparse.ArgumentTypeError("0 이상의 정수를 입력하세요.")
    return number


def positive_seconds(value: str) -> float:
    try:
        seconds = float(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("양수인 초 단위를 입력하세요.") from exc
    if not math.isfinite(seconds) or seconds <= 0:
        raise argparse.ArgumentTypeError("유한한 양수인 초 단위를 입력하세요.")
    return seconds


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="서버의 미생성 오디오를 edge-tts로 병렬 합성하고 업로드합니다."
    )
    parser.add_argument(
        "--base-url",
        default=os.environ.get("HABIT66_BASE_URL") or "http://127.0.0.1:8080",
        help="서버 주소 (환경 변수: HABIT66_BASE_URL)",
    )
    parser.add_argument(
        "--admin-token",
        default=os.environ.get("APP_ADMIN_TOKEN"),
        help="관리자 토큰 (환경 변수: APP_ADMIN_TOKEN)",
    )
    parser.add_argument(
        "--import",
        dest="import_files",
        nargs="+",
        default=[],
        metavar="FILE",
        help="먼저 임포트할 UTF-8 레슨 JSON 파일",
    )
    parser.add_argument(
        "--concurrency",
        type=positive_int,
        default=4,
        metavar="N",
        help="동시 처리 수 (기본: 4)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="임포트 후 작업 목록만 출력하고 합성·업로드는 생략",
    )
    parser.add_argument(
        "--limit",
        type=nonnegative_int,
        metavar="N",
        help="작업 목록의 앞에서 N개만 처리 (0 허용)",
    )
    parser.add_argument(
        "--timeout",
        type=positive_seconds,
        default=30.0,
        metavar="SEC",
        help="HTTP 요청 제한 시간, 초 (기본: 30)",
    )
    return parser


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, OSError, ValueError):
        pass

    parser = build_parser()
    args = parser.parse_args()

    args.base_url = args.base_url.strip().rstrip("/")
    try:
        parsed = urlsplit(args.base_url)
        valid_url = (
            parsed.scheme in ("http", "https")
            and bool(parsed.hostname)
            and parsed.username is None
            and parsed.password is None
            and not parsed.query
            and not parsed.fragment
        )
        parsed.port
    except ValueError:
        valid_url = False
    if not valid_url:
        parser.error("--base-url에 올바른 http:// 또는 https:// 서버 주소를 입력하세요.")

    try:
        edge_tts = importlib.import_module("edge_tts")
    except ImportError:
        print(
            "edge_tts 패키지를 불러올 수 없습니다. "
            "pip install edge-tts 명령으로 설치하세요.",
            flush=True,
        )
        return 2

    if sys.platform == "win32":
        policy = getattr(asyncio, "WindowsSelectorEventLoopPolicy", None)
        if policy is not None:
            asyncio.set_event_loop_policy(policy())

    try:
        return asyncio.run(run(args, edge_tts))
    except KeyboardInterrupt:
        print("\n사용자가 작업을 중단했습니다.", flush=True)
        return 1
    except Exception as exc:
        print(f"실행 실패: {error_text(exc)}", flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main())
