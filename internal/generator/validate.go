package generator

import (
	"fmt"
	"regexp"
	"strings"
)

var (
	namePattern    = regexp.MustCompile(`^[a-z][a-z0-9]*(-[a-z0-9]+)*$`)
	segmentPattern = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)
)

// javaKeywords cannot appear in a package name.
var javaKeywords = map[string]struct{}{
	"abstract": {}, "assert": {}, "boolean": {}, "break": {}, "byte": {},
	"case": {}, "catch": {}, "char": {}, "class": {}, "const": {},
	"continue": {}, "default": {}, "do": {}, "double": {}, "else": {},
	"enum": {}, "extends": {}, "final": {}, "finally": {}, "float": {},
	"for": {}, "goto": {}, "if": {}, "implements": {}, "import": {},
	"instanceof": {}, "int": {}, "interface": {}, "long": {}, "native": {},
	"new": {}, "package": {}, "private": {}, "protected": {}, "public": {},
	"return": {}, "short": {}, "static": {}, "strictfp": {}, "super": {},
	"switch": {}, "synchronized": {}, "this": {}, "throw": {}, "throws": {},
	"transient": {}, "try": {}, "void": {}, "volatile": {}, "while": {},
	"true": {}, "false": {}, "null": {}, "var": {}, "record": {},
	"sealed": {}, "permits": {}, "yield": {},
}

func validateName(name string) error {
	if name == "" {
		return fmt.Errorf("name is required")
	}
	if !namePattern.MatchString(name) {
		return fmt.Errorf("invalid name %q: use lowercase letters, digits and single dashes, starting with a letter (e.g. order-service)", name)
	}
	return nil
}

func validateGroup(group string) error {
	if group == "" {
		return fmt.Errorf("group is required (e.g. com.acme)")
	}

	segments := strings.Split(group, ".")
	if len(segments) < 2 {
		return fmt.Errorf("invalid group %q: expected at least two dot-separated segments (e.g. com.acme)", group)
	}
	for _, segment := range segments {
		if !segmentPattern.MatchString(segment) {
			return fmt.Errorf("invalid group %q: segment %q is not a Java identifier", group, segment)
		}
		if _, keyword := javaKeywords[segment]; keyword {
			return fmt.Errorf("invalid group %q: segment %q is a Java keyword", group, segment)
		}
	}
	return nil
}

func validatePackage(pkg string) error {
	for _, segment := range strings.Split(pkg, ".") {
		if _, keyword := javaKeywords[segment]; keyword {
			return fmt.Errorf("derived package %q contains the Java keyword %q; choose another name or --group", pkg, segment)
		}
	}
	return nil
}

// Pascal converts a kebab-case name to PascalCase: order-service -> OrderService.
func Pascal(name string) string {
	parts := strings.Split(name, "-")
	var b strings.Builder
	for _, part := range parts {
		if part == "" {
			continue
		}
		b.WriteString(strings.ToUpper(part[:1]))
		b.WriteString(part[1:])
	}
	return b.String()
}
