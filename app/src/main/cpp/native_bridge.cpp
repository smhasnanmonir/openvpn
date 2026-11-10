// SPDX-License-Identifier: MPL-2.0
//
// JNI boundary. All OpenVPN protocol/crypto work is performed by OpenVPN 3
// Core. This file owns JNI object/thread lifetimes and translates between the
// core callbacks and the small Kotlin interface in nativevpn/NativeCallbacks.kt.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <stdexcept>
#include <cstring>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include <openvpn/common/version.hpp>

#include "engine_adapter/callbacks.hpp"
#include "engine_adapter/ovpn_client.hpp"

namespace {

std::string to_std(JNIEnv *env, jstring value)
{
    if (value == nullptr)
        return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr)
        return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring to_java(JNIEnv *env, const std::string &value)
{
    return env->NewStringUTF(value.c_str());
}

std::string lower_ascii(std::string value)
{
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c)
                   { return static_cast<char>(std::tolower(c)); });
    return value;
}

// Never let a log line carrying credentials or key material reach Kotlin.
std::string sanitize_log(const std::string &line)
{
    const std::string lower = lower_ascii(line);
    static const char *const markers[] = {
        "password",
        "passphrase",
        "private key",
        "begin certificate",
        "begin rsa private",
        "auth-token",
        "auth_token",
        "session_id",
        "session token",
    };
    for (const char *marker : markers)
    {
        if (lower.find(marker) != std::string::npos)
            return "[redacted: sensitive log line]";
    }
    if (line.size() > 2048)
        return line.substr(0, 2048) + "...[truncated]";
    return line;
}

std::string sanitize_error(const std::string &message)
{
    std::string out = message;
    if (out.size() > 1024)
        out = out.substr(0, 1024) + "...[truncated]";
    return out;
}

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

std::string bool_json(bool value)
{
    return value ? "true" : "false";
}

bool callback_exception(JNIEnv *env)
{
    if (env->ExceptionCheck())
    {
        env->ExceptionClear();
        return true;
    }
    return false;
}

class JniSession final : public monirvpn::EngineCallbacks
{
  public:
    JniSession(JNIEnv *env, jobject callbacks)
        : callbacks_(env->NewGlobalRef(callbacks))
    {
        env->GetJavaVM(&vm_);
        jclass cls = env->GetObjectClass(callbacks);
        event_mid_ = env->GetMethodID(cls, "onEngineEvent", "(Ljava/lang/String;Ljava/lang/String;ZZ)V");
        log_mid_ = env->GetMethodID(cls, "onEngineLog", "(Ljava/lang/String;)V");
        tun_mid_ = env->GetMethodID(cls, "onTunEstablish", "(Ljava/lang/String;)I");
        protect_mid_ = env->GetMethodID(cls, "onProtectSocket", "(I)Z");
        challenge_mid_ = env->GetMethodID(cls, "onChallenge", "(Ljava/lang/String;ZZLjava/lang/String;)V");
        env->DeleteLocalRef(cls);
        if (callback_exception(env))
            throw std::runtime_error("NativeCallbacks has an incompatible signature");
    }

    ~JniSession() override
    {
        disconnect();
        JNIEnv *env = nullptr;
        bool attached = false;
        if (vm_ != nullptr)
        {
            const jint result = vm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
            if (result == JNI_EDETACHED)
            {
                vm_->AttachCurrentThread(&env, nullptr);
                attached = true;
            }
        }
        if (env != nullptr && callbacks_ != nullptr)
            env->DeleteGlobalRef(callbacks_);
        callbacks_ = nullptr;
        if (attached && vm_ != nullptr)
            vm_->DetachCurrentThread();
    }

