"""Cache-only research must never fall through to the newly opt-in cloud CLI."""
import copy
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'research'))
from android_cache_benchmark import CacheBenchmark


BASE=dict(version='1.6.0',settings=dict(provider='cli',personal_cli_enabled=False),
          cli=dict(supported_backend=False,experimental_enabled=False),translation_available=False,
          experimental_cli_enabled=False,backend_mode='cache_only')


def test_cache_guard_accepts_explicitly_disabled_cli():
    CacheBenchmark.guard(None,BASE)


@pytest.mark.parametrize('where,key,value',[
    ('settings','personal_cli_enabled',True), ('cli','experimental_enabled',True),
    (None,'translation_available',True),(None,'experimental_cli_enabled',True),
    (None,'backend_mode','cli_experimental'),
])
def test_cache_guard_rejects_experimental_translation_before_lookup(where,key,value):
    status=copy.deepcopy(BASE)
    (status[where] if where else status)[key]=value
    with pytest.raises(RuntimeError): CacheBenchmark.guard(None,status)
