package prompt

import (
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
