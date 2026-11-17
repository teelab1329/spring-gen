package generator

import (
	"io/fs"
	"strings"
	"testing"

	"github.com/teelab1329/spring-gen/internal/assets"
)

// allowedLogTags is the rule from AGENTS.md: generated code may only use these
// tags, and every log statement must carry one.
var allowedLogTags = []string{
	"[HTTP-REQUEST]",
	"[HTTP-RESPONSE]",
	"[BUSINESS-ERROR]",
	"[SYSTEM-ERROR]",
	"[RESTCLIENT-REQUEST]",
	"[RESTCLIENT-RESPONSE]",
	"[RESTCLIENT-CONFIG]",
}

// TestLogStatementsFollowRule fails when a template logs without a LogType tag.
func TestLogStatementsFollowRule(t *testing.T) {
	err := fs.WalkDir(assets.Templates, "templates", func(filePath string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() || !strings.HasSuffix(filePath, ".tmpl") {
			return nil
		}

		raw, err := fs.ReadFile(assets.Templates, filePath)
		if err != nil {
			return err
		}

		lines := strings.Split(string(raw), "\n")
		for i := 0; i < len(lines); i++ {
			line := lines[i]
			if !isLogStart(line) {
				continue
			}

			// Log statements may wrap; collect until the terminating semicolon.
			statement := line
			for j := i; j < len(lines) && !strings.Contains(statement, ";"); j++ {
				if j > i {
					statement += " " + strings.TrimSpace(lines[j])
				}
			}

			if !strings.Contains(statement, "LogType.") {
				t.Errorf("%s:%d: log statement must use a LogType tag: %s", filePath, i+1, strings.TrimSpace(statement))
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}

// TestLogTypeDeclaresEveryTag keeps the constant class in sync with the rule.
func TestLogTypeDeclaresEveryTag(t *testing.T) {
	raw, err := fs.ReadFile(assets.Templates, "templates/logging/LogType.java.tmpl")
	if err != nil {
		t.Fatalf("read LogType template: %v", err)
	}

	for _, tag := range allowedLogTags {
		if !strings.Contains(string(raw), `"`+tag+`"`) {
			t.Errorf("LogType does not declare %s", tag)
		}
	}
}

func isLogStart(line string) bool {
	return strings.Contains(line, "log.info(") ||
		strings.Contains(line, "log.warn(") ||
		strings.Contains(line, "log.error(") ||
		strings.Contains(line, "log.debug(")
}
