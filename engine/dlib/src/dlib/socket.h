// Copyright 2020 The Defold Foundation
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
//
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#ifndef DM_SOCKET_H
#define DM_SOCKET_H

#include <stdint.h>
#include <string.h> // memset, memcmp

#if defined(__linux__) || defined(__MACH__) || defined(ANDROID) || defined(__EMSCRIPTEN__) || defined(__NX__)
#include <sys/select.h>
#elif defined(_WIN32)
#include <winsock2.h>
#else
#error "Unsupported platform"
#endif

#include <dmsdk/dlib/socket.h>

/**
 * Socket abstraction
 * @note For Recv* and Send* function ETIMEDOUT is translated to EWOULDBLOCK
 * on win32 for compatibility with BSD sockets.
 */
namespace dmSocket
{
    struct Selector
    {
        fd_set m_FdSets[3];
        int    m_Nfds;
        Selector();
    };

    enum SelectorKind
    {
        SELECTOR_KIND_READ   = 0,
        SELECTOR_KIND_WRITE  = 1,
        SELECTOR_KIND_EXCEPT = 2,
    };

    /**
     * Network address
     * Network addresses were previously represented as an uint32_t, but in
     * order to support IPv6 the internal representation was changed to a
     * struct.
     */
    struct Address
    {

        Address() {
            m_family = dmSocket::DOMAIN_MISSING;
            memset(m_address, 0x0, sizeof(m_address));
        }

        Domain m_family;
        uint32_t m_address[4];
    };

    /**
     * Comparison operators for dmSocket::Address (network address).
     * These operators are required since network code was initially designed
     * with the assumption that addresses were stored as uint32_t (IPv4), and
     * thus sortable.
     */
    inline bool operator==(const Address& lhs, const Address& rhs)
    {
        return memcmp(lhs.m_address, rhs.m_address, sizeof(lhs.m_address)) == 0;
    }

    inline bool operator< (const Address& lhs, const Address& rhs)
    {
        return memcmp(lhs.m_address, rhs.m_address, sizeof(lhs.m_address)) < 0;
    }

    inline bool operator!=(const Address& lhs, const Address& rhs) { return !operator==(lhs,rhs); }
    inline bool operator> (const Address& lhs, const Address& rhs) { return  operator< (rhs,lhs); }
    inline bool operator<=(const Address& lhs, const Address& rhs) { return !operator> (lhs,rhs); }
    inline bool operator>=(const Address& lhs, const Address& rhs) { return !operator< (lhs,rhs); }

    struct IfAddr
    {
        char     m_Name[128];
        uint32_t m_Flags;
        Address  m_Address;
        uint8_t  m_MacAddress[6];
    };

    /**
     * Initialize socket system. Network initialization is required on some platforms.
     * @return RESULT_OK on success
     */
    Result Initialize();

    /**
     * Finalize socket system.
     * @return RESULT_OK on success
     */
    Result Finalize();

    /**
     * Add multicast membership
     * @param socket socket to add membership on
     * @param multi_addr multicast address
     * @param interface_addr interface address
     * @param ttl multicast package time to live
     * @return RESULT_OK
     */
    Result AddMembership(Socket socket, Address multi_addr, Address interface_addr, int ttl);

    /**
     * Set address for outgoing multicast datagrams
     * @param socket socket to set multicast address for
     * @param address address of network interface to use
     * @return RESULT_OK
     */
    Result SetMulticastIf(Socket socket, Address address);

    /**
     * Accept a connection on a socket
     * @param socket Socket to accept connections on
     * @param address Result address parameter
     * @param accept_socket Pointer to accepted socket (result)
     * @return RESULT_OK on success
     */
    Result Accept(Socket socket, Address* address, Socket* accept_socket);

    /**
     * Bind a name to a socket
     * @param socket Socket to bind name to
     * @param address Address to bind
     * @param port Port to bind to
     * @return RESULT_OK on success
     */
    Result Bind(Socket socket, Address address, int port);

    /**
     * Initiate a connection on a socket
     * @param socket Socket to initiate connection on
     * @param address Address to connect to
     * @param port Port to connect to
     * @return RESULT_OK on success
     */
    Result Connect(Socket socket, Address address, int port);

    /**
     * Listen for connections on a socket
     * @param socket Socket to listen on
     * @param backlog Maximum length for the queue of pending connections
     * @return RESULT_OK on success
     */
    Result Listen(Socket socket, int backlog);

    /**
     * Shutdown part of a socket connection
     * @param socket Socket to shutdown connection ow
     * @param how Shutdown type
     * @return RESULT_OK on success
     */
    Result Shutdown(Socket socket, ShutdownType how);

    /**
     * Send a message to a specific address
     * @param socket Socket to send a message on
     * @param buffer Buffer to send
     * @param length Length of buffer to send
     * @param sent_bytes Number of bytes sent (result)
     * @param to_addr To address
     * @param to_port From addres
     * @return RESULT_OK on success
     */
    Result SendTo(Socket socket, const void* buffer, int length, int* sent_bytes, Address to_addr, uint16_t to_port);