    bool connect(const std::string &config,
                 const std::string &username,
                 const std::string &password,
                 const std::string &response,
                 const std::string &key_password,
                 bool block_unused_families)
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (running_)
            return false;
        if (thread_.joinable())
            thread_.join();
        config_ = config;
        dynamic_cookie_.clear();
        username_ = username;
        password_ = password;
        response_ = response;
        key_password_ = key_password;
        block_unused_families_ = block_unused_families;
        dynamic_challenge_pending_ = false;
        return start_locked();
    }

    void disconnect()
    {
        std::shared_ptr<monirvpn::OvpnClient> client;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            client = client_;
        }
        if (client)
            client->stop();
        if (thread_.joinable())
            thread_.join();
        {
            std::lock_guard<std::mutex> lock(mutex_);
            client_.reset();
            running_ = false;
        }
    }

    bool respond_to_challenge(const std::string &response)
    {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (client_)
                dynamic_cookie_ = client_->dynamic_challenge_cookie();
        }
        disconnect();
        {
            std::lock_guard<std::mutex> lock(mutex_);
            response_ = response;
            dynamic_challenge_pending_ = false;
        }
        std::lock_guard<std::mutex> lock(mutex_);
        if (running_)
            return false;
        return start_locked();
    }

    bool reconnect(int seconds)
    {
        std::shared_ptr<monirvpn::OvpnClient> client;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            client = client_;
        }
        if (!client)
            return false;
        client->reconnect(seconds);
        return true;
    }

    std::string stats_json()
    {
        std::shared_ptr<monirvpn::OvpnClient> client;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            client = client_;
        }
        std::ostringstream os;
        os << '{';
        if (client)
        {
            try
            {
                const auto bundle = client->stats_bundle();
                const int count = monirvpn::OvpnClient::stats_n();
                const int limit = std::min<int>(count, static_cast<int>(bundle.size()));
                for (int i = 0; i < limit; ++i)
                {
                    if (i)
                        os << ',';
                    os << '"' << json_escape(monirvpn::OvpnClient::stats_name(i)) << "\":"
                       << bundle[static_cast<std::size_t>(i)];
                }
            }
            catch (...)
            {
            }
        }
        os << '}';
        return os.str();
    }

    std::string connection_info_json()
    {
        std::shared_ptr<monirvpn::OvpnClient> client;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            client = client_;
        }
        std::ostringstream os;
        os << '{';
        if (client)
        {
            try
            {
                const auto info = client->connection_info();
                os << "\"defined\":" << bool_json(info.defined);
                os << ",\"serverHost\":\"" << json_escape(info.serverHost) << '"';
                os << ",\"serverPort\":\"" << json_escape(info.serverPort) << '"';
                os << ",\"serverProto\":\"" << json_escape(info.serverProto) << '"';
                os << ",\"serverIp\":\"" << json_escape(info.serverIp) << '"';
                os << ",\"vpnIp4\":\"" << json_escape(info.vpnIp4) << '"';
                os << ",\"vpnIp6\":\"" << json_escape(info.vpnIp6) << '"';
                os << ",\"vpnMtu\":\"" << json_escape(info.vpnMtu) << '"';
                os << ",\"gw4\":\"" << json_escape(info.gw4) << '"';
                os << ",\"gw6\":\"" << json_escape(info.gw6) << '"';
                os << ",\"clientIp\":\"" << json_escape(info.clientIp) << '"';
                os << ",\"tunName\":\"" << json_escape(info.tunName) << '"';
            }
            catch (...)
            {
            }
        }
        os << '}';
        return os.str();
    }

    bool is_running() const
    {
        return running_.load();
    }

    // EngineCallbacks
    void on_event(const std::string &name, const std::string &info, bool error, bool fatal) override
    {
        if (name == "DYNAMIC_CHALLENGE")
            dynamic_challenge_pending_ = true;
        std::string safe_info = name == "DYNAMIC_CHALLENGE" ? info : sanitize_error(info);
        if (name == "DYNAMIC_CHALLENGE")
        {
            openvpn::ClientAPI::DynamicChallenge challenge;
            if (openvpn::ClientAPI::OpenVPNClientHelper::parse_dynamic_challenge(info, challenge))
                safe_info = challenge.challenge;
            else
                safe_info.clear();
        }
        JNIEnv *env = env_for_callback();
        if (env == nullptr || callbacks_ == nullptr || event_mid_ == nullptr)
            return;
        jstring jname = to_java(env, name);
        jstring jinfo = to_java(env, safe_info);
        env->CallVoidMethod(callbacks_, event_mid_, jname, jinfo, static_cast<jboolean>(error), static_cast<jboolean>(fatal));
        env->DeleteLocalRef(jname);
        env->DeleteLocalRef(jinfo);
        callback_exception(env);
    }

    void on_log(const std::string &line) override
    {
        JNIEnv *env = env_for_callback();
        if (env == nullptr || callbacks_ == nullptr || log_mid_ == nullptr)
            return;
        const std::string safe = sanitize_log(line);
        jstring jline = to_java(env, safe);
        env->CallVoidMethod(callbacks_, log_mid_, jline);
        env->DeleteLocalRef(jline);
        callback_exception(env);
    }

    bool on_protect_socket(int fd, const std::string &remote, bool ipv6) override
    {
        (void)remote;
        (void)ipv6;
        JNIEnv *env = env_for_callback();
        if (env == nullptr || callbacks_ == nullptr || protect_mid_ == nullptr)
            return false;
        const jboolean result = env->CallBooleanMethod(callbacks_, protect_mid_, static_cast<jint>(fd));
        if (callback_exception(env))
            return false;
        return result == JNI_TRUE;
    }

    int on_establish_tun(const std::string &config_json) override
    {
        JNIEnv *env = env_for_callback();
        if (env == nullptr || callbacks_ == nullptr || tun_mid_ == nullptr)
            return -1;
        jstring jjson = to_java(env, config_json);
        const jint fd = env->CallIntMethod(callbacks_, tun_mid_, jjson);
        env->DeleteLocalRef(jjson);
        if (callback_exception(env))
            return -1;
        return static_cast<int>(fd);
    }

    void on_challenge(const std::string &challenge,
                      bool echo,
                      bool response_required,
                      const std::string &state_id) override
    {
        JNIEnv *env = env_for_callback();
        if (env == nullptr || callbacks_ == nullptr || challenge_mid_ == nullptr)
            return;
        jstring jchallenge = to_java(env, challenge);
        jstring jstate = to_java(env, state_id);
        env->CallVoidMethod(callbacks_,
                            challenge_mid_,
                            jchallenge,
                            static_cast<jboolean>(echo),
                            static_cast<jboolean>(response_required),
                            jstate);
        env->DeleteLocalRef(jchallenge);
        env->DeleteLocalRef(jstate);
        callback_exception(env);
    }

  private:
    bool start_locked()
    {
        client_ = std::make_shared<monirvpn::OvpnClient>(this);
        client_->set_block_unused_families(block_unused_families_);
        running_ = true;
        thread_ = std::thread([this]()
                              { run(); });
        return true;
    }

    void run()
    {
        JNIEnv *env = nullptr;
        bool attached = false;
        if (vm_ != nullptr && vm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED)
        {
            vm_->AttachCurrentThread(&env, nullptr);
            attached = true;
        }

        std::shared_ptr<monirvpn::OvpnClient> client;
        std::string config;
        std::string username;
        std::string password;
        std::string response;
        std::string key_password;
        std::string dynamic_cookie;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            client = client_;
            config = config_;
            username = username_;
            password = password_;
            response = response_;
            key_password = key_password_;
            dynamic_cookie = dynamic_cookie_;
        }

        if (client)
        {
            openvpn::ClientAPI::Config cfg;
            cfg.content = config;
            cfg.guiVersion = "OpenVPNClient Android 1.0.0";
            cfg.info = true;
            cfg.echo = true;
            cfg.autologinSessions = true;
            cfg.retryOnAuthFailed = false;
            cfg.tunPersist = false;
            cfg.clockTickMS = 0;
            cfg.connTimeout = 60;
            cfg.privateKeyPassword = key_password;
            cfg.googleDnsFallback = false;
            cfg.synchronousDnsLookup = false;
            cfg.enableRouteEmulation = true;

            openvpn::ClientAPI::ProvideCreds creds;
            creds.username = username;
            creds.password = password;
            creds.response = response;
            creds.dynamicChallengeCookie = dynamic_cookie;

            openvpn::ClientAPI::Status status;
            try
            {
                const auto eval = client->eval_config(cfg);
                if (eval.error)
                {
                    on_event("PROFILE_REJECTED", eval.message, true, true);
                    status.error = true;
                    status.message = eval.message;
                }
                else
                {
                    const auto creds_status = client->provide_creds(creds);
                    if (creds_status.error)
                    {
                        on_event("CREDENTIALS_REJECTED", creds_status.message, true, true);
                        status = creds_status;
                    }
                    else
                    {
                        status = client->connect();
                    }
                }
            }
            catch (const std::exception &e)
            {
                status.error = true;
                status.message = e.what();
            }
            catch (...)
            {
                status.error = true;
                status.message = "Unknown native engine failure";
            }

            // A dynamic challenge intentionally ends the blocking connect() call.
            // Keep the session object alive so the app can supply the response and
            // start a fresh connect with the server-provided cookie.
            const bool challenge_pending = dynamic_challenge_pending_.load();
            if (!challenge_pending)
                on_event("SESSION_FINISHED", status.message, status.error, false);
        }

        running_ = false;
        if (attached && vm_ != nullptr)
            vm_->DetachCurrentThread();
    }

    JNIEnv *env_for_callback()
    {
        if (vm_ == nullptr)
            return nullptr;
        JNIEnv *env = nullptr;
        const jint result = vm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        if (result == JNI_EDETACHED)
        {
            if (vm_->AttachCurrentThread(&env, nullptr) != JNI_OK)
                return nullptr;
        }
        return env;
    }

    JavaVM *vm_ = nullptr;
    jobject callbacks_ = nullptr;
    jmethodID event_mid_ = nullptr;
    jmethodID log_mid_ = nullptr;
    jmethodID tun_mid_ = nullptr;
    jmethodID protect_mid_ = nullptr;
    jmethodID challenge_mid_ = nullptr;

    mutable std::mutex mutex_;
    std::shared_ptr<monirvpn::OvpnClient> client_;
    std::thread thread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> dynamic_challenge_pending_{false};
    std::string config_;
    std::string username_;
    std::string password_;
    std::string response_;
    std::string key_password_;
    std::string dynamic_cookie_;
    bool block_unused_families_ = true;
};

