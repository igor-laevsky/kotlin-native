/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifdef KONAN_ANDROID
#include <android/log.h>
#endif
#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#if !KONAN_NO_THREADS
#include <pthread.h>
#endif
#include <unistd.h>
#if KONAN_WINDOWS
#include <windows.h>
#endif

#include <chrono>

#include "Common.h"
#include "Porting.h"

#if KONAN_WASM || KONAN_ZEPHYR
extern "C" RUNTIME_NORETURN void Konan_abort(const char*);
extern "C" RUNTIME_NORETURN void Konan_exit(int32_t status);
#endif
#ifdef KONAN_ZEPHYR
// In Zephyr's Newlib strnlen(3) is not included from string.h by default.
extern "C" size_t strnlen(const char* buffer, size_t maxSize);
#endif

namespace konan {

// Console operations.
void consoleInit() {
#if KONAN_WINDOWS
  // Note that this code enforces UTF-8 console output, so we may want to rethink
  // how we perform console IO, if it turns out, that UTF-16 is better output format.
  ::SetConsoleCP(CP_UTF8);
  ::SetConsoleOutputCP(CP_UTF8);
#endif
}

void consoleWriteUtf8(const void* utf8, uint32_t sizeBytes) {
#ifdef KONAN_ANDROID
  // TODO: use sizeBytes!
  __android_log_print(ANDROID_LOG_INFO, "Konan_main", "%s", utf8);
#else
  ::write(STDOUT_FILENO, utf8, sizeBytes);
#endif
}

void consoleErrorUtf8(const void* utf8, uint32_t sizeBytes) {
#ifdef KONAN_ANDROID
  // TODO: use sizeBytes!
  __android_log_print(ANDROID_LOG_ERROR, "Konan_main", "%s", utf8);
#else
  ::write(STDERR_FILENO, utf8, sizeBytes);
#endif
}

int32_t consoleReadUtf8(void* utf8, uint32_t maxSizeBytes) {
#ifdef KONAN_ZEPHYR
  return 0;
#else
#ifdef KONAN_WASM
  FILE* file = nullptr;
#else
  FILE* file = stdin;
#endif
  char* result = ::fgets(reinterpret_cast<char*>(utf8), maxSizeBytes - 1, file);
  if (result == nullptr) return -1;
  int32_t length = ::strlen(result);
  // fgets reads until EOF or newline so we need to remove linefeeds.
  char* current = result + length - 1;
  bool isTrimming = true;
  while (current >= result && isTrimming) {
    switch (*current) {
      case '\n':
      case '\r':
        *current = 0;
        length--;
        break;
      default:
        isTrimming = false;
    }
    current--;
  }
  return length;
#endif
}

#if KONAN_INTERNAL_SNPRINTF
extern "C" int rpl_vsnprintf(char *, size_t, const char *, va_list);
#define vsnprintf_impl rpl_vsnprintf
#else
#define vsnprintf_impl ::vsnprintf
#endif

void consolePrintf(const char* format, ...) {
  char buffer[1024];
  va_list args;
  va_start(args, format);
  int rv = vsnprintf_impl(buffer, sizeof(buffer) - 1, format, args);
  va_end(args);
  consoleWriteUtf8(buffer, rv);
}

// Thread execution.
#if !KONAN_NO_THREADS

pthread_key_t terminationKey;
pthread_once_t terminationKeyOnceControl =  PTHREAD_ONCE_INIT;

typedef void (*destructor_t)(void*);

struct DestructorRecord {
  struct DestructorRecord* next;
  destructor_t destructor;
  void* destructorParameter;
};

static void onThreadExitCallback(void* value) {
  DestructorRecord* record = reinterpret_cast<DestructorRecord*>(value);
  while (record != nullptr) {
    record->destructor(record->destructorParameter);
    auto next = record->next;
    free(record);
    record = next;
  }
  pthread_setspecific(terminationKey, nullptr);
}

static void onThreadExitInit() {
  pthread_key_create(&terminationKey, onThreadExitCallback);
}

#endif  // !KONAN_NO_THREADS

void onThreadExit(void (*destructor)(void*), void* destructorParameter) {
#if KONAN_NO_THREADS
#if KONAN_WASM || KONAN_ZEPHYR
  // No way to do that.
#else
#error "How to do onThreadExit()?"
#endif
#else  // !KONAN_NO_THREADS
  // We cannot use pthread_cleanup_push() as it is lexical scope bound.
  pthread_once(&terminationKeyOnceControl, onThreadExitInit);
  DestructorRecord* destructorRecord = (DestructorRecord*)calloc(1, sizeof(DestructorRecord));
  destructorRecord->destructor = destructor;
  destructorRecord->destructorParameter = destructorParameter;
  destructorRecord->next =
      reinterpret_cast<DestructorRecord*>(pthread_getspecific(terminationKey));
  pthread_setspecific(terminationKey, destructorRecord);
#endif  // !KONAN_NO_THREADS
}

// Process execution.
void abort(void) {
  ::abort();
}

#if KONAN_WASM || KONAN_ZEPHYR
void exit(int32_t status) {
  Konan_exit(status);
}
#else
void exit(int32_t status) {
  ::exit(status);
}
#endif

// String/byte operations.
// memcpy/memmove are not here intentionally, as frequently implemented/optimized
// by C compiler.
void* memmem(const void *big, size_t bigLen, const void *little, size_t littleLen) {
#if KONAN_NO_MEMMEM
  for (size_t i = 0; i + littleLen <= bigLen; ++i) {
    void* pos = ((char*)big) + i;
    if (::memcmp(little, pos, littleLen) == 0) return pos;
  }
  return nullptr;
#else
  return ::memmem(big, bigLen, little, littleLen);
#endif

}

// The sprintf family.
int snprintf(char* buffer, size_t size, const char* format, ...) {
  va_list args;
  va_start(args, format);
  int rv = vsnprintf_impl(buffer, size, format, args);
  va_end(args);
  return rv;
}

size_t strnlen(const char* buffer, size_t maxSize) {
  return ::strnlen(buffer, maxSize);
}

// Memory operations.
#if KONAN_INTERNAL_DLMALLOC
extern "C" void* dlcalloc(size_t, size_t);
extern "C" void dlfree(void*);
#define calloc_impl dlcalloc
#define free_impl dlfree
#else
#define calloc_impl ::calloc
#define free_impl ::free
#endif

void* calloc(size_t count, size_t size) {
  return calloc_impl(count, size);
}

void free(void* pointer) {
  free_impl(pointer);
}

#if KONAN_INTERNAL_NOW

#ifdef KONAN_ZEPHYR
void Konan_date_now(uint64_t* arg) {
    // TODO: so how will we support time for embedded?
    *arg = 0LL;
}
#else
extern "C" void Konan_date_now(uint64_t*);
#endif

uint64_t getTimeMillis() {
    uint64_t now;
    Konan_date_now(&now);
    return now;
}

uint64_t getTimeMicros() {
    return getTimeMillis() * 1000ULL;
}

uint64_t getTimeNanos() {
    return getTimeMillis() * 1000000ULL;
}

#else
// Time operations.
using namespace std::chrono;

uint64_t getTimeMillis() {
  return duration_cast<milliseconds>(high_resolution_clock::now().time_since_epoch()).count();
}

uint64_t getTimeNanos() {
  return duration_cast<nanoseconds>(high_resolution_clock::now().time_since_epoch()).count();
}

uint64_t getTimeMicros() {
  return duration_cast<microseconds>(high_resolution_clock::now().time_since_epoch()).count();
}
#endif

#if KONAN_INTERNAL_DLMALLOC
// This function is being called when memory allocator needs more RAM.

#if KONAN_WASM

namespace {

constexpr uint32_t MFAIL = ~(uint32_t)0;
constexpr uint32_t WASM_PAGESIZE_EXPONENT = 16;
constexpr uint32_t WASM_PAGESIZE = 1u << WASM_PAGESIZE_EXPONENT;
constexpr uint32_t WASM_PAGEMASK = WASM_PAGESIZE-1;

uint32_t pageAlign(int32_t value) {
  return (value + WASM_PAGEMASK) & ~ (WASM_PAGEMASK);
}

uint32_t inBytes(uint32_t pageCount) {
  return pageCount << WASM_PAGESIZE_EXPONENT;
}

uint32_t inPages(uint32_t value) {
  return value >> WASM_PAGESIZE_EXPONENT;
}

extern "C" void Konan_notify_memory_grow();

uint32_t memorySize() {
  return __builtin_wasm_memory_size(0);
}

int32_t growMemory(uint32_t delta) {
  int32_t oldLength =  __builtin_wasm_memory_grow(0, delta);
  Konan_notify_memory_grow();
  return oldLength;
}

}

void* moreCore(int32_t delta) {
  uint32_t top = inBytes(memorySize());
  if (delta > 0) {
    if (growMemory(inPages(pageAlign(delta))) == 0) {
      return (void *) MFAIL;
    }
  } else if (delta < 0) {
    return (void *) MFAIL;
  }
  return (void *) top;
}

// dlmalloc() wants to know the page size.
long getpagesize() {
    return WASM_PAGESIZE;
}

#else
void* moreCore(int size) {
    return sbrk(size);
}

long getpagesize() {
    return sysconf(_SC_PAGESIZE);
}
#endif
#endif

}  // namespace konan

