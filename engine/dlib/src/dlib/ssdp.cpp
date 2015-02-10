#include <stdio.h>
#include <stdlib.h>
#include <ctype.h>
#include "ssdp.h"

#include "array.h"
#include "dstrings.h"
#include "socket.h"
#include "hash.h"
#include "hashtable.h"
#include "time.h"
#include "log.h"
#include "template.h"
#include "http_server_private.h"
#include "http_client_private.h"
#include "http_server.h"

namespace dmSSDP
{
    const char * SSDP_MCAST_ADDR = "239.255.255.250";
    const uint16_t SSDP_MCAST_PORT = 1900U;
    const uint32_t SSDP_MCAST_TTL = 4U;

    static const char* SSDP_ALIVE_TMPL =
        "NOTIFY * HTTP/1.1\r\n"
        "SERVER: Defold SSDP 1.0\r\n"
        "CACHE-CONTROL: max-age=${MAX_AGE}\r\n"
        "HOST: 239.255.255.250:1900\r\n"
        "LOCATION: http://${HOSTNAME}:${HTTPPORT}/${ID}\r\n"
        "NTS: ssdp:alive\r\n"
        "NT: ${NT}\r\n"
        "USN: ${UDN}::${DEVICE_TYPE}\r\n\r\n";

    static const char* SSDP_BYEBYE_TMPL =
        "NOTIFY * HTTP/1.1\r\n"
        "SERVER: Defold SSDP 1.0\r\n"
        "HOST: 239.255.255.250:1900\r\n"
        "NTS: ssdp:byebye\r\n"
        "NT: ${NT}\r\n"
        "USN: ${UDN}::${DEVICE_TYPE}\r\n\r\n";

    /*
     * NOTE: We skip the following recommended headers (no time/data api in dlib)
     * - DATE
     */
    static const char* SEARCH_RESULT_FMT =
        "HTTP/1.1 200 OK\r\n"
        "SERVER: Defold SSDP 1.0\r\n"
        "CACHE-CONTROL: max-age=${MAX_AGE}\r\n"
        "LOCATION: http://${HOSTNAME}:${HTTPPORT}/${ID}\r\n"
        "ST: ${ST}\r\n"
        "EXT:\r\n"
        "USN: ${UDN}::${DEVICE_TYPE}\r\n"
        "Content-Length: 0\r\n\r\n";

    static const char * M_SEARCH_FMT =
        "M-SEARCH * HTTP/1.1\r\n"
        "SERVER: Defold SSDP 1.0\r\n"
        "HOST: 239.255.255.250:1900\r\n"
        "MAN: \"ssdp:discover\"\r\n"
        "MX: 3\r\n"
        "ST: upnp:rootdevice\r\n\r\n";

    const uint32_t SSDP_LOCAL_ADDRESS_EXPIRATION = 4U;

    struct Device
    {
        // Only available for registered devices
        const DeviceDesc*   m_DeviceDesc;

        // Time when the device expires
        // For registered devices: a notification should be sent
        // For discovered devices: the device should be removed
        uint64_t            m_Expires;

        Device()
        {
            memset(this, 0, sizeof(*this));
        }

        Device(const DeviceDesc* device_desc)
        {
            memset(this, 0, sizeof(*this));
            m_DeviceDesc = device_desc;
            // NOTE: We set expires to such that announce messages
            // will be sent in one second (if enabled)
            m_Expires = dmTime::GetTime();
        }
    };

    struct SSDP
    {
        SSDP()
        {
            memset(this, 0, sizeof(*this));
            m_DiscoveredDevices.SetCapacity(983, 1024);
            m_RegistredEntries.SetCapacity(17, 32);
            m_Socket = dmSocket::INVALID_SOCKET_HANDLE;
            m_MCastSocket = dmSocket::INVALID_SOCKET_HANDLE;
        }

        // Max age for registered devices
        uint32_t                m_MaxAge;
        char                    m_MaxAgeText[16];

        // True if announce messages should be sent
        uint32_t                m_Announce : 1;

        // True if reconnection should be performed in next update
        uint32_t                m_Reconnect : 1;

        // Send/Receive buffer
        uint8_t                 m_Buffer[1500];

        // All discovered devices
        dmHashTable64<Device>   m_DiscoveredDevices;

