package main

import (
	"strings"
	"testing"
)

// A secret-shaped marker: it must never come back in an error.
const secretAddress = "tcSECRETmarker0123456789"

func TestBadAddressReturnsNoHandleAndARedactedError(t *testing.T) {
	for _, address := range []string{"", "junk", secretAddress, "tc" + strings.Repeat("A", 5000)} {
		handle, url, state, message := startClient(address, "", "")
		if handle != 0 || url != "" || state != "" {
			t.Fatalf("startClient(%.12q) = %d %q %q, want a refusal", address, handle, url, state)
		}
		if message == "" {
			t.Fatalf("startClient(%.12q) gave no error", address)
		}
		if address != "" && strings.Contains(message, address) || strings.Contains(message, "SECRET") {
			t.Fatalf("error echoes the address: %q", message)
		}
	}
	if open := openHandles(); open != 0 {
		t.Fatalf("%d handles left open after refusals", open)
	}
}

func TestCorruptStateIsRefusedWithoutEchoingIt(t *testing.T) {
	// The address is checked first, so a bad address wins over bad state;
	// the state check itself is covered in the adapter package. Either way
	// the state text never comes back.
	_, _, _, message := startClient(secretAddress, `{"private_key":"SECRETSTATE"}`, "")
	if strings.Contains(message, "SECRET") {
		t.Fatalf("error echoes its inputs: %q", message)
	}
}

func TestCloseIsIdempotentAndIgnoresUnknownHandles(t *testing.T) {
	for _, handle := range []int64{0, -1, 1, 42, 42} {
		closeClient(handle)
	}
	if open := openHandles(); open != 0 {
		t.Fatalf("%d handles open", open)
	}
}
