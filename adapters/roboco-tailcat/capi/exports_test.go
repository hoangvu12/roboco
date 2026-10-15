//go:build cgo

package main

import (
	"strings"
	"testing"
)

func TestABIVersionIsOne(t *testing.T) {
	if got := roboco_tailcat_abi_version(); got != 1 {
		t.Fatalf("abi version = %d, want 1", got)
	}
}

func TestStartClientThroughTheCABIRefusesABadAddress(t *testing.T) {
	address := cString(secretAddress)
	defer roboco_tailcat_free(address)
	url, state, message := nullCString(), nullCString(), nullCString()
	handle := roboco_tailcat_start_client(address, nil, nil, &url, &state, &message)
	if handle != 0 {
		roboco_tailcat_close(handle)
		t.Fatalf("handle = %d, want 0", handle)
	}
	if url != nil || state != nil {
		t.Fatal("outputs set on failure")
	}
	if message == nil {
		t.Fatal("no error message")
	}
	text := goString(message)
	roboco_tailcat_free(message)
	if text == "" || strings.Contains(text, "SECRET") {
		t.Fatalf("error = %q, want a non-empty redacted message", text)
	}
}

func TestStartClientNeedsItsOutputPointers(t *testing.T) {
	address := cString(secretAddress)
	defer roboco_tailcat_free(address)
	message := nullCString()
	if handle := roboco_tailcat_start_client(address, nil, nil, nil, nil, &message); handle != 0 {
		t.Fatalf("handle = %d, want 0", handle)
	}
	if message == nil {
		t.Fatal("no error message")
	}
	roboco_tailcat_free(message)
	// No out_error at all must not crash either.
	if handle := roboco_tailcat_start_client(address, nil, nil, nil, nil, nil); handle != 0 {
		t.Fatalf("handle = %d, want 0", handle)
	}
}

func TestCloseAndFreeTolerateRepeatsAndNull(t *testing.T) {
	roboco_tailcat_close(0)
	roboco_tailcat_close(7)
	roboco_tailcat_close(7)
	roboco_tailcat_free(nil)
	if open := openHandles(); open != 0 {
		t.Fatalf("%d handles open", open)
	}
}
