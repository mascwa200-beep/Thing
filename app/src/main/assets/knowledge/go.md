# Go (Golang) reference

## Basics
```go
package main
import "fmt"

func add(a, b int) int { return a + b }

func main() {
    name := "Jarvis"        // := infers type
    fmt.Printf("Hi %s\n", name)
}
```

## Types & structs
```go
var x int = 1
type User struct { ID int64; Name string }
u := User{ID: 1, Name: "A"}
type Greeter interface { Greet() string }   // implicit implementation
```

## Errors (explicit, no exceptions)
```go
func load() (int, error) {
    v, err := doThing()
    if err != nil {
        return 0, fmt.Errorf("load: %w", err)  // wrap with context
    }
    return v, nil
}
```

## Collections
```go
xs := []int{1, 2, 3}          // slice
xs = append(xs, 4)
m := map[string]int{"a": 1}
v, ok := m["a"]               // ok = found?
for i, v := range xs { }
```

## Concurrency (goroutines + channels)
```go
ch := make(chan int)
go func() { ch <- 42 }()      // goroutine
val := <-ch                   // receive
// "Don't communicate by sharing memory; share memory by communicating."
```

## Idioms
- Return `(value, error)`; check `if err != nil`. Use `defer` for cleanup (e.g. `defer f.Close()`).
- gofmt formats code; `go build`, `go test`, modules via go.mod.
- Keep it simple; small interfaces; zero values are usable.
