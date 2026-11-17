// Package cli wires the spring-gen command tree.
package cli

import (
	"github.com/spf13/cobra"

	"github.com/teelab1329/spring-gen/internal/version"
)

// NewRootCmd builds the spring-gen command tree. Each call returns an
// independent tree so tests never share flag or output state.
func NewRootCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "spring-gen",
		Short: "Generate Spring Boot projects from embedded templates",
		Long:  "spring-gen generates Spring Boot projects from templates compiled into the binary.\nIt never calls the network.",
		// --version prints the same line as the version command.
		Version: version.Get().String(),
		// Report errors once, from main, instead of printing usage walls.
		SilenceUsage:  true,
		SilenceErrors: true,
	}
	cmd.SetVersionTemplate("{{.Version}}\n")

	cmd.AddCommand(newVersionCmd())
	cmd.AddCommand(newNewCmd())

	return cmd
}

// Execute runs the command tree with the process arguments.
func Execute() error {
	return NewRootCmd().Execute()
}
