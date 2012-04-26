#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include <assert.h>
#include "array.h"
#include "dstrings.h"
#include "log.h"
#include "socket.h"
#include "message.h"
#include "thread.h"
#include "time.h"

struct dmLogConnection
{
    dmLogConnection()
    {
        m_Socket = dmSocket::INVALID_SOCKET_HANDLE;
    }

    dmSocket::Socket m_Socket;
};

struct dmLogMessage
{
    enum Type
    {
        MESSAGE = 0,
        SHUTDOWN = 1,
    };

    uint8_t m_Type;
    char    m_Message[0];
};

static const uint32_t DLIB_MAX_LOG_CONNECTIONS = 16;

struct dmLogServer
{
    dmLogServer(dmSocket::Socket server_socket, uint16_t port,
                 dmMessage::HSocket message_socket)
    {
        m_Connections.SetCapacity(DLIB_MAX_LOG_CONNECTIONS);
        m_ServerSocket = server_socket;
        m_Port = port;
        m_MessgeSocket = message_socket;
        m_Thread = 0;
    }

    dmArray<dmLogConnection> m_Connections;
    dmSocket::Socket         m_ServerSocket;
    uint16_t                 m_Port;
    dmMessage::HSocket       m_MessgeSocket;
    dmThread::Thread         m_Thread;
};

dmLogServer* g_dmLogServer = 0;

dmLogSeverity g_LogLevel = DM_LOG_SEVERITY_WARNING;

static dmSocket::Result SendAll(dmSocket::Socket socket, const char* buffer, int length)
{
    int total_sent_bytes = 0;
    int sent_bytes = 0;

    while (total_sent_bytes < length)
    {
        dmSocket::Result r = dmSocket::Send(socket, buffer + total_sent_bytes, length - total_sent_bytes, &sent_bytes);
        if (r == dmSocket::RESULT_TRY_AGAIN)
            continue;

        if (r != dmSocket::RESULT_OK)
        {
            return r;
        }

        total_sent_bytes += sent_bytes;
    }

    return dmSocket::RESULT_OK;
}

static void dmLogUpdateNetwork()
{
    dmLogServer* self = g_dmLogServer;

    dmSocket::Selector selector;
    dmSocket::SelectorSet(&selector, dmSocket::SELECTOR_KIND_READ, self->m_ServerSocket);
    dmSocket::Result r = dmSocket::Select(&selector, 0);
    if (r == dmSocket::RESULT_OK)
    {
        // Check for new connections
        if (dmSocket::SelectorIsSet(&selector, dmSocket::SELECTOR_KIND_READ, self->m_ServerSocket))
        {
            dmSocket::Address address;
            dmSocket::Socket client_socket;
            r = dmSocket::Accept(self->m_ServerSocket, &address, &client_socket);
            if (r == dmSocket::RESULT_OK)
            {
                if (self->m_Connections.Full())
                {
                    const char* msg = "ERROR:DLIB: Too many log connections opened";
                    fprintf(stderr, "%s\n", msg);
                    const char* resp = "1 Too many log connections opened\n";
                    SendAll(client_socket, resp, strlen(resp));
                    dmSocket::Shutdown(client_socket, dmSocket::SHUTDOWNTYPE_READWRITE);
                    dmSocket::Delete(client_socket);
                }
                else
                {
                    const char* resp = "0 OK\n";
                    SendAll(client_socket, resp, strlen(resp));
                    dmSocket::SetNoDelay(client_socket, true);
                    dmLogConnection connection;
                    memset(&connection, 0, sizeof(connection));
                    connection.m_Socket = client_socket;
                    self->m_Connections.Push(connection);
                }
            }
        }
    }
}

static void dmLogDispatch(dmMessage::Message *message, void* user_ptr)
{
    dmLogServer* self = g_dmLogServer;

    bool* run = (bool*) user_ptr;
    dmLogMessage* log_message = (dmLogMessage*) &message->m_Data[0];
    if (log_message->m_Type == dmLogMessage::SHUTDOWN)
    {
        *run = false;
        return;
    }
    int msg_len = (int) strlen(log_message->m_Message);

    // NOTE: Keep i as signed! See --i below after EraseSwap
    int n = (int) self->m_Connections.Size();
    for (int i = 0; i < n; ++i)
    {
        dmLogConnection* c = &self->m_Connections[i];
        dmSocket::Result r;
        int sent_bytes;
        int total_sent = 0;
        do
        {
            r = dmSocket::Send(c->m_Socket, log_message->m_Message + total_sent, msg_len - total_sent, &sent_bytes);
            if (r == dmSocket::RESULT_OK)
            {
                total_sent += sent_bytes;
            }
            else if (r == dmSocket::RESULT_TRY_AGAIN)
            {
                // Ok
            }
            else
            {
                dmSocket::Shutdown(c->m_Socket, dmSocket::SHUTDOWNTYPE_READWRITE);
                dmSocket::Delete(c->m_Socket);
                self->m_Connections.EraseSwap(i);
                --n;
                --i;
                break;
            }
        } while (total_sent < msg_len);
    }
}

static void dmLogThread(void* args)
{
    dmLogServer* self = g_dmLogServer;

    volatile bool run = true;
    while (run)
    {
        // NOTE: In the future we might add support for waiting for messages... :-)
        dmTime::Sleep(1000 * 30);
        dmLogUpdateNetwork();
        dmMessage::Dispatch(self->m_MessgeSocket, dmLogDispatch, (void*) &run);
    }
}

