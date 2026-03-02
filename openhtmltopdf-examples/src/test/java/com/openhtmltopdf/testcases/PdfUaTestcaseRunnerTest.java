package com.openhtmltopdf.testcases;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDObjectReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.testlistener.PrintingRunner;
import com.openhtmltopdf.visualtest.TestSupport;

import static org.junit.Assert.*;

/**
 * Tests for PDF accessiblility (PDF/UA, WCAG, Section 508).
 * These tests only test that the PDF/UA implementation doesn't crash.
 * They additionally need confirming manually with the PDF Accessibility Checker (PAC).
 * This free (but closed source) Windows software is available at:
 *   https://www.access-for-all.ch/en/pdf-lab/pdf-accessibility-checker-pac.html
 *
 *  If you don't want to confirm all, please at least confirm the all-in-one testcase!
 */
@RunWith(PrintingRunner.class)
public class PdfUaTestcaseRunnerTest {
    @BeforeClass
    public static void configure() throws IOException {
        Files.createDirectories(Paths.get("./target/test/manual/pdfua-test-cases/"));

        TestSupport.makeFontFiles();
        TestSupport.quietLogs();
    }

    private static void run(String testCase) throws IOException {
        byte[] htmlBytes = null;
        try (InputStream is = PdfUaTestcaseRunnerTest.class.getResourceAsStream("/testcases/pdfua/" + testCase + ".html")) {
            htmlBytes = IOUtils.toByteArray(is);
        }
        String html = new String(htmlBytes, StandardCharsets.UTF_8);

        try (FileOutputStream os = new FileOutputStream("./target/test/manual/pdfua-test-cases/" + testCase + ".pdf")) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.testMode(true);
            builder.usePdfUaAccessibility(true);
            builder.useFont(new File("target/test/visual-tests/Karla-Bold.ttf"), "TestFont");
            builder.withHtmlContent(html, PdfUaTestcaseRunnerTest.class.getResource("/testcases/pdfua/").toString());
            builder.toStream(os);
            builder.run();
        }
    }

    /** Generates a PDF/UA document in memory and returns the bytes. */
    private static byte[] renderToBytes(String testCase) throws IOException {
        byte[] htmlBytes;
        try (InputStream is = PdfUaTestcaseRunnerTest.class.getResourceAsStream("/testcases/pdfua/" + testCase + ".html")) {
            htmlBytes = IOUtils.toByteArray(is);
        }
        String html = new String(htmlBytes, StandardCharsets.UTF_8);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.testMode(true);
        builder.usePdfUaAccessibility(true);
        builder.useFont(new File("target/test/visual-tests/Karla-Bold.ttf"), "TestFont");
        builder.withHtmlContent(html, PdfUaTestcaseRunnerTest.class.getResource("/testcases/pdfua/").toString());
        builder.toStream(baos);
        builder.run();
        return baos.toByteArray();
    }

    /**
     * Recursively dumps the PDF logical structure tree as indented text.
     * Useful for debugging tagged PDF structure.
     */
    static String dumpStructureTree(byte[] pdfBytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
            if (root == null) {
                return "(no structure tree)";
            }
            List<Object> kids = root.getKids();
            if (kids != null) {
                for (Object kid : kids) {
                    dumpItem(kid, sb, 0);
                }
            }
        }
        return sb.toString();
    }

    private static void dumpItem(Object item, StringBuilder sb, int depth) {
        String pad = "  ".repeat(depth);
        if (item instanceof PDStructureElement) {
            PDStructureElement elem = (PDStructureElement) item;
            sb.append(pad).append("<").append(elem.getStructureType()).append(">\n");
            List<Object> kids = elem.getKids();
            if (kids != null) {
                for (Object kid : kids) {
                    dumpItem(kid, sb, depth + 1);
                }
            }
        } else if (item instanceof PDObjectReference) {
            sb.append(pad).append("[ObjRef/Annotation]\n");
        } else if (item instanceof COSDictionary) {
            COSDictionary dict = (COSDictionary) item;
            COSName type = dict.getCOSName(COSName.TYPE);
            if (dict.containsKey(COSName.MCID)) {
                sb.append(pad).append("[MCID=").append(dict.getInt(COSName.MCID)).append("]\n");
            } else if (type != null && "MCR".equals(type.getName())) {
                sb.append(pad).append("[MCR MCID=").append(dict.getInt(COSName.MCID)).append("]\n");
            } else {
                sb.append(pad).append("[Dict type=").append(type).append("]\n");
            }
        } else {
            sb.append(pad).append("[").append(item == null ? "null" : item.getClass().getSimpleName()).append("]\n");
        }
    }

    /**
     * Returns a description of all P/H* elements that have a Link somewhere below
     * them, showing whether a Link appears as a DIRECT child of P *before* any
     * content item (Integer / COSDictionary).  A Span child wrapping the Link is
     * acceptable – only a bare Link appearing before content in P's own kids list
     * is considered wrong.
     *
     * Returns null if no P/H* with a Link descendant is found.
     */
    private static String getPWithLinkChildOrder(byte[] pdfBytes) throws IOException {
        StringBuilder result = new StringBuilder();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
            if (root == null) return null;
            List<Object> kids = root.getKids();
            if (kids == null) return null;
            for (Object kid : kids) {
                collectPWithLink(kid, result);
            }
        }
        return result.length() == 0 ? null : result.toString();
    }

    /** Returns true if this item is or contains a Link structure element. */
    private static boolean containsLink(Object item) {
        if (!(item instanceof PDStructureElement)) return false;
        PDStructureElement elem = (PDStructureElement) item;
        if ("Link".equals(elem.getStructureType())) return true;
        List<Object> kids = elem.getKids();
        if (kids == null) return false;
        for (Object kid : kids) {
            if (containsLink(kid)) return true;
        }
        return false;
    }

    /**
     * For a P/H* element that contains a Link somewhere below it, check whether
     * a bare Link element is the FIRST item in P's own kids list (before any content).
     * Returns "WRONG: Link before content" if so, "OK" otherwise.
     */
    private static String checkPLinkOrder(PDStructureElement pElem) {
        List<Object> kids = pElem.getKids();
        if (kids == null) return "OK (no kids)";
        for (Object kid : kids) {
            // Content items (Integer, COSDictionary) = text content in P
            if (!(kid instanceof PDStructureElement)) {
                return "OK"; // content found before any direct Link
            }
            PDStructureElement childElem = (PDStructureElement) kid;
            if ("Link".equals(childElem.getStructureType())) {
                // A bare Link appears before any content item in P's kids – wrong!
                return "WRONG: Link is first direct child of P before any content";
            }
            // Span (or other inline wrapper) as first child is acceptable –
            // it may wrap before-text + Link + after-text together in correct order.
            return "OK"; // first child is not a bare Link
        }
        return "OK (empty)";
    }

    private static void collectPWithLink(Object item, StringBuilder result) {
        if (!(item instanceof PDStructureElement)) return;
        PDStructureElement elem = (PDStructureElement) item;
        List<Object> kids = elem.getKids();
        if (kids == null) return;

        String type = elem.getStructureType();
        if ("P".equals(type) || (type != null && type.matches("H[1-6]?"))) {
            boolean hasLink = kids.stream().anyMatch(PdfUaTestcaseRunnerTest::containsLink);
            if (hasLink) {
                String order = checkPLinkOrder(elem);
                result.append("[").append(type).append("]: ").append(order).append("\n");
            }
        }

        for (Object kid : kids) {
            collectPWithLink(kid, result);
        }
    }

    @Test
    public void testAllInOne() throws Exception {
        run("all-in-one");
    }

    @Test
    public void testSimplest() throws Exception {
        run("simplest");
    }
    
    @Test
    public void testSimple() throws Exception {
        run("simple");
    }
    
    @Test
    public void testLayersZIndex() throws Exception {
        run("layers-z-index");
    }
    
    @Test
    public void testTextOverTwoPages() throws Exception {
        run("text-over-two-pages");
    }
    
    @Test
    public void testImage() throws Exception {
        run("image");
    }
    
    @Test
    public void testImageOverTwoPages() throws Exception {
        run("image-over-two-pages");
    }
    
    @Test
    public void testRunning() throws Exception {
        run("running");
    }
    
    @Test
    public void testLists() throws Exception {
        run("lists");
    }
    
    @Test
    public void testBookmarks() throws Exception {
        run("bookmarks");
    }
    
    @Test
    public void testTables() throws Exception {
        run("tables");
    }
    
    @Test
    public void testOrdering() throws Exception {
        run("ordering");
    }
    
    @Test
    public void testLinks() throws Exception {
        run("links");
    }

    @Test
    public void testListMarkers() throws Exception {
        run("list-markers");
    }

    // -----------------------------------------------------------------------
    // Helpers for list marker structure assertions
    // -----------------------------------------------------------------------

    /**
     * Walks the structure tree and returns two counts:
     * [0] = number of LI elements that have a Lbl direct child,
     * [1] = number of LI elements that have NO Lbl direct child.
     */
    private static int[] countLiWithAndWithoutLbl(byte[] pdfBytes) throws IOException {
        int[] counts = new int[2];
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
            if (root == null) return counts;
            List<Object> kids = root.getKids();
            if (kids != null) {
                for (Object kid : kids) {
                    countLiItems(kid, counts);
                }
            }
        }
        return counts;
    }

    private static void countLiItems(Object item, int[] counts) {
        if (!(item instanceof PDStructureElement)) return;
        PDStructureElement elem = (PDStructureElement) item;

        if ("LI".equals(elem.getStructureType())) {
            boolean hasLbl = false;
            List<Object> kids = elem.getKids();
            if (kids != null) {
                for (Object kid : kids) {
                    if (kid instanceof PDStructureElement
                            && "Lbl".equals(((PDStructureElement) kid).getStructureType())) {
                        hasLbl = true;
                        break;
                    }
                }
            }
            if (hasLbl) counts[0]++; else counts[1]++;
            // recurse into LBody (nested lists are inside LBody)
            if (kids != null) {
                for (Object kid : kids) {
                    countLiItems(kid, counts);
                }
            }
            return;
        }

        List<Object> kids = elem.getKids();
        if (kids != null) {
            for (Object kid : kids) {
                countLiItems(kid, counts);
            }
        }
    }

    // -----------------------------------------------------------------------

    /**
     * Verifies that inside paragraphs containing links, the PDF structure order has
     * text content BEFORE the Link element (not after).
     *
     * Uses all-in-one.html which has a standard page size so links do not wrap
     * across pages (which would cause duplicate Link elements – a separate issue).
     *
     * Run with -Dtest.dumpStructure=true to print the full structure tree.
     */
    @Test
    public void testLinkStructureOrder() throws Exception {
        byte[] pdf = renderToBytes("all-in-one");

        if (Boolean.getBoolean("test.dumpStructure")) {
            System.out.println("=== PDF Structure Tree (all-in-one.html) ===");
            System.out.println(dumpStructureTree(pdf));
        }

        String childOrders = getPWithLinkChildOrder(pdf);
        assertNotNull("No <P>/<H*> with a <Link> descendant found in structure tree", childOrders);

        System.out.println("P/H-with-link child orders:\n" + childOrders);

        // For every paragraph/heading that has a link, a bare Link element must NOT
        // appear as the first direct child of P before any content items.
        // A Span (or other wrapper) as the first child is acceptable – it may contain
        // the before-text, Link and after-text all in correct reading order.
        for (String line : childOrders.split("\n")) {
            if (line.trim().isEmpty()) continue;
            assertFalse(
                "Link appears before content in paragraph/heading structure. Found: " + line,
                line.contains("WRONG:")
            );
        }
    }

    /**
     * Verifies PDF/UA structure for all list marker types in list-markers.html.
     *
     * <p>Expected structure per list:
     * <ul>
     *   <li>disc / circle / square – glyph markers → Lbl present (with ActualText in content dict)</li>
     *   <li>custom string '* ' – TextMarker → Lbl present</li>
     *   <li>list-style: none – no marker data → NO Lbl</li>
     *   <li>list-style-image – ImageMarker → NO Lbl (decorative, skipped as artifact)</li>
     *   <li>ol decimal / upper-alpha / lower-roman – TextMarker → Lbl present</li>
     * </ul>
     *
     * <p>list-markers.html contains:
     * <ul>
     *   <li>ul disc ×2, circle ×2, square ×2, custom ×2 → 8 LI with Lbl</li>
     *   <li>ol decimal ×3, upper-alpha ×3, lower-roman ×3 → 9 LI with Lbl</li>
     *   <li>ul none ×2, ul image ×2 → 4 LI without Lbl</li>
     * </ul>
     *
     * Run with {@code -Dtest.dumpStructure=true} to print the full structure tree.
     */
    @Test
    public void testListMarkerStructure() throws Exception {
        byte[] pdf = renderToBytes("list-markers");

        if (Boolean.getBoolean("test.dumpStructure")) {
            System.out.println("=== PDF Structure Tree (list-markers.html) ===");
            System.out.println(dumpStructureTree(pdf));
        }

        int[] counts = countLiWithAndWithoutLbl(pdf);
        int liWithLbl    = counts[0];
        int liWithoutLbl = counts[1];

        System.out.printf("list-markers structure: LI with Lbl=%d, LI without Lbl=%d%n",
                liWithLbl, liWithoutLbl);

        // disc(2) + circle(2) + square(2) + custom-string(2)
        //   + ol-decimal(3) + ol-upper-alpha(3) + ol-lower-roman(3) = 17
        assertEquals("LI elements with Lbl marker", 17, liWithLbl);

        // list-style:none(2) + list-style-image(2) = 4
        assertEquals("LI elements without Lbl (none + image markers)", 4, liWithoutLbl);
    }

    @Test
    public void testAbbreviations() throws Exception {
        run("abbreviations");
    }

    // -----------------------------------------------------------------------
    // Helpers for abbreviation structure assertions
    // -----------------------------------------------------------------------

    /**
     * Result record for a Span element that has Expansion Text (E attribute).
     */
    private static class AbbrSpanInfo {
        final String expansionText;
        final boolean hasContent; // true if the Span has at least one child content item

        AbbrSpanInfo(String expansionText, boolean hasContent) {
            this.expansionText = expansionText;
            this.hasContent = hasContent;
        }

        @Override
        public String toString() {
            return "Span[E=\"" + expansionText + "\", hasContent=" + hasContent + "]";
        }
    }

    /**
     * Walks the whole structure tree and collects all Span elements that carry
     * an Expansion Text (E attribute).  Each entry reports whether the Span has
     * at least one child item (content item or nested structure element) so we
     * can assert that the Span is not empty.
     */
    private static List<AbbrSpanInfo> collectAbbrSpans(byte[] pdfBytes) throws IOException {
        List<AbbrSpanInfo> result = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
            if (root == null) return result;
            List<Object> kids = root.getKids();
            if (kids != null) {
                for (Object kid : kids) {
                    collectAbbrSpansFromItem(kid, result);
                }
            }
        }
        return result;
    }

    private static void collectAbbrSpansFromItem(Object item, List<AbbrSpanInfo> result) {
        if (!(item instanceof PDStructureElement)) return;
        PDStructureElement elem = (PDStructureElement) item;

        if ("Span".equals(elem.getStructureType())) {
            // getExpandedForm() / getCOSObject "E" is the PDF expansion text attribute.
            String expansion = elem.getExpandedForm();
            if (expansion != null && !expansion.isEmpty()) {
                List<Object> kids = elem.getKids();
                boolean hasContent = kids != null && !kids.isEmpty();
                result.add(new AbbrSpanInfo(expansion, hasContent));
                // Don't recurse further – children are content items, not nested Spans.
                return;
            }
        }

        List<Object> kids = elem.getKids();
        if (kids != null) {
            for (Object kid : kids) {
                collectAbbrSpansFromItem(kid, result);
            }
        }
    }

    // -----------------------------------------------------------------------

    /**
     * Verifies the PDF/UA structure for abbreviations in abbreviations.html.
     *
     * <p>Checks:
     * <ol>
     *   <li>Every {@code &lt;abbr title="..."&gt;} produces a PDF {@code Span} element
     *       whose {@code E} (Expansion Text) attribute matches the {@code title}.</li>
     *   <li>Each such {@code Span} is non-empty – i.e. the abbreviation text content
     *       is correctly placed <em>inside</em> the Span, not alongside it.</li>
     *   <li>An {@code &lt;abbr&gt;} without a {@code title} does NOT produce an
     *       expansion-text Span (no false positives).</li>
     * </ol>
     *
     * <p>abbreviations.html contains the following titled abbreviations:
     * HTML (×1), W3C (×2: scenario 2 and 5), WHATWG (×1), UE (×2: scenario 4 and 6),
     * EU (×1), HTTP (×1), HTTPS (×1), API (×1), PDF (×1), UA (×1), W3C again in
     * scenario 10. The exact count is validated below.
     *
     * Run with {@code -Dtest.dumpStructure=true} to print the full structure tree.
     */
    @Test
    public void testAbbreviationStructure() throws Exception {
        byte[] pdf = renderToBytes("abbreviations");

        if (Boolean.getBoolean("test.dumpStructure")) {
            System.out.println("=== PDF Structure Tree (abbreviations.html) ===");
            System.out.println(dumpStructureTree(pdf));
        }

        List<AbbrSpanInfo> abbrSpans = collectAbbrSpans(pdf);

        System.out.println("Abbreviation Span elements found:");
        for (AbbrSpanInfo info : abbrSpans) {
            System.out.println("  " + info);
        }

        // Every abbr with a title must produce a non-empty Span
        for (AbbrSpanInfo info : abbrSpans) {
            assertTrue(
                "Abbr Span with E=\"" + info.expansionText + "\" has no content children – " +
                "abbreviation text was not placed inside the Span",
                info.hasContent
            );
        }

        // No empty Spans with expansion text are acceptable
        long emptyCount = abbrSpans.stream().filter(s -> !s.hasContent).count();
        assertEquals("There should be no empty Abbr Spans", 0, emptyCount);

        // abbreviations.html has exactly these titled abbr occurrences:
        // scenario 1: HTML
        // scenario 2: W3C
        // scenario 4: UE (fr)
        // scenario 5: HTML, W3C, WHATWG
        // scenario 6 (list): EU, UE (fr)
        // scenario 7 (ol): HTTP, HTTPS
        // scenario 8 (heading): API
        // scenario 9 (table): PDF, UA
        // scenario 10: W3C
        // Total = 1+1+1+3+2+2+1+2+1 = 14
        assertEquals("Expected number of titled <abbr> Span elements", 14, abbrSpans.size());

        // Verify specific expansion texts are present
        List<String> expansions = new ArrayList<>();
        for (AbbrSpanInfo info : abbrSpans) {
            expansions.add(info.expansionText);
        }
        assertTrue("Missing HTML expansion",     expansions.contains("HyperText Markup Language"));
        assertTrue("Missing W3C expansion",      expansions.contains("World Wide Web Consortium"));
        assertTrue("Missing WHATWG expansion",   expansions.contains("Web Hypertext Application Technology Working Group"));
        assertTrue("Missing EU expansion",       expansions.contains("European Union"));
        assertTrue("Missing UE expansion",       expansions.contains("Union Europeene"));
        assertTrue("Missing HTTP expansion",     expansions.contains("HyperText Transfer Protocol"));
        assertTrue("Missing HTTPS expansion",    expansions.contains("HyperText Transfer Protocol Secure"));
        assertTrue("Missing API expansion",      expansions.contains("Application Programming Interface"));
        assertTrue("Missing PDF expansion",      expansions.contains("Portable Document Format"));
        assertTrue("Missing UA expansion",       expansions.contains("Universal Accessibility"));
    }
}
