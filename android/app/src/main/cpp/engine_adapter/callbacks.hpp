// SPDX-License-Identifier: MPL-2.0
//
// Small C++ <-> JNI adapter around the official OpenVPN 3 Core client API.
// The protocol, TLS, certificate validation and packet framing are entirely
// implemented by OpenVPN 3 Core (pinned revision in third_party/openvpn3).

#ifndef MONIRVPN_ENGINE_CALLBACKS_HPP
#define MONIRVPN_ENGINE_CALLBACKS_HPP

#include <string>

namespace monirvpn {

// Interface implemented by the JNI session and consumed by the engine adapter.
// Keeping this interface free of JNI types makes the engine adapter easier to
// reason about and keeps all JNI object ownership in native_bridge.cpp.
class EngineCallbacks {
  public:
    virtual ~EngineCallbacks() = default;

    virtual void on_event(const std::string &name,
                          const std::string &info,
                          bool error,
                          bool fatal) = 0;
    virtual void on_log(const std::string &line) = 0;
    virtual bool on_protect_socket(int fd, const std::string &remote, bool ipv6) = 0;
    virtual int on_establish_tun(const std::string &config_json) = 0;
    virtual void on_challenge(const std::string &challenge,
                              bool echo,
                              bool response_required,
                              const std::string &state_id) = 0;
};

} // namespace monirvpn

#endif // MONIRVPN_ENGINE_CALLBACKS_HPP
