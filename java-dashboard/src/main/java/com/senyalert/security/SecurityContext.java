package com.senyalert.security;

import java.util.Objects;

/** Pass one context from successful login to controllers, tools, and remote support. */
public final class SecurityContext {
    private final SecurityService service;
    private final Session session;
    public SecurityContext(SecurityService service, Session session) {
        this.service = Objects.requireNonNull(service);
        this.session = Objects.requireNonNull(session);
    }
    public SecurityService service() { return service; }
    public Session session() { return session; }
    public boolean can(Permission permission) { return service.can(session, permission); }
    public void require(Permission permission) { service.require(session, permission); }
    public void audit(String action, String target, String details) { service.audit(session, action, target, details); }
    public void beginAction(Permission permission, String action, String target, String details) {
        require(permission);
        audit(action + "_REQUESTED", target, details);
    }
    public void logout() { service.logout(session); }
}
