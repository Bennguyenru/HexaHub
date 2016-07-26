#ifndef DM_TIME_H
#define DM_TIME_H

#include <stdint.h>


namespace dmTime
{
    /**
     * Sleep thread with low precision (~10 milliseconds).
     * @param useconds Time to sleep in microseconds
     */
    void Sleep(uint32_t useconds);

    /**
     * Get current time in microseconds since since Jan. 1, 1970.
     * @return
     */
    uint64_t GetTime();

    /**
     * Busy wait thread with high precision (~10 microseconds).
     * NOTE This function currently has low precision, see DEF-2013
     * @param useconds Time to wait in microseconds
     */
    inline void BusyWait(uint32_t useconds) {
        uint64_t end = dmTime::GetTime() + (uint64_t)useconds;
        while (dmTime::GetTime() < end);
    }
}

#endif // DM_TIME_H
