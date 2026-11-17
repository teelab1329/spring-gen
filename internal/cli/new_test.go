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
		"--architecture", "layered",
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
		"src/main/java/com/acme/orderservice/common/response/JSendResponse.java",
		"src/main/java/com/acme/orderservice/controller/HelloController.java",
	} {
		if _, err := os.Stat(filepath.Join(projectDir, filepath.FromSlash(path))); err != nil {
			t.Errorf("missing %s: %v", path, err)
		}
	}

	yml, readErr := os.ReadFile(filepath.Join(projectDir, "src/main/resources/application.yml"))
	if readErr != nil {
		t.Fatalf("read application.yml: %v", readErr)
	}
	if !strings.Contains(string(yml), "enabled: true") {
		t.Errorf("virtual threads are not enabled by default:\n%s", yml)
	}
}

func TestNewRejectsExtraArgs(t *testing.T) {
	if _, err := execute(t, "new", "demo", "extra", "--group", "com.acme"); err == nil {
		t.Fatal("new accepted two positional arguments, want error")
	}
}

func TestNewRejectsInvalidName(t *testing.T) {
	if _, err := execute(t, "new", "Bad_Name", "--group", "com.acme", "--architecture", "layered", "--output", t.TempDir(), "--no-interactive"); err == nil {
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

	// name, group, description, architecture, virtual threads, sample
	stdout, stderr, err := runNewWith(t,
		"order-service\ncom.acme\n\n1\n\n\n",
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
	for _, want := range []string{
		"Service name",
		"Group (e.g. com.acme)",
		"Architecture",
		"1) layered",
		"Enable virtual threads? [Y/n]",
		"Include a sample Hello endpoint? [Y/n]",
	} {
		if !strings.Contains(stderr, want) {
			t.Errorf("stderr does not contain %q:\n%s", want, stderr)
		}
	}

	pom, readErr := os.ReadFile(filepath.Join(projectDir, "pom.xml"))
	if readErr != nil {
		t.Fatalf("read pom.xml: %v", readErr)
	}
	if !strings.Contains(string(pom), "<description>order-service service</description>") {
		t.Errorf("default description missing from pom.xml")
	}

	if _, statErr := os.Stat(filepath.Join(projectDir, "src/main/java/com/acme/orderservice/controller/HelloController.java")); statErr != nil {
		t.Errorf("sample controller missing: %v", statErr)
	}
}

func TestNewReasksAfterInvalidAnswers(t *testing.T) {
	output := t.TempDir()

	// invalid name, valid name, invalid group, valid group, default description,
	// two invalid architecture answers, "clean", invalid yes/no, "y", "n"
	stdout, stderr, err := runNewWith(t,
		"Bad_Name\norder-service\nacme\ncom.acme\n\n3\n0\nclean\nmaybe\ny\nn\n",
		"--output", output,
	)
	if err != nil {
		t.Fatalf("new returned error: %v\nstderr:\n%s", err, stderr)
	}

	for _, want := range []string{
		"invalid name",
		`invalid group "acme"`,
		"choose 1-2",
		"answer yes or no",
	} {
		if !strings.Contains(stderr, want) {
			t.Errorf("stderr does not contain %q:\n%s", want, stderr)
		}
	}
	if lines := strings.Split(strings.TrimSpace(stdout), "\n"); len(lines) != 1 {
		t.Errorf("stdout = %q, want only the Created line", stdout)
	}

	projectDir := filepath.Join(output, "order-service")
	if _, statErr := os.Stat(filepath.Join(projectDir, "src/main/java/com/acme/orderservice/infrastructure/web/response/JSendResponse.java")); statErr != nil {
		t.Errorf("clean layout not used: %v", statErr)
	}
	if _, statErr := os.Stat(filepath.Join(projectDir, "src/main/java/com/acme/orderservice/application/service/HelloService.java")); !os.IsNotExist(statErr) {
		t.Errorf("sample was generated even though the answer was no: %v", statErr)
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
	for _, want := range []string{"<name> argument", "--group", "--architecture"} {
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

func TestNewSkipsPromptForExplicitFlags(t *testing.T) {
	output := t.TempDir()

	_, stderr, err := runNewWith(t,
		"demo\ncom.acme\n\n\n\n",
		"--architecture", "clean",
		"--virtual-threads=false",
		"--sample=false",
		"--output", output,
	)
	if err != nil {
		t.Fatalf("new returned error: %v\nstderr:\n%s", err, stderr)
	}

	for _, unwanted := range []string{"Architecture", "Enable virtual threads?", "Include a sample Hello endpoint?"} {
		if strings.Contains(stderr, unwanted) {
			t.Errorf("prompted for %q even though the flag was given:\n%s", unwanted, stderr)
		}
	}

	projectDir := filepath.Join(output, "demo")
	yml, readErr := os.ReadFile(filepath.Join(projectDir, "src/main/resources/application.yml"))
	if readErr != nil {
		t.Fatalf("read application.yml: %v", readErr)
	}
	if strings.Contains(string(yml), "virtual") {
		t.Errorf("--virtual-threads=false was ignored:\n%s", yml)
	}
	if _, statErr := os.Stat(filepath.Join(projectDir, "src/main/java/com/acme/demo/infrastructure/web/HelloController.java")); !os.IsNotExist(statErr) {
		t.Errorf("--sample=false was ignored: %v", statErr)
	}
}
