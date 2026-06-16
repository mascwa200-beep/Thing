# Java reference

## Basics
```java
int x = 1;                       // primitive
final String NAME = "Jarvis";    // constant
var list = new ArrayList<String>(); // local type inference (Java 10+)
int add(int a, int b) { return a + b; }
String msg = "Hi " + name;
```

## Classes
```java
public class User {
    private final long id;
    private String name;
    public User(long id, String name) { this.id = id; this.name = name; }
    public String getName() { return name; }
}
public record Point(int x, int y) {}        // immutable data class (Java 16+)
interface Greeter { String greet(); }       // default methods allowed
enum Color { RED, GREEN }
```

## Collections (java.util)
```java
List<Integer> nums = List.of(1, 2, 3);      // immutable
Map<String, Integer> m = new HashMap<>(); m.put("a", 1); m.getOrDefault("b", 0);
Set<Integer> s = new HashSet<>();
```

## Streams (functional)
```java
int sum = nums.stream().map(n -> n * 2).filter(n -> n > 2).reduce(0, Integer::sum);
List<String> names = users.stream().map(User::getName).toList();
```

## Control & errors
```java
for (int i = 0; i < 10; i++) {}
for (var n : nums) {}
try { risky(); } catch (IOException e) { e.printStackTrace(); } finally { cleanup(); }
Optional<User> u = find(id); u.map(User::getName).orElse("none");  // avoid null
```

## Idioms
- `equals()`/`hashCode()` together; use records for value types.
- Use `Optional` instead of returning null. Prefer interfaces (List) over impls (ArrayList) in types.
- Build tools: Maven (pom.xml) or Gradle.
