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
