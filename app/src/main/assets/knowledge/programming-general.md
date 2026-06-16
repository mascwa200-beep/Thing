# General programming concepts

## Big-O complexity (time/space, worst case)
- O(1) constant; O(log n) binary search; O(n) linear scan; O(n log n) good sorts (merge/heap/quick avg);
  O(n^2) nested loops; O(2^n) / O(n!) brute-force — avoid for large n.

## Core data structures
- Array/List: O(1) index, O(n) insert-middle. Hash map/set: ~O(1) average lookup/insert.
- Stack (LIFO), Queue (FIFO), Deque. Tree/BST: O(log n) balanced. Heap: O(log n) push/pop, O(1) peek.
- Graph: adjacency list; traverse with BFS (shortest hops) or DFS (recursion/stack).

## Paradigms
- Imperative/OOP: state + objects (encapsulation, inheritance, polymorphism).
- Functional: pure functions, immutability, map/filter/reduce, no side effects.
- Declarative: say what, not how (SQL, HTML, regex).

## Good practice
- DRY (don't repeat yourself), single responsibility, small functions, clear names.
- Handle errors explicitly; validate inputs at boundaries; fail fast with clear messages.
- Write tests (unit for logic, integration for wiring). Prefer pure functions — easy to test.
- Measure before optimizing; correctness first, then performance.

## Concurrency basics
- Race condition: two threads touch shared state unsynchronized. Use locks/atomics/immutability.
- Deadlock: circular waiting on locks — always acquire locks in a consistent order.
- Prefer message passing / structured concurrency (coroutines, async/await) over raw threads.
