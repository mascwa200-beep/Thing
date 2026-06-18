# Swift reference

## Basics
```swift
let immutable = 1            // constant
var mutable = 2             // variable
func add(_ a: Int, _ b: Int) -> Int { a + b }
let name = "Jarvis"
print("Hi \(name)")        // string interpolation
```

## Optionals (no null)
```swift
var s: String? = nil
let len = s?.count ?? 0     // optional chaining + nil-coalescing
if let v = s { use(v) }     // optional binding
guard let v = s else { return }  // early exit
```

## Types
```swift
struct User { let id: Int; var name: String }   // value type
class Animal { var legs = 4 }                    // reference type
enum Direction { case north, south }
protocol Greeter { func greet() -> String }      // like interface
```

## Collections
```swift
let xs = [1, 2, 3]
xs.map { $0 * 2 }.filter { $0 > 2 }.reduce(0, +)
var dict = ["a": 1]; dict["b", default: 0]
for x in xs { }
```

## Idioms
- Prefer `struct` (value semantics) over `class` unless you need identity/inheritance.
- Use `guard` for preconditions; handle errors with `do/try/catch` and `throws`.
- SwiftUI for modern UI: `@State`, `@Binding`, declarative `View` structs.
- Closures: `{ params in body }`; trailing closure syntax is idiomatic.
