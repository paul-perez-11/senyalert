package com.senyalert.security;

import java.util.Set;

/** Login identity. The service rechecks revocation and current grants for each action. */
public record Session(long userId, String username, Role role, Set<Permission> permissions,
                      long authVersion, String sessionId) {
    public Session { permissions = Set.copyOf(permissions); }
    public boolean can(Permission permission) { return role != Role.USER || permissions.contains(permission); }
    public boolean isSuperadmin() { return role == Role.SUPERADMIN; }
}
