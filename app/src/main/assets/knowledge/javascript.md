# JavaScript reference

## Basics
```js
const k = 1;          // block-scoped, no reassign
let v = 2;            // block-scoped, reassignable
// avoid var (function-scoped, hoisted)
const add = (a, b) => a + b;            // arrow function
const msg = `Hi ${name}`;               // template literal
const x = cond ? 1 : 2;
```

## Equality & types
- Use `===` / `!==` (strict, no coercion); avoid `==`.
- Types: number, string, boolean, null, undefined, object, symbol, bigint.
- `a ?? b` nullish coalescing (b if a is null/undefined); `a?.b` optional chaining.

## Arrays & objects
```js
const xs = [1, 2, 3];
xs.map(n => n * 2).filter(n => n > 2).reduce((s, n) => s + n, 0);
const { a, b } = obj;          // destructuring
const merged = { ...obj, c: 3 }; // spread
const copy = [...xs];
```

## Async
```js
async function load() {
  try {
    const res = await fetch(url);
    return await res.json();
  } catch (e) { console.error(e); }
}
Promise.all([p1, p2]).then(([r1, r2]) => {});
```

## Functions & classes
```js
function f(...args) {}                  // rest params
class User { constructor(name) { this.name = name; } greet() { return `Hi ${this.name}`; } }
```

## Idioms
- Modules: `import { x } from './m.js'`; `export const y = ...`.
- Falsy: 0, "", null, undefined, NaN, false. `arr.length ? ... : ...` to check non-empty.
- Node.js runs JS server-side; npm/package.json manages deps.