        // All registered devices
        dmHashTable64<Device*>  m_RegistredEntries;

        // Socket for unicast send/receive and for multicast send
        dmSocket::Socket        m_Socket;
        // Port for m_Socket
        uint16_t                m_Port;

        // Socket for multicast receive
        dmSocket::Socket        m_MCastSocket;

        // Hostname (local) in ip-format (x.y.z.w)
        char                    m_Hostname[32];
        // Local IP Address
        dmSocket::Address       m_Address;
        uint64_t                m_AddressExpires;

        // Http server for device descriptions
        dmHttpServer::HServer   m_HttpServer;
        char                    m_HttpPortText[8];
    };

    struct Replacer
    {
        Replacer*                   m_Parent;
        void*                       m_Userdata;
        dmTemplate::ReplaceCallback m_Callback;

        Replacer(Replacer* parent, void* user_data, dmTemplate::ReplaceCallback call_back)
        {
            m_Parent = parent;
            m_Userdata = user_data;
            m_Callback = call_back;
        }

        static const char* Replace(void* user_data, const char* key);
    };

    const char* Replacer::Replace(void* user_data, const char* key)
    {
        Replacer* self = (Replacer*) user_data;
        const char* value = self->m_Callback(self->m_Userdata, key);
        if (value)
            return value;
        else if (self->m_Parent)
            return Replacer::Replace(self->m_Parent, key);
        else
            return 0;
    }

    struct ReplaceContext
    {
        SSDP*   m_SSDP;
        Device* m_Device;

        ReplaceContext(SSDP* ssdp, Device* device)
        {
            m_SSDP = ssdp;
            m_Device = device;
        }
    };

    enum RequestType
    {
        RT_UNKNOWN    = 0,
        RT_NOTIFY     = 1,
        RT_M_SEARCH   = 2,
    };

    struct RequestParseState
    {
        SSDP*                       m_SSDP;
        // Parsed max-age
        uint32_t                    m_MaxAge;

        // Request-type, ie NOTIFY or M-SEARCH
        RequestType                 m_RequestType;

        // All headers
        dmHashTable64<const char*>  m_Headers;

        // HTTP status
        int                         m_Status;

        // Notification Type (NT)
        dmhash_t                    m_NTHash;
        // Notification Sub Type (NTS)
        dmhash_t                    m_NTSHash;

        RequestParseState(SSDP* ssdp)
        {
            memset(this, 0, sizeof(*this));
            m_SSDP = ssdp;
            m_Headers.SetCapacity(27, 64);
            // Default max-age if none is found
            m_MaxAge = 1800;
        }

        static void FreeCallback(RequestParseState *state, const dmhash_t* key, const char** value);

        ~RequestParseState()
        {
            m_Headers.Iterate(FreeCallback, this);
        }
    };

    struct SearchResponseContext
    {
        RequestParseState*  m_State;
        const char*         m_ST;
        dmSocket::Address   m_FromAddress;
        uint16_t            m_FromPort;

        SearchResponseContext(RequestParseState* state, const char* st, dmSocket::Address from_address, uint16_t from_port)
        {
            m_State = state;
            m_ST = st;
            m_FromAddress = from_address;
            m_FromPort = from_port;
        }
    };

    static const char* ReplaceSSDPVar(void* user_data, const char* key)
    {
        SSDP* ssdp = (SSDP*) user_data;

        if (strcmp(key, "HOSTNAME") == 0)
        {
            return ssdp->m_Hostname;
        }
        else if (strcmp(key, "HTTPPORT") == 0)
        {
            return ssdp->m_HttpPortText;
        }
        else if (strcmp(key, "MAX_AGE") == 0)
        {
            return ssdp->m_MaxAgeText;
        }
        return 0;
    }

    static const char* ReplaceDeviceVar(void* user_data, const char* key)
    {
        dmSSDP::Device *device = (dmSSDP::Device*) user_data;

        if (strcmp(key, "UDN") == 0)
        {
            return device->m_DeviceDesc->m_UDN;
        }
        else if (strcmp(key, "NT") == 0)
        {
            return device->m_DeviceDesc->m_DeviceType;
        }
        else if (strcmp(key, "DEVICE_TYPE") == 0)
        {
            return device->m_DeviceDesc->m_DeviceType;
        }
        else if (strcmp(key, "ID") == 0)
        {
            return device->m_DeviceDesc->m_Id;
        }

        return 0;
    }

