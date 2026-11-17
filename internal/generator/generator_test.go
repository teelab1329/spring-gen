package generator

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestNewDataDerivesNames(t *testing.T) {
	data, err := NewData(Input{Name: "order-service", Group: "com.acme"})
	if err != nil {
		t.Fatalf("NewData returned error: %v", err)
	}

	if want := "com.acme.orderservice"; data.Package != want {
		t.Errorf("Package = %q, want %q", data.Package, want)
	}
	if want := "com/acme/orderservice"; data.BaseDir != want {
		t.Errorf("BaseDir = %q, want %q", data.BaseDir, want)
	}
	if want := "OrderServiceApplication"; data.ClassName != want {
		t.Errorf("ClassName = %q, want %q", data.ClassName, want)
	}
	if want := "order-service service"; data.Description != want {
		t.Errorf("Description = %q, want %q", data.Description, want)
	}
	if data.Java != JavaVersion || data.Boot != BootVersion {
		t.Errorf("versions = %s/%s, want %s/%s", data.Java, data.Boot, JavaVersion, BootVersion)
	}
}

func TestNewDataRejectsInvalidInput(t *testing.T) {
	tests := []struct {
		name  string
		input Input
	}{
		{"empty name", Input{Group: "com.acme"}},
		{"uppercase name", Input{Name: "Order", Group: "com.acme"}},
		{"double dash", Input{Name: "order--service", Group: "com.acme"}},
		{"trailing dash", Input{Name: "order-", Group: "com.acme"}},
		{"path separator", Input{Name: "../order", Group: "com.acme"}},
		{"missing group", Input{Name: "order-service"}},
		{"single segment group", Input{Name: "order-service", Group: "acme"}},
		{"group with dash", Input{Name: "order-service", Group: "com.acme-corp"}},
		{"keyword group", Input{Name: "order-service", Group: "com.class"}},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if _, err := NewData(test.input); err == nil {
				t.Fatal("NewData accepted invalid input, want error")
			}
		})
	}
}

func TestPascal(t *testing.T) {
	for in, want := range map[string]string{
		"order-service": "OrderService",
		"demo":          "Demo",
		"a-b-c":         "ABC",
	} {
		if got := Pascal(in); got != want {
			t.Errorf("Pascal(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestGenerateWritesRunnableProject(t *testing.T) {
	output := t.TempDir()

	result, err := Generate(Input{
		Name:        "order-service",
		Group:       "com.acme",
		Description: "Orders",
		Output:      output,
	})
	if err != nil {
		t.Fatalf("Generate returned error: %v", err)
	}

	wantDir := filepath.Join(output, "order-service")
	if result.Dir != wantDir {
		t.Errorf("Dir = %q, want %q", result.Dir, wantDir)
	}

	for _, path := range []string{
		"pom.xml",
		"mvnw",
		"mvnw.cmd",
		".mvn/wrapper/maven-wrapper.properties",
		".gitignore",
		"src/main/resources/application.yml",
		"src/main/java/com/acme/orderservice/OrderServiceApplication.java",
		"src/test/java/com/acme/orderservice/OrderServiceApplicationTests.java",
	} {
		if _, err := os.Stat(filepath.Join(result.Dir, filepath.FromSlash(path))); err != nil {
			t.Errorf("missing generated file %s: %v", path, err)
		}
	}

	pom, err := os.ReadFile(filepath.Join(result.Dir, "pom.xml"))
	if err != nil {
		t.Fatalf("read pom.xml: %v", err)
	}
	for _, want := range []string{
		"<version>" + BootVersion + "</version>",
		"<java.version>" + JavaVersion + "</java.version>",
		"<groupId>com.acme</groupId>",
		"<artifactId>order-service</artifactId>",
		"<description>Orders</description>",
		"spring-boot-starter-web",
		"spring-boot-starter-actuator",
	} {
		if !strings.Contains(string(pom), want) {
			t.Errorf("pom.xml does not contain %q", want)
		}
	}

	info, err := os.Stat(filepath.Join(result.Dir, "mvnw"))
	if err != nil {
		t.Fatalf("stat mvnw: %v", err)
	}
	if info.Mode().Perm()&0o111 == 0 {
		t.Errorf("mvnw mode = %v, want executable", info.Mode().Perm())
	}

	if entries, err := os.ReadDir(output); err != nil {
		t.Fatalf("read output dir: %v", err)
	} else if len(entries) != 1 {
		t.Errorf("output dir has %d entries, want only the project (staging dir left behind?)", len(entries))
	}
}

func TestGenerateReplacesEmptyDirectory(t *testing.T) {
	output := t.TempDir()
	if err := os.Mkdir(filepath.Join(output, "demo"), 0o755); err != nil {
		t.Fatal(err)
	}

	if _, err := Generate(Input{Name: "demo", Group: "com.acme", Output: output}); err != nil {
		t.Fatalf("Generate into empty directory returned error: %v", err)
	}
}

func TestGenerateRejectsNonEmptyDirectory(t *testing.T) {
	output := t.TempDir()
	target := filepath.Join(output, "demo")
	if err := os.Mkdir(target, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(target, "keep.txt"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}

	_, err := Generate(Input{Name: "demo", Group: "com.acme", Output: output})
	if err == nil {
		t.Fatal("Generate overwrote a non-empty directory without --force")
	}

	if _, statErr := os.Stat(filepath.Join(target, "keep.txt")); statErr != nil {
		t.Errorf("existing file was removed despite the error: %v", statErr)
	}
}

func TestGenerateForceReplacesNonEmptyDirectory(t *testing.T) {
	output := t.TempDir()
	target := filepath.Join(output, "demo")
	if err := os.Mkdir(target, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(target, "stale.txt"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}

	if _, err := Generate(Input{Name: "demo", Group: "com.acme", Output: output, Force: true}); err != nil {
		t.Fatalf("Generate with Force returned error: %v", err)
	}

	if _, err := os.Stat(filepath.Join(target, "stale.txt")); !os.IsNotExist(err) {
		t.Errorf("stale file survived --force: %v", err)
	}
	if _, err := os.Stat(filepath.Join(target, "pom.xml")); err != nil {
		t.Errorf("pom.xml missing after forced generation: %v", err)
	}
}
