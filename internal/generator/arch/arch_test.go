package arch

import (
	"strings"
	"testing"
)

func TestPackagePerArchitecture(t *testing.T) {
	tests := []struct {
		architecture string
		role         Role
		want         string
	}{
		{"layered", RoleController, "controller"},
		{"layered", RoleService, "service"},
		{"layered", RoleModel, "model"},
		{"layered", RoleResponse, "common.response"},
		{"layered", RoleHandler, "common.exception"},
		{"layered", RoleLogging, "common.logging"},
		{"layered", RoleRestClient, "common.restclient"},
		{"layered", RoleClient, "client"},
		{"layered", RoleSecurity, "common.security"},
		{"clean", RoleController, "infrastructure.web"},
		{"clean", RoleUseCase, "application.port.in"},
		{"clean", RoleService, "application.service"},
		{"clean", RoleModel, "domain.model"},
		{"clean", RoleResponse, "infrastructure.web.response"},
		{"clean", RoleException, "domain.exception"},
		{"clean", RoleLogging, "infrastructure.logging"},
		{"clean", RoleRestClient, "infrastructure.restclient"},
		{"clean", RoleClient, "infrastructure.client"},
		{"clean", RoleOutPort, "application.port.out"},
		{"clean", RoleSecurity, "infrastructure.security"},
	}

	for _, test := range tests {
		t.Run(test.architecture+"/"+string(test.role), func(t *testing.T) {
			architecture, err := Get(test.architecture)
			if err != nil {
				t.Fatalf("Get(%q): %v", test.architecture, err)
			}
			got, err := architecture.Package(test.role)
			if err != nil {
				t.Fatalf("Package(%q): %v", test.role, err)
			}
			if got != test.want {
				t.Errorf("Package(%q) = %q, want %q", test.role, got, test.want)
			}
		})
	}
}

func TestLayeredHasNoUseCase(t *testing.T) {
	layered, err := Get("layered")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := layered.Package(RoleUseCase); err == nil {
		t.Fatal("layered architecture defines a use case package, want error")
	}
}

func TestGetRejectsUnknownArchitecture(t *testing.T) {
	_, err := Get("hexagonal")
	if err == nil {
		t.Fatal("Get accepted an unknown architecture")
	}
	if !strings.Contains(err.Error(), "layered") || !strings.Contains(err.Error(), "clean") {
		t.Errorf("error = %q, want the available options", err)
	}
}
