package main

/*
#include <stdint.h>
#include <stdlib.h>
*/
import "C"

import "unsafe"

// The C ABI (android-next#02). Every string the library returns is
// allocated with malloc and must be released with roboco_tailcat_free.
//
//	int32_t roboco_tailcat_abi_version(void);
//	int64_t roboco_tailcat_start_client(const char* address, const char* state_json,
//	        const char* derp_map, char** out_url, char** out_state_json, char** out_error);
//	void    roboco_tailcat_close(int64_t handle);
//	void    roboco_tailcat_free(char* s);
//	int32_t roboco_tailcat_path(int64_t handle, char** out_json);
//	void    roboco_tailcat_set_background(int64_t handle, int32_t background);
//
// The last two were added after ABI version 1 without bumping it: they are
// additive, and the Rust loader resolves them optionally.

//export roboco_tailcat_abi_version
func roboco_tailcat_abi_version() C.int32_t { return C.int32_t(abiVersion) }

// roboco_tailcat_start_client starts a client for address (a secret tc…
// address) with the caller-held identity state_json (NULL or "" creates
// one) and an optional DERP map URL. It blocks for up to ~20 s while the
// tunnel comes up. On success it returns a handle > 0 and sets *out_url
// (http://127.0.0.1:<port>) and *out_state_json (the identity to store). On
// failure it returns 0 and sets *out_error to a redacted message that never
// contains the address.
//
//export roboco_tailcat_start_client
func roboco_tailcat_start_client(address, stateJSON, derpMap *C.char, outURL, outState, outError **C.char) C.int64_t {
	for _, out := range []**C.char{outURL, outState, outError} {
		if out != nil {
			*out = nil
		}
	}
	if outURL == nil || outState == nil {
		put(outError, "Tailcat client startup needs out_url and out_state_json")
		return 0
	}
	handle, url, state, message := startClient(C.GoString(address), C.GoString(stateJSON), C.GoString(derpMap))
	if handle == 0 {
		put(outError, message)
		return 0
	}
	put(outURL, url)
	put(outState, state)
	return C.int64_t(handle)
}

// roboco_tailcat_close stops a client and its loopback listener. Unknown
// and already closed handles are ignored.
//
//export roboco_tailcat_close
func roboco_tailcat_close(handle C.int64_t) { closeClient(int64(handle)) }

// roboco_tailcat_path reports how a client last reached its server
// (fleet-redesign 16). It returns 1 and sets *out_json to a status line —
// {"path":"direct","rttMs":35} or {"path":"relay","region":"tok","rttMs":160}
// — or returns 0 while the path is unknown (and for unknown handles). The
// line never carries an address or a key.
//
//export roboco_tailcat_path
func roboco_tailcat_path(handle C.int64_t, outJSON **C.char) C.int32_t {
	if outJSON == nil {
		return 0
	}
	*outJSON = nil
	line := pathOf(int64(handle))
	if line == "" {
		return 0
	}
	put(outJSON, line)
	return 1
}

// roboco_tailcat_set_background pauses a client's path pings while the app
// is in the background (non-zero) and re-arms them when it returns (zero),
// keeping the phone's cadence low there (ADR 0010).
//
//export roboco_tailcat_set_background
func roboco_tailcat_set_background(handle C.int64_t, background C.int32_t) {
	setBackground(int64(handle), background != 0)
}

// roboco_tailcat_free releases a string this library returned. NULL is
// ignored.
//
//export roboco_tailcat_free
func roboco_tailcat_free(s *C.char) { C.free(unsafe.Pointer(s)) }

func put(out **C.char, value string) {
	if out != nil {
		*out = C.CString(value)
	}
}

// cString and goString let the cgo tests (which cannot import "C") build
// and read C strings.
func cString(value string) *C.char { return C.CString(value) }

func goString(value *C.char) string { return C.GoString(value) }

// nullCString is a typed NULL for the tests' out parameters.
func nullCString() *C.char { return nil }
