// SPDX-License-Identifier: MPL-2.0
//
// OpenVPN 3 Core client adapter. See docs/openvpn-core-integration.md for the
// exact upstream revision and the APIs used here.

#ifndef MONIRVPN_OVPN_CLIENT_HPP
#define MONIRVPN_OVPN_CLIENT_HPP

#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include <openvpn/log/lognull.hpp>
#include <openvpn/io/io.hpp>
#include <openvpn/client/dns.hpp>
#include <ovpncli.hpp>
#include <openvpn/tun/builder/base.hpp>

#include "callbacks.hpp"

namespace monirvpn {

struct TunAddress {
    std::string address;
    int prefix = 0;
    bool ipv6 = false;
};

struct TunRoute {
    std::string address;
    int prefix = 0;
    int metric = -1;
    bool ipv6 = false;
};

struct TunProxy {
    bool enabled = false;
    std::string host;
    int port = 0;
};

// Implements the platform side of OpenVPN 3 Core's TunBuilderBase callback
// interface. The collected configuration is serialized and handed to Kotlin,
// which owns the Android VpnService.Builder and returns the TUN fd.
class AndroidTunBuilder : public openvpn::TunBuilderBase {
  public:
    explicit AndroidTunBuilder(EngineCallbacks *callbacks)
        : callbacks_(callbacks)
    {
    }

    bool tun_builder_new() override;
    bool tun_builder_set_layer(int layer) override;
    bool tun_builder_set_remote_address(const std::string &address, bool ipv6) override;
    bool tun_builder_add_address(const std::string &address,
                                 int prefix_length,
                                 const std::string &gateway,
                                 bool ipv6,
                                 bool net30) override;
    bool tun_builder_set_route_metric_default(int metric) override;
    bool tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int flags) override;
    bool tun_builder_add_route(const std::string &address,
                               int prefix_length,
                               int metric,
                               bool ipv6) override;
    bool tun_builder_exclude_route(const std::string &address,
                                   int prefix_length,
                                   int metric,
                                   bool ipv6) override;
    bool tun_builder_add_dns_options(const openvpn::DnsOptions &dns) override;
    bool tun_builder_add_dns_server(const std::string &address, bool ipv6) override;
    bool tun_builder_add_search_domain(const std::string &domain) override;
    bool tun_builder_set_mtu(int mtu) override;
    bool tun_builder_set_session_name(const std::string &name) override;
    bool tun_builder_add_proxy_bypass(const std::string &bypass_host) override;
    bool tun_builder_set_proxy_auto_config_url(const std::string &url) override;
    bool tun_builder_set_proxy_http(const std::string &host, int port) override;
    bool tun_builder_set_proxy_https(const std::string &host, int port) override;
    bool tun_builder_add_wins_server(const std::string &address) override;
    bool tun_builder_set_allow_family(int af, bool allow) override;
    bool tun_builder_set_allow_local_dns(bool allow) override;
    bool tun_builder_set_adapter_domain_suffix(const std::string &name) override;
    int tun_builder_establish() override;
    bool tun_builder_persist() override;
    void tun_builder_establish_lite() override;
    void tun_builder_teardown(bool disconnect) override;

    // Application policy: deny an address family that the tunnel does not
    // provide, which prevents packets for that family escaping outside the VPN
    // on Android versions that support VpnService.Builder.allowFamily().
    void set_block_unused_families(bool block)
    {
        block_unused_families_ = block;
    }

    bool has_ipv6_address() const;

  private:
    void add_route_if_missing(const std::string &address, int prefix, int metric, bool ipv6);
    std::string to_json() const;
    bool dns_contains(const std::string &server) const;

    EngineCallbacks *callbacks_;
    std::vector<TunAddress> addresses_;
    std::vector<TunRoute> routes_;
    std::vector<TunRoute> exclude_routes_;
    std::vector<std::string> dns_servers_;
    std::vector<std::string> search_domains_;
    int mtu_ = 0;
    int layer_ = 3;
    std::string session_name_ = "OpenVPN";
    std::string remote_address_;
    TunProxy http_proxy_;
    bool allow_ipv4_ = true;
    bool allow_ipv6_ = true;
    bool allow_local_dns_ = true;
    bool block_unused_families_ = true;
    std::atomic<bool> established_{false};
};

class OvpnClient : public openvpn::ClientAPI::OpenVPNClient {
  public:
    explicit OvpnClient(EngineCallbacks *callbacks);

    bool socket_protect(openvpn_io::detail::socket_type socket,
                        std::string remote,
                        bool ipv6) override;

