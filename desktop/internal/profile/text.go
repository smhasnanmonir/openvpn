package profile

import "strings"

// splitConfig normalizes line endings and returns the body lines plus the
// original trailing newline so transforms can be idempotent.
func splitConfig(config string) ([]string, string) {
	trailing := trailingNewline(config)
	body := strings.TrimRight(config, "\r\n")
	body = strings.ReplaceAll(body, "\r\n", "\n")
	body = strings.ReplaceAll(body, "\r", "\n")
	if body == "" {
		return nil, trailing
	}
	return strings.Split(body, "\n"), trailing
}

func joinConfig(lines []string, trailing string) string {
	// Drop empty lines that a transform may leave at the end, but keep interior
	// blank lines: profiles use them and they are harmless.
	end := len(lines)
	for end > 0 && strings.TrimSpace(lines[end-1]) == "" {
		end--
	}
	body := strings.Join(lines[:end], "\n")
	if trailing == "" {
		return body
	}
	return body + trailing
}

func trailingNewline(config string) string {
	switch {
	case strings.HasSuffix(config, "\r\n"):
		return "\r\n"
	case strings.HasSuffix(config, "\n"):
		return "\n"
	case strings.HasSuffix(config, "\r"):
		return "\r"
	default:
		return ""
	}
}

func isCommentOrEmpty(line string) bool {
	t := strings.TrimSpace(line)
	return t == "" || strings.HasPrefix(t, "#") || strings.HasPrefix(t, ";")
}

// stripInlineComment removes a trailing #/; comment. Directive values cannot
// contain those characters, and a comment only starts after whitespace.
func stripInlineComment(line string) string {
	for i := 0; i < len(line); i++ {
		c := line[i]
		if (c == '#' || c == ';') && (i == 0 || line[i-1] == ' ' || line[i-1] == '\t') {
			return line[:i]
		}
	}
	return line
}

// directive returns the lower-cased option name and the raw value text. A line
// starting with <tag> yields name "<tag>" and an empty value.
func directive(line string) (name, value string, ok bool) {
	l := strings.TrimSpace(stripInlineComment(line))
	if l == "" {
		return "", "", false
	}
	i := 0
	for i < len(l) && l[i] != ' ' && l[i] != '\t' {
		i++
	}
	name = strings.ToLower(l[:i])
	value = strings.TrimSpace(l[i:])
	return name, value, true
}

func directiveName(line string) (string, bool) {
	name, _, ok := directive(line)
	return name, ok
}

func hasDirective(config string, names ...string) bool {
	want := make(map[string]bool, len(names))
	for _, n := range names {
		want[strings.ToLower(n)] = true
	}
	for _, line := range strings.Split(config, "\n") {
		name, ok := directiveName(line)
		if ok && want[name] {
			return true
		}
	}
	return false
}

// removeDirectives removes every real (non-comment) line whose directive name
// is in names.
func removeDirectives(config string, names ...string) string {
	lines, trailing := splitConfig(config)
	want := make(map[string]bool, len(names))
	for _, n := range names {
		want[strings.ToLower(n)] = true
	}
	out := make([]string, 0, len(lines))
	for _, line := range lines {
		name, ok := directiveName(line)
		if ok && want[name] {
			continue
		}
		out = append(out, line)
	}
	return joinConfig(out, trailing)
}

// appendLines appends policy lines, dropping exact duplicates first so the
// transform is idempotent.
func appendLines(config string, additions ...string) string {
	lines, trailing := splitConfig(config)
	existing := make(map[string]bool, len(lines))
	for _, line := range lines {
		existing[strings.TrimSpace(line)] = true
	}
	for _, add := range additions {
		add = strings.TrimSpace(add)
		if add == "" || existing[add] {
			continue
		}
		lines = append(lines, add)
		existing[add] = true
	}
	return joinConfig(lines, trailing)
}

func fields(s string) []string { return strings.Fields(s) }

func containsFold(list []string, value string) bool {
	for _, v := range list {
		if strings.EqualFold(v, value) {
			return true
		}
	}
	return false
}
