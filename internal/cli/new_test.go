package cli

import (
	"bytes"
	"io"
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

// runNewWith scripted answers simulates a terminal run without a real TTY.
func runNewWith(t *testing.T, stdin string, args ...string) (stdout, stderr string, err error) {
	t.Helper()

	cmd := newNewCmdWithOptions(newOptions{
		interactive: func(io.Reader) bool { return true },
	})
	var out, errOut bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&errOut)
	cmd.SetIn(strings.NewReader(stdin))
	cmd.SetArgs(args)

	err = cmd.Execute()
	return out.String(), errOut.String(), err
}

func TestNewPromptsForMissingValues(t *testing.T) {
	output := t.TempDir()

	stdout, stderr, err := runNewWith(t,
		"order-service\ncom.acme\n\n",
		"--output", output,
	)
	if err != nil {
		t.Fatalf("new returned error: %v\nstderr:\n%s", err, stderr)
	}

	projectDir := filepath.Join(output, "order-service")
	if !strings.Contains(stdout, "Created "+projectDir) {
		t.Errorf("stdout = %q, want the created path", stdout)
	}
	if strings.Contains(stdout, "Service name") {
		t.Errorf("prompt leaked into stdout: %q", stdout)
	}
	if !strings.Contains(stderr, "Service name") || !strings.Contains(stderr, "Group (e.g. com.acme)") {
		t.Errorf("stderr = %q, want both prompts", stderr)
	}

	pom, readErr := os.ReadFile(filepath.Join(projectDir, "pom.xml"))
	if readErr != nil {
		t.Fatalf("read pom.xml: %v", readErr)
	}
	if !strings.Contains(string(pom), "<description>order-service service</description>") {
		t.Errorf("default description missing from pom.xml")
	}
}

func TestNewReasksAfterInvalidAnswers(t *testing.T) {
	output := t.TempDir()

	stdout, stderr, err := runNewWith(t,
		"Bad_Name\norder-service\nacme\ncom.acme\n\n",
		"--output", output,
	)
	if err != nil {
		t.Fatalf("new returned error: %v\nstderr:\n%s", err, stderr)
	}

	if !strings.Contains(stderr, "invalid name") {
		t.Errorf("stderr = %q, want the invalid name message", stderr)
	}
	if !strings.Contains(stderr, `invalid group "acme"`) {
		t.Errorf("stderr = %q, want the invalid group message", stderr)
	}
	if lines := strings.Split(strings.TrimSpace(stdout), "\n"); len(lines) != 1 {
		t.Errorf("stdout = %q, want only the Created line", stdout)
	}
	if _, statErr := os.Stat(filepath.Join(output, "order-service", "pom.xml")); statErr != nil {
		t.Errorf("project was not generated: %v", statErr)
	}
}

func TestNewAbortsOnEndOfInput(t *testing.T) {
	output := t.TempDir()

	_, _, err := runNewWith(t, "", "--output", output)
	if err == nil {
		t.Fatal("new succeeded with no input")
	}
	if !strings.Contains(err.Error(), "aborted: no input for name") {
		t.Errorf("error = %q, want the abort hint", err)
	}
	if entries, readErr := os.ReadDir(output); readErr == nil && len(entries) != 0 {
		t.Errorf("output dir has %d entries after abort, want none", len(entries))
	}
}

func TestNewReportsAllMissingValuesWhenNotInteractive(t *testing.T) {
	_, err := execute(t, "new", "--output", t.TempDir(), "--no-interactive")
	if err == nil {
		t.Fatal("new succeeded without a name and group")
	}
	for _, want := range []string{"<name> argument", "--group"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("error = %q, want %q", err, want)
		}
	}
}

func TestNewFailsInvalidGroupFlagWithoutPrompting(t *testing.T) {
	output := t.TempDir()

	_, stderr, err := runNewWith(t, "com.acme\n", "demo", "--group", "acme", "--output", output)
	if err == nil {
		t.Fatal("new accepted an invalid --group")
	}
	if !strings.Contains(err.Error(), "invalid group") {
		t.Errorf("error = %q, want the validation error", err)
	}
	if strings.Contains(stderr, "Group (e.g. com.acme)") {
		t.Errorf("prompted for a value that was passed as a flag: %q", stderr)
	}
	if _, statErr := os.Stat(filepath.Join(output, "demo")); !os.IsNotExist(statErr) {
		t.Errorf("project was created despite the error: %v", statErr)
	}
}
