// Package generator turns a small set of inputs into a Spring Boot project
// rendered from the embedded templates.
package generator

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Versions are fixed by design: every generated project targets Java 21 and
// the pinned Spring Boot release. Change them here, never in the templates.
const (
	JavaVersion = "21"
	BootVersion = "3.5.16"
)

// Input is what the user asked for, before validation and derivation.
type Input struct {
	Name        string
	Group       string
	Description string
	Output      string
	Force       bool
}

// Data is the resolved template data.
type Data struct {
	Name        string // artifactId and spring.application.name, e.g. order-service
	Group       string // Maven groupId, e.g. com.acme
	Artifact    string // Maven artifactId, same as Name
	Description string
	Package     string // Java package, e.g. com.acme.orderservice
	BaseDir     string // package as a path, e.g. com/acme/orderservice
	ClassName   string // main class, e.g. OrderServiceApplication
	Java        string
	Boot        string
}

// Result describes a successful generation.
type Result struct {
	Dir   string   // absolute path of the created project
	Files []string // slash-separated paths relative to Dir
}

// NewData validates the input and derives the template data.
func NewData(in Input) (Data, error) {
	if err := validateName(in.Name); err != nil {
		return Data{}, err
	}

	group := strings.TrimSpace(in.Group)
	if err := validateGroup(group); err != nil {
		return Data{}, err
	}

	description := strings.TrimSpace(in.Description)
	if description == "" {
		description = fmt.Sprintf("%s service", in.Name)
	}

	pkg := group + "." + strings.ReplaceAll(in.Name, "-", "")
	if err := validatePackage(pkg); err != nil {
		return Data{}, err
	}

	return Data{
		Name:        in.Name,
		Group:       group,
		Artifact:    in.Name,
		Description: description,
		Package:     pkg,
		BaseDir:     strings.ReplaceAll(pkg, ".", "/"),
		ClassName:   Pascal(in.Name) + "Application",
		Java:        JavaVersion,
		Boot:        BootVersion,
	}, nil
}

// Generate renders the project into <Output>/<Name>. It writes into a staging
// directory and renames it into place only after every file rendered, so a
// failure never leaves a half-written project behind.
func Generate(in Input) (Result, error) {
	data, err := NewData(in)
	if err != nil {
		return Result{}, err
	}

	parent := in.Output
	if parent == "" {
		parent = "."
	}
	parent, err = filepath.Abs(parent)
	if err != nil {
		return Result{}, fmt.Errorf("resolve output directory: %w", err)
	}
	if err := os.MkdirAll(parent, 0o755); err != nil {
		return Result{}, fmt.Errorf("create output directory: %w", err)
	}

	target := filepath.Join(parent, in.Name)
	if err := prepareTarget(target, in.Force); err != nil {
		return Result{}, err
	}

	files, err := Render(data)
	if err != nil {
		return Result{}, fmt.Errorf("render %s: %w", in.Name, err)
	}

	staging, err := os.MkdirTemp(parent, ".spring-gen-"+in.Name+"-")
	if err != nil {
		return Result{}, fmt.Errorf("create staging directory: %w", err)
	}
	defer os.RemoveAll(staging)

	if err := writeFiles(staging, files); err != nil {
		return Result{}, err
	}
	if err := os.Rename(staging, target); err != nil {
		return Result{}, fmt.Errorf("create %s: %w", target, err)
	}

	names := make([]string, len(files))
	for i, file := range files {
		names[i] = file.Path
	}

	return Result{Dir: target, Files: names}, nil
}

// prepareTarget fails unless the destination is missing, empty, or Force is
// set. In the empty and forced cases the destination is removed so the staged
// directory can be renamed into its place.
func prepareTarget(target string, force bool) error {
	info, err := os.Stat(target)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("inspect %s: %w", target, err)
	}
	if !info.IsDir() {
		return fmt.Errorf("%s already exists and is not a directory", target)
	}

	if !force {
		entries, err := os.ReadDir(target)
		if err != nil {
			return fmt.Errorf("inspect %s: %w", target, err)
		}
		if len(entries) > 0 {
			return fmt.Errorf("%s already exists and is not empty (use --force to replace it)", target)
		}
	}

	if err := os.RemoveAll(target); err != nil {
		return fmt.Errorf("replace %s: %w", target, err)
	}
	return nil
}

func writeFiles(root string, files []File) error {
	for _, file := range files {
		dst := filepath.Join(root, filepath.FromSlash(file.Path))
		if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
			return fmt.Errorf("create directory for %s: %w", file.Path, err)
		}
		mode := file.Mode
		if mode == 0 {
			mode = 0o644
		}
		if err := os.WriteFile(dst, file.Data, mode); err != nil {
			return fmt.Errorf("write %s: %w", file.Path, err)
		}
		// WriteFile applies the umask, so set the mode explicitly (mvnw must be
		// executable).
		if err := os.Chmod(dst, mode); err != nil {
			return fmt.Errorf("set mode on %s: %w", file.Path, err)
		}
	}
	return nil
}
