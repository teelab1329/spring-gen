// Package assets holds the project templates compiled into the binary.
package assets

import "embed"

// Templates contains every generator template. The all: prefix is required so
// files and directories whose names start with "." or "_" are embedded too.
//
//go:embed all:templates
var Templates embed.FS