    void event(const openvpn::ClientAPI::Event &ev) override;
    void acc_event(const openvpn::ClientAPI::AppCustomControlMessageEvent &ev) override;
    void log(const openvpn::ClientAPI::LogInfo &log) override;
    bool pause_on_connection_timeout() override;
    void external_pki_cert_request(openvpn::ClientAPI::ExternalPKICertRequest &req) override;
    void external_pki_sign_request(openvpn::ClientAPI::ExternalPKISignRequest &req) override;

    // OpenVPNClient already derives from TunBuilderBase, so the core calls
    // these virtuals on OvpnClient. Delegate to the AndroidTunBuilder that
    // collects the configuration; otherwise Core would hit the base-class
    // defaults and fail with "tun_builder_new failed".
    bool tun_builder_new() override { return tun_builder_.tun_builder_new(); }
    bool tun_builder_set_layer(int layer) override { return tun_builder_.tun_builder_set_layer(layer); }
    bool tun_builder_set_remote_address(const std::string &address, bool ipv6) override
    { return tun_builder_.tun_builder_set_remote_address(address, ipv6); }
    bool tun_builder_add_address(const std::string &address, int prefix_length,
                                 const std::string &gateway, bool ipv6, bool net30) override
    { return tun_builder_.tun_builder_add_address(address, prefix_length, gateway, ipv6, net30); }
    bool tun_builder_set_route_metric_default(int metric) override
    { return tun_builder_.tun_builder_set_route_metric_default(metric); }
    bool tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int flags) override
    { return tun_builder_.tun_builder_reroute_gw(ipv4, ipv6, flags); }
    bool tun_builder_add_route(const std::string &address, int prefix_length, int metric, bool ipv6) override
    { return tun_builder_.tun_builder_add_route(address, prefix_length, metric, ipv6); }
    bool tun_builder_exclude_route(const std::string &address, int prefix_length, int metric, bool ipv6) override
    { return tun_builder_.tun_builder_exclude_route(address, prefix_length, metric, ipv6); }
    bool tun_builder_add_dns_options(const openvpn::DnsOptions &dns) override
    { return tun_builder_.tun_builder_add_dns_options(dns); }
    bool tun_builder_add_dns_server(const std::string &address, bool ipv6) override
    { return tun_builder_.tun_builder_add_dns_server(address, ipv6); }
    bool tun_builder_add_search_domain(const std::string &domain) override
    { return tun_builder_.tun_builder_add_search_domain(domain); }
    bool tun_builder_set_mtu(int mtu) override { return tun_builder_.tun_builder_set_mtu(mtu); }
    bool tun_builder_set_session_name(const std::string &name) override
    { return tun_builder_.tun_builder_set_session_name(name); }
    bool tun_builder_add_proxy_bypass(const std::string &host) override
    { return tun_builder_.tun_builder_add_proxy_bypass(host); }
    bool tun_builder_set_proxy_auto_config_url(const std::string &url) override
    { return tun_builder_.tun_builder_set_proxy_auto_config_url(url); }
    bool tun_builder_set_proxy_http(const std::string &host, int port) override
    { return tun_builder_.tun_builder_set_proxy_http(host, port); }
    bool tun_builder_set_proxy_https(const std::string &host, int port) override
    { return tun_builder_.tun_builder_set_proxy_https(host, port); }
    bool tun_builder_add_wins_server(const std::string &address) override
    { return tun_builder_.tun_builder_add_wins_server(address); }
    bool tun_builder_set_allow_family(int af, bool allow) override
    { return tun_builder_.tun_builder_set_allow_family(af, allow); }
    bool tun_builder_set_allow_local_dns(bool allow) override
    { return tun_builder_.tun_builder_set_allow_local_dns(allow); }
    bool tun_builder_set_adapter_domain_suffix(const std::string &name) override
    { return tun_builder_.tun_builder_set_adapter_domain_suffix(name); }
    int tun_builder_establish() override { return tun_builder_.tun_builder_establish(); }
    bool tun_builder_persist() override { return tun_builder_.tun_builder_persist(); }
    void tun_builder_establish_lite() override { tun_builder_.tun_builder_establish_lite(); }
    void tun_builder_teardown(bool disconnect) override { tun_builder_.tun_builder_teardown(disconnect); }

    void set_block_unused_families(bool block)
    {
        tun_builder_.set_block_unused_families(block);
    }

    const std::string &dynamic_challenge_cookie() const
    {
        return dynamic_challenge_cookie_;
    }

  private:
    EngineCallbacks *callbacks_;
    AndroidTunBuilder tun_builder_;
    std::string dynamic_challenge_cookie_;
    // Set once this client has reached CONNECTED. A later connection-timeout
    // is a reconnect attempt, not a failed first connection.
    bool reached_connected_ = false;
};

} // namespace monirvpn

#endif // MONIRVPN_OVPN_CLIENT_HPP
