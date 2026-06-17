# TypeScript reference

TypeScript = JavaScript + static types (compiles to JS via `tsc`). All JS is valid TS.

## Types
```ts
let n: number = 1;
let s: string = "x";
let ok: boolean = true;
let xs: number[] = [1, 2];
let pair: [string, number] = ["a", 1];   // tuple
let any1: unknown;                        // safer than `any`
function add(a: number, b: number): number { return a + b; }
const f = (x: number): string => `${x}`;
```

## Interfaces & types
```ts
interface User { id: number; name: string; admin?: boolean }  // ? optional
type ID = string | number;                  // union
type Result<T> = { ok: true; value: T } | { ok: false; error: string }; // discriminated union
type Partial2<T> = { [K in keyof T]?: T[K] }; // mapped type
```

## Generics
```ts
function first<T>(xs: T[]): T | undefined { return xs[0]; }
class Box<T> { constructor(public value: T) {} }
```

## Narrowing
```ts
function area(s: Shape) {
  if (s.kind === "circle") return Math.PI * s.r ** 2;  // type guard via discriminant
  return s.w * s.h;
}
```

## Idioms
- Enable `"strict": true` in tsconfig.json — catches null/undefined bugs.
- Prefer `unknown` over `any`; use `as` casts sparingly.
- `readonly`, `const` assertions (`as const`), and utility types (Partial, Pick, Omit, Record).
