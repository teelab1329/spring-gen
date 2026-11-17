package cli

import (
	"errors"
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/spf13/cobra"

	"github.com/teelab1329/spring-gen/internal/generator"
	"github.com/teelab1329/spring-gen/internal/generator/arch"
	"github.com/teelab1329/spring-gen/internal/prompt"
)

type newOptions struct {
	group          string
	description    string
	architecture   string
	virtualThreads bool
	sample         bool
	output         string
	force          bool
	noInteractive  bool

	// interactive is the terminal check; tests replace it to script answers.
	interactive func(io.Reader) bool
}

func newNewCmd() *cobra.Command {
	return newNewCmdWithOptions(newOptions{interactive: isInteractive})
}

func newNewCmdWithOptions(opts newOptions) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "new [name]",
		Short: "Generate a new Spring Boot service",
		Long: "Generate a runnable Spring Boot service from the embedded templates.\n\n" +
			"Missing values are prompted for on a terminal; use --no-interactive to fail instead.\n\n" +
			"The project targets Java " + generator.JavaVersion + " and Spring Boot " + generator.BootVersion + ".",
		Args: cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runNew(cmd, args, opts)
		},
	}

	flags := cmd.Flags()
	flags.StringVar(&opts.group, "group", "", "Maven groupId and Java package prefix (e.g. com.acme)")
	flags.StringVar(&opts.description, "description", "", "Project description (default \"<name> service\")")
	flags.StringVar(&opts.architecture, "architecture", "", "Project architecture: "+strings.Join(arch.IDList(), " or "))
	flags.BoolVar(&opts.virtualThreads, "virtual-threads", true, "Enable virtual threads (use --virtual-threads=false to disable)")
	flags.BoolVar(&opts.sample, "sample", true, "Include the sample Hello endpoint (use --sample=false to disable)")
	flags.StringVar(&opts.output, "output", ".", "Directory that will contain the new project")
	flags.BoolVar(&opts.force, "force", false, "Replace the target directory if it already exists")
	flags.BoolVar(&opts.noInteractive, "no-interactive", false, "Never prompt; fail when required values are missing")

	_ = cmd.RegisterFlagCompletionFunc("architecture", func(*cobra.Command, []string, string) ([]string, cobra.ShellCompDirective) {
		return arch.IDList(), cobra.ShellCompDirectiveNoFileComp
	})

	return cmd
}

func runNew(cmd *cobra.Command, args []string, opts newOptions) error {
	in := generator.Input{
		Group:          strings.TrimSpace(opts.group),
		Description:    strings.TrimSpace(opts.description),
		Architecture:   strings.TrimSpace(opts.architecture),
		VirtualThreads: opts.virtualThreads,
		Sample:         opts.sample,
		Output:         opts.output,
		Force:          opts.force,
	}
	if len(args) > 0 {
		in.Name = strings.TrimSpace(args[0])
	}

	interactive := opts.interactive
	if interactive == nil {
		interactive = isInteractive
	}
	if !opts.noInteractive && interactive(cmd.InOrStdin()) {
		if err := fillInteractively(cmd, &in); err != nil {
			return err
		}
	} else if missing := missingValues(in); len(missing) > 0 {
		return fmt.Errorf("missing required values: %s", strings.Join(missing, ", "))
	}

	result, err := generator.Generate(in)
	if err != nil {
		return err
	}

	fmt.Fprintf(cmd.OutOrStdout(), "Created %s\n", result.Dir)
	return nil
}

// missingValues lists the required inputs that were neither passed as flags
// nor as arguments.
func missingValues(in generator.Input) []string {
	var missing []string
	if in.Name == "" {
		missing = append(missing, "<name> argument")
	}
	if in.Group == "" {
		missing = append(missing, "--group")
	}
	if in.Architecture == "" {
		missing = append(missing, "--architecture")
	}
	return missing
}

// fillInteractively asks for every value the flags left empty. Flag values are
// validated but never re-prompted: a script that passes a bad --group should
// fail, not become an interactive session.
func fillInteractively(cmd *cobra.Command, in *generator.Input) error {
	prompter := &prompt.Prompt{In: cmd.InOrStdin(), Out: cmd.ErrOrStderr()}

	if in.Name != "" {
		if err := generator.ValidateName(in.Name); err != nil {
			return err
		}
	} else {
		name, err := askRequired(prompter, "name", "Service name (e.g. order-service)", generator.ValidateName)
		if err != nil {
			return err
		}
		in.Name = name
	}

	if in.Group != "" {
		if err := generator.ValidateGroup(in.Group); err != nil {
			return err
		}
	} else {
		group, err := askRequired(prompter, "group", "Group (e.g. com.acme)", generator.ValidateGroup)
		if err != nil {
			return err
		}
		in.Group = group
	}

	if in.Description == "" {
		answer, err := prompter.Ask("Description", in.Name+" service")
		if err != nil {
			return abortErr("description", err)
		}
		in.Description = answer
	}

	if in.Architecture != "" {
		if _, err := arch.Get(in.Architecture); err != nil {
			return err
		}
	} else {
		options := make([]prompt.Option, 0, len(arch.All()))
		for _, architecture := range arch.All() {
			options = append(options, prompt.Option{Value: architecture.ID(), Label: architecture.Name()})
		}
		answer, err := prompter.Choose("Architecture", options, "")
		if err != nil {
			return abortErr("architecture", err)
		}
		in.Architecture = answer
	}

	if !cmd.Flags().Changed("virtual-threads") {
		answer, err := prompter.Confirm("Enable virtual threads?", true)
		if err != nil {
			return abortErr("virtual threads", err)
		}
		in.VirtualThreads = answer
	}

	if !cmd.Flags().Changed("sample") {
		answer, err := prompter.Confirm("Include a sample Hello endpoint?", true)
		if err != nil {
			return abortErr("sample", err)
		}
		in.Sample = answer
	}

	return nil
}

// askRequired repeats the question until validate accepts a non-empty answer.
func askRequired(p *prompt.Prompt, field, label string, validate func(string) error) (string, error) {
	for {
		answer, err := p.Ask(label, "")
		if err != nil {
			return "", abortErr(field, err)
		}
		if answer == "" {
			fmt.Fprintf(p.Out, "  %s is required\n", field)
			continue
		}
		if err := validate(answer); err != nil {
			fmt.Fprintf(p.Out, "  %v\n", err)
			continue
		}
		return answer, nil
	}
}

// abortErr turns end-of-input into a clear message instead of looping forever.
func abortErr(field string, err error) error {
	if errors.Is(err, io.EOF) {
		return fmt.Errorf("aborted: no input for %s", field)
	}
	return err
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
