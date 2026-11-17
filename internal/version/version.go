// Package version reports build information for the spring-gen binary.
package version

import (
	"fmt"
	"runtime"
	"runtime/debug"
)

// Overridden at build time, for example:
//
//	go build -ldflags "-X github.com/teelab1329/spring-gen/internal/version.Version=v0.1.0"
//
// When they are empty, Get falls back to the module version and VCS metadata
// that the Go toolchain embeds in the binary.
var (
	Version string
	Commit  string
	Date    string
)

// Info is the resolved build information of the running binary.
type Info struct {
	Version string
	Commit  string
	Date    string
	Go      string
	OS      string
	Arch    string
}

const (
	devVersion     = "dev"
	unknown        = "unknown"
	shortCommitLen = 12
)

// Get resolves build information, preferring ldflags values over toolchain
// metadata, and never returns empty fields.
func Get() Info {
	info := Info{
		Version: Version,
		Commit:  Commit,
		Date:    Date,
		Go:      runtime.Version(),
		OS:      runtime.GOOS,
		Arch:    runtime.GOARCH,
	}

	if build, ok := debug.ReadBuildInfo(); ok {
		if info.Version == "" && build.Main.Version != "" && build.Main.Version != "(devel)" {
			info.Version = build.Main.Version
		}
		for _, setting := range build.Settings {
			switch setting.Key {
			case "vcs.revision":
				if info.Commit == "" {
					info.Commit = setting.Value
				}
			case "vcs.time":
				if info.Date == "" {
					info.Date = setting.Value
				}
			}
		}
	}

	if info.Version == "" {
		info.Version = devVersion
	}
	if info.Commit == "" {
		info.Commit = unknown
	} else {
		info.Commit = shorten(info.Commit)
	}
	if info.Date == "" {
		info.Date = unknown
	}

	return info
}

// String renders one line suitable for `spring-gen version` and `--version`.
func (i Info) String() string {
	return fmt.Sprintf("spring-gen %s (commit %s, built %s, %s %s/%s)",
		i.Version, i.Commit, i.Date, i.Go, i.OS, i.Arch)
}

func shorten(revision string) string {
	if len(revision) > shortCommitLen {
		return revision[:shortCommitLen]
	}
	return revision
}