    static const char* ReplaceSearchResponseVar(void* user_data, const char* key)
    {
        SearchResponseContext* context = (SearchResponseContext*) user_data;
        if (strcmp(key, "ST") == 0)
        {
            return context->m_ST;
        }
        return 0;
    }

    static dmSocket::Socket NewSocket()
    {
        dmSocket::Socket socket = dmSocket::INVALID_SOCKET_HANDLE;
        dmSocket::Result sr = dmSocket::New(dmSocket::TYPE_DGRAM, dmSocket::PROTOCOL_UDP, &socket);
        if (sr != dmSocket::RESULT_OK)
            goto bail;

        sr = dmSocket::SetReuseAddress(socket, true);
        if (sr != dmSocket::RESULT_OK)
            goto bail;

/*        sr = dmSocket::SetBroadcast(socket, true);
        if (sr != dmSocket::RESULT_OK)
            goto bail;*/

        return socket;
bail:
        if (socket)
            dmSocket::Delete(socket);

        return dmSocket::INVALID_SOCKET_HANDLE;
    }

    static void HttpResponse(void* user_data, const dmHttpServer::Request* request)
    {
        SSDP* ssdp = (SSDP*) user_data;

        const char* last_slash = strrchr(request->m_Resource, '/');
        if (!last_slash)
        {
            dmHttpServer::SetStatusCode(request, 400);
            const char* s = "Bad URL";
            dmHttpServer::Send(request, s, strlen(s));
            return;
        }
        const char* id = last_slash + 1;

        dmhash_t id_hash = dmHashString64(id);
        Device** device = ssdp->m_RegistredEntries.Get(id_hash);
        if (!device)
        {
            dmHttpServer::SetStatusCode(request, 404);
            const char* s = "Device not found";
            dmHttpServer::Send(request, s, strlen(s));
            return;
        }

        const char* device_desc = (*device)->m_DeviceDesc->m_DeviceDescription;
        dmHttpServer::Send(request, device_desc, strlen(device_desc));
    }

    static void Disconnect(SSDP* ssdp)
    {
        if (ssdp->m_Socket != dmSocket::INVALID_SOCKET_HANDLE)
        {
            dmSocket::Delete(ssdp->m_Socket);
            ssdp->m_Socket = dmSocket::INVALID_SOCKET_HANDLE;
        }

        if (ssdp->m_MCastSocket != dmSocket::INVALID_SOCKET_HANDLE)
        {
            dmSocket::Delete(ssdp->m_MCastSocket);
            ssdp->m_MCastSocket = dmSocket::INVALID_SOCKET_HANDLE;
        }
    }

    static Result Connect(SSDP* ssdp)
    {
        Disconnect(ssdp);

        dmSocket::Socket sock = dmSocket::INVALID_SOCKET_HANDLE;
        dmSocket::Socket mcast_sock = dmSocket::INVALID_SOCKET_HANDLE;
        dmSocket::Result sr;
        dmSocket::Address address;

        sr = dmSocket::GetLocalAddress(&address);
        if (sr != dmSocket::RESULT_OK) goto bail;

        sock = NewSocket();
        if (sock == dmSocket::INVALID_SOCKET_HANDLE) goto bail;
        sr = dmSocket::Bind(sock, 0, 0);
        if (sr != dmSocket::RESULT_OK) goto bail;
        dmSocket::Address sock_addr;
        uint16_t sock_port;
        sr = dmSocket::GetName(sock, &sock_addr, &sock_port);
        if (sr != dmSocket::RESULT_OK) goto bail;

        mcast_sock = NewSocket();
        if (mcast_sock == dmSocket::INVALID_SOCKET_HANDLE) goto bail;
        sr = dmSocket::Bind(mcast_sock, 0, SSDP_MCAST_PORT);
        if (sr != dmSocket::RESULT_OK) goto bail;

        sr = dmSocket::AddMembership(mcast_sock,
                                    dmSocket::AddressFromIPString(SSDP_MCAST_ADDR),
                                    0,
                                    SSDP_MCAST_TTL);

        if (sr != dmSocket::RESULT_OK)
        {
            dmLogError("Unable to add broadcast membership for ssdp socket. No network connection? (%d)", sr);
        }

        ssdp->m_Socket = sock;
        ssdp->m_Address = address;
        ssdp->m_Port = sock_port;
        ssdp->m_MCastSocket = mcast_sock;

        return RESULT_OK;

bail:
        if (sock != dmSocket::INVALID_SOCKET_HANDLE)
            dmSocket::Delete(sock);

        if (mcast_sock != dmSocket::INVALID_SOCKET_HANDLE)
            dmSocket::Delete(mcast_sock);

        return RESULT_NETWORK_ERROR;
    }

