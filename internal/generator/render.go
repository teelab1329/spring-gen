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
)

// File is one rendered output file, before it is written to disk.
type File struct {
	Path string // slash-separated, relative to the project root
	Data []byte
	Mode fs.FileMode
}

type templateEntry struct {
	source string // path inside internal/assets/templates
	target func(Data) string
	mode   fs.FileMode
}

// Templates ending in .tmpl are executed; everything else is copied verbatim
// (the Maven wrapper scripts are large and contain no template syntax).
var manifest = []templateEntry{
	{source: "base/pom.xml.tmpl", target: func(Data) string { return "pom.xml" }},
	{source: "base/application.yml.tmpl", target: func(Data) string { return "src/main/resources/application.yml" }},
	{source: "base/Main.java.tmpl", target: func(d Data) string { return "src/main/java/" + d.BaseDir + "/" + d.ClassName + ".java" }},
	{source: "base/ApplicationTests.java.tmpl", target: func(d Data) string { return "src/test/java/" + d.BaseDir + "/" + d.ClassName + "Tests.java" }},
	{source: "base/gitignore.tmpl", target: func(Data) string { return ".gitignore" }},
	{source: "base/mvnw", target: func(Data) string { return "mvnw" }, mode: 0o755},
	{source: "base/mvnw.cmd", target: func(Data) string { return "mvnw.cmd" }},
	{source: "base/mvn/wrapper/maven-wrapper.properties", target: func(Data) string { return ".mvn/wrapper/maven-wrapper.properties" }},
}

var funcs = template.FuncMap{
	"xml":    html.EscapeString,
	"lower":  strings.ToLower,
	"pascal": Pascal,
}

// Render executes every template in the manifest and returns the files sorted
// by path. It touches no filesystem state.
func Render(data Data) ([]File, error) {
	files := make([]File, 0, len(manifest))

	for _, entry := range manifest {
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

		files = append(files, File{
			Path: path.Clean(entry.target(data)),
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