JniSession *from_handle(jlong handle)
{
    return reinterpret_cast<JniSession *>(handle);
}

std::string eval_config_json(const std::string &content)
{
    using namespace monirvpn;
    using namespace openvpn;
    ClientAPI::OpenVPNClientHelper helper;
    ClientAPI::Config cfg;
    cfg.content = content;
    const auto eval = helper.eval_config(cfg);
    std::ostringstream os;
    os << '{';
    os << "\"error\":" << bool_json(eval.error);
    os << ",\"message\":\"" << json_escape(sanitize_error(eval.message)) << '"';
    os << ",\"profileName\":\"" << json_escape(eval.profileName) << '"';
    os << ",\"friendlyName\":\"" << json_escape(eval.friendlyName) << '"';
    os << ",\"autologin\":" << bool_json(eval.autologin);
    os << ",\"userlockedUsername\":\"" << json_escape(eval.userlockedUsername) << '"';
    os << ",\"staticChallenge\":\"" << json_escape(eval.staticChallenge) << '"';
    os << ",\"staticChallengeEcho\":" << bool_json(eval.staticChallengeEcho);
    os << ",\"privateKeyPasswordRequired\":" << bool_json(eval.privateKeyPasswordRequired);
    os << ",\"allowPasswordSave\":" << bool_json(eval.allowPasswordSave);
    os << ",\"externalPki\":" << bool_json(eval.externalPki);
    os << ",\"remoteHost\":\"" << json_escape(eval.remoteHost) << '"';
    os << ",\"remotePort\":\"" << json_escape(eval.remotePort) << '"';
    os << ",\"remoteProto\":\"" << json_escape(eval.remoteProto) << '"';
    os << ",\"serverList\":[";
    bool first = true;
    for (const auto &server : eval.serverList)
    {
        if (!first)
            os << ',';
        first = false;
        os << "{\"server\":\"" << json_escape(server.server)
           << "\",\"friendlyName\":\"" << json_escape(server.friendlyName) << "\"}";
    }
    os << "]}";
    return os.str();
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_smhasnan_openvpn_nativevpn_OpenVpnNative_createSession(JNIEnv *env, jobject, jobject callbacks)
{
    if (callbacks == nullptr)
        return 0;
    try
    {
        return reinterpret_cast<jlong>(new JniSession(env, callbacks));
    }
    catch (...)
    {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_smhasnan_openvpn_nativevpn_OpenVpnNative_destroySession(JNIEnv *, jobject, jlong handle)
{
    delete from_handle(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_smhasnan_openvpn_nativevpn_OpenVpnNative_evalConfig(JNIEnv *env, jobject, jstring config)
{
    try
    {
        return to_java(env, eval_config_json(to_std(env, config)));
    }
    catch (const std::exception &e)
    {
        return to_java(env, std::string("{\"error\":true,\"message\":\"") + json_escape(sanitize_error(e.what())) + "\"}");
    }
    catch (...)
    {
        return to_java(env, "{\"error\":true,\"message\":\"Unsupported or malformed profile\"}");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_smhasnan_openvpn_nativevpn_OpenVpnNative_mergeConfig(JNIEnv *env, jobject, jstring path)
{
    using namespace monirvpn;
    using namespace openvpn;
    try
    {
        ClientAPI::OpenVPNClientHelper helper;
        const auto merged = helper.merge_config(to_std(env, path), true);
        std::ostringstream os;
        os << '{';
        os << "\"status\":\"" << json_escape(merged.status) << '"';
        os << ",\"errorText\":\"" << json_escape(sanitize_error(merged.errorText)) << '"';
        os << ",\"basename\":\"" << json_escape(merged.basename) << '"';
        os << ",\"content\":\"" << json_escape(merged.profileContent) << '"';
        os << ",\"refCount\":" << merged.refPathList.size();
        os << '}';
        return to_java(env, os.str());
    }
    catch (const std::exception &e)
    {
        return to_java(env, std::string("{\"status\":\"ERROR\",\"errorText\":\"") + json_escape(sanitize_error(e.what())) + "\"}");
    }
    catch (...)
    {
        return to_java(env, "{\"status\":\"ERROR\",\"errorText\":\"Unable to read profile\"}");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_smhasnan_openvpn_nativevpn_OpenVpnNative_connect(JNIEnv *env,
                                                          jobject,
                                                          jlong handle,
                                                          jstring config,
                                                          jstring username,
                                                          jstring password,
                                                          jstring response,
                                                          jstring keyPassword,
                                                          jboolean blockUnusedFamilies)
{
    auto *session = from_handle(handle);
    if (session == nullptr)
        return JNI_FALSE;
    try
    {
        return session->connect(to_std(env, config),
                                to_std(env, username),
                                to_std(env, password),
                                to_std(env, response),
                                to_std(env, keyPassword),
                                blockUnusedFamilies == JNI_TRUE)
                   ? JNI_TRUE
                   : JNI_FALSE;
    }
    catch (...)
    {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_smhasnan_openvpn_nativevpn_OpenVpnNative_disconnect(JNIEnv *, jobject, jlong handle)
{
    if (auto *session = from_handle(handle))
        session->disconnect();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_smhasnan_openvpn_nativevpn_OpenVpnNative_respondToChallenge(JNIEnv *env, jobject, jlong handle, jstring response)
{
    if (auto *session = from_handle(handle))
        return session->respond_to_challenge(to_std(env, response)) ? JNI_TRUE : JNI_FALSE;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_smhasnan_openvpn_nativevpn_OpenVpnNative_reconnect(JNIEnv *, jobject, jlong handle, jint seconds)
{
    if (auto *session = from_handle(handle))
        return session->reconnect(static_cast<int>(seconds)) ? JNI_TRUE : JNI_FALSE;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_smhasnan_openvpn_nativevpn_OpenVpnNative_getStats(JNIEnv *env, jobject, jlong handle)
{
    if (auto *session = from_handle(handle))
        return to_java(env, session->stats_json());
    return to_java(env, "{}");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_smhasnan_openvpn_nativevpn_OpenVpnNative_getConnectionInfo(JNIEnv *env, jobject, jlong handle)
{
    if (auto *session = from_handle(handle))
        return to_java(env, session->connection_info_json());
    return to_java(env, "{}");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_smhasnan_openvpn_nativevpn_OpenVpnNative_isRunning(JNIEnv *, jobject, jlong handle)
{
    if (auto *session = from_handle(handle))
        return session->is_running() ? JNI_TRUE : JNI_FALSE;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_smhasnan_openvpn_nativevpn_OpenVpnNative_engineInfo(JNIEnv *env, jobject)
{
    try
    {
        openvpn::ClientAPI::OpenVPNClientHelper helper;
        std::ostringstream os;
        os << '{';
        os << "\"coreVersion\":\"" << json_escape(OPENVPN_VERSION) << '"';
        os << ",\"platform\":\"" << json_escape(helper.platform()) << '"';
        os << ",\"copyright\":\"" << json_escape(helper.copyright()) << '"';
        os << '}';
        return to_java(env, os.str());
    }
    catch (...)
    {
        return to_java(env, "{\"coreVersion\":\"unknown\",\"platform\":\"unknown\",\"copyright\":\"\"}");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_smhasnan_openvpn_nativevpn_OpenVpnNative_selfTest(JNIEnv *env, jobject)
{
    try
    {
        openvpn::ClientAPI::OpenVPNClientHelper helper;
        const std::string result = helper.crypto_self_test();
        bool ok = true;
        bool found_status = false;
        std::size_t pos = 0;
        while ((pos = result.find("status=", pos)) != std::string::npos)
        {
            found_status = true;
            pos += 7;
            const int value = std::atoi(result.c_str() + pos);
            if (value != 0)
                ok = false;
        }
        if (!result.empty() && !found_status)
            ok = false;
        if (result.find("not compiled") != std::string::npos)
            ok = false;
        std::ostringstream os;
        os << '{';
        os << "\"ok\":" << (ok ? "true" : "false");
        os << ",\"message\":\"" << json_escape(sanitize_error(result)) << '"';
        os << '}';
        return to_java(env, os.str());
    }
    catch (const std::exception &e)
    {
        return to_java(env, std::string("{\"ok\":false,\"message\":\"") + json_escape(sanitize_error(e.what())) + "\"}");
    }
    catch (...)
    {
        return to_java(env, "{\"ok\":false,\"message\":\"Native self-test failed\"}");
    }
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *)
{
    return JNI_VERSION_1_6;
}