    Result New(const NewParams* params,  HSSDP* hssdp)
    {
        *hssdp = 0;
        SSDP* ssdp = 0;
        dmHttpServer::HServer http_server = 0;
        dmHttpServer::NewParams http_params;
        dmHttpServer::Result hsr;

        ssdp = new SSDP();
        Result r = Connect(ssdp);
        if (r != RESULT_OK) goto bail;

        ssdp->m_MaxAge = params->m_MaxAge;
        DM_SNPRINTF(ssdp->m_MaxAgeText, sizeof(ssdp->m_MaxAgeText), "%u", params->m_MaxAge);
        ssdp->m_Announce = params->m_Announce;
        ssdp->m_AddressExpires = dmTime::GetTime() + SSDP_LOCAL_ADDRESS_EXPIRATION * uint64_t(1000000U);

        *hssdp = ssdp;

        http_params.m_HttpHeader = 0;
        http_params.m_HttpResponse = HttpResponse;
        http_params.m_Userdata = ssdp;
        hsr = dmHttpServer::New(&http_params, 0, &http_server);
        if (hsr != dmHttpServer::RESULT_OK)
            goto bail;

        ssdp->m_HttpServer = http_server;

        dmSocket::Address http_address;
        uint16_t http_port;
        dmHttpServer::GetName(http_server, &http_address, &http_port);

        DM_SNPRINTF(ssdp->m_HttpPortText, sizeof(ssdp->m_HttpPortText), "%u", http_port);

        DM_SNPRINTF(ssdp->m_Hostname, sizeof(ssdp->m_Hostname), "%u.%u.%u.%u",
                (ssdp->m_Address >> 24) & 0xff, (ssdp->m_Address >> 16) & 0xff, (ssdp->m_Address >> 8) & 0xff, (ssdp->m_Address >> 0) & 0xff);

        dmLogInfo("SSDP started (ssdp://%s:%u, http://%u.%u.%u.%u:%u)",
                ssdp->m_Hostname,
                ssdp->m_Port,
                (http_address >> 24) & 0xff,
                (http_address >> 16) & 0xff,
                (http_address >> 8) & 0xff,
                (http_address >> 0) & 0xff,
                http_port);

        return RESULT_OK;

bail:
        Disconnect(ssdp);

        if (http_server)
            dmHttpServer::Delete(http_server);

        delete ssdp;

        return RESULT_NETWORK_ERROR;
    }

    Result Delete(HSSDP ssdp)
    {
        dmHttpServer::Delete(ssdp->m_HttpServer);
        Disconnect(ssdp);
        delete ssdp;
        return RESULT_OK;
    }

    static void SendAnnounce(HSSDP ssdp, Device* device)
    {
        dmLogDebug("SSDP Announcing '%s'", device->m_DeviceDesc->m_Id);
        Replacer replacer1(0, device, ReplaceDeviceVar);
        Replacer replacer2(&replacer1, ssdp, ReplaceSSDPVar);
        dmTemplate::Result tr = dmTemplate::Format(&replacer2, (char*) ssdp->m_Buffer, sizeof(ssdp->m_Buffer), SSDP_ALIVE_TMPL, Replacer::Replace);
        if (tr != dmTemplate::RESULT_OK)
        {
            dmLogError("Error formating announce message (%d)", tr);
            return;
        }

        int sent_bytes;
        dmSocket::Result sr = dmSocket::SendTo(ssdp->m_Socket, ssdp->m_Buffer, strlen((char*) ssdp->m_Buffer), &sent_bytes, dmSocket::AddressFromIPString(SSDP_MCAST_ADDR), SSDP_MCAST_PORT);
        if (sr != dmSocket::RESULT_OK)
        {
            dmLogWarning("Failed to send announce message (%d)", sr);
        }
    }

