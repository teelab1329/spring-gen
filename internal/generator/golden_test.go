package generator

import (
	"flag"
	"os"
	"path/filepath"
	"testing"
)

var update = flag.Bool("update", false, "rewrite testdata/golden from the current templates")

// TestGolden pins the full rendered output for a representative input. Run
// `go test ./... -update` after template changes and review the diff.
func TestGolden(t *testing.T) {
	output := t.TempDir()
	result, err := Generate(Input{
		Name:        "order-service",
		Group:       "com.acme",
		Description: "Order service",
		Output:      output,
	})
	if err != nil {
		t.Fatalf("Generate returned error: %v", err)
	}

	goldenDir := filepath.Join("testdata", "golden", "basic")
	got := readTree(t, result.Dir)

	if *update {
		if err := os.RemoveAll(goldenDir); err != nil {
			t.Fatalf("reset golden dir: %v", err)
		}
		if err := writeTree(goldenDir, got); err != nil {
			t.Fatalf("write golden dir: %v", err)
		}
	}

	want := readTree(t, goldenDir)

	for path, wantData := range want {
		gotData, ok := got[path]
		if !ok {
			t.Errorf("missing file %s", path)
			continue
		}
		if string(gotData) != string(wantData) {
			t.Errorf("content mismatch for %s", path)
		}
	}
	for path := range got {
		if _, ok := want[path]; !ok {
			t.Errorf("unexpected file %s", path)
		}
	}
}

func readTree(t *testing.T, root string) map[string][]byte {
	t.Helper()

	files := make(map[string][]byte)
	err := filepath.WalkDir(root, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() {
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		files[filepath.ToSlash(rel)] = data
		return nil
	})
	if err != nil {
		t.Fatalf("read tree %s: %v", root, err)
	}
	return files
}

func writeTree(root string, files map[string][]byte) error {
	for path, data := range files {
		dst := filepath.Join(root, filepath.FromSlash(path))
		if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
			return err
		}
		if err := os.WriteFile(dst, data, 0o644); err != nil {
			return err
		}
	}
	return nil
}
