package com.companyrag;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Configuration
class Security {
  @Bean
  org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      registrations(
          @org.springframework.beans.factory.annotation.Value("${OIDC_ISSUER}") String issuer,
          @org.springframework.beans.factory.annotation.Value(
                  "${OIDC_INTERNAL:http://keycloak:8080/auth/realms/company}")
              String internal,
          @org.springframework.beans.factory.annotation.Value("${OIDC_CLIENT_SECRET}")
              String secret) {
    String protocol = "/protocol/openid-connect";
    var client =
        org.springframework.security.oauth2.client.registration.ClientRegistration
            .withRegistrationId("keycloak")
            .clientId("company-rag")
            .clientSecret(secret)
            .scope("openid", "profile", "email", "roles")
            .authorizationGrantType(
                org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .issuerUri(issuer)
            .authorizationUri(issuer + protocol + "/auth")
            .tokenUri(internal + protocol + "/token")
            .providerConfigurationMetadata(
                Map.of("end_session_endpoint", issuer + protocol + "/logout"))
            .jwkSetUri(internal + protocol + "/certs")
            .userInfoUri(internal + protocol + "/userinfo")
            .userNameAttributeName("sub")
            .build();
    return new org.springframework.security.oauth2.client.registration
        .InMemoryClientRegistrationRepository(client);
  }

  @Bean
  @Order(1)
  SecurityFilterChain management(HttpSecurity h) throws Exception {
    return h.securityMatcher(r -> r.getLocalPort() == 9091)
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .build();
  }

  @Bean
  SecurityFilterChain applicationSecurity(
      HttpSecurity h,
      OAuth2AuthorizedClientService clients,
      Access access,
      org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
          registrations)
      throws Exception {
    var logoutHandler =
        new org.springframework.security.oauth2.client.oidc.web.logout
            .OidcClientInitiatedLogoutSuccessHandler(registrations);
    logoutHandler.setPostLogoutRedirectUri("{baseUrl}/");
    var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
    var handler = new CsrfTokenRequestAttributeHandler();
    return h.authorizeHttpRequests(
            a ->
                a.requestMatchers("/error", "/oauth2/**", "/login/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .requestCache(
            c ->
                c.requestCache(
                    new org.springframework.security.web.savedrequest.NullRequestCache()))
        .oauth2Login(
            o ->
                o.successHandler(
                    (r, s, a) ->
                        s.sendRedirect(
                            access.roles(a).contains("company-admin") ? "/admin/" : "/chat/")))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (r, s, x) -> {
                      String path = r.getRequestURI();
                      if (path.startsWith("/api/")
                          || path.startsWith("/v1/")
                          || path.startsWith("/chat/v1/")) s.sendError(401);
                      else s.sendRedirect("/oauth2/authorization/keycloak");
                    }))
        .csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(handler))
        .logout(
            l ->
                l.logoutSuccessHandler(logoutHandler)
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID", "SESSION", "XSRF-TOKEN"))
        .addFilterAfter(
            new OncePerRequestFilter() {
              protected void doFilterInternal(
                  HttpServletRequest r, HttpServletResponse s, FilterChain chain)
                  throws ServletException, IOException {
                var a =
                    org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
                if (a != null && a.getPrincipal() instanceof OidcUser) {
                  var client = clients.loadAuthorizedClient("keycloak", a.getName());
                  if (client == null
                      || client.getAccessToken().getExpiresAt() == null
                      || client.getAccessToken().getExpiresAt().isBefore(Instant.now())) {
                    if (r.getSession(false) != null) r.getSession(false).invalidate();
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                    s.sendError(401);
                    return;
                  }
                }
                var token = (CsrfToken) r.getAttribute(CsrfToken.class.getName());
                if (token != null) token.getToken();
                chain.doFilter(r, s);
              }
            },
            BasicAuthenticationFilter.class)
        .build();
  }
}

@Component
class Access {
  Set<String> roles(Authentication a) {
    if (a == null || !(a.getPrincipal() instanceof OidcUser u))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    Object realm = u.getClaims().get("realm_access");
    if (realm instanceof Map<?, ?> m && m.get("roles") instanceof Collection<?> c) {
      var result = new HashSet<String>();
      c.forEach(x -> result.add(x.toString()));
      return result;
    }
    return Set.of();
  }

  void admin(Authentication a) {
    if (!roles(a).contains("company-admin"))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  String subject(Authentication a) {
    return ((OidcUser) a.getPrincipal()).getSubject();
  }
}
