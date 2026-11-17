// Package prompt provides minimal line prompts for the interactive parts of
// the CLI. Prompts are written to the configured writer, never stdout, so
// machine-readable output stays clean.
package prompt

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"strings"
)

// Prompt reads answers from In and writes questions to Out.
type Prompt struct {
	In  io.Reader
	Out io.Writer

	reader *bufio.Reader
}

// Option is one choice in Choose.
type Option struct {
	Value string
	Label string
}

// Ask prints the label with an optional default and returns the trimmed
// answer, falling back to def when the answer is empty. End of input is
// reported as io.EOF so callers can abort instead of looping.
func (p *Prompt) Ask(label, def string) (string, error) {
	if def != "" {
		fmt.Fprintf(p.Out, "%s [%s]: ", label, def)
	} else {
		fmt.Fprintf(p.Out, "%s: ", label)
	}

	answer, err := p.readLine()
	if err != nil {
		return "", err
	}
	if answer == "" {
		return def, nil
	}
	return answer, nil
}

// Choose renders a numbered list and accepts either the number or the value.
// An empty answer selects def; without a default the question is repeated.
func (p *Prompt) Choose(label string, options []Option, def string) (string, error) {
	if len(options) == 0 {
		return "", errors.New("prompt: Choose needs at least one option")
	}
	if def != "" && !hasOption(options, def) {
		return "", fmt.Errorf("prompt: default %q is not one of the options", def)
	}

	for {
		fmt.Fprintf(p.Out, "%s:\n", label)
		for i, option := range options {
			fmt.Fprintf(p.Out, "  %d) %s\n", i+1, option.Label)
		}
		if def != "" {
			fmt.Fprintf(p.Out, "Choice [%s]: ", def)
		} else {
			fmt.Fprint(p.Out, "Choice: ")
		}

		answer, err := p.readLine()
		if err != nil {
			return "", err
		}

		if answer == "" {
			if def != "" {
				return def, nil
			}
			fmt.Fprint(p.Out, "  a choice is required\n")
			continue
		}
		if value, ok := matchOption(options, answer); ok {
			return value, nil
		}
		fmt.Fprintf(p.Out, "  choose 1-%d or one of the names\n", len(options))
	}
}

// Confirm asks a yes/no question, accepting y, yes, n, no, or an empty answer
// for the default.
func (p *Prompt) Confirm(label string, def bool) (bool, error) {
	hint := "[y/N]"
	if def {
		hint = "[Y/n]"
	}

	for {
		fmt.Fprintf(p.Out, "%s %s: ", label, hint)

		answer, err := p.readLine()
		if err != nil {
			return false, err
		}

		switch strings.ToLower(answer) {
		case "":
			return def, nil
		case "y", "yes":
			return true, nil
		case "n", "no":
			return false, nil
		}
		fmt.Fprint(p.Out, "  answer yes or no\n")
	}
}

// readLine returns one trimmed line, or io.EOF when the input ended before a
// newline or any content.
func (p *Prompt) readLine() (string, error) {
	if p.In == nil || p.Out == nil {
		return "", errors.New("prompt: reader and writer are required")
	}
	if p.reader == nil {
		p.reader = bufio.NewReader(p.In)
	}

	line, err := p.reader.ReadString('\n')
	if err != nil && !errors.Is(err, io.EOF) {
		return "", err
	}

	answer := strings.TrimSpace(line)
	if answer == "" && errors.Is(err, io.EOF) {
		return "", io.EOF
	}
	return answer, nil
}

func hasOption(options []Option, value string) bool {
	for _, option := range options {
		if option.Value == value {
			return true
		}
	}
	return false
}

func matchOption(options []Option, answer string) (string, bool) {
	if index := parseIndex(answer, len(options)); index >= 0 {
		return options[index].Value, true
	}
	for _, option := range options {
		if strings.EqualFold(option.Value, answer) || strings.EqualFold(option.Label, answer) {
			return option.Value, true
		}
	}
	return "", false
}

func parseIndex(answer string, count int) int {
	if answer == "" {
		return -1
	}
	index := 0
	for _, r := range answer {
		if r < '0' || r > '9' {
			return -1
		}
		index = index*10 + int(r-'0')
	}
	if index < 1 || index > count {
		return -1
	}
	return index - 1
}
