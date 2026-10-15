//go:build android

package main

import "os"

// Go's crypto/x509 reads only /system/etc/security/cacerts (and the user
// store) on Android (crypto/x509/root_linux.go); since Android 14 the
// up-to-date roots ship in the Conscrypt APEX (golang/go#71258). The DERP
// map fetch (https://tailcat.dev/derpmap.json) uses those system roots, so
// list the APEX directory first. SSL_CERT_DIR replaces Go's whole Android
// directory list, so the system and user-added directories stay listed.
// A c-shared library starts with an empty Go environment
// (runtime/rt0_android_*.s), so the app never set it; an unreadable or
// missing directory is skipped (crypto/x509/root.go loadOnDiskRoots).
func init() {
	if os.Getenv("SSL_CERT_DIR") == "" {
		_ = os.Setenv("SSL_CERT_DIR", "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts:/data/misc/keychain/certs-added")
	}
}
