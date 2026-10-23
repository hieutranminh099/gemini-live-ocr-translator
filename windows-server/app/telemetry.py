"""Request-local durations; never store prompts, OCR text or provider diagnostics."""
from contextvars import ContextVar
import time


current_trace = ContextVar('translation_trace', default=None)


def duration(name, start):
    trace = current_trace.get()
    if trace is not None:
        trace['timings_ms'][name] = round((time.monotonic() - start) * 1000, 3)


def diagnostic(name, value):
    trace = current_trace.get()
    if trace is not None:
        trace['diagnostics'][name] = value
