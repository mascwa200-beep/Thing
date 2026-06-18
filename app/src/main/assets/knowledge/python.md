# Python reference

## Basics
```python
name = "Jarvis"            # dynamic typing
def add(a: int, b: int) -> int:   # optional type hints
    return a + b
greeting = f"Hi {name}"    # f-string
x = 1 if cond else 2       # ternary
```

## Collections
```python
nums = [1, 2, 3]                 # list (mutable)
pair = (1, 2)                    # tuple (immutable)
uniq = {1, 2, 3}                 # set
d = {"a": 1}; d.get("b", 0)     # dict + default
squares = [n*n for n in nums if n > 1]      # comprehension
doubled = {k: v*2 for k, v in d.items()}
```

## Control flow
```python
for i in range(10): ...          # 0..9
while cond: ...
try:
    risky()
except ValueError as e:
    print(e)
finally:
    cleanup()
with open("f.txt") as fh:        # context manager (auto-close)
    data = fh.read()
```

## Functions & classes
```python
def f(*args, **kwargs): ...      # var args
g = lambda x: x + 1
class User:
    def __init__(self, name): self.name = name
    def __repr__(self): return f"User({self.name})"
from dataclasses import dataclass
@dataclass
class Point: x: int; y: int      # auto __init__/__eq__/__repr__
```

## Idioms
- Truthiness: empty list/dict/str/0/None are falsy. `if not items:` to check empty.
- Enumerate/zip: `for i, v in enumerate(xs)`, `for a, b in zip(xs, ys)`.
- Virtual env: `python -m venv .venv`; deps in requirements.txt; `pip install -r`.
- f-strings, pathlib.Path, and type hints are the modern style.