    static void SendUnannounce(HSSDP ssdp, Device* device)
    {
        Replacer replacer(0, device, ReplaceDeviceVar);
        dmTemplate::Result tr = dmTemplate::Format(&replacer, (char*) ssdp->m_Buffer, sizeof(ssdp->m_Buffer), SSDP_BYEBYE_TMPL, Replacer::Replace);
        if (tr != dmTemplate::RESULT_OK)
        {
            dmLogError("Error formating unannounce message (%d)", tr);
            return;
        }

        int sent_bytes;
        dmSocket::Result sr = dmSocket::SendTo(ssdp->m_Socket, ssdp->m_Buffer, strlen((char*) ssdp->m_Buffer), &sent_bytes, dmSocket::AddressFromIPString(SSDP_MCAST_ADDR), SSDP_MCAST_PORT);
        if (sr != dmSocket::RESULT_OK)
        {
            dmLogWarning("Failed to send unannounce message (%d)", sr);
        }
    }

    Result RegisterDevice(HSSDP ssdp, const DeviceDesc* device_desc)
    {
        const char* id = device_desc->m_Id;
        dmhash_t id_hash = dmHashString64(id);
        if (ssdp->m_RegistredEntries.Get(id_hash) != 0)
        {
            return RESULT_ALREADY_REGISTRED;
        }

        if (ssdp->m_RegistredEntries.Full())
        {
            return RESULT_OUT_OF_RESOURCES;
        }

        Device* device = new Device(device_desc);
        ssdp->m_RegistredEntries.Put(id_hash, device);
        dmLogDebug("SSDP device '%s' registered", id);
        return RESULT_OK;
    }

    Result DeregisterDevice(HSSDP ssdp, const char* id)
    {
        dmhash_t id_hash = dmHashString64(id);
        if (ssdp->m_RegistredEntries.Get(id_hash) == 0)
        {
            return RESULT_NOT_REGISTRED;
        }

        Device** d  = ssdp->m_RegistredEntries.Get(id_hash);
        SendUnannounce(ssdp, *d);
        delete *d;
        ssdp->m_RegistredEntries.Erase(id_hash);
        dmLogDebug("SSDP device '%s' deregistered", id);
        return RESULT_OK;
    }

    void RequestParseState::FreeCallback(RequestParseState *state, const dmhash_t* key, const char** value)
    {
        free((void*) *value);
    }

    static void VersionCallback(void* user_data, int major, int minor, int status, const char* status_str)
    {
        RequestParseState* state = (RequestParseState*) user_data;
        state->m_Status = status;
    }

    static void RequestCallback(void* user_data, const char* request_method, const char* resource, int major, int minor)
    {
        RequestParseState* state = (RequestParseState*) user_data;

        if (strcmp("NOTIFY", request_method) == 0)
            state->m_RequestType = RT_NOTIFY;
        else if (strcmp("M-SEARCH", request_method) == 0)
            state->m_RequestType = RT_M_SEARCH;
        else
            state->m_RequestType = RT_UNKNOWN;
    }

    static void HeaderCallback(void* user_data, const char* orig_key, const char* value)
    {
        RequestParseState* state = (RequestParseState*) user_data;

        char key[64];
        key[sizeof(key)-1] = '\0'; // Ensure NULL termination
        for (uint32_t i = 0; i < sizeof(key); ++i) {
            key[i] = toupper(orig_key[i]);
            if (key[i] == '\0')
                break;
        }

        if (strcmp(key, "CACHE-CONTROL") == 0)
        {
            const char* p= strstr(value, "max-age=");
            if (p)
            {
                state->m_MaxAge = atoi(p + sizeof("max-age=")-1);
            }
        }
        else if (strcmp(key, "NT") == 0)
        {
            state->m_NTHash = dmHashString64(value);
        }
        else if (strcmp(key, "NTS") == 0)
        {
            state->m_NTSHash = dmHashString64(value);
        }

        dmhash_t key_hash = dmHashString64(key);
        state->m_Headers.Put(key_hash, strdup(value));
    }

