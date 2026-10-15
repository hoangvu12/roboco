//go:build android

package main

/*
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <sys/socket.h>

static int roboco_family(const struct sockaddr* sa) { return sa ? sa->sa_family : -1; }
static const void* roboco_in4(const struct sockaddr* sa) { return &((const struct sockaddr_in*)sa)->sin_addr; }
static const void* roboco_in6(const struct sockaddr* sa) { return &((const struct sockaddr_in6*)sa)->sin6_addr; }
*/
import "C"

import (
	"errors"
	"net"
	"unsafe"

	"tailscale.com/net/netmon"
)

// Apps targeting API 30+ may not bind a netlink socket, so Go's
// net.Interfaces (RTM_GETLINK) fails with EACCES (golang/go#40569) and
// tailcat's netmon.New with it: no client could start. bionic's getifaddrs
// is allowed (RTM_GETADDR only, names via if_indextoname, flags via
// SIOCGIFFLAGS), so netmon gets its interfaces from there, the way the
// Tailscale app feeds them from Java. Interface.AltAddrs is always non-nil:
// a nil value would make netmon call Interface.Addrs, which is netlink again.
func init() { netmon.RegisterInterfaceGetter(androidInterfaces) }

func androidInterfaces() ([]netmon.Interface, error) {
	var head *C.struct_ifaddrs
	if rc, err := C.getifaddrs(&head); rc != 0 {
		if err == nil {
			err = errors.New("failed")
		}
		return nil, errors.New("getifaddrs: " + err.Error())
	}
	defer C.freeifaddrs(head)

	var interfaces []netmon.Interface
	byName := map[string]int{}
	for entry := head; entry != nil; entry = entry.ifa_next {
		name := C.GoString(entry.ifa_name)
		index, seen := byName[name]
		if !seen {
			index = len(interfaces)
			byName[name] = index
			interfaces = append(interfaces, netmon.Interface{
				Interface: &net.Interface{
					Name:  name,
					Index: int(C.if_nametoindex(entry.ifa_name)),
					Flags: interfaceFlags(uint32(entry.ifa_flags)),
				},
				AltAddrs: []net.Addr{},
			})
		}
		if address := ipNet(entry.ifa_addr, entry.ifa_netmask); address != nil {
			interfaces[index].AltAddrs = append(interfaces[index].AltAddrs, address)
		}
	}
	return interfaces, nil
}

func interfaceFlags(flags uint32) net.Flags {
	var out net.Flags
	for _, pair := range []struct {
		bit  uint32
		flag net.Flags
	}{
		{C.IFF_UP, net.FlagUp},
		{C.IFF_BROADCAST, net.FlagBroadcast},
		{C.IFF_LOOPBACK, net.FlagLoopback},
		{C.IFF_POINTOPOINT, net.FlagPointToPoint},
		{C.IFF_MULTICAST, net.FlagMulticast},
		{C.IFF_RUNNING, net.FlagRunning},
	} {
		if flags&pair.bit != 0 {
			out |= pair.flag
		}
	}
	return out
}

// ipNet converts an AF_INET/AF_INET6 address and its netmask; anything else
// (or no address) is nil. netmon only counts *net.IPNet addresses.
func ipNet(address, netmask *C.struct_sockaddr) *net.IPNet {
	family := C.roboco_family(address)
	var size C.int
	switch family {
	case C.AF_INET:
		size = 4
	case C.AF_INET6:
		size = 16
	default:
		return nil
	}
	ip := net.IP(C.GoBytes(sockaddrBytes(address, family), size))
	mask := net.CIDRMask(int(size)*8, int(size)*8)
	if C.roboco_family(netmask) == family {
		mask = net.IPMask(C.GoBytes(sockaddrBytes(netmask, family), size))
	}
	return &net.IPNet{IP: ip, Mask: mask}
}

func sockaddrBytes(sa *C.struct_sockaddr, family C.int) unsafe.Pointer {
	if family == C.AF_INET {
		return C.roboco_in4(sa)
	}
	return C.roboco_in6(sa)
}
