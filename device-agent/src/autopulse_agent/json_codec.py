"""Exact JSON numbers on input and output; native floats are already approximate."""

from simplejson import dumps as _dumps, loads as _loads


def loads(raw):
    return _loads(raw, use_decimal=True, allow_nan=False)


def dumps(value, **options):
    return _dumps(value, use_decimal=True, allow_nan=False, **options)
