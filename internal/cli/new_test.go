package cli

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestNewRequiresGroupWhenNotInteractive(t *testing.T) {
	output := t.TempDir()

	cmd := NewRootCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"new", "demo", "--output", output, "--no-interactive"})

	err := cmd.Execute()
	if err == nil {
		t.Fatal("new without --group succeeded in non-interactive mode")
	}
	if !strings.Contains(err.Error(), "--group") {
		t.Errorf("error = %q, want a missing --group hint", err)
	}
	if _, statErr := os.Stat(filepath.Join(output, "demo")); !os.IsNotExist(statErr) {
		t.Errorf("project was created despite the error: %v", statErr)
	}
}

func TestNewWithFlags(t *testing.T) {
	output := t.TempDir()

	out, err := execute(t,
		"new", "order-service",
		"--group", "com.acme",
		"--description", "Order service",
		"--output", output,
		"--no-interactive",
	)
	if err != nil {
		t.Fatalf("new returned error: %v", err)
	}

	projectDir := filepath.Join(output, "order-service")
	if !strings.Contains(out, projectDir) {
		t.Errorf("output %q does not mention %q", out, projectDir)
	}
	for _, path := range []string{
		"pom.xml",
		"src/main/java/com/acme/orderservice/OrderServiceApplication.java",
	} {
		if _, err := os.Stat(filepath.Join(projectDir, filepath.FromSlash(path))); err != nil {
			t.Errorf("missing %s: %v", path, err)
		}
	}
}

func TestNewRejectsExtraArgs(t *testing.T) {
	if _, err := execute(t, "new", "demo", "extra", "--group", "com.acme"); err == nil {
		t.Fatal("new accepted two positional arguments, want error")
	}
}

func TestNewRejectsInvalidName(t *testing.T) {
	if _, err := execute(t, "new", "Bad_Name", "--group", "com.acme", "--output", t.TempDir(), "--no-interactive"); err == nil {
		t.Fatal("new accepted an invalid name, want error")
	}
}
