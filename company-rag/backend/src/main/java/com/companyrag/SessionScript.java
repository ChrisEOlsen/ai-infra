package com.companyrag;

import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
class SessionScript {
  private final Access access;

  SessionScript(Access access) {
    this.access = access;
  }

  @GetMapping(value = "/chat/session.js", produces = "application/javascript")
  String script(HttpSession session, Authentication authentication) {
    Object key = session.getAttribute("chat-namespace");
    if (key == null) {
      key = UUID.randomUUID().toString();
      session.setAttribute("chat-namespace", key);
    }
    return "window.__RAG_SESSION__='"
        + key
        + "';window.__RAG_ADMIN__="
        + access.roles(authentication).contains("company-admin")
        + ";";
  }
}
