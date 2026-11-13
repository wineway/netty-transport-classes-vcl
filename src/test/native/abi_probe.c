/* Test-only header check. This file is never linked into the transport. */
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/eventfd.h>
#include <vcl/vppcom.h>

_Static_assert(sizeof(void *) == 8, "64-bit pointers required");
_Static_assert(sizeof(vppcom_endpt_t) == 48, "endpoint ABI");
_Static_assert(offsetof(vppcom_endpt_t, is_ip4) == 1, "is_ip4 offset");
_Static_assert(offsetof(vppcom_endpt_t, ip) == 8, "ip offset");
_Static_assert(offsetof(vppcom_endpt_t, port) == 16, "port offset");
_Static_assert(offsetof(vppcom_endpt_t, unused2) == 24, "unused2 offset");
_Static_assert(offsetof(vppcom_endpt_t, app_tlv_len) == 32, "app_tlv_len offset");
_Static_assert(offsetof(vppcom_endpt_t, app_tlvs) == 40, "app_tlvs offset");
_Static_assert(sizeof(struct epoll_event) == 12, "epoll_event ABI");
_Static_assert(offsetof(struct epoll_event, data) == 4, "epoll_event packed data");
_Static_assert(sizeof(struct pollfd) == 8, "pollfd ABI");
_Static_assert(VPPCOM_ATTR_GET_LCL_ADDR == 4 && VPPCOM_ATTR_GET_PEER_ADDR == 6, "address attributes");
_Static_assert(EPOLLIN == 1 && EPOLLOUT == 4 && EPOLLERR == 8 && EPOLLHUP == 16 && EPOLLRDHUP == 0x2000, "epoll flags");
_Static_assert(EPOLL_CTL_ADD == 1 && EPOLL_CTL_DEL == 2 && EPOLL_CTL_MOD == 3, "epoll operations");
_Static_assert(O_NONBLOCK == 0x800 && EFD_NONBLOCK == 0x800 && EFD_CLOEXEC == 0x80000, "fd flags");
_Static_assert(EAGAIN == 11 && EINPROGRESS == 115 && EINTR == 4, "errno ABI");
int main(void) { puts("VCL 25.10/Linux x86_64 ABI verified"); return 0; }
