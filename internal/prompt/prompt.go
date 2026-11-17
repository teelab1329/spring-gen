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

// Ask prints the label with an optional default and returns the trimmed
// answer, falling back to def when the answer is empty.
func (p *Prompt) Ask(label, def string) (string, error) {
	if p.In == nil || p.Out == nil {
		return "", errors.New("prompt: reader and writer are required")
	}
	if p.reader == nil {
		p.reader = bufio.NewReader(p.In)
	}

	if def != "" {
		fmt.Fprintf(p.Out, "%s [%s]: ", label, def)
	} else {
		fmt.Fprintf(p.Out, "%s: ", label)
	}

	line, err := p.reader.ReadString('\n')
	if err != nil && !errors.Is(err, io.EOF) {
		return "", err
	}

	answer := strings.TrimSpace(line)
	if answer == "" {
		return def, nil
	}
	return answer, nil
}
