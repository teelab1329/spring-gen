package cli

import (
	"bytes"
	"runtime"
	"strings"
	"testing"

	"github.com/teelab1329/spring-gen/internal/version"
)

// execute runs a fresh command tree and returns everything it wrote to stdout
// and stderr.
func execute(t *testing.T, args ...string) (string, error) {
	t.Helper()

	cmd := NewRootCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs(args)

	err := cmd.Execute()
	return out.String(), err
}

func TestVersionCommand(t *testing.T) {
	out, err := execute(t, "version")
	if err != nil {
		t.Fatalf("version returned error: %v", err)
	}

	got := strings.TrimSuffix(out, "\n")
	if want := version.Get().String(); got != want {
		t.Errorf("version output = %q, want %q", got, want)
	}
	if strings.Contains(got, "\n") {
		t.Errorf("version output should be one line, got %q", got)
	}

	for _, want := range []string{
		"spring-gen ",
		"commit ",
		"built ",
		runtime.Version(),
		runtime.GOOS + "/" + runtime.GOARCH,
	} {
		if !strings.Contains(got, want) {
			t.Errorf("version output %q does not contain %q", got, want)
		}
	}
}

func TestVersionCommandRejectsArgs(t *testing.T) {
	if _, err := execute(t, "version", "extra"); err == nil {
		t.Fatal("version accepted a positional argument, want error")
	}
}

func TestVersionFlagMatchesVersionCommand(t *testing.T) {
	flagOut, err := execute(t, "--version")
	if err != nil {
		t.Fatalf("--version returned error: %v", err)
	}

	cmdOut, err := execute(t, "version")
	if err != nil {
		t.Fatalf("version returned error: %v", err)
	}

	if flagOut != cmdOut {
		t.Errorf("--version output = %q, want %q", flagOut, cmdOut)
	}
}
