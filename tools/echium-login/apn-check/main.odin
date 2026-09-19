package main

import "base:runtime"
import "core:fmt"
import "core:os"
import "core:strings"
import "../echium/cfg"
import "../echium/patches"

captured: string

// 只替换原回调的接收端，执行真实 URL 分支，不访问网络或游戏。
receive :: proc "c" (rcx, rdx, r8, r9: rawptr) -> rawptr {
	context = runtime.default_context()
	captured = strings.clone(string((cast([^]u8)(rdx))[:int(uintptr(r8))]))
	return nil
}

main :: proc () {
	cfg.load()
	patches.orig_apn_alloc = receive
	patches.detour_logger = context.logger
	inputs := [3]string {
		"https://api-takumi.mihoyo.com/account/test?fixture=1",
		"https://api-account-os.hoyoverse.com/account/test?fixture=1",
		"https://example.invalid/account/test?fixture=1",
	}
	failures := 0
	for input, i in inputs {
		patches.patched_apn_alloc(nil, raw_data(input), rawptr(uintptr(len(input))), nil)
		expected := "http://127.0.0.1:21001/account/test?fixture=1" if i < 2 else input
		ok := captured == expected
		fmt.printf("APN_URL_CASE=%d MATCH=%v RESULT=%s\n", i, ok, captured)
		if !ok { failures += 1 }
		delete(captured)
	}
	if failures != 0 { os.exit(2) }
}