    static void ResponseCallback(void* user_data, int offset)
    {
        (void) user_data;
        (void) offset;
    }

    static void HandleAnnounce(RequestParseState* state, const char* usn)
    {
        static const dmhash_t location_hash = dmHashString64("LOCATION");

        dmhash_t id = dmHashString64(usn);
        SSDP* ssdp = state->m_SSDP;

        if (ssdp->m_DiscoveredDevices.Get(id) == 0)
        {
            Device device;
            device.m_Expires = dmTime::GetTime() + state->m_MaxAge * uint64_t(1000000);

            // New
            if (ssdp->m_DiscoveredDevices.Full())
            {
                dmLogWarning("Out of SSDP entries. Ignoring message");
                return;
            }
            ssdp->m_DiscoveredDevices.Put(id, device);
            const char* location = "UNKNOWN";
            const char** loc = state->m_Headers.Get(location_hash);
            if (loc)
                location = *loc;
            dmLogDebug("SSDP new %s (%s) (announce/search-response)", usn, location);
        }
        else
        {
            // Renew
            dmLogDebug("SSDP renew %s (announce/search-response)", usn);
            Device* old_device = ssdp->m_DiscoveredDevices.Get(id);
            old_device->m_Expires = dmTime::GetTime() + state->m_MaxAge * uint64_t(1000000);
        }

        if (ssdp->m_DiscoveredDevices.Full())
        {
            dmLogWarning("Out of SSDP entries. Ignoring message");
            return;
        }
    }

    static void HandleUnAnnounce(RequestParseState* state, const char* usn)
    {
        dmhash_t id = dmHashString64(usn);
        SSDP* ssdp = state->m_SSDP;

        if (ssdp->m_DiscoveredDevices.Get(id) != 0)
        {
            dmLogDebug("SSDP unannounce (removing) %s", usn);
            ssdp->m_DiscoveredDevices.Erase(id);
        }
    }

    static void SearchCallback(SearchResponseContext* ctx, const dmhash_t* key, Device** device)
    {
        dmLogDebug("Sending search response: %s", (*device)->m_DeviceDesc->m_UDN);

        if (strcmp(ctx->m_ST, (*device)->m_DeviceDesc->m_DeviceType) != 0) {
            return;
        }

        SSDP* ssdp = ctx->m_State->m_SSDP;
        Replacer replacer1(0, *device, ReplaceDeviceVar);
        Replacer replacer2(&replacer1, ctx, ReplaceSearchResponseVar);
        Replacer replacer3(&replacer2, ssdp, ReplaceSSDPVar);
        dmTemplate::Result tr = dmTemplate::Format(&replacer3, (char*) ssdp->m_Buffer, sizeof(ssdp->m_Buffer), SEARCH_RESULT_FMT, Replacer::Replace);
        if (tr != dmTemplate::RESULT_OK)
        {
            dmLogError("Error formating search response message (%d)", tr);
            return;
        }

        int sent_bytes;
        dmSocket::SendTo(ssdp->m_Socket, ssdp->m_Buffer, strlen((char*) ssdp->m_Buffer), &sent_bytes, ctx->m_FromAddress, ctx->m_FromPort);
    }

    static void HandleSearch(RequestParseState* state, dmSocket::Address from_address, uint16_t from_port)
    {
        static const dmhash_t st_hash = dmHashString64("ST");
        const char** st = state->m_Headers.Get(st_hash);
        if (!st)
        {
            dmLogWarning("Malformed search package. Missing ST header");
            return;
        }

        SearchResponseContext context(state, *st, from_address, from_port);
        state->m_SSDP->m_RegistredEntries.Iterate(SearchCallback, &context);
    }

    struct ExpireContext
    {
        SSDP*                   m_SSDP;
        uint64_t                m_Now;
        dmArray<dmhash_t>       m_ToExpire;

        ExpireContext(SSDP* ssdp)
        {
            memset(this, 0, sizeof(*this));
            m_SSDP = ssdp;
            m_Now = dmTime::GetTime();
        }
    };

