package captiveportal

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestValidatePortalURL(t *testing.T) {
	for _, in := range []string{"http://portal.example/login", "https://10.0.0.1:8443/x"} {
		if _, ok := ValidatePortalURL(in); !ok {
			t.Fatalf("rejected %q", in)
		}
	}
	for _, in := range []string{"", "javascript:alert(1)", "file:///etc/passwd", "data:text/html,x", "/login", "ftp://x"} {
		if _, ok := ValidatePortalURL(in); ok {
			t.Fatalf("accepted unsafe URL %q", in)
		}
	}
}

func TestProbeInternet(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()
	res := Probe(context.Background(), nil, srv.URL)
	if res.Status != StatusInternet || res.Code != http.StatusNoContent {
		t.Fatalf("result = %+v", res)
	}
}

func TestProbePortalRedirect(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/login", http.StatusFound)
	}))
	defer srv.Close()
	res := Probe(context.Background(), nil, srv.URL)
	if res.Status != StatusPortal {
		t.Fatalf("result = %+v", res)
	}
	if res.PortalURL != srv.URL+"/login" {
		t.Fatalf("portal URL = %q, want %q", res.PortalURL, srv.URL+"/login")
	}
}

func TestProbePortalPage(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		_, _ = w.Write([]byte("<html><body>Sign in</body></html>"))
	}))
	defer srv.Close()
	res := Probe(context.Background(), nil, srv.URL)
	if res.Status != StatusPortal || res.PortalURL == "" {
		t.Fatalf("result = %+v", res)
	}
}

func TestProbeOffline(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	url := srv.URL
	srv.Close()
	res := Probe(context.Background(), nil, url)
	if res.Status != StatusOffline || res.Err == "" {
		t.Fatalf("result = %+v", res)
	}
}

func TestProbeRejectsUnsafeProbeURL(t *testing.T) {
	res := Probe(context.Background(), nil, "file:///etc/passwd")
	if res.Status != StatusOffline {
		t.Fatalf("result = %+v", res)
	}
}

func TestClassifyUnsafeLocationFallsBack(t *testing.T) {
	req, _ := http.NewRequest(http.MethodGet, "http://portal.example/check", nil)
	resp := &http.Response{
		StatusCode: http.StatusFound,
		Header:     http.Header{"Location": []string{"javascript:alert(1)"}},
		Request:    req,
	}
	res := Classify(resp, nil, "http://portal.example/check")
	if res.Status != StatusPortal {
		t.Fatalf("result = %+v", res)
	}
	if res.PortalURL != "http://portal.example/check" {
		t.Fatalf("unsafe location was used: %q", res.PortalURL)
	}
}
