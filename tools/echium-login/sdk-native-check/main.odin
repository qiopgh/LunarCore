package main

import "base:runtime"
import "core:fmt"
import "core:os"
import "core:strings"
import "../echium/cfg"
import "../echium/patches"

Url_Case :: struct {
	input:    string,
	expected: string,
}

captured: string

// 只替换原字符串赋值接收端，直接执行候选回调，不访问网络或游戏。
receive :: proc "c" (rcx, rdx, r8, r9: rawptr) -> rawptr {
	context = runtime.default_context()
	captured = strings.clone(string((cast([^]u8)(rdx))[:int(uintptr(r8))]))
	return nil
}

main :: proc () {
	cfg.cfg.redirect_http = true
	cfg.cfg.sdk_url = "http://127.0.0.1:21001"
	patches.orig_hoyo_network_alloc = receive
	patches.orig_sdk_fallback_alloc = receive
	patches.detour_logger = context.logger

	cases := []Url_Case {
		{"https://api-account-os.hoyoverse.com/account/ma-passport/api/appLoginByPassword?fixture=1#step", "http://127.0.0.1:21001/account/ma-passport/api/appLoginByPassword?fixture=1#step"},
		{"https://passport-api.mihoyo.com/account/ma-cn-passport/app/loginByPassword?fixture=2", "http://127.0.0.1:21001/account/ma-cn-passport/app/loginByPassword?fixture=2"},
		{"https://hoyoverse.com", "http://127.0.0.1:21001"},
		{"https://globaldp-prod-os01.starrails.com?fixture=3", "http://127.0.0.1:21001?fixture=3"},
		{"https://dispatch.bhsr.com#fixture", "http://127.0.0.1:21001#fixture"},
		{"HTTPS://API-ACCOUNT-OS.HOYOVERSE.COM:443/account/test", "http://127.0.0.1:21001/account/test"},
		{"https://example.invalid/account/test", "https://example.invalid/account/test"},
		{"https://hoyoverse.com.evil.invalid/account/test", "https://hoyoverse.com.evil.invalid/account/test"},
		{"https://fixture@api-account-os.hoyoverse.com/account/test", "https://fixture@api-account-os.hoyoverse.com/account/test"},
		{"file://api-account-os.hoyoverse.com/account/test", "file://api-account-os.hoyoverse.com/account/test"},
	}

	failures := 0
	for test, i in cases {
		patches.patched_hoyo_network_alloc(nil, raw_data(test.input), rawptr(uintptr(len(test.input))), nil)
		hoyo_ok := captured == test.expected
		fmt.printf("SDK_NATIVE_URL_CASE=%d SOURCE=HOYO_NATIVE MATCH=%v RESULT=%s\n", i, hoyo_ok, captured)
		if !hoyo_ok { failures += 1 }
		delete(captured)

		patches.patched_sdk_fallback_alloc(nil, raw_data(test.input), rawptr(uintptr(len(test.input))), nil)
		fallback_ok := captured == test.expected
		fmt.printf("SDK_NATIVE_URL_CASE=%d SOURCE=SDK_FALLBACK MATCH=%v RESULT=%s\n", i, fallback_ok, captured)
		if !fallback_ok { failures += 1 }
		delete(captured)
	}

	fmt.printf("SDK_NATIVE_URL_CHECK PASS=%v CASES=%d CALLBACKS=%d\n", failures == 0, len(cases), len(cases) * 2)
	if failures != 0 { os.exit(2) }
}
