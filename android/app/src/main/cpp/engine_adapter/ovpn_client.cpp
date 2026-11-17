// SPDX-License-Identifier: MPL-2.0

#include "ovpn_client.hpp"

#include <algorithm>
#include <sstream>

#include <sys/socket.h>

namespace monirvpn {
namespace {

std::string json_escape(const std::string &in)
{
    std::string out;
    out.reserve(in.size() + 8);
    for (unsigned char c : in)
    {
        switch (c)
        {
        case '"':
            out += "\\\"";
            break;
        case '\\':
            out += "\\\\";
            break;
        case '\b':
            out += "\\b";
            break;
        case '\f':
            out += "\\f";
            break;
        case '\n':
            out += "\\n";
            break;
        case '\r':
            out += "\\r";
            break;
        case '\t':
            out += "\\t";
            break;
        default:
            if (c < 0x20)
            {
                static const char hex[] = "0123456789abcdef";
                out += "\\u00";
                out += hex[(c >> 4) & 0x0f];
                out += hex[c & 0x0f];
            }
            else
            {
                out += static_cast<char>(c);
            }
        }
    }
    return out;
}

void append_address(std::ostringstream &os, const TunAddress &a, bool &first)
{
    if (!first)
        os << ',';
    first = false;
    os << "{\"address\":\"" << json_escape(a.address) << "\",\"prefix\":" << a.prefix
       << ",\"ipv6\":" << (a.ipv6 ? "true" : "false") << '}';
}

void append_route(std::ostringstream &os, const TunRoute &r, bool &first)
{
    if (!first)
        os << ',';
    first = false;
    os << "{\"address\":\"" << json_escape(r.address) << "\",\"prefix\":" << r.prefix
       << ",\"metric\":" << r.metric << ",\"ipv6\":" << (r.ipv6 ? "true" : "false") << '}';
}

} // namespace

bool AndroidTunBuilder::tun_builder_new()
{
    addresses_.clear();
    routes_.clear();
    exclude_routes_.clear();
    dns_servers_.clear();
    search_domains_.clear();
    mtu_ = 0;
    layer_ = 3;
    session_name_ = "OpenVPN";
    remote_address_.clear();
    http_proxy_ = TunProxy{};
    allow_ipv4_ = true;
    allow_ipv6_ = true;
    allow_local_dns_ = true;
    established_ = false;
    return true;
}

bool AndroidTunBuilder::tun_builder_set_layer(int layer)
{
    if (layer != 3)
        return false;
    layer_ = layer;
    return true;
}

bool AndroidTunBuilder::tun_builder_set_remote_address(const std::string &address, bool ipv6)
{
    (void)ipv6;
    remote_address_ = address;
    return true;
}

bool AndroidTunBuilder::tun_builder_add_address(const std::string &address,
                                                int prefix_length,
                                                const std::string &gateway,
                                                bool ipv6,
                                                bool net30)
{
    (void)gateway;
    // Legacy "topology net30" servers pass the client address with a /30 prefix
    // and the server's virtual address as the gateway. Android has no
    // point-to-point interface concept, but a /30 address is valid for
    // VpnService.Builder.addAddress() and the routes (including the full-tunnel
    // default route) are installed separately, so net30 servers work without a
    // synthetic gateway. Rejecting net30 made the client fail against every
    // OpenVPN server that uses the legacy default topology.
    (void)net30;
    if (prefix_length < 0 || prefix_length > (ipv6 ? 128 : 32))
        return false;
    addresses_.push_back(TunAddress{address, prefix_length, ipv6});
    return true;
}

bool AndroidTunBuilder::tun_builder_set_route_metric_default(int metric)
{
    (void)metric;
    return true;
}

bool AndroidTunBuilder::tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int flags)
{
    (void)flags;
    if (ipv4)
        add_route_if_missing("0.0.0.0", 0, -1, false);
    if (ipv6)
        add_route_if_missing("::", 0, -1, true);
    return true;
}

bool AndroidTunBuilder::tun_builder_add_route(const std::string &address,
                                              int prefix_length,
                                              int metric,
                                              bool ipv6)
{
    if (prefix_length < 0 || prefix_length > (ipv6 ? 128 : 32))
        return false;
    routes_.push_back(TunRoute{address, prefix_length, metric, ipv6});
    return true;
}

bool AndroidTunBuilder::tun_builder_exclude_route(const std::string &address,
                                                  int prefix_length,
                                                  int metric,
                                                  bool ipv6)
{
    if (prefix_length < 0 || prefix_length > (ipv6 ? 128 : 32))
        return false;
    exclude_routes_.push_back(TunRoute{address, prefix_length, metric, ipv6});
    return true;
}

