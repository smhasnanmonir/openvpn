// Package captiveportal detects a captive portal without assuming the
// operating system's connectivity service is available. It performs one
// explicit HTTP request and classifies the result: 204 means the internet is
// reachable, a redirect or an HTTP 200 means a portal is intercepting traffic,
// and a transport error means the network is offline.
package captiveportal

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const DefaultProbeURL = "http://connectivitycheck.gstatic.com/generate_204"

type Status string

const (
	StatusInternet Status = "internet"
	StatusPortal   Status = "portal"
	StatusOffline  Status = "offline"
)

// Result is the classified outcome.
type Result struct {
	Status    Status
	PortalURL string
	Code      int
	Err       string
}

// ValidatePortalURL returns a usable http/https portal URL. Anything else
// (javascript:, file:, data:, relative) is refused before it reaches the
// browser.
func ValidatePortalURL(raw string) (string, bool) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", false
	}
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return "", false
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return "", false
	}
	return u.String(), true
}

// Probe performs the connectivity check. A nil client uses a 6-second client
// that does not follow redirects (the redirect is the portal signal).
func Probe(ctx context.Context, client *http.Client, probeURL string) Result {
	probeURL = strings.TrimSpace(probeURL)
	if probeURL == "" {
		probeURL = DefaultProbeURL
	}
	if _, ok := ValidatePortalURL(probeURL); !ok {
		return Result{Status: StatusOffline, Err: "invalid captive-portal probe URL"}
	}
	if client == nil {
		client = &http.Client{
			Timeout: 6 * time.Second,
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				return http.ErrUseLastResponse
			},
		}
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, probeURL, nil)
	if err != nil {
		return Result{Status: StatusOffline, Err: err.Error()}
	}
	req.Header.Set("User-Agent", "OpenVPN-Desktop/1.0 captive-portal-check")
	resp, err := client.Do(req)
	if err != nil {
		return Result{Status: StatusOffline, Err: err.Error()}
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	return Classify(resp, body, probeURL)
}

// Classify turns a probe response into a result. It is exported for tests and
// for callers that already have a response.
func Classify(resp *http.Response, body []byte, probeURL string) Result {
	if resp == nil {
		return Result{Status: StatusOffline, Err: "no response"}
	}
	res := Result{Code: resp.StatusCode}
	switch {
	case resp.StatusCode == http.StatusNoContent:
		res.Status = StatusInternet
	case resp.StatusCode >= 300 && resp.StatusCode < 400:
		res.Status = StatusPortal
		res.PortalURL = resolvePortalURL(resp, probeURL)
	case resp.StatusCode == http.StatusOK:
		// A plain connectivity endpoint answers 204; a 200 is a portal page
		// (some portals return a page with a meta refresh or JavaScript).
		res.Status = StatusPortal
		res.PortalURL = resolvePortalURL(resp, probeURL)
	default:
		res.Status = StatusOffline
		res.Err = "connectivity check returned HTTP " + http.StatusText(resp.StatusCode)
	}
	return res
}

func resolvePortalURL(resp *http.Response, fallback string) string {
	if location := strings.TrimSpace(resp.Header.Get("Location")); location != "" {
		base := resp.Request.URL
		if base == nil {
			if u, err := url.Parse(fallback); err == nil {
				base = u
			}
		}
		if base != nil {
			if resolved, err := base.Parse(location); err == nil {
				if safe, ok := ValidatePortalURL(resolved.String()); ok {
					return safe
				}
			}
		}
	}
	if resp.Request != nil && resp.Request.URL != nil {
		if safe, ok := ValidatePortalURL(resp.Request.URL.String()); ok {
			return safe
		}
	}
	if safe, ok := ValidatePortalURL(fallback); ok {
		return safe
	}
	return ""
}

// ErrNoPortalURL is returned by the UI when a portal was detected but its URL
// could not be validated.
var ErrNoPortalURL = errors.New("the captive portal did not provide a safe http(s) URL")
