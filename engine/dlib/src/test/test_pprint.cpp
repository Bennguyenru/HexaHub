#include <stdint.h>
#include <stdio.h>
#define JC_TEST_IMPLEMENTATION
#include <jc_test/jc_test.h>
#include "../dlib/pprint.h"

TEST(dmPPrint, Init)
{
    char buf[1024];
    dmPPrint::Printer p(buf, sizeof(buf));
    ASSERT_STREQ("", buf);
}

TEST(dmPPrint, Simple)
{
    char buf[1024];
    dmPPrint::Printer p(buf, sizeof(buf));
    p.Printf("%d", 1234);
    p.Printf("%d", 5678);
    ASSERT_STREQ("12345678", buf);
}

TEST(dmPPrint, NewLine)
{
    char buf[1024];
    dmPPrint::Printer p(buf, sizeof(buf));
    p.Printf("%d\n", 10);
    p.Printf("%d\n", 20);
    ASSERT_STREQ("10\n20\n", buf);
}

TEST(dmPPrint, Indent)
{
    char buf[1024];
    dmPPrint::Printer p(buf, sizeof(buf));
    p.SetIndent(2);
    p.Printf("%d\n", 10);
    p.Printf("%d\n", 20);
    ASSERT_STREQ("  10\n  20\n", buf);
}

TEST(dmPPrint, Truncate1)
{
    char buf[2] = { (char)0xff, (char)0xff };
    dmPPrint::Printer p(buf, sizeof(buf) - 1);
    p.Printf("%d", 1234);
    ASSERT_STREQ("", buf);
    ASSERT_EQ((char) 0xff, buf[1]);
}

TEST(dmPPrint, Truncate2)
{
    char buf[3] = { (char)0xff, (char)0xff, (char)0xff };
    dmPPrint::Printer p(buf, sizeof(buf) - 1);
    p.Printf("%d", 1234);
    ASSERT_STREQ("1", buf);
    ASSERT_EQ((char) 0xff, buf[2]);
}

TEST(dmPPrint, Truncate3)
{
    char buf[3] = { (char)0xff, (char)0xff, (char)0xff };
    dmPPrint::Printer p(buf, sizeof(buf) - 1);
    p.SetIndent(1);
    p.Printf("%d", 1234);
    ASSERT_STREQ(" ", buf);
    ASSERT_EQ((char) 0xff, buf[2]);
}

int main(int argc, char **argv)
{
    jc_test_init(&argc, argv);
    return JC_TEST_RUN_ALL();
}
