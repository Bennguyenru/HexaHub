#include "dns.h"
#include "socket.h"

namespace dmDNS
{
    static Result SocketResultToDNS(const dmSocket::Result res)
    {
        Result dns_res = dmDNS::RESULT_UNKNOWN_ERROR;
        switch(res)
        {
            case(dmSocket::RESULT_OK):
                dns_res = dmDNS::RESULT_OK;
                break;
            case(dmSocket::RESULT_HOST_NOT_FOUND):
                dns_res = dmDNS::RESULT_HOST_NOT_FOUND;
                break;
        }

        return dns_res;
    }

    Result Initialize() { return RESULT_OK; }
    Result Finalize() { return RESULT_OK; }
    Result NewChannel(HChannel* channel) { return RESULT_OK; }
    void   StopChannel(HChannel channel) {}
    void   DeleteChannel(HChannel channel) {}
    Result GetHostByName(const char* name, dmSocket::Address* address, HChannel channel, bool ipv4, bool ipv6)
    {
        return SocketResultToDNS(dmSocket::GetHostByName(name, address, ipv4, ipv6));
    }
}
