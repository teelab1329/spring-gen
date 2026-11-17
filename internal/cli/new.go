package cli

import (
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/spf13/cobra"

	"github.com/teelab1329/spring-gen/internal/generator"
	"github.com/teelab1329/spring-gen/internal/prompt"
)

type newOptions struct {
	group         string
	description   string
	output        string
	force         bool
	noInteractive bool
}

func newNewCmd() *cobra.Command {
	var opts newOptions

	cmd := &cobra.Command{
		Use:   "new <name>",
		Short: "Generate a new Spring Boot service",
		Long: "Generate a runnable Spring Boot service from the embedded templates.\n\n" +
			"The project targets Java " + generator.JavaVersion + " and Spring Boot " + generator.BootVersion + ".",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runNew(cmd, args[0], opts)
		},
	}

	flags := cmd.Flags()
	flags.StringVar(&opts.group, "group", "", "Maven groupId and Java package prefix (e.g. com.acme)")
	flags.StringVar(&opts.description, "description", "", "Project description (default \"<name> service\")")
	flags.StringVar(&opts.output, "output", ".", "Directory that will contain the new project")
	flags.BoolVar(&opts.force, "force", false, "Replace the target directory if it already exists")
	flags.BoolVar(&opts.noInteractive, "no-interactive", false, "Never prompt; fail when required values are missing")

	return cmd
}

func runNew(cmd *cobra.Command, name string, opts newOptions) error {
	in := generator.Input{
		Name:        name,
		Group:       strings.TrimSpace(opts.group),
		Description: strings.TrimSpace(opts.description),
		Output:      opts.output,
		Force:       opts.force,
	}

	interactive := !opts.noInteractive && isInteractive(cmd.InOrStdin())

	if in.Group == "" && !interactive {
		return fmt.Errorf("missing required flags: --group")
	}

	prompter := &prompt.Prompt{In: cmd.InOrStdin(), Out: cmd.ErrOrStderr()}

	if in.Group == "" {
		answer, err := prompter.Ask("Group (e.g. com.acme)", "")
		if err != nil {
			return fmt.Errorf("read group: %w", err)
		}
		in.Group = answer
	}

	if interactive && in.Description == "" {
		answer, err := prompter.Ask("Description", name+" service")
		if err != nil {
			return fmt.Errorf("read description: %w", err)
		}
		in.Description = answer
	}

	result, err := generator.Generate(in)
	if err != nil {
		return err
	}

	fmt.Fprintf(cmd.OutOrStdout(), "Created %s\n", result.Dir)
	return nil
}

// isInteractive reports whether in is a terminal. Non-terminal input (CI,
// piped stdin) must never block waiting for an answer.
func isInteractive(in io.Reader) bool {
	file, ok := in.(*os.File)
	if !ok {
		return false
	}
	info, err := file.Stat()
	if err != nil {
		return false
	}
	return info.Mode()&os.ModeCharDevice != 0
}
