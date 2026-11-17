package prompt

import (
	"errors"
	"io"
	"strings"
	"testing"
)

func TestAskUsesDefaultOnEmptyAnswer(t *testing.T) {
	var out strings.Builder
	p := &Prompt{In: strings.NewReader("\n"), Out: &out}

	answer, err := p.Ask("Description", "demo service")
	if err != nil {
		t.Fatalf("Ask returned error: %v", err)
	}
	if answer != "demo service" {
		t.Errorf("answer = %q, want the default", answer)
	}
	if !strings.Contains(out.String(), "[demo service]") {
		t.Errorf("prompt %q does not show the default", out.String())
	}
}

func TestAskReturnsEOFOnEmptyInput(t *testing.T) {
	p := &Prompt{In: strings.NewReader(""), Out: new(strings.Builder)}

	if _, err := p.Ask("Service name", ""); !errors.Is(err, io.EOF) {
		t.Errorf("Ask error = %v, want io.EOF", err)
	}
}

func TestAskReadsFinalLineWithoutNewline(t *testing.T) {
	p := &Prompt{In: strings.NewReader("com.acme"), Out: new(strings.Builder)}

	answer, err := p.Ask("Group", "")
	if err != nil {
		t.Fatalf("Ask returned error: %v", err)
	}
	if answer != "com.acme" {
		t.Errorf("answer = %q, want %q", answer, "com.acme")
	}
}

func TestAskReadsMultipleAnswers(t *testing.T) {
	p := &Prompt{In: strings.NewReader("com.acme\nOrders\n"), Out: new(strings.Builder)}

	first, err := p.Ask("Group", "")
	if err != nil {
		t.Fatalf("first Ask returned error: %v", err)
	}
	second, err := p.Ask("Description", "demo service")
	if err != nil {
		t.Fatalf("second Ask returned error: %v", err)
	}

	if first != "com.acme" || second != "Orders" {
		t.Errorf("answers = %q, %q, want %q, %q", first, second, "com.acme", "Orders")
	}
}
