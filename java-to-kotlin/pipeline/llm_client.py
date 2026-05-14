"""Thin OpenAI-compatible chat client for LM Studio.

The plan calls for:
  - configurable temperature (low for faithful translation)
  - generous max_tokens
  - timeout + transient-error retry (network/HTTP), separate from semantic retries
  - request/response logged to disk for debugging and review.
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass
class LLMConfig:
    base_url: str
    model_name: str
    temperature: float = 0.1
    max_tokens: int = 16384
    timeout_seconds: int = 600
    transient_retries: int = 3
    transient_backoff_seconds: int = 5


class LLMError(RuntimeError):
    pass


class LLMClient:
    def __init__(self, cfg: LLMConfig, log_dir: Path | None = None) -> None:
        self.cfg = cfg
        self.log_dir = log_dir
        if log_dir is not None:
            log_dir.mkdir(parents=True, exist_ok=True)

    def chat(
        self,
        messages: list[dict[str, str]],
        *,
        log_tag: str | None = None,
        max_tokens: int | None = None,
        temperature: float | None = None,
    ) -> str:
        payload = {
            "model": self.cfg.model_name,
            "messages": messages,
            "temperature": self.cfg.temperature if temperature is None else temperature,
            "max_tokens": self.cfg.max_tokens if max_tokens is None else max_tokens,
            "stream": False,
        }
        body = json.dumps(payload).encode("utf-8")
        url = self.cfg.base_url.rstrip("/") + "/chat/completions"

        last_err: Exception | None = None
        for attempt in range(1, self.cfg.transient_retries + 1):
            req = urllib.request.Request(
                url,
                data=body,
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            t0 = time.time()
            try:
                with urllib.request.urlopen(req, timeout=self.cfg.timeout_seconds) as resp:
                    raw = resp.read().decode("utf-8")
                elapsed = time.time() - t0
                data = json.loads(raw)
                content = self._extract_content(data)
                self._log(log_tag, attempt, payload, data, elapsed, None)
                return content
            except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as e:
                last_err = e
                elapsed = time.time() - t0
                self._log(log_tag, attempt, payload, None, elapsed, repr(e))
                if attempt < self.cfg.transient_retries:
                    time.sleep(self.cfg.transient_backoff_seconds * attempt)
                    continue
                break
            except (KeyError, ValueError, json.JSONDecodeError) as e:
                self._log(log_tag, attempt, payload, None, time.time() - t0, repr(e))
                raise LLMError(f"malformed response from LM Studio: {e!r}") from e

        raise LLMError(f"LM Studio request failed after {self.cfg.transient_retries} attempts: {last_err!r}")

    @staticmethod
    def _extract_content(data: dict[str, Any]) -> str:
        """Return the assistant's text. Falls back to reasoning_content.

        Some LM Studio backends (e.g. the Gemma reasoning variant served here)
        emit the working answer into ``message.reasoning_content`` and leave
        ``message.content`` empty if the model is truncated mid-stream. The
        retry / extraction logic downstream is happy to find the fenced kotlin
        block in either stream, so we concatenate them.
        """
        choices = data.get("choices") or []
        if not choices:
            raise LLMError(f"no choices in response: {data!r}")
        msg = choices[0].get("message") or {}
        content = msg.get("content") or ""
        reasoning = msg.get("reasoning_content") or ""
        combined = content
        if not content.strip() and reasoning.strip():
            combined = reasoning
        elif reasoning.strip():
            # Both present — keep both so the fence extractor can pick the
            # longest valid block.
            combined = reasoning + "\n\n" + content
        if not combined.strip():
            raise LLMError(f"no usable text in choice: {choices[0]!r}")
        return combined

    def _log(
        self,
        tag: str | None,
        attempt: int,
        request: dict[str, Any],
        response: dict[str, Any] | None,
        elapsed: float,
        error: str | None,
    ) -> None:
        if self.log_dir is None:
            return
        ts = time.strftime("%Y%m%dT%H%M%S")
        safe_tag = (tag or "untagged").replace("/", "_")
        path = self.log_dir / f"{ts}_{safe_tag}_a{attempt}.json"
        entry = {
            "tag": tag,
            "attempt": attempt,
            "elapsed_seconds": round(elapsed, 3),
            "request": request,
            "response": response,
            "error": error,
        }
        path.write_text(json.dumps(entry, indent=2, ensure_ascii=False))
