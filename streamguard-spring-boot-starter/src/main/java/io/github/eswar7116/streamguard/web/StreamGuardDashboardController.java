package io.github.eswar7116.streamguard.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

/** Serves the dashboard at {@code <streamguard.web.base-path>/}. The page calls the API with relative URLs. */
@Controller
public class StreamGuardDashboardController {

    private static final Resource PAGE = new ClassPathResource("streamguard/dashboard.html");

    private final String basePath;

    public StreamGuardDashboardController(String basePath) {
        this.basePath = basePath;
    }

    // The trailing slash matters: the page's relative "api/..." URLs resolve against it
    @GetMapping("${streamguard.web.base-path:/streamguard}")
    public RedirectView redirectToSlash() {
        return new RedirectView(basePath + "/", true);
    }

    @GetMapping("${streamguard.web.base-path:/streamguard}/")
    public ResponseEntity<Resource> dashboard() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noCache())
                .body(PAGE);
    }
}
