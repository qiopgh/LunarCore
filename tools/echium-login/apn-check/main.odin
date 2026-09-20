package main

import "base:runtime"
import "core:fmt"
import "core:os"
import "core:strings"
import "../echium/cfg"
import "../echium/patches"

captured: string
endpoint_value: string

// 只替换原回调的接收端，执行真实 URL 分支，不访问网络或游戏。
receive :: proc "c" (rcx, rdx, r8, r9: rawptr) -> rawptr {
	context = runtime.default_context()
	captured = strings.clone(string((cast([^]u8)(rdx))[:int(uintptr(r8))]))
	return nil
}

endpoint_lookup :: proc "c" (rcx, rdx, r8: rawptr) -> rawptr {
	value := cast(^patches.Apn_Std_String)(rdx)
	value.size = uintptr(len(endpoint_value))
	value.capacity = uintptr(len(endpoint_value))
	(cast(^rawptr)(rdx))^ = raw_data(endpoint_value)
	return rdx
}

main :: proc () {
	cfg.load()
	patches.orig_apn_alloc = receive
	patches.orig_apn_endpoint_lookup = endpoint_lookup
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
	for input, i in inputs {
		captured = ""
		rewritten := patches.apn_rewrite_endpoint(nil, input)
		expected := "http://127.0.0.1:21001/account/test?fixture=1" if i < 2 else ""
		ok := captured == expected && rewritten == (i < 2)
		fmt.printf("APN_ENDPOINT_CASE=%d MATCH=%v REWRITTEN=%v RESULT=%s\n", i, ok, rewritten, captured)
		if !ok { failures += 1 }
		if captured != "" { delete(captured) }
	}
	for input, i in inputs {
		endpoint_value = input
		captured = ""
		value := patches.Apn_Std_String {}
		result := patches.patched_apn_endpoint_lookup(nil, &value, nil)
		view, readable := patches.apn_std_string_view(result)
		expected := "http://127.0.0.1:21001/account/test?fixture=1" if i < 2 else ""
		ok := readable && view == input && captured == expected
		fmt.printf("APN_ENDPOINT_HOOK_CASE=%d MATCH=%v READABLE=%v CAPTURED=%s\n", i, ok, readable, captured)
		if !ok { failures += 1 }
		if captured != "" { delete(captured) }
	}
	if failures != 0 { os.exit(2) }
}
