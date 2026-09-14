package com.justjava.humanresource.aau;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Component("coreAuthenticationManager")
public class AuthenticationManager {

    public Object get(String fieldName){
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        DefaultOidcUser defaultOidcUser = (DefaultOidcUser) authentication.getPrincipal();
        return defaultOidcUser.getClaims().get(fieldName);
    }

    private Set<String> normalizedGroups() {
        Object groupsClaim = this.get("groups");
        if (!(groupsClaim instanceof Collection<?> groups)) {
            return Set.of();
        }
        return groups.stream()
                .map(String::valueOf)
                .map(this::normalizeGroup)
                .collect(Collectors.toSet());
    }

    private String normalizeGroup(String group) {
        String normalized = group.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    public boolean isEmployee() {
        return normalizedGroups().contains("employees");
    }

    public boolean isAssetManager() {
        return normalizedGroups().contains("assetmanager");
    }

    public boolean isAuditor() {
        return normalizedGroups().contains("auditor");
    }

    public boolean isDepartmentHead() {
        return normalizedGroups().contains("departmenthead");
    }

    public Object getAllAttributes() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        DefaultOidcUser defaultOidcUser = (DefaultOidcUser) authentication.getPrincipal();
        return defaultOidcUser.getClaims();
    }
}