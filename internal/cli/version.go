package cli

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/teelab1329/spring-gen/internal/version"
)

func newVersionCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "version",
		Short: "Print build information",
		Long:  "Print the version, commit, build date, and Go toolchain of this binary.",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			_, err := fmt.Fprintln(cmd.OutOrStdout(), version.Get().String())
			return err
		},
	}
}