void dmLogInitialize()
{
    if (g_dmLogServer)
    {
        fprintf(stderr, "ERROR:DLIB: dmLog already initialized\n");
        return;
    }

    dmSocket::Socket server_socket = dmSocket::INVALID_SOCKET_HANDLE;
    dmMessage::HSocket message_socket = 0;
    dmMessage::Result mr;
    dmThread::Thread thread = 0;
    dmSocket::Address address;
    uint16_t port;
    dmSocket::Result r;
    const char* error_msg = 0;

    mr = dmMessage::NewSocket("@log", &message_socket);
    if (mr != dmMessage::RESULT_OK)
    {
        error_msg = "Unable to create @log message socket";
        goto bail;
    }

    r = dmSocket::New(dmSocket::TYPE_STREAM, dmSocket::PROTOCOL_TCP, &server_socket);
    if (r != dmSocket::RESULT_OK)
    {
        error_msg = "Unable to create log socket";
        goto bail;
    }

    dmSocket::SetReuseAddress(server_socket, true);

    r = dmSocket::Bind(server_socket, dmSocket::AddressFromIPString("0.0.0.0"), 0);
    if (r != dmSocket::RESULT_OK)
    {
        error_msg = "Unable to bind to log socket";
        goto bail;
    }

    dmSocket::GetName(server_socket, &address, &port);

    r = dmSocket::Listen(server_socket, 32);
    if (r != dmSocket::RESULT_OK)
    {
        error_msg = "Unable to listen on log socket";
        goto bail;
    }

    g_dmLogServer = new dmLogServer(server_socket, port, message_socket);
    thread = dmThread::New(dmLogThread, 0x80000, 0);
    g_dmLogServer->m_Thread = thread;

    return;

bail:
    fprintf(stderr, "ERROR:DLIB: %s\n", error_msg);
    if (message_socket != 0)
        dmMessage::DeleteSocket(message_socket);

    if (server_socket != dmSocket::INVALID_SOCKET_HANDLE)
        dmSocket::Delete(server_socket);
}

void dmLogFinalize()
{
    if (!g_dmLogServer)
        return;
    dmLogServer* self = g_dmLogServer;

    dmLogMessage msg;
    msg.m_Type = dmLogMessage::SHUTDOWN;
    dmMessage::URL receiver;
    receiver.m_Socket = self->m_MessgeSocket;
    receiver.m_Path = 0;
    receiver.m_Fragment = 0;
    dmMessage::Post(0, &receiver, 0, 0, 0, &msg, sizeof(msg));
    dmThread::Join(self->m_Thread);

    uint32_t n = self->m_Connections.Size();
    for (uint32_t i = 0; i < n; ++i)
    {
        dmLogConnection* c = &self->m_Connections[i];
        dmSocket::Shutdown(c->m_Socket, dmSocket::SHUTDOWNTYPE_READWRITE);
        dmSocket::Delete(c->m_Socket);
    }

    if (self->m_ServerSocket != dmSocket::INVALID_SOCKET_HANDLE)
    {
        dmSocket::Delete(self->m_ServerSocket);
    }

    if (self->m_MessgeSocket != 0)
    {
        dmMessage::DeleteSocket(self->m_MessgeSocket);
    }

    delete self;
    g_dmLogServer = 0;
}

uint16_t dmLogGetPort()
{
    if (!g_dmLogServer)
        return 0;

    return g_dmLogServer->m_Port;
}

void dmLogSetlevel(dmLogSeverity severity)
{
    g_LogLevel = severity;
}

void dmLogInternal(dmLogSeverity severity, const char* domain, const char* format, ...)
{
    if (severity < g_LogLevel)
        return;

    va_list lst;
    va_start(lst, format);

    const char* severity_str = 0;
    switch (severity)
    {
        case DM_LOG_SEVERITY_DEBUG:
            severity_str = "DEBUG";
            break;
        case DM_LOG_SEVERITY_INFO:
            severity_str = "INFO";
            break;
        case DM_LOG_SEVERITY_WARNING:
            severity_str = "WARNING";
            break;
        case DM_LOG_SEVERITY_ERROR:
            severity_str = "ERROR";
            break;
        case DM_LOG_SEVERITY_FATAL:
            severity_str = "FATAL";
            break;
        default:
            assert(0);
            break;
    }


    const int str_buf_size = 512;
    char tmp_buf[sizeof(dmLogMessage) + str_buf_size];
    dmLogMessage* msg = (dmLogMessage*) &tmp_buf[0];
    char* str_buf = &tmp_buf[sizeof(dmLogMessage)];

    int n = 0;
    n += DM_SNPRINTF(str_buf + n, str_buf_size - n, "%s:%s: ", severity_str, domain);
    if (n < str_buf_size)
    {
        n += vsnprintf(str_buf + n, str_buf_size - n, format, lst);
    }

    if (n < str_buf_size)
    {
        n += DM_SNPRINTF(str_buf + n, str_buf_size - n, "\n");
    }
    str_buf[str_buf_size-1] = '\0';

    fwrite(str_buf, 1, n, stderr);
    va_end(lst);

    dmLogServer* self = g_dmLogServer;
    if (self)
    {
        msg->m_Type = dmLogMessage::MESSAGE;
        dmMessage::URL receiver;
        receiver.m_Socket = self->m_MessgeSocket;
        receiver.m_Path = 0;
        receiver.m_Fragment = 0;
        dmMessage::Post(0, &receiver, 0, 0, 0, msg, sizeof(dmLogMessage) + n + 1);
    }
}
