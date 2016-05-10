#include <assert.h>
#include <string.h>
#include "message.h"
#include "atomic.h"
#include "hash.h"
#include "profile.h"
#include "array.h"
#include "index_pool.h"
#include "mutex.h"
#include "condition_variable.h"
#include "dstrings.h"

namespace dmMessage
{
    // Alignment of allocations
    const uint32_t DM_MESSAGE_ALIGNMENT = 16U;
    // Page size must be a multiple of ALIGNMENT. Currently 4 but could be changed to 16.
    // This simplifies the allocation scheme
    const uint32_t DM_MESSAGE_PAGE_SIZE = 4096U;

    struct MemoryPage
    {
        uint8_t     m_Memory[DM_MESSAGE_PAGE_SIZE];
        uint32_t    m_Current;
        MemoryPage* m_NextPage;
    };

    struct MemoryAllocator
    {
        MemoryAllocator()
        {
            m_CurrentPage = 0;
            m_FreePages = 0;
            m_FullPages = 0;
        }
        MemoryPage* m_CurrentPage;
        MemoryPage* m_FreePages;
        MemoryPage* m_FullPages;
    };

    static void AllocateNewPage(MemoryAllocator* allocator)
    {
        if (allocator->m_CurrentPage)
        {
            // Link current page to full pages
            allocator->m_CurrentPage->m_NextPage = allocator->m_FullPages;
            allocator->m_FullPages = allocator->m_CurrentPage;
        }

        MemoryPage* new_page = 0;

        if (allocator->m_FreePages)
        {
            // Free page to use
            new_page = allocator->m_FreePages;
            allocator->m_FreePages = new_page->m_NextPage;
        }
        else
        {
            // Allocate new page
            new_page = new MemoryPage;
        }

        new_page->m_Current = 0;
        new_page->m_NextPage = 0;

        allocator->m_CurrentPage = new_page;
    }

    static void* AllocateMessage(MemoryAllocator* allocator, uint32_t size)
    {
        // At least ALIGNMENT bytes alignment of size in order to ensure that the next allocation is aligned
        size += DM_MESSAGE_ALIGNMENT-1;
        size &= ~(DM_MESSAGE_ALIGNMENT-1);
        assert(size <= DM_MESSAGE_PAGE_SIZE);

        if (allocator->m_CurrentPage == 0 || (DM_MESSAGE_PAGE_SIZE-allocator->m_CurrentPage->m_Current) < size)
        {
            // No current page or allocation didn't fit.
            AllocateNewPage(allocator);
        }

        MemoryPage* page = allocator->m_CurrentPage;
        void* ret = (void*) ((uintptr_t) &page->m_Memory[0] + page->m_Current);
        page->m_Current += size;
        return ret;
    }

    struct MessageSocket
    {
        dmhash_t        m_NameHash;
        Message*        m_Header;
        Message*        m_Tail;
        const char*     m_Name;
        dmMutex::Mutex  m_Mutex;
        dmConditionVariable::ConditionVariable m_Condition;
        MemoryAllocator m_Allocator;
        uint16_t        m_Version;
    };

    const uint32_t MAX_SOCKETS = 128;
    bool g_Initialized = false;
    int32_atomic_t g_NextVersionNumber = 0;
    dmArray<MessageSocket> g_Sockets;
    dmIndexPool16 g_SocketPool;

    Result NewSocket(const char* name, HSocket* socket)
    {
        if (!g_Initialized)
        {
            g_Sockets.SetCapacity(MAX_SOCKETS);
            g_Sockets.SetSize(MAX_SOCKETS);
            memset(&g_Sockets[0], 0, sizeof(g_Sockets[0]) * MAX_SOCKETS);
            g_SocketPool.SetCapacity(MAX_SOCKETS);
            g_Initialized = true;
        }
        if (name == 0x0 || *name == 0 || strchr(name, '#') != 0x0 || strchr(name, ':') != 0x0)
        {
            return RESULT_INVALID_SOCKET_NAME;
        }

        HSocket tmp;
        if (GetSocket(name, &tmp) == RESULT_OK)
        {
            return RESULT_SOCKET_EXISTS;
        }

        if (g_SocketPool.Remaining() == 0)
        {
            return RESULT_SOCKET_OUT_OF_RESOURCES;
        }

        uint16_t id  = g_SocketPool.Pop();
        dmhash_t name_hash = dmHashString64(name);

        MessageSocket s;
        s.m_Header = 0;
        s.m_Tail = 0;
        s.m_NameHash = name_hash;
        s.m_Version = dmAtomicIncrement32(&g_NextVersionNumber);
        s.m_Name = strdup(name);
        s.m_Mutex = dmMutex::New();
        s.m_Condition = dmConditionVariable::New();

        // 0 is an invalid handle. We can't use 0 as version number.
        if (s.m_Version == 0)
            s.m_Version = dmAtomicIncrement32(&g_NextVersionNumber);

        g_Sockets[id] = s;
        *socket = s.m_Version << 16 | id;

        return RESULT_OK;
    }