bool AndroidTunBuilder::tun_builder_add_dns_options(const openvpn::DnsOptions &dns)
{
    for (const auto &[priority, server] : dns.servers)
    {
        (void)priority;
        if (server.transport != openvpn::DnsServer::Transport::Unset
            && server.transport != openvpn::DnsServer::Transport::Plain)
            return false;
        for (const auto &address : server.addresses)
        {
            // Android's VpnService.Builder has no port or DoH/DoT support.
            if (address.port != 0)
                return false;
            const std::string text = address.address.to_string();
            if (!dns_contains(text))
                dns_servers_.push_back(text);
        }
    }
    for (const auto &domain : dns.search_domains)
    {
        if (!domain.domain.empty())
            search_domains_.push_back(domain.domain);
    }
    return true;
}

bool AndroidTunBuilder::tun_builder_add_dns_server(const std::string &address, bool ipv6)
{
    (void)ipv6;
    if (!dns_contains(address))
        dns_servers_.push_back(address);
    return true;
}

bool AndroidTunBuilder::tun_builder_add_search_domain(const std::string &domain)
{
    if (!domain.empty())
        search_domains_.push_back(domain);
    return true;
}

bool AndroidTunBuilder::tun_builder_set_mtu(int mtu)
{
    if (mtu <= 0)
        return false;
    mtu_ = mtu;
    return true;
}

bool AndroidTunBuilder::tun_builder_set_session_name(const std::string &name)
{
    if (!name.empty())
        session_name_ = name;
    return true;
}

bool AndroidTunBuilder::tun_builder_add_proxy_bypass(const std::string &bypass_host)
{
    (void)bypass_host;
    // Android's ProxyInfo has no per-host bypass list. Not failing here keeps
    // proxy profiles usable; this is documented as a platform limitation.
    return true;
}

bool AndroidTunBuilder::tun_builder_set_proxy_auto_config_url(const std::string &url)
{
    (void)url;
    return false;
}

bool AndroidTunBuilder::tun_builder_set_proxy_http(const std::string &host, int port)
{
    if (host.empty() || port <= 0 || port > 65535)
        return false;
    http_proxy_.enabled = true;
    http_proxy_.host = host;
    http_proxy_.port = port;
    return true;
}

bool AndroidTunBuilder::tun_builder_set_proxy_https(const std::string &host, int port)
{
    (void)host;
    (void)port;
    // VpnService.Builder cannot configure a separate HTTPS proxy endpoint.
    return false;
}

bool AndroidTunBuilder::tun_builder_add_wins_server(const std::string &address)
{
    (void)address;
    return false;
}

bool AndroidTunBuilder::tun_builder_set_allow_family(int af, bool allow)
{
    if (af == AF_INET)
        allow_ipv4_ = allow;
    else if (af == AF_INET6)
        allow_ipv6_ = allow;
    return true;
}

bool AndroidTunBuilder::tun_builder_set_allow_local_dns(bool allow)
{
    allow_local_dns_ = allow;
    return true;
}

bool AndroidTunBuilder::tun_builder_set_adapter_domain_suffix(const std::string &name)
{
    if (!name.empty())
        search_domains_.push_back(name);
    return true;
}

int AndroidTunBuilder::tun_builder_establish()
{
    if (established_.exchange(true))
        return -1;
    if (callbacks_ == nullptr)
        return -1;
    return callbacks_->on_establish_tun(to_json());
}

bool AndroidTunBuilder::tun_builder_persist()
{
    // Android TUN persistence across reconnect is intentionally disabled. A
    // fresh VpnService.Builder session is used for every connection so stale
    // file descriptors can never survive a service restart.
    return false;
}

void AndroidTunBuilder::tun_builder_establish_lite()
{
}

void AndroidTunBuilder::tun_builder_teardown(bool disconnect)
{
    (void)disconnect;
    // OpenVPN Core owns and closes the TUN fd. The Kotlin VpnService is shut
    // down by MyVpnService when the session ends.
}

bool AndroidTunBuilder::has_ipv6_address() const
{
    return std::any_of(addresses_.begin(), addresses_.end(), [](const TunAddress &a)
                       { return a.ipv6; });
}

void AndroidTunBuilder::add_route_if_missing(const std::string &address, int prefix, int metric, bool ipv6)
{
    const auto found = std::find_if(routes_.begin(), routes_.end(), [&](const TunRoute &r)
                                    { return r.address == address && r.prefix == prefix && r.ipv6 == ipv6; });
    if (found == routes_.end())
        routes_.push_back(TunRoute{address, prefix, metric, ipv6});
}

