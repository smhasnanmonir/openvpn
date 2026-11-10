`test-profile.ovpn` is a throwaway, self-contained OpenVPN profile with a
test-only CA/cert/key generated for instrumentation tests. It is not used by
the production app and must never be used to protect real traffic.
