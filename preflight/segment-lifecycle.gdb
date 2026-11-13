set pagination off
set breakpoint pending on
set debuginfod enabled off
handle SIGSEGV nostop noprint pass
handle SIGILL nostop noprint pass
break vcl_segment_attach
commands
silent
printf "ATTACH worker=%lu handle=%lx name=%s\n", __vcl_worker_index, segment_handle, name
continue
end
break vcl_segment_detach
commands
silent
printf "DETACH worker=%lu handle=%lx\n", __vcl_worker_index, segment_handle
bt 4
continue
end
run
bt
