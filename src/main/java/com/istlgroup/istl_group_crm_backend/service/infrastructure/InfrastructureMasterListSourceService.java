package com.istlgroup.istl_group_crm_backend.service.infrastructure;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Discovers the latest "Harmonized Master List of Infrastructure sub-sectors"
 * circular on the official PPP India circulars &amp; orders page, without any
 * assumption about which year/version is current — a future "...2026" or
 * later revision is found the same way the 2025 one is.
 *
 * <p>The page (verified by fetching it directly) publishes a plain HTML
 * {@code <table id="datatabledummy">} listing every circular as
 * {@code Subject | Reference No. | Notification Date | Download Link}, which
 * is what this class parses with Jsoup — deterministic HTML parsing, no
 * AI/LLM interpretation of the page.
 */
@Component
public class InfrastructureMasterListSourceService {

    static final String SOURCE_PAGE_URL = "https://www.pppinindia.gov.in/circulars_and_orders";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final Pattern HML_SUBJECT = Pattern.compile("(?i)harmoni[sz]ed\\s+master\\s+list\\s+of\\s+infrastructure\\s+sub[-\\s]?sectors");
    private static final DateTimeFormatter NOTIFICATION_DATE_FORMAT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("d MMMM yyyy")
            .toFormatter(Locale.ENGLISH);

    public record DiscoveredDocument(String title, String referenceNo, LocalDate notificationDate,
                                      String sourcePageUrl, String pdfUrl) {
    }

    private final WebClient client;

    public InfrastructureMasterListSourceService(WebClient.Builder builder) {
        this.client = builder.clone()
                .baseUrl("https://www.pppinindia.gov.in")
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
    }

    public DiscoveredDocument discoverLatest() {
        String html = client.get()
                .uri("/circulars_and_orders")
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(20))
                .block();

        if (html == null || html.isBlank()) {
            throw new InfrastructureMasterListSourceException("Empty response from " + SOURCE_PAGE_URL);
        }

        Document doc = Jsoup.parse(html, SOURCE_PAGE_URL);
        Element table = doc.selectFirst("table#datatabledummy");
        if (table == null) {
            throw new InfrastructureMasterListSourceException(
                    "Circulars table not found on " + SOURCE_PAGE_URL + " (page structure may have changed)");
        }

        DiscoveredDocument latest = null;
        Elements rows = table.select("tbody tr");
        for (Element row : rows) {
            Elements cells = row.select("td");
            if (cells.size() < 4) continue;

            String subject = cells.get(0).text().trim();
            if (!HML_SUBJECT.matcher(subject).find()) continue;

            String referenceNo = cells.get(1).text().trim();
            LocalDate notificationDate = parseDate(cells.get(2).text().trim());
            Element link = cells.get(3).selectFirst("a[href]");
            if (link == null || notificationDate == null) continue;

            String pdfUrl = link.absUrl("href");
            if (pdfUrl.isBlank()) continue;

            if (latest == null || notificationDate.isAfter(latest.notificationDate())) {
                latest = new DiscoveredDocument(subject, referenceNo, notificationDate, SOURCE_PAGE_URL, pdfUrl);
            }
        }

        if (latest == null) {
            throw new InfrastructureMasterListSourceException(
                    "No 'Harmonized Master List of Infrastructure sub-sectors' entry found on " + SOURCE_PAGE_URL);
        }
        return latest;
    }

    public byte[] downloadPdf(String pdfUrl) {
        byte[] bytes = WebClient.builder()
                .defaultHeader("User-Agent", USER_AGENT)
                .build()
                .get()
                .uri(pdfUrl)
                .accept(MediaType.APPLICATION_PDF, MediaType.ALL)
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(Duration.ofSeconds(60))
                .block();

        if (bytes == null || bytes.length == 0) {
            throw new InfrastructureMasterListSourceException("Empty PDF downloaded from " + pdfUrl);
        }
        return bytes;
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text.replaceAll("\\s+", " ").trim(), NOTIFICATION_DATE_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }

    public static class InfrastructureMasterListSourceException extends RuntimeException {
        public InfrastructureMasterListSourceException(String message) {
            super(message);
        }
    }
}
