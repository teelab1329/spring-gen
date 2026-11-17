package generator

import (
	"bytes"
	"fmt"
	"html"
	"io/fs"
	"path"
	"sort"
	"strings"
	"text/template"

	"github.com/teelab1329/spring-gen/internal/assets"
	"github.com/teelab1329/spring-gen/internal/generator/arch"
)

// File is one rendered output file, before it is written to disk.
type File struct {
	Path string // slash-separated, relative to the project root
	Data []byte
	Mode fs.FileMode
}

type templateEntry struct {
	source string                     // path inside internal/assets/templates
	target func(Data) (string, error) // destination path
	mode   fs.FileMode
	when   func(Data) bool // optional condition on the resolved data
}

// fileTarget returns a fixed destination path.
func fileTarget(dst string) func(Data) (string, error) {
	return func(Data) (string, error) { return dst, nil }
}

// javaTarget places a file in the package the architecture assigns to role.
func javaTarget(role arch.Role, file string, testFile bool) func(Data) (string, error) {
	return func(d Data) (string, error) {
		pkg, err := d.Package(string(role))
		if err != nil {
			return "", err
		}
		root := "src/main/java"
		if testFile {
			root = "src/test/java"
		}
		return path.Join(root, strings.ReplaceAll(pkg, ".", "/"), file), nil
	}
}

func hasSample(d Data) bool { return d.Sample }

func hasUseCase(d Data) bool { return d.Sample && d.Architecture == "clean" }

// Templates ending in .tmpl are executed; everything else is copied verbatim
// (the Maven wrapper scripts are large and contain no template syntax).
var manifest = []templateEntry{
	{source: "base/pom.xml.tmpl", target: fileTarget("pom.xml")},
	{source: "base/application.yml.tmpl", target: fileTarget("src/main/resources/application.yml")},
	{source: "base/Main.java.tmpl", target: func(d Data) (string, error) {
		return path.Join("src/main/java", d.BaseDir, d.ClassName+".java"), nil
	}},
	{source: "base/ApplicationTests.java.tmpl", target: func(d Data) (string, error) {
		return path.Join("src/test/java", d.BaseDir, d.ClassName+"Tests.java"), nil
	}},
	{source: "base/gitignore.tmpl", target: fileTarget(".gitignore")},
	{source: "base/mvnw", target: fileTarget("mvnw"), mode: 0o755},
	{source: "base/mvnw.cmd", target: fileTarget("mvnw.cmd")},
	{source: "base/mvn/wrapper/maven-wrapper.properties", target: fileTarget(".mvn/wrapper/maven-wrapper.properties")},

	// JSend envelope and error handling: always generated.
	{source: "jsend/JSendResponse.java.tmpl", target: javaTarget(arch.RoleResponse, "JSendResponse.java", false)},
	{source: "jsend/ValidationException.java.tmpl", target: javaTarget(arch.RoleException, "ValidationException.java", false)},
	{source: "jsend/BusinessException.java.tmpl", target: javaTarget(arch.RoleException, "BusinessException.java", false)},
	{source: "jsend/GlobalExceptionHandler.java.tmpl", target: javaTarget(arch.RoleHandler, "GlobalExceptionHandler.java", false)},
	{source: "jsend/GlobalExceptionHandlerTest.java.tmpl", target: javaTarget(arch.RoleHandler, "GlobalExceptionHandlerTest.java", true)},

	// HTTP request/response logging (always generated, toggled by property).
	{source: "logging/LogType.java.tmpl", target: javaTarget(arch.RoleLogging, "LogType.java", false)},
	{source: "logging/HttpLoggingSupport.java.tmpl", target: javaTarget(arch.RoleLogging, "HttpLoggingSupport.java", false)},
	{source: "logging/HttpLoggingProperties.java.tmpl", target: javaTarget(arch.RoleLogging, "HttpLoggingProperties.java", false)},
	{source: "logging/HttpLoggingConfig.java.tmpl", target: javaTarget(arch.RoleLogging, "HttpLoggingConfig.java", false)},
	{source: "logging/HttpLoggingFilter.java.tmpl", target: javaTarget(arch.RoleLogging, "HttpLoggingFilter.java", false)},
	{source: "logging/RestClientLoggingInterceptor.java.tmpl", target: javaTarget(arch.RoleLogging, "RestClientLoggingInterceptor.java", false)},
	{source: "logging/HttpLoggingFilterTest.java.tmpl", target: javaTarget(arch.RoleLogging, "HttpLoggingFilterTest.java", true)},
	{source: "logging/RestClientLoggingInterceptorTest.java.tmpl", target: javaTarget(arch.RoleLogging, "RestClientLoggingInterceptorTest.java", true)},

	// Hello sample: optional, and the use case port only exists in clean.
	{source: "sample/Greeting.java.tmpl", target: javaTarget(arch.RoleModel, "Greeting.java", false), when: hasSample},
	{source: "sample/HelloUseCase.java.tmpl", target: javaTarget(arch.RoleUseCase, "HelloUseCase.java", false), when: hasUseCase},
	{source: "sample/HelloService.java.tmpl", target: javaTarget(arch.RoleService, "HelloService.java", false), when: hasSample},
	{source: "sample/HelloResponse.java.tmpl", target: javaTarget(arch.RoleDTO, "HelloResponse.java", false), when: hasSample},
	{source: "sample/HelloController.java.tmpl", target: javaTarget(arch.RoleController, "HelloController.java", false), when: hasSample},
	{source: "sample/HelloControllerTest.java.tmpl", target: javaTarget(arch.RoleController, "HelloControllerTest.java", true), when: hasSample},
}

var funcs = template.FuncMap{
	"xml":    html.EscapeString,
	"lower":  strings.ToLower,
	"pascal": Pascal,
}

// Render executes every applicable template and returns the files sorted by
// path. It touches no filesystem state.
func Render(data Data) ([]File, error) {
	files := make([]File, 0, len(manifest))

	for _, entry := range manifest {
		if entry.when != nil && !entry.when(data) {
			continue
		}

		raw, err := fs.ReadFile(assets.Templates, path.Join("templates", entry.source))
		if err != nil {
			return nil, fmt.Errorf("read template %s: %w", entry.source, err)
		}

		content := raw
		if strings.HasSuffix(entry.source, ".tmpl") {
			content, err = execute(entry.source, raw, data)
			if err != nil {
				return nil, err
			}
		}

		target, err := entry.target(data)
		if err != nil {
			return nil, fmt.Errorf("resolve path for %s: %w", entry.source, err)
		}

		files = append(files, File{
			Path: path.Clean(target),
			Data: content,
			Mode: entry.mode,
		})
	}

	sort.Slice(files, func(i, j int) bool { return files[i].Path < files[j].Path })
	return files, nil
}

func execute(name string, raw []byte, data Data) ([]byte, error) {
	tmpl, err := template.New(name).
		Option("missingkey=error").
		Funcs(funcs).
		Parse(string(raw))
	if err != nil {
		return nil, fmt.Errorf("parse template %s: %w", name, err)
	}

	var out bytes.Buffer
	if err := tmpl.Execute(&out, data); err != nil {
		return nil, fmt.Errorf("execute template %s: %w", name, err)
	}
	return out.Bytes(), nil
}
