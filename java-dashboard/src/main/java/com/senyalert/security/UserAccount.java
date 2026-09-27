package com.senyalert.security;

import java.util.Set;

/** Account metadata deliberately omits the password verifier. */
public record UserAccount(long id, String username, Role role, Set<Permission> permissions,
                          boolean enabled, String createdAt, String updatedAt) {
    public UserAccount { permissions = Set.copyOf(permissions); }
}