    /**
     * Dispatch socket
     * @param ssdp ssdp handle
     * @param socket socket to dispatch
     * @param response true for response-dispatch
     * @return true on success or on transient errors. false on permanent errors.
     */
    static bool DispatchSocket(SSDP* ssdp, dmSocket::Socket socket, bool response)
    {
        static const dmhash_t usn_hash = dmHashString64("USN");
        static const dmhash_t ssdp_alive_hash = dmHashString64("ssdp:alive");
        static const dmhash_t ssdp_byebye_hash = dmHashString64("ssdp:byebye");

        dmSocket::Result sr;
        int recv_bytes;
        dmSocket::Address from_addr;
        uint16_t from_port;
        sr = dmSocket::ReceiveFrom(socket,
                                   ssdp->m_Buffer,
                                   sizeof(ssdp->m_Buffer),
                                   &recv_bytes, &from_addr, &from_port);

        if (sr != dmSocket::RESULT_OK)
        {
            // When returning from sleep mode on iOS socket is in state ECONNABORTED
            if (sr == dmSocket::RESULT_CONNABORTED || sr == dmSocket::RESULT_NOTCONN)
            {
                dmLogDebug("SSDP permanent dispatch error");
                return false;
            }
            else
            {
                dmLogDebug("SSDP transient dispatch error");
                return true;
            }
        }

        uint8_t comps[4];
        comps[0] = (from_addr >> 24) & 0xff; comps[1] = (from_addr >> 16) & 0xff;
        comps[2] = (from_addr >> 8) & 0xff; comps[3] = (from_addr >> 0) & 0xff;

        if (from_addr == ssdp->m_Address && from_port == ssdp->m_Port)
        {
            dmLogDebug("Ignoring package from self (%u.%u.%u.%u:%d)", comps[0],comps[1],comps[2],comps[3], from_port);
            return true;
        }

        dmLogDebug("Multicast SSDP message from %u.%u.%u.%u:%d", comps[0],comps[1],comps[2],comps[3], from_port);

        RequestParseState state(ssdp);
        bool ok = false;

        if (response)
        {
            dmHttpClientPrivate::ParseResult pr = dmHttpClientPrivate::ParseHeader((char*) ssdp->m_Buffer, &state, VersionCallback, HeaderCallback, ResponseCallback);
            ok = pr == dmHttpClientPrivate::PARSE_RESULT_OK;
        }
        else
        {
            dmHttpServerPrivate::ParseResult pr = dmHttpServerPrivate::ParseHeader((char*) ssdp->m_Buffer, &state, RequestCallback, HeaderCallback, ResponseCallback);
            ok = pr == dmHttpServerPrivate::PARSE_RESULT_OK;
        }

        if (ok)
        {
            const char** usn = state.m_Headers.Get(usn_hash);

            if (response)
            {
                if (state.m_Status == 200)
                {
                    if (usn != 0)
                    {
                        HandleAnnounce(&state, *usn);
                    }
                    else
                    {
                        dmLogWarning("Malformed message from %u.%u.%u.%u:%d. Missing USN header.", comps[0],comps[1],comps[2],comps[3], from_port);
                    }
                }
            }
            else
            {
                if (state.m_RequestType == RT_NOTIFY)
                {
                    if (usn != 0)
                    {
                        if (state.m_NTSHash == ssdp_alive_hash)
                        {
                            HandleAnnounce(&state, *usn);
                        }
                        else if (state.m_NTSHash == ssdp_byebye_hash)
                        {
                            HandleUnAnnounce(&state, *usn);
                        }
                    }
                    else
                    {
                        dmLogWarning("Malformed message from %u.%u.%u.%u:%d. Missing USN header.", comps[0],comps[1],comps[2],comps[3], from_port);
                    }
                }
                else if (state.m_RequestType == RT_M_SEARCH)
                {
                    HandleSearch(&state, from_addr, from_port);
                }
            }
        }
        else
        {
            dmLogWarning("Malformed message from %u.%u.%u.%u:%d", comps[0],comps[1],comps[2],comps[3], from_port);
        }

       return true;
    }

    static void VisitDiscoveredExpireDevice(ExpireContext* context, const dmhash_t* key, Device* device)
    {
        if (context->m_Now >= device->m_Expires)
        {
            if (context->m_ToExpire.Full())
            {
                context->m_ToExpire.OffsetCapacity(64);
            }
            context->m_ToExpire.Push(*key);
        }
    }

