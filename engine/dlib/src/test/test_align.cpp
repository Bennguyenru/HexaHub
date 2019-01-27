#include <stdint.h>
#include <stdio.h>
#define JC_TEST_IMPLEMENTATION
#include <jc/test.h>
#include "../dlib/align.h"

struct AlignStruct
{
    char DM_ALIGNED(128) x;
};

char DM_ALIGNED(256) g_Array[] = "foobar";

TEST(dmAlign, Alignment)
{
    ASSERT_EQ(128U, sizeof(AlignStruct));
    ASSERT_EQ(0U, ((uintptr_t) &g_Array[0]) & 255U);
}

TEST(dmAlign, Align)
{
    void* p = (void*) 0xaabb7;

    p = (void*) DM_ALIGN(p, 16);

    ASSERT_EQ(0xaabc0U, (uintptr_t) p);
}

int main(int argc, char **argv)
{
    jc_test_init(&argc, argv);
    return JC_TEST_RUN_ALL();
}