std::string AndroidTunBuilder::to_json() const
{
    std::ostringstream os;
    os << '{';
    os << "\"addresses\":[";
    bool first = true;
    for (const auto &a : addresses_)
        append_address(os, a, first);
    os << "],\"routes\":[";
    first = true;
    for (const auto &r : routes_)
        append_route(os, r, first);
    os << "],\"excludeRoutes\":[";
    first = true;
    for (const auto &r : exclude_routes_)
        append_route(os, r, first);
    os << "],\"dnsServers\":[";
    first = true;
    for (const auto &d : dns_servers_)
    {
        if (!first)
            os << ',';
        first = false;
        os << '"' << json_escape(d) << '"';
    }
    os << "],\"searchDomains\":[";
    first = true;
    for (const auto &d : search_domains_)
    {
        if (!first)
            os << ',';
        first = false;
        os << '"' << json_escape(d) << '"';
    }
    os << "],\"mtu\":" << mtu_;
    os << ",\"layer\":" << layer_;
    os << ",\"sessionName\":\"" << json_escape(session_name_) << '"';
    os << ",\"remoteAddress\":\"" << json_escape(remote_address_) << '"';
    os << ",\"allowIpv4\":" << (allow_ipv4_ ? "true" : "false");
    os << ",\"allowIpv6\":" << (allow_ipv6_ ? "true" : "false");
    os << ",\"blockUnusedFamilies\":" << (block_unused_families_ ? "true" : "false");
    os << ",\"allowLocalDns\":" << (allow_local_dns_ ? "true" : "false");
    os << ",\"httpProxy\":{";
    os << "\"enabled\":" << (http_proxy_.enabled ? "true" : "false");
    os << ",\"host\":\"" << json_escape(http_proxy_.host) << '"';
    os << ",\"port\":" << http_proxy_.port << '}';
    os << '}';
    return os.str();
}

bool AndroidTunBuilder::dns_contains(const std::string &server) const
{
    return std::find(dns_servers_.begin(), dns_servers_.end(), server) != dns_servers_.end();
}

OvpnClient::OvpnClient(EngineCallbacks *callbacks)
    : callbacks_(callbacks), tun_builder_(callbacks)
{
}

bool OvpnClient::socket_protect(openvpn_io::detail::socket_type socket,
                                std::string remote,
                                bool ipv6)
{
    if (callbacks_ == nullptr)
        return false;
    return callbacks_->on_protect_socket(static_cast<int>(socket), remote, ipv6);
}

void OvpnClient::event(const openvpn::ClientAPI::Event &ev)
{
    if (ev.name == "CONNECTED")
        reached_connected_ = true;
    if (ev.name == "DYNAMIC_CHALLENGE")
    {
        dynamic_challenge_cookie_ = ev.info;
        openvpn::ClientAPI::DynamicChallenge challenge;
        if (openvpn::ClientAPI::OpenVPNClientHelper::parse_dynamic_challenge(ev.info, challenge)
            && callbacks_ != nullptr)
        {
            callbacks_->on_challenge(challenge.challenge,
                                     challenge.echo,
                                     challenge.responseRequired,
                                     challenge.stateID);
        }
    }
    if (callbacks_ != nullptr)
        callbacks_->on_event(ev.name, ev.info, ev.error, ev.fatal);
}

void OvpnClient::acc_event(const openvpn::ClientAPI::AppCustomControlMessageEvent &ev)
{
    (void)ev;
}

void OvpnClient::log(const openvpn::ClientAPI::LogInfo &log)
{
    if (callbacks_ != nullptr)
        callbacks_->on_log(log.text);
}

bool OvpnClient::pause_on_connection_timeout()
{
    // A timeout before the first CONNECTED is a real failure: surface it so the
    // user sees why the profile could not connect. After a successful
    // connection, the same timeout is normally a reconnect attempt after a
    // network transition or the device sleeping. Pause instead of stopping so
    // the service can resume when the network is usable again; the app maps the
    // PAUSE event to RECONNECTING and retries.
    return reached_connected_;
}

void OvpnClient::external_pki_cert_request(openvpn::ClientAPI::ExternalPKICertRequest &req)
{
    req.error = true;
    req.errorText = "External PKI profiles are not supported by this app.";
}

void OvpnClient::external_pki_sign_request(openvpn::ClientAPI::ExternalPKISignRequest &req)
{
    req.error = true;
    req.errorText = "External PKI profiles are not supported by this app.";
}

} // namespace monirvpn
