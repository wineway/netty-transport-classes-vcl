/* SPDX-License-Identifier: Apache-2.0 */
/* Test-only header probe. Never linked into the Java transport. */
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <arpa/inet.h>
#include <vcl/vppcom.h>
typedef struct epoll_event epoll_event;
typedef void *pointer;

#define TYPE(t) printf(#t ".size=%zu\n" #t ".align=%zu\n", sizeof(t), _Alignof(t))
#define FIELD(t, f) printf(#t "." #f "=%zu\n", offsetof(t, f))
#define CFG(f) FIELD(vppcom_cfg_t, f)
#define NUM(n) printf(#n "=%lld\n", (long long)(n))
int main(void) {
  TYPE(vppcom_endpt_t);
  FIELD(vppcom_endpt_t, unused); FIELD(vppcom_endpt_t, is_ip4);
  FIELD(vppcom_endpt_t, ip); FIELD(vppcom_endpt_t, port);
  FIELD(vppcom_endpt_t, unused2); FIELD(vppcom_endpt_t, app_tlv_len);
  FIELD(vppcom_endpt_t, app_tlvs);
  TYPE(vppcom_data_segment_t);
  FIELD(vppcom_data_segment_t, data); FIELD(vppcom_data_segment_t, len);
  TYPE(vcl_poll_t); FIELD(vcl_poll_t, fds_ndx); FIELD(vcl_poll_t, sh);
  FIELD(vcl_poll_t, events); FIELD(vcl_poll_t, revents);
  TYPE(vppcom_cfg_t);
  CFG(heapsize); CFG(max_workers); CFG(segment_baseva); CFG(segment_size);
  CFG(add_segment_size); CFG(preallocated_fifo_pairs); CFG(rx_fifo_size);
  CFG(tx_fifo_size); CFG(event_queue_size); CFG(app_proxy_transport_tcp);
  CFG(app_proxy_transport_udp); CFG(app_scope_local); CFG(app_scope_global);
  CFG(namespace_id); CFG(namespace_secret); CFG(use_mq_eventfd); CFG(app_timeout);
  CFG(session_timeout); CFG(event_log_path); CFG(vpp_app_socket_api);
  CFG(vpp_bapi_socket_name); CFG(app_name); CFG(tls_engine); CFG(mt_wrk_supported);
  CFG(huge_page); CFG(app_original_dst); CFG(debug_level);
  TYPE(epoll_event); FIELD(epoll_event, events); FIELD(epoll_event, data);
  TYPE(pointer); TYPE(size_t); TYPE(uint32_t); TYPE(uint16_t); TYPE(int); TYPE(long);
  NUM(EPOLLIN); NUM(EPOLLOUT); NUM(EPOLLERR); NUM(EPOLLHUP); NUM(EPOLLRDHUP);
  NUM(EPOLL_CTL_ADD); NUM(EPOLL_CTL_MOD); NUM(EPOLL_CTL_DEL);
  NUM(VPPCOM_PROTO_TCP); NUM(VPPCOM_PROTO_UDP); NUM(VPPCOM_EAGAIN);
  NUM(VPPCOM_EINPROGRESS); NUM(VPPCOM_ENOTSUP); NUM(O_NONBLOCK);
  NUM(VPPCOM_ATTR_GET_NREAD); NUM(VPPCOM_ATTR_GET_LCL_ADDR);
  NUM(htons(0x1234));
  return 0;
}