    static MessageSocket* GetSocketInternal(HSocket socket, uint16_t& out_id)
    {
        if (socket != 0)
        {
            uint16_t version = socket >> 16;
            assert(version != 0);

            uint16_t id = socket & 0xffff;

            if (id < g_Sockets.Size())
            {
                MessageSocket* s = &g_Sockets[id];
                assert(s->m_Version == version);
                out_id = id;
                return s;
            }
        }
        return 0x0;
    }

    Result DeleteSocket(HSocket socket)
    {
        uint16_t id;
        MessageSocket* s = GetSocketInternal(socket, id);
        if (s != 0x0)
        {
            free((void*) s->m_Name);

            MemoryPage* p = s->m_Allocator.m_FreePages;
            while (p)
            {
                MemoryPage* next = p->m_NextPage;
                delete p;
                p = next;
            }
            p = s->m_Allocator.m_FullPages;
            while (p)
            {
                MemoryPage* next = p->m_NextPage;
                delete p;
                p = next;
            }
            if (s->m_Allocator.m_CurrentPage)
                delete s->m_Allocator.m_CurrentPage;

            dmConditionVariable::Delete(s->m_Condition);
            dmMutex::Delete(s->m_Mutex);

            memset(s, 0, sizeof(*s));
            g_SocketPool.Push(id);
            return RESULT_OK;
        }
        return RESULT_SOCKET_NOT_FOUND;
    }

    Result GetSocket(const char *name, HSocket* out_socket)
    {
        DM_PROFILE(Message, "GetSocket")

        if (name == 0x0 || *name == 0 || strchr(name, '#') != 0x0 || strchr(name, ':') != 0x0)
        {
            return RESULT_INVALID_SOCKET_NAME;
        }
        dmhash_t name_hash = dmHashString64(name);
        for (uint32_t i = 0; i < g_Sockets.Size(); ++i)
        {
            MessageSocket* socket = &g_Sockets[i];
            if (socket->m_NameHash == name_hash)
            {
                *out_socket = socket->m_Version << 16 | i;
                return RESULT_OK;
            }
        }
        return RESULT_SOCKET_NOT_FOUND;
    }

    const char* GetSocketName(HSocket socket)
    {
        uint16_t index;
        MessageSocket* message_socket = GetSocketInternal(socket, index);
        if (message_socket != 0x0)
        {
            return message_socket->m_Name;
        }
        else
        {
            return 0x0;
        }
    }

    bool IsSocketValid(HSocket socket)
    {
        if (socket != 0)
        {
            uint16_t version = socket >> 16;
            assert(version != 0);

            uint16_t id = socket & 0xffff;

            if (id < g_Sockets.Size())
            {
                MessageSocket* s = &g_Sockets[id];
                return s->m_Version == version;
            }
        }
        return false;
    }

    bool HasMessages(HSocket socket)
    {
        uint16_t id;
        MessageSocket*s = GetSocketInternal(socket, id);
        dmMutex::Lock(s->m_Mutex);
        bool has_messages = false;
        if (s->m_Header)
        {
            has_messages = true;
        }
        dmMutex::Unlock(s->m_Mutex);

        return has_messages;
    }

    void ResetURL(const URL& url)
    {
        memset((void*)&url, 0, sizeof(URL));
    }

    uint32_t g_MessagesHash = dmHashString32("Messages");

    Result Post(const URL* sender, const URL* receiver, dmhash_t message_id, uintptr_t user_data, uintptr_t descriptor, const void* message_data, uint32_t message_data_size)
    {
        DM_PROFILE(Message, "Post")
        DM_COUNTER_HASH("Messages", g_MessagesHash, 1)

        if (receiver == 0x0)
        {
            return RESULT_SOCKET_NOT_FOUND;
        }
        uint16_t socket_id;
        MessageSocket* s = GetSocketInternal(receiver->m_Socket, socket_id);
        if (s == 0x0)
        {
            return RESULT_SOCKET_NOT_FOUND;
        }

        dmMutex::Lock(s->m_Mutex);
        MemoryAllocator* allocator = &s->m_Allocator;
        uint32_t data_size = sizeof(Message) + message_data_size;
        Message *new_message = (Message *) AllocateMessage(allocator, data_size);
        if (sender != 0x0)
        {
            new_message->m_Sender = *sender;
        }
        else
        {
            ResetURL(new_message->m_Sender);
        }
        new_message->m_Receiver = *receiver;
        new_message->m_Id = message_id;
        new_message->m_UserData = user_data;
        new_message->m_Descriptor = descriptor;
        new_message->m_DataSize = message_data_size;
        new_message->m_Next = 0;
        memcpy(&new_message->m_Data[0], message_data, message_data_size);

        if (!s->m_Header)
        {
            s->m_Header = new_message;
            s->m_Tail = new_message;
        }
        else
        {
            s->m_Tail->m_Next = new_message;
            s->m_Tail = new_message;
        }

        dmConditionVariable::Signal(s->m_Condition);
        dmMutex::Unlock(s->m_Mutex);
        return RESULT_OK;
    }

