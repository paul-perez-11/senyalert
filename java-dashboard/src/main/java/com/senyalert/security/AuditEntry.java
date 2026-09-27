package com.senyalert.security;

public record AuditEntry(long id, String timestamp, String actor, String role, String action,
                         String target, String details, String previousHash, String entryHash) { }