    static void ExpireDiscovered(SSDP* ssdp)
    {
        ExpireContext context(ssdp);
        ssdp->m_DiscoveredDevices.Iterate(VisitDiscoveredExpireDevice, &context);
        uint32_t n = context.m_ToExpire.Size();
        for(uint32_t i = 0; i < n; ++i)
        {
            uint64_t id = context.m_ToExpire[i];
            dmLogDebug("SSDP expired %s", (const char*) dmHashReverse64(id, 0));

            ssdp->m_DiscoveredDevices.Erase(id);
        }
    }

    static void VisitRegistredAnnounceDevice(SSDP* ssdp, const dmhash_t* key, Device** device)
    {
        uint64_t now = dmTime::GetTime();
        if (now >= (*device)->m_Expires)
        {
            SendAnnounce(ssdp, *device);
            (*device)->m_Expires = now + ssdp->m_MaxAge * 1000000;
        }
    }

    static void AnnounceRegistred(SSDP* ssdp)
    {
        ssdp->m_RegistredEntries.Iterate(VisitRegistredAnnounceDevice, ssdp);
    }

    void Update(HSSDP ssdp, bool search)
    {
        if (ssdp->m_Reconnect)
        {
            dmLogWarning("Reconnecting SSDP");
            Connect(ssdp);
            ssdp->m_Reconnect = 0;
        }

        dmSocket::Address address;

        uint64_t current_time = dmTime::GetTime();
        if (current_time > ssdp->m_AddressExpires)
        {
            dmLogDebug("Update SSDP address");
            // Update address. It might have change. 3G -> wifi etc
            if (dmSocket::GetLocalAddress(&address) == dmSocket::RESULT_OK)
            {
                ssdp->m_Address = address;
            }
            ssdp->m_AddressExpires = current_time + SSDP_LOCAL_ADDRESS_EXPIRATION * uint64_t(1000000U);
        }

        ExpireDiscovered(ssdp);
        if (ssdp->m_Announce)
        {
            AnnounceRegistred(ssdp);
        }

        dmHttpServer::Update(ssdp->m_HttpServer);

        bool incoming_data;
        do
        {
            incoming_data = false;
            dmSocket::Selector selector;
            dmSocket::SelectorZero(&selector);
            dmSocket::SelectorSet(&selector, dmSocket::SELECTOR_KIND_READ, ssdp->m_MCastSocket);
            dmSocket::SelectorSet(&selector, dmSocket::SELECTOR_KIND_READ, ssdp->m_Socket);
            dmSocket::Select(&selector, 0);

            if (dmSocket::SelectorIsSet(&selector, dmSocket::SELECTOR_KIND_READ, ssdp->m_MCastSocket))
            {
                if (DispatchSocket(ssdp, ssdp->m_MCastSocket, false))
                {
                    incoming_data = true;
                }
                else
                {
                    ssdp->m_Reconnect = 1;
                }
            }
            if (dmSocket::SelectorIsSet(&selector, dmSocket::SELECTOR_KIND_READ, ssdp->m_Socket))
            {
                if (DispatchSocket(ssdp, ssdp->m_Socket, true))
                {
                    incoming_data = true;
                }
                else
                {
                    ssdp->m_Reconnect = 1;
                }
            }
        } while (incoming_data);

        if (search)
        {
            int sent_bytes;
            dmSocket::Result sr;
            sr = dmSocket::SendTo(ssdp->m_Socket,
                             M_SEARCH_FMT,
                             strlen(M_SEARCH_FMT),
                             &sent_bytes,
                             dmSocket::AddressFromIPString(SSDP_MCAST_ADDR),
                             SSDP_MCAST_PORT);

            dmLogDebug("SSDP M-SEARCH");
            if (sr != dmSocket::RESULT_OK)
            {
                dmLogWarning("Failed to send SSDP search package (%d)", sr);
            }
        }
    }

    void ClearDiscovered(HSSDP ssdp)
    {
        ssdp->m_DiscoveredDevices.Clear();
    }

    void IterateDevicesInternal(HSSDP ssdp, void (*call_back)(void *context, const dmhash_t* usn, Device* device), void* context)
    {
        assert(ssdp);
        ssdp->m_DiscoveredDevices.Iterate(call_back, context);
    }

}  // namespace dmSSPD

