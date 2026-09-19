package main

import "core:fmt"
import "core:os"
import "../echium/cfg"

// 直接调用原配置加载器，不模拟 JSON 默认值或错误回退。
main :: proc () {
	cfg.load()
	fmt.printf("redirect_http=%v patch_rsa=%v patch_censorship=%v sdk_url=%s\n",
		cfg.cfg.redirect_http, cfg.cfg.patch_rsa, cfg.cfg.patch_censorship, cfg.cfg.sdk_url)
	if !cfg.cfg.redirect_http || cfg.cfg.patch_rsa || cfg.cfg.patch_censorship || cfg.cfg.sdk_url != "http://127.0.0.1:21001" {
		os.exit(2)
	}
}