    /**
     * Receive from socket
     * @param socket Socket to receive data on
     * @param buffer Buffer to receive to
     * @param length Receive buffer length
     * @param received_bytes Number of received bytes (result)
     * @param from_addr From address (result)
     * @param from_port To address (result)
     * @return RESULT_OK on success
     */
    Result ReceiveFrom(Socket socket, void* buffer, int length, int* received_bytes,
                       Address* from_addr, uint16_t* from_port);


    /**
     * Get name, address and port for socket
     * @param socket Socket to get name for
     * @param address Address (result)
     * @param port Socket (result)
     * @return RESULT_OK on success
     */
    Result GetName(Socket socket, Address*address, uint16_t* port);

    /**
     * Get local hostname
     * @param hostname hostname buffer
     * @param hostname_length hostname buffer length
     * @return RESULT_OK on success
     */
    Result GetHostname(char* hostname, int hostname_length);

    /**
     * Get first local IP address
     * The function tries to determine the local IP address. If several
     * IP addresses are available only a single is returned
     * @note This function might fallback to 127.0.0.1 if no adapter is found
     *       Sometimes it might be appropriate to run this function periodically
     * @param address address result
     * @return RESULT_OK on success
     */
    Result GetLocalAddress(Address* address);

    /**
     * Get address from ip string
     * @param address IP-string
     * @return Address
     */
    Address AddressFromIPString(const char* address);

    /**
     * Convert address to ip string
     * @param address address to convert
     * @return IP string. The caller is responsible to free the string using free()
     */
    char* AddressToIPString(Address address);

    /**
     * Get host by name.
     * @param name  Hostname to resolve
     * @param address Host address result
     * @param ipv4 Whether or not to search for IPv4 addresses
     * @param ipv6 Whether or not to search for IPv6 addresses
     * @return RESULT_OK on success
     */
    Result GetHostByName(const char* name, Address* address, bool ipv4 = true, bool ipv6 = true);

    /**
     * Get information about network adapters (loopback devices are not included)
     * @note Make sure that addresses is large enough. If too small
     * the result is capped.
     * @param addresses array of if-addresses
     * @param addresses_count count
     * @param count actual count
     */
    void GetIfAddresses(IfAddr* addresses, uint32_t addresses_count, uint32_t* count);

    /**
     * Converts a native result (error) to dmSocket::Result
     * Also logs the error
     * @param filename the file that calls this function
     * @param line the line number of this call
     * @param r the native result
     * @return Result
     */
    Result NativeToResult(const char* filename, int line, int r);

    /**
     * Check if a network address is empty (all zeroes).
     * @param address The address to check
     * @return True if the address is empty, false otherwise
     */
    bool Empty(Address address);

    /**
     * Return a pointer to the IPv4 buffer of address.
     * @note Make sure the address family of address is actually AF_INET before
     * attempting to retrieve the IPv4 buffer, otherwise an assert will trigger.
     * @param address Pointer to the address containing the buffer
     * @return Pointer to the buffer that holds the IPv4 address
     */
    uint32_t* IPv4(Address* address);

    /**
     * Return a pointer to the IPv6 buffer of address.
     * @note Make sure the address family of address is actually AF_INET6 before
     * attempting to retrieve the IPv6 buffer, otherwise an assert will trigger.
     * @param address Pointer to the address containing the buffer
     * @return Pointer to the buffer that holds the IPv6 address
     */
    uint32_t* IPv6(Address* address);

    /**
     * Checks if a socket was created for IPv4 (AF_INET).
     * @param socket The socket to check
     * @return True if the socket was created for IPv4 communication, false otherwise
     */
    bool IsSocketIPv4(Socket socket);

    /**
     * Checks if a socket was created for IPv6 (AF_INET6).
     * @param socket The socket to check
     * @return True if the socket was created for IPv6 communication, false otherwise
     */
    bool IsSocketIPv6(Socket socket);

    /**
     * Calculate the number of bits that differs between address a and b.
     * @note This is used for the Hamming Distance.
     * @param a The first address to compare
     * @param b The second address to compare
     * @return Number of bits that differs between a and b
     */
    uint32_t BitDifference(Address a, Address b);

    struct Selector;

        /**
     * Clear selector for socket. Similar to FD_CLR
     * @param selector Selector
     * @param selector_kind Kind to clear
     * @param socket Socket to clear
     */
    void SelectorClear(Selector* selector, SelectorKind selector_kind, Socket socket);

    /**
     * Set selector for socket. Similar to FD_SET
     * @param selector Selector
     * @param selector_kind Kind to clear
     * @param socket Socket to set
     */
    void SelectorSet(Selector* selector, SelectorKind selector_kind, Socket socket);

    /**
     * Check if selector is set. Similar to FD_ISSET
     * @param selector Selector
     * @param selector_kind Selector kind
     * @param socket Socket to check for
     * @return True if set.
     */
    bool SelectorIsSet(Selector* selector, SelectorKind selector_kind, Socket socket);

    /**
     * Clear selector (all kinds). Similar to FD_ZERO
     * @param selector Selector
     */
    void SelectorZero(Selector* selector);

    /**
     * Select for pending data
     * @param selector Selector
     * @param timeout Timeout. For blocking pass -1
     * @return RESULT_OK on success
     */
    Result Select(Selector* selector, int32_t timeout);
}

#endif // DM_SOCKET_H
