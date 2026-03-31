package com.lamastudio.backend.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Root controller to redirect "/" to Swagger UI documentation.
 */
@Controller
public class RootController {

    /**
     * Redirect root path to Swagger UI
     * @return redirect to swagger-ui.html
     */
    @GetMapping("/")
    public String redirectToSwagger() {
        return "redirect:/swagger-ui.html";
    }
}
