# Kotlin reference

## Basics
```kotlin
val immutable = 1          // read-only
var mutable = 2            // reassignable
val name: String = "Jarvis" // explicit type optional
fun add(a: Int, b: Int): Int = a + b      // expression body
fun greet(who: String = "world") = "Hi $who"  // default arg + string template
```

## Null safety
```kotlin
var s: String? = null      // nullable type
val len = s?.length ?: 0   // safe call + Elvis (default)
s!!.length                 // non-null assertion (throws if null) — avoid
val x = s ?: return        // early return on null
```

## Collections
```kotlin
val list = listOf(1, 2, 3)             // immutable
val m = mutableListOf<Int>().apply { add(1) }
list.map { it * 2 }.filter { it > 2 }.sum()
val map = mapOf("a" to 1); map["a"]
list.firstOrNull { it > 1 }            // null if none
```

## Classes
```kotlin
data class User(val id: Long, val name: String)   // equals/hashCode/copy/toString free
sealed interface Result { data class Ok(val v: Int): Result; data object Err: Result }
class Counter(private var n: Int = 0) { fun inc() { n++ } }
object Singleton                                   // one instance
enum class Color { RED, GREEN }
```

## when / control flow
```kotlin
val label = when (x) { 0 -> "zero"; in 1..9 -> "digit"; else -> "big" }
for (i in 0 until 10) {}        // 0..9
repeat(3) { println(it) }
```

## Coroutines (structured concurrency)
```kotlin
suspend fun load(): Int { delay(100); return 42 }
viewModelScope.launch { val v = load() }        // launch fire-and-forget
val a = async { load() }; a.await()             // parallel
flow { emit(1) }.collect { }                    // cold stream
```

## Scope functions
let (transform/null), apply (configure, returns receiver), run, also (side effect), with.
