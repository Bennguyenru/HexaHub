#include "time.h"

#ifdef _WIN32
#include "safe_windows.h"
#include <time.h>
#else
#include <sys/time.h>
#include <unistd.h>
#endif

namespace dmTime
{
    void Sleep(uint32_t useconds)
    {
    #if defined(__linux__) || defined(__MACH__) || defined(__EMSCRIPTEN__) || defined(__AVM2__)
        usleep(useconds);
    #elif defined(_WIN32)
        ::Sleep(useconds / 1000);
    #else
    #error "Unsupported platform"
    #endif
    }

    uint64_t GetTime()
    {
#if defined(_WIN32)

#if defined(_MSC_VER) || defined(_MSC_EXTENSIONS)
  #define DELTA_EPOCH_IN_MICROSECS  11644473600000000Ui64
#else
  #define DELTA_EPOCH_IN_MICROSECS  11644473600000000ULL
#endif
        uint64_t t;
        uint64_t f;
        QueryPerformanceFrequency((LARGE_INTEGER*) &f);
        f = f / 1000000;
        QueryPerformanceCounter((LARGE_INTEGER*) &t);
        return t / f;
#else
        timeval tv;
        gettimeofday(&tv, 0);
        return ((uint64_t) tv.tv_sec) * 1000000U + tv.tv_usec;
#endif
    }
}