    uint32_t InternalDispatch(HSocket socket, DispatchCallback dispatch_callback, void* user_ptr, bool blocking)
    {
        uint16_t id;
        MessageSocket*s = GetSocketInternal(socket, id);
        DM_PROFILE(Message, dmProfile::Internalize(s->m_Name));

        dmMutex::Lock(s->m_Mutex);
        MemoryAllocator* allocator = &s->m_Allocator;

        if (!s->m_Header)
        {
            if (blocking) {
                dmConditionVariable::Wait(s->m_Condition, s->m_Mutex);
            } else {
                dmMutex::Unlock(s->m_Mutex);
                return 0;
            }
        }
        uint32_t dispatch_count = 0;

        Message *message_object = s->m_Header;
        s->m_Header = 0;
        s->m_Tail = 0;

        // Unlink full pages
        MemoryPage*full_pages = allocator->m_FullPages;
        allocator->m_FullPages = 0;

        dmMutex::Unlock(s->m_Mutex);

        while (message_object)
        {
            dispatch_callback(message_object, user_ptr);
            message_object = message_object->m_Next;
            dispatch_count++;
        }

        // Reclaim all full pages active when dispatch started
        dmMutex::Lock(s->m_Mutex);
        MemoryPage* p = full_pages;
        while (p)
        {
            MemoryPage* next = p->m_NextPage;
            p->m_NextPage = allocator->m_FreePages;
            allocator->m_FreePages = p;
            p = next;
        }
        dmMutex::Unlock(s->m_Mutex);

        return dispatch_count;
    }

    uint32_t Dispatch(HSocket socket, DispatchCallback dispatch_callback, void* user_ptr)
    {
        return InternalDispatch(socket, dispatch_callback, user_ptr, false);
    }

    uint32_t DispatchBlocking(HSocket socket, DispatchCallback dispatch_callback, void* user_ptr)
    {
        return InternalDispatch(socket, dispatch_callback, user_ptr, true);
    }

    static void ConsumeCallback(dmMessage::Message*, void*)
    {
    }

    uint32_t Consume(HSocket socket)
    {
        return Dispatch(socket, &ConsumeCallback, 0);
    }

    Result ParseURL(const char* uri, StringURL* out_url)
    {
        if (uri == 0x0)
        {
            *out_url = StringURL();
            return RESULT_OK;
        }
        const char* socket = 0x0;
        uint32_t socket_size = 0;
        const char* path = uri;
        uint32_t path_size = 0;
        const char* fragment = 0x0;
        uint32_t fragment_size = 0;
        const char* socket_end = strchr(uri, ':');
        const char* fragment_start = strchr(uri, '#');
        if (fragment_start != 0x0)
        {
            if (fragment_start < socket_end)
            {
                return RESULT_MALFORMED_URL;
            }
            if (fragment_start != strrchr(uri, '#'))
            {
                return RESULT_MALFORMED_URL;
            }
        }
        if (socket_end != 0x0)
        {
            if (socket_end != strrchr(uri, ':'))
            {
                return RESULT_MALFORMED_URL;
            }
            socket_size = socket_end - uri;
            if (socket_size >= 64)
            {
                return RESULT_MALFORMED_URL;
            }
            socket = uri;
            path = socket_end + 1;
        }
        if (fragment_start != 0x0)
        {
            fragment = fragment_start + 1;
            fragment_size = strlen(uri) - (fragment - uri);
            path_size = fragment_start - path;
        }
        else
        {
            path_size = strlen(uri) - (path - uri);
        }
        out_url->m_Socket = socket;
        out_url->m_SocketSize = socket_size;
        out_url->m_Path = path;
        out_url->m_PathSize = path_size;
        out_url->m_Fragment = fragment;
        out_url->m_FragmentSize = fragment_size;
        return RESULT_OK;
    }
}

