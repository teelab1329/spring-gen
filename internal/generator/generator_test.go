package generator

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

func validInput(name string) Input {
	return Input{
		Name:           name,
		Group:          "com.acme",
		Architecture:   "layered",
		VirtualThreads: true,
		Sample:         true,
	}
}

func TestNewDataDerivesNames(t *testing.T) {
	in := validInput("order-service")
	data, err := NewData(in)
	if err != nil {
		t.Fatalf("NewData returned error: %v", err)
	}

	if want := "com.acme.orderservice"; data.BasePackage != want {
		t.Errorf("BasePackage = %q, want %q", data.BasePackage, want)
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
	if data.Architecture != "layered" || !data.VirtualThreads || !data.Sample {
		t.Errorf("data = %+v, want architecture, virtual threads and sample preserved", data)
	}
}

func TestDataPackageResolvesRoles(t *testing.T) {
	data := Data{BasePackage: "com.acme.app", Architecture: "clean"}

	tests := map[string]string{
		"controller": "com.acme.app.infrastructure.web",
		"usecase":    "com.acme.app.application.port.in",
		"service":    "com.acme.app.application.service",
		"response":   "com.acme.app.infrastructure.web.response",
	}
	for role, want := range tests {
		got, err := data.Package(role)
		if err != nil {
			t.Fatalf("Package(%q): %v", role, err)
		}
		if got != want {
			t.Errorf("Package(%q) = %q, want %q", role, got, want)
		}
	}

	if _, err := data.Package("nonsense"); err == nil {
		t.Error("Package accepted an unknown role")
	}
}

func TestNewDataRejectsInvalidInput(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*Input)
	}{
		{"empty name", func(in *Input) { in.Name = "" }},
		{"uppercase name", func(in *Input) { in.Name = "Order" }},
		{"double dash", func(in *Input) { in.Name = "order--service" }},
		{"trailing dash", func(in *Input) { in.Name = "order-" }},
		{"path separator", func(in *Input) { in.Name = "../order" }},
		{"missing group", func(in *Input) { in.Group = "" }},
		{"single segment group", func(in *Input) { in.Group = "acme" }},
		{"group with dash", func(in *Input) { in.Group = "com.acme-corp" }},
		{"keyword group", func(in *Input) { in.Group = "com.class" }},
		{"missing architecture", func(in *Input) { in.Architecture = "" }},
		{"unknown architecture", func(in *Input) { in.Architecture = "hexagonal" }},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			in := validInput("order-service")
			test.mutate(&in)
			if _, err := NewData(in); err == nil {
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
	in := validInput("order-service")
	in.Description = "Orders"
	in.Output = output

	result, err := Generate(in)
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
		"src/main/java/com/acme/orderservice/common/response/JSendResponse.java",
		"src/main/java/com/acme/orderservice/common/exception/GlobalExceptionHandler.java",
		"src/test/java/com/acme/orderservice/common/exception/GlobalExceptionHandlerTest.java",
		"src/main/java/com/acme/orderservice/common/logging/HttpLoggingFilter.java",
		"src/test/java/com/acme/orderservice/common/logging/HttpLoggingFilterTest.java",
		"src/main/java/com/acme/orderservice/controller/HelloController.java",
		"src/main/java/com/acme/orderservice/service/HelloService.java",
		"src/test/java/com/acme/orderservice/controller/HelloControllerTest.java",
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
		"micrometer-tracing-bridge-otel",
	} {
		if !strings.Contains(string(pom), want) {
			t.Errorf("pom.xml does not contain %q", want)
		}
	}
	exporterDependency := regexp.MustCompile(`<artifactId>[^<]*exporter[^<]*</artifactId>`)
	if exporterDependency.Match(pom) {
		t.Errorf("pom.xml must not declare a trace exporter:\n%s", pom)
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

func TestGenerateCleanArchitecture(t *testing.T) {
	in := validInput("order-service")
	in.Architecture = "clean"
	in.Output = t.TempDir()

	result, err := Generate(in)
	if err != nil {
		t.Fatalf("Generate returned error: %v", err)
	}

	for _, path := range []string{
		"src/main/java/com/acme/orderservice/domain/model/Greeting.java",
		"src/main/java/com/acme/orderservice/domain/exception/ValidationException.java",
		"src/main/java/com/acme/orderservice/application/port/in/HelloUseCase.java",
		"src/main/java/com/acme/orderservice/application/service/HelloService.java",
		"src/main/java/com/acme/orderservice/infrastructure/web/HelloController.java",
		"src/main/java/com/acme/orderservice/infrastructure/web/response/JSendResponse.java",
		"src/main/java/com/acme/orderservice/infrastructure/web/dto/HelloResponse.java",
		"src/main/java/com/acme/orderservice/infrastructure/logging/HttpLoggingFilter.java",
		"src/test/java/com/acme/orderservice/infrastructure/web/HelloControllerTest.java",
	} {
		if _, err := os.Stat(filepath.Join(result.Dir, filepath.FromSlash(path))); err != nil {
			t.Errorf("missing generated file %s: %v", path, err)
		}
	}

	for _, path := range []string{
		"src/main/java/com/acme/orderservice/controller/HelloController.java",
		"src/main/java/com/acme/orderservice/service/HelloService.java",
	} {
		if _, err := os.Stat(filepath.Join(result.Dir, filepath.FromSlash(path))); !os.IsNotExist(err) {
			t.Errorf("layered path %s exists in a clean project: %v", path, err)
		}
	}
}

func TestGenerateLayeredHasNoUseCase(t *testing.T) {
	in := validInput("demo")
	in.Output = t.TempDir()

	result, err := Generate(in)
	if err != nil {
		t.Fatalf("Generate returned error: %v", err)
	}
	for _, file := range result.Files {
		if strings.Contains(file, "HelloUseCase") {
			t.Errorf("layered project contains %s", file)
		}
	}
}

func TestGenerateWithoutSampleKeepsJSend(t *testing.T) {
	in := validInput("demo")
	in.Sample = false
	in.VirtualThreads = false
	in.Output = t.TempDir()

	result, err := Generate(in)
	if err != nil {
		t.Fatalf("Generate returned error: %v", err)
	}

	for _, file := range result.Files {
		switch {
		case strings.Contains(file, "Hello"), strings.Contains(file, "Greeting"):
			t.Errorf("sample file %s generated with --sample=false", file)
		case strings.Contains(file, "JSendResponse"), strings.Contains(file, "GlobalExceptionHandler"), strings.Contains(file, "BusinessException"):
			// expected: JSend support is always generated
		}
	}

	yml, err := os.ReadFile(filepath.Join(result.Dir, "src/main/resources/application.yml"))
	if err != nil {
		t.Fatalf("read application.yml: %v", err)
	}
	if strings.Contains(string(yml), "virtual") {
		t.Errorf("application.yml enables virtual threads while they are disabled:\n%s", yml)
	}
}

func TestGenerateWithVirtualThreads(t *testing.T) {
	in := validInput("demo")
	in.Output = t.TempDir()

	result, err := Generate(in)
	if err != nil {
		t.Fatalf("Generate returned error: %v", err)
	}

	yml, err := os.ReadFile(filepath.Join(result.Dir, "src/main/resources/application.yml"))
	if err != nil {
		t.Fatalf("read application.yml: %v", err)
	}
	if !strings.Contains(string(yml), "virtual:\n      enabled: true") {
		t.Errorf("application.yml does not enable virtual threads:\n%s", yml)
	}
}

func TestGenerateReplacesEmptyDirectory(t *testing.T) {
	output := t.TempDir()
	if err := os.Mkdir(filepath.Join(output, "demo"), 0o755); err != nil {
		t.Fatal(err)
	}

	in := validInput("demo")
	in.Output = output
	if _, err := Generate(in); err != nil {
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

	in := validInput("demo")
	in.Output = output
	if _, err := Generate(in); err == nil {
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

	in := validInput("demo")
	in.Output = output
	in.Force = true
	if _, err := Generate(in); err != nil {
		t.Fatalf("Generate with Force returned error: %v", err)
	}

	if _, err := os.Stat(filepath.Join(target, "stale.txt")); !os.IsNotExist(err) {
		t.Errorf("stale file survived --force: %v", err)
	}
	if _, err := os.Stat(filepath.Join(target, "pom.xml")); err != nil {
		t.Errorf("pom.xml missing after forced generation: %v", err)
	}
}
