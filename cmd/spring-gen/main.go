package main

import (
	"fmt"
	"os"

	"github.com/teelab1329/spring-gen/internal/cli"
)

func main() {
	if err := cli.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, "spring-gen:", err)
		os.Exit(1)
	}
}
