// Package arch maps logical template roles to Java packages for each supported
// project architecture. Templates declare a role; only this package decides
// where the role lands.
package arch

import "fmt"

// Role is a logical kind of generated file.
type Role string

const (
	RoleResponse   Role = "response"   // JSend envelope
	RoleException  Role = "exception"  // application exceptions
	RoleHandler    Role = "handler"    // @RestControllerAdvice
	RoleModel      Role = "model"      // domain value types
	RoleUseCase    Role = "usecase"    // inbound port (clean only)
	RoleService    Role = "service"    // implementation
	RoleDTO        Role = "dto"        // web response DTOs
	RoleController Role = "controller" // HTTP entry points
)

// Architecture is one named role-to-package mapping. Packages are suffixes
// relative to the project base package.
type Architecture struct {
	id       string
	name     string
	packages map[Role]string
}

// ID returns the identifier used in flags, e.g. "layered".
func (a Architecture) ID() string { return a.id }

// Name returns the label shown in prompts.
func (a Architecture) Name() string { return a.name }

// Package returns the package suffix for role. Roles an architecture does not
// define are an error, so a template can never silently land in the wrong place.
func (a Architecture) Package(role Role) (string, error) {
	suffix, ok := a.packages[role]
	if !ok {
		return "", fmt.Errorf("architecture %s does not define the %s role", a.id, role)
	}
	return suffix, nil
}

var registry = []Architecture{
	{
		id:   "layered",
		name: "layered — controller, service, dto, model",
		packages: map[Role]string{
			RoleResponse:   "common.response",
			RoleException:  "common.exception",
			RoleHandler:    "common.exception",
			RoleModel:      "model",
			RoleService:    "service",
			RoleDTO:        "dto",
			RoleController: "controller",
		},
	},
	{
		id:   "clean",
		name: "clean — domain, application, infrastructure",
		packages: map[Role]string{
			RoleResponse:   "infrastructure.web.response",
			RoleException:  "domain.exception",
			RoleHandler:    "infrastructure.web",
			RoleModel:      "domain.model",
			RoleUseCase:    "application.port.in",
			RoleService:    "application.service",
			RoleDTO:        "infrastructure.web.dto",
			RoleController: "infrastructure.web",
		},
	},
}

// All returns every architecture in display order.
func All() []Architecture {
	return append([]Architecture(nil), registry...)
}

// IDList returns the architecture IDs in display order, for completion.
func IDList() []string {
	ids := make([]string, len(registry))
	for i, architecture := range registry {
		ids[i] = architecture.id
	}
	return ids
}

// Get looks up an architecture by ID.
func Get(id string) (Architecture, error) {
	for _, architecture := range registry {
		if architecture.id == id {
			return architecture, nil
		}
	}
	return Architecture{}, fmt.Errorf("unknown architecture %q (choose one of: %v)", id, IDList())
}
