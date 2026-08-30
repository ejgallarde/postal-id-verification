package com.postalid.verification.web;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Tells every console page whether the PHLPost logo is actually on the classpath.
 *
 * <p>The logo is a supplied asset rather than something this repository can generate, so it may
 * legitimately be absent — on a fresh clone, or before someone has dropped the file in. Checking
 * once at startup lets the header fall back to a text wordmark instead of rendering a broken image,
 * which is a poor look on a page whose entire job is to inspire confidence in an identity check.
 */
@ControllerAdvice(basePackages = "com.postalid.verification.web")
public class BrandingAdvice {

    private static final Logger log = LoggerFactory.getLogger(BrandingAdvice.class);

    static final String LOGO_PATH = "static/img/phlpost-logo.png";

    private boolean logoAvailable;

    @PostConstruct
    void detectLogo() {
        logoAvailable = new ClassPathResource(LOGO_PATH).exists();
        if (logoAvailable) {
            log.info("PHLPost logo found; console will use it as the primary brand mark.");
        } else {
            log.info("No PHLPost logo at classpath:{} — the console falls back to a text wordmark. "
                    + "Add the file and rebuild to enable it.", LOGO_PATH);
        }
    }

    @ModelAttribute("brandLogoAvailable")
    public boolean brandLogoAvailable() {
        return logoAvailable;
    }
}
