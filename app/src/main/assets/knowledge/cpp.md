# C++ reference (modern, C++17/20)

## Basics
```cpp
#include <iostream>
#include <vector>
#include <string>
int add(int a, int b) { return a + b; }
int main() {
    auto name = std::string{"Jarvis"};   // type inference
    std::cout << "Hi " << name << "\n";
    return 0;
}
```

## Memory & RAII (no manual new/delete)
```cpp
#include <memory>
auto p = std::make_unique<int>(42);      // sole owner, auto-freed
auto sp = std::make_shared<int>(7);      // ref-counted
// RAII: resources released in destructors when scope ends. Prefer stack objects.
```

## Containers & algorithms
```cpp
std::vector<int> v{1, 2, 3};
v.push_back(4);
#include <algorithm>
std::sort(v.begin(), v.end());
auto it = std::find(v.begin(), v.end(), 2);
for (auto& x : v) x *= 2;                 // range-for by reference
```

## Classes
```cpp
class User {
public:
    User(long id, std::string name) : id_(id), name_(std::move(name)) {}
    const std::string& name() const { return name_; }
private:
    long id_;
    std::string name_;
};
```

## Idioms
- Pass big objects by `const&`; move with `std::move` to transfer ownership.
- Use smart pointers, not raw owning pointers. `nullptr`, not NULL.
- `const` correctness; `constexpr` for compile-time. Templates for generics.
- Build: CMake. Compile: `g++ -std=c++20 -Wall`.
