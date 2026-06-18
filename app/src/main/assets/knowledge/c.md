# C reference

## Basics
```c
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int add(int a, int b) { return a + b; }

int main(void) {
    const char *name = "Jarvis";
    printf("Hi %s\n", name);
    return 0;
}
```

## Types & pointers
```c
int x = 1;
int *p = &x;        // p holds address of x
*p = 2;             // dereference: x is now 2
int arr[3] = {1, 2, 3};   // arrays decay to pointers
char str[] = "hi";        // null-terminated
```

## Manual memory (you free what you allocate)
```c
int *buf = malloc(n * sizeof(int));
if (!buf) return 1;       // always check
// ... use buf ...
free(buf);                // avoid leaks; don't use after free
```

## Control & functions
```c
for (int i = 0; i < n; i++) {}
while (cond) {}
struct User { long id; char name[32]; };
typedef struct User User;
```

## Idioms / pitfalls
- Strings: use strncpy/snprintf to avoid buffer overflows; never gets().
- Check every malloc; free exactly once; init pointers to NULL.
- Undefined behavior: out-of-bounds, use-after-free, signed overflow — leads to crashes/security bugs.
- Compile with warnings: `gcc -std=c17 -Wall -Wextra`. Use valgrind to find leaks.
