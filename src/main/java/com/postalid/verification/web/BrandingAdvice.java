package com.postalid.verification.web;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Tells every console page which brand marks are actually on the classpath.
 *
 * <p>The marks are supplied assets rather than something this repository can generate, so either
 * may legitimately be absent — on a fresh clone, or before someone has dropped the file in.
 * Checking once at startup lets the header fall back to a text wordmark instead of rendering a
 * broken image, which is a poor look on a page whose entire job is to inspire confidence in an
 * identity check.
 */
@ControllerAdvice(basePackages = "com.postalid.verification.web")
public class BrandingAdvice {

    private static final Logger log = LoggerFactory.getLogger(BrandingAdvice.class);

    static final String LOGO_PATH = "static/img/phlpost-logo.png";

    /**
     * The Bagong Pilipinas mark, which government websites carry alongside the agency's own
     * (Memorandum Circular No. 24, s. 2023). This is a web-sized derivative; the full-resolution
     * original is far too heavy to serve as a header image.
     */
    static final String GOV_MARK_PATH = "static/img/bagong-pilipinas-mark.png";

    private boolean logoAvailable;
    private boolean govMarkAvailable;

    @PostConstruct
    void detectLogo() {
        logoAvailable = new ClassPathResource(LOGO_PATH).exists();
        govMarkAvailable = new ClassPathResource(GOV_MARK_PATH).exists();
        if (logoAvailable) {
            log.info("PHLPost logo found; console will use it as the primary brand mark.");
        } else {
            log.info("No PHLPost logo at classpath:{} — the console falls back to a text wordmark. "
                    + "Add the file and rebuild to enable it.", LOGO_PATH);
        }
        if (!govMarkAvailable) {
            log.info("No Bagong Pilipinas mark at classpath:{}; the header shows the agency mark alone.",
                    GOV_MARK_PATH);
        }
    }

    @ModelAttribute("brandLogoAvailable")
    public boolean brandLogoAvailable() {
        return logoAvailable;
    }

    @ModelAttribute("govMarkAvailable")
    public boolean govMarkAvailable() {
        return govMarkAvailable;
    }
}