extern "C" {
// TODO: get rid of these.
#if (KONAN_WASM || KONAN_ZEPHYR)
    void _ZNKSt3__120__vector_base_commonILb1EE20__throw_length_errorEv(void) {
        Konan_abort("TODO: throw_length_error not implemented.");
    }
    void _ZNKSt3__220__vector_base_commonILb1EE20__throw_length_errorEv(void) {
        Konan_abort("TODO: throw_length_error not implemented.");
    }
    void _ZNKSt3__121__basic_string_commonILb1EE20__throw_length_errorEv(void) {
        Konan_abort("TODO: throw_length_error not implemented.");
    }
    void _ZNKSt3__221__basic_string_commonILb1EE20__throw_length_errorEv(void) {
        Konan_abort("TODO: throw_length_error not implemented.");
    }
    int _ZNSt3__212__next_primeEj(unsigned long n) {
        static unsigned long primes[] = {
                11UL,
                101UL,
                1009UL,
                10007UL,
                100003UL,
                1000003UL,
                10000019UL,
                100000007UL,
                1000000007UL
        };
        int table_length = sizeof(primes)/sizeof(unsigned long);

        if (n > primes[table_length - 1]) konan::abort();

        unsigned long prime = primes[0];
        for (unsigned long i=0; i< table_length; i++) {
            prime = primes[i];
            if (prime >= n) break;
        }
        return prime;
    }

    int _ZNSt3__212__next_primeEm(int n) {
       return _ZNSt3__212__next_primeEj(n);
    }

    int _ZNSt3__112__next_primeEj(unsigned long n) {
        return _ZNSt3__212__next_primeEj(n);
    }
    void __assert_fail(const char * assertion, const char * file, unsigned int line, const char * function) {
        char buf[1024];
        konan::snprintf(buf, sizeof(buf), "%s:%d in %s: runtime assert: %s\n", file, line, function, assertion);
        Konan_abort(buf);
    }
    int* __errno_location() {
        static int theErrno = 0;
        return &theErrno;
    }

    // Some math.h functions.

    double pow(double x, double y) {
        return __builtin_pow(x, y);
    }
#endif

#ifdef KONAN_WASM
    // Some string.h functions.
    void *memcpy(void *dst, const void *src, size_t n) {
        for (long i = 0; i != n; ++i)
            *((char*)dst + i) = *((char*)src + i);
        return dst;
    }

    void *memmove(void *dst, const void *src, size_t len)  {
        if (src < dst) {
            for (long i = len; i != 0; --i) {
                *((char*)dst + i - 1) = *((char*)src + i - 1);
            }
        } else {
            memcpy(dst, src, len);
        }
        return dst;
    }

    int memcmp(const void *s1, const void *s2, size_t n) {
        for (long i = 0; i != n; ++i) {
            if (*((char*)s1 + i) != *((char*)s2 + i)) {
                return *((char*)s1 + i) - *((char*)s2 + i);
            }
        }
        return 0;
    }

    void *memset(void *b, int c, size_t len) {
        for (long i = 0; i != len; ++i) {
            *((char*)b + i) = c;
        }
        return b;
    }

    size_t strlen(const char *s) {
        for (long i = 0;; ++i) {
            if (s[i] == 0) return i;
        }
    }

    size_t strnlen(const char *s, size_t maxlen) {
        for (long i = 0; i<=maxlen; ++i) {
            if (s[i] == 0) return i;
        }
        return maxlen;
    }
#endif

#ifdef KONAN_ZEPHYR
    RUNTIME_USED void Konan_abort(const char*) {
        while(1) {}
    }
#endif // KONAN_ZEPHYR

}  // extern "C"
