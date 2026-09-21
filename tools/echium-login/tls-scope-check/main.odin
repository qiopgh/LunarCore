package main

import "core:fmt"
import "core:os"
import "candidate:cfg"
import "candidate:patches"

Fake_Easy :: struct {
	padding_before_verify: [0x390]u8,
	verify_peer:           u8,
	verify_host:           u8,
	padding_before_url:    [0x296]u8,
	url:                   cstring,
}

Case :: struct {
	name:     string,
	url:      string,
	expected: bool,
}

main :: proc() {
	cfg.cfg.local_tls_mitm = true
	cfg.cfg.tls_mitm_hosts = []string{
		"api-account-os.hoyoverse.com",
		"hoyoverse.com",
	}

	cases := []Case{
		{"允许精确域名隐式443", "https://api-account-os.hoyoverse.com/account", true},
		{"允许精确域名显式443", "https://api-account-os.hoyoverse.com:443/account", true},
		{"拒绝伪子域", "https://api-account-os.hoyoverse.com.evil.invalid/account", false},
		{"拒绝清单外同根子域", "https://outside.hoyoverse.com/account", false},
		{"拒绝错误scheme", "http://api-account-os.hoyoverse.com/account", false},
		{"拒绝错误端口", "https://api-account-os.hoyoverse.com:444/account", false},
		{"拒绝userinfo", "https://user@api-account-os.hoyoverse.com/account", false},
	}

	failures := 0
	for item in cases {
		actual := patches.apn_tls_url_allowed(item.url)
		fmt.printf("CASE name=%s expected=%v actual=%v\n", item.name, item.expected, actual)
		if actual != item.expected {
			failures += 1
		}
	}

	easy := Fake_Easy{verify_peer = 1, verify_host = 1}
	allowed := patches.apn_tls_url_allowed("https://api-account-os.hoyoverse.com/account")
	patches.apn_tls_set_verify(&easy, !allowed)
	fmt.printf("HANDLE step=allowed peer=%v host=%v\n", easy.verify_peer, easy.verify_host)
	if easy.verify_peer != 0 || easy.verify_host != 0 {
		failures += 1
	}

	reused_allowed := patches.apn_tls_url_allowed("https://api-account-os.hoyoverse.com:444/account")
	patches.apn_tls_set_verify(&easy, !reused_allowed)
	fmt.printf("HANDLE step=reused_out_of_scope peer=%v host=%v\n", easy.verify_peer, easy.verify_host)
	if easy.verify_peer != 1 || easy.verify_host != 1 {
		failures += 1
	}

	if failures != 0 {
		fmt.eprintf("TLS_SCOPE_NATIVE_FAIL failures=%d\n", failures)
		os.exit(1)
	}

	fmt.println("TLS_SCOPE_NATIVE_PASS")
}
