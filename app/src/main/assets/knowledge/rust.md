# Rust reference

## Basics
```rust
let x = 1;              // immutable by default
let mut y = 2;          // mutable
fn add(a: i32, b: i32) -> i32 { a + b }   // last expr is the return
let name = "Jarvis";
println!("Hi {name}");
```

## Ownership & borrowing (the core idea)
```rust
let s = String::from("hi");
let s2 = s;             // move: s is no longer valid
let r = &s2;            // immutable borrow (many allowed)
let m = &mut y;         // mutable borrow (exactly one, no other borrows)
// The borrow checker enforces this at compile time — no GC, no use-after-free.
```

## Enums, Option, Result (no null, no exceptions)
```rust
let maybe: Option<i32> = Some(3);
match maybe { Some(n) => n, None => 0 };
let r: Result<i32, String> = Ok(5);
let v = r?;             // ? propagates the error
let len = maybe.unwrap_or(0);
```

## Structs & traits
```rust
struct User { id: u64, name: String }
impl User { fn greet(&self) -> String { format!("Hi {}", self.name) } }
trait Greet { fn hello(&self) -> String; }   // like an interface
#[derive(Debug, Clone)]
struct Point { x: i32, y: i32 }
```

## Collections & iterators
```rust
let v = vec![1, 2, 3];
let sum: i32 = v.iter().map(|n| n * 2).filter(|n| *n > 2).sum();
for n in &v { }
```

## Idioms
- Prefer `Result`/`Option` over panics; use `?` to bubble errors.
- `cargo new`, `cargo build`, `cargo test`. Deps in Cargo.toml.
- Clone only when needed; borrow otherwise. Lifetimes describe how long references are valid.
