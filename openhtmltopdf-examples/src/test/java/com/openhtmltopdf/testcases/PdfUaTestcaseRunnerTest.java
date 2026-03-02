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

        // Regression guard: link underlines are paths and must be tagged as artifacts.
        // Before the fix, BACKGROUND for per-run blocks returned FALSE_TOKEN unconditionally,
        // leaving the underline path untagged – causing "Path object not tagged" in PAC.
        int untaggedPaths = countUntaggedPathOps(pdf);
        assertEquals("No untagged path operations (link underlines must be /Artifact)",
            0, untaggedPaths);
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

    /** Block-level structure types that should never appear as children of H1-H6 or P. */
    private static final java.util.Set<String> BLOCK_TYPES = new java.util.HashSet<>(java.util.Arrays.asList(
            "H1", "H2", "H3", "H4", "H5", "H6", "P", "L", "Table", "Div", "Sect", "Art"
    ));

    /**
     * Walks the whole structure tree and collects problems.
     * Returns a list of human-readable violation strings; empty = no violations.
     *
     * Checks:
     * <ol>
     *   <li>No Span element has zero children (empty Span – usually means abbr/link text
     *       was not routed into the Span's marked content).</li>
     *   <li>No block-level element (H1-H6, P) has another block-level element as a
     *       DIRECT child (indicates content from a sibling block leaked into the wrong
     *       parent due to an unclosed block span).</li>
     * </ol>
     */
    private static List<String> collectStructureViolations(byte[] pdfBytes) throws IOException {
        List<String> violations = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
            if (root == null) return violations;
            List<Object> kids = root.getKids();
            if (kids != null) {
                for (Object kid : kids) {
                    checkStructureViolations(kid, null, violations);
                }
            }
        }
        return violations;
    }

    private static void checkStructureViolations(Object item, String parentType, List<String> violations) {
        if (!(item instanceof PDStructureElement)) return;
        PDStructureElement elem = (PDStructureElement) item;
        String type = elem.getStructureType();

        // Check 1: no empty Span (a Span with no children is always a bug)
        if ("Span".equals(type)) {
            List<Object> kids = elem.getKids();
            if (kids == null || kids.isEmpty()) {
                violations.add("Empty Span (no children) found inside parent " + parentType);
            }
        }

        // Check 2: block-level elements (H*, P) must not contain other block-level elements
        if (parentType != null && isHeadingOrP(parentType) && BLOCK_TYPES.contains(type)) {
            violations.add("Block element <" + type + "> is a direct child of <" + parentType +
                    "> – possible block-span leakage from a sibling element");
        }

        List<Object> kids = elem.getKids();
        if (kids != null) {
            for (Object kid : kids) {
                checkStructureViolations(kid, type, violations);
            }
        }
    }

    private static boolean isHeadingOrP(String type) {
        return "P".equals(type) || type.matches("H[1-6]?");
    }

    /**
     * Collects all Span elements in the structure tree that carry an Expansion Text
     * (E attribute, from PDF/UA abbreviation tagging).  Each entry also reports
     * whether the Span has at least one child so we can assert non-emptiness.
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
            String expansion = elem.getExpandedForm();
            if (expansion != null && !expansion.isEmpty()) {
                List<Object> kids = elem.getKids();
                boolean hasContent = kids != null && !kids.isEmpty();
                result.add(new AbbrSpanInfo(expansion, hasContent));
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

    /**
     * Result record for a Span element that has Expansion Text (E attribute).
     */
    private static class AbbrSpanInfo {
        final String expansionText;
        final boolean hasContent;

        AbbrSpanInfo(String expansionText, boolean hasContent) {
            this.expansionText = expansionText;
            this.hasContent = hasContent;
        }

        @Override
        public String toString() {
            return "Span[E=\"" + expansionText + "\", hasContent=" + hasContent + "]";
        }
    }

    // -----------------------------------------------------------------------

    /**
     * Counts how many times a given PDF structure type (e.g. "THead", "TFoot") appears
     * anywhere in the logical structure tree.
     */
    private static int countStructureType(byte[] pdfBytes, String typeName) throws IOException {
        final int[] count = {0};
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
            if (root == null) return 0;
            List<Object> kids = root.getKids();
            if (kids != null) {
                for (Object kid : kids) {
                    countStructureTypeInItem(kid, typeName, count);
                }
            }
        }
        return count[0];
    }

    private static void countStructureTypeInItem(Object item, String typeName, int[] count) {
        if (!(item instanceof PDStructureElement)) return;
        PDStructureElement elem = (PDStructureElement) item;
        if (typeName.equals(elem.getStructureType())) {
            count[0]++;
        }
        List<Object> kids = elem.getKids();
        if (kids != null) {
            for (Object kid : kids) {
                countStructureTypeInItem(kid, typeName, count);
            }
        }
    }

    /**
     * Counts PDF path-painting operators (f, F, s, S, b, B, etc.) that appear outside
     * any BDC/BMC marked-content region.  Such operators cause "Path object not tagged"
     * errors in PDF/UA compliance checkers like PAC.
     */
    private static int countUntaggedPathOps(byte[] pdfBytes) throws IOException {
        return countUntaggedPathOps(pdfBytes, false);
    }

    private static int countUntaggedPathOps(byte[] pdfBytes, boolean debug) throws IOException {
        final int[] count = {0};
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int pageNum = 0;
            for (org.apache.pdfbox.pdmodel.PDPage page : doc.getPages()) {
                pageNum++;
                final int[] depth = {0};
                final int pageNumFinal = pageNum;
                new org.apache.pdfbox.contentstream.PDFStreamEngine() {
                    @Override
                    protected void processOperator(
                            org.apache.pdfbox.contentstream.operator.Operator operator,
                            java.util.List<org.apache.pdfbox.cos.COSBase> operands)
                            throws java.io.IOException {
                        switch (operator.getName()) {
                            case "BDC": case "BMC": depth[0]++; break;
                            case "EMC": if (depth[0] > 0) depth[0]--; break;
                            /* Path-painting operators: if none of these is inside a marked-content
                             * region the operator is "untagged" per PDF/UA. */
                            case "f": case "F": case "f*":
                            case "s": case "S":
                            case "b": case "B": case "b*": case "B*":
                                if (depth[0] == 0) {
                                    count[0]++;
                                    if (debug) {
                                        System.out.println("  UNTAGGED path op '" + operator.getName()
                                            + "' on page " + pageNumFinal + " depth=" + depth[0]);
                                    }
                                }
                                break;
                            default: break;
                        }
                    }
                }.processPage(page);
            }
        }
        return count[0];
    }

    // -----------------------------------------------------------------------

    /**
     * Verifies the PDF/UA structure for abbreviations in abbreviations.html.
     *
     * <p>Structural checks (hierarchy):
     * <ul>
     *   <li>No Span element is empty (abbreviation text must be inside the Span).</li>
     *   <li>No block-level element (H1-H6, P) has another block-level element as a
     *       direct child – which would mean content from a sibling block leaked into
     *       it because a preceding block span was not closed before entering per-run
     *       mode.</li>
     * </ul>
     *
     * <p>Content checks:
     * <ul>
     *   <li>Every {@code &lt;abbr title="..."&gt;} produces exactly one PDF Span with
     *       the matching Expansion Text value.</li>
     *   <li>{@code &lt;abbr&gt;} WITHOUT a title does NOT produce any expansion Span.</li>
     * </ul>
     *
     * <p>abbreviations.html titled abbr count per scenario:
     * <pre>
     *  1  standalone P      : HTML
     *  2  P with text       : W3C
     *  3  no title          : (none)
     *  4  lang attr         : UE
     *  5  multiple in P     : HTML, W3C, WHATWG
     *  6  list (ul)         : EU, UE
     *  7  list (ol)         : HTTP, HTTPS
     *  8  heading           : API
     *  9  table             : PDF, UA, WWW
     *  10 abbr + link in P  : W3C
     *  Total = 15
     * </pre>
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

        // ---- 1. Hierarchy checks (no leaked content, no empty Spans) ----
        List<String> violations = collectStructureViolations(pdf);
        if (!violations.isEmpty()) {
            System.out.println("Structure violations found:");
            for (String v : violations) System.out.println("  " + v);
        }
        assertTrue(
            "Structure violations detected (see stdout for details): " + violations,
            violations.isEmpty()
        );

        // ---- 2. Collect all expansion-text Spans ----
        List<AbbrSpanInfo> abbrSpans = collectAbbrSpans(pdf);

        System.out.println("Abbreviation Span elements found (" + abbrSpans.size() + "):");
        for (AbbrSpanInfo info : abbrSpans) {
            System.out.println("  " + info);
        }

        // ---- 3. Every expansion Span must be non-empty ----
        for (AbbrSpanInfo info : abbrSpans) {
            assertTrue(
                "Abbr Span E=\"" + info.expansionText + "\" is empty – text not placed inside Span",
                info.hasContent
            );
        }

        // ---- 4. Correct total count ----
        // Scenarios: 1+1+0+1+3+2+2+1+3+1 = 15
        assertEquals("Total titled <abbr> Span count", 15, abbrSpans.size());

        // ---- 5. Specific expansion texts must be present ----
        List<String> expansions = new ArrayList<>();
        for (AbbrSpanInfo info : abbrSpans) {
            expansions.add(info.expansionText);
        }
        assertTrue("HTML",     expansions.contains("HyperText Markup Language"));
        assertTrue("W3C",      expansions.contains("World Wide Web Consortium"));
        assertTrue("WHATWG",   expansions.contains("Web Hypertext Application Technology Working Group"));
        assertTrue("EU",       expansions.contains("European Union"));
        assertTrue("UE",       expansions.contains("Union Europeene"));
        assertTrue("HTTP",     expansions.contains("HyperText Transfer Protocol"));
        assertTrue("HTTPS",    expansions.contains("HyperText Transfer Protocol Secure"));
        assertTrue("API",      expansions.contains("Application Programming Interface"));
        assertTrue("PDF",      expansions.contains("Portable Document Format"));
        assertTrue("UA",       expansions.contains("Universal Accessibility"));
        assertTrue("WWW",      expansions.contains("World Wide Web"));

        // ---- 6. abbr WITHOUT title must NOT appear in expansion Spans ----
        assertFalse("Plain <abbr> without title should not produce an expansion Span",
            expansions.stream().anyMatch(e -> e == null || e.isEmpty()));

        // ---- 7. Table structure: <thead> present, empty <tfoot> must be skipped ----
        // abbreviations.html table has <thead> but no <tfoot>.
        // TFoot is optional per PDF spec (ISO 32000) – empty ones must not be emitted.
        assertEquals("abbreviations.html table has <thead>: expect 1 THead in structure",
            1, countStructureType(pdf, "THead"));
        assertEquals("abbreviations.html table has no <tfoot>: expect 0 TFoot in structure",
            0, countStructureType(pdf, "TFoot"));

        // ---- 8. No untagged path operations (regression: link underline must be an artifact) ----
        // Scenario 10 has <a href> inside a per-run Span block (link+abbr in same paragraph).
        // The link underline is a path; it must be wrapped in an /Artifact BDC to avoid
        // "Path object not tagged" errors in PDF/UA compliance checkers.
        int untaggedPaths = countUntaggedPathOps(pdf);
        assertEquals("No untagged path operations (link underlines must be tagged as artifacts)",
            0, untaggedPaths);
    }
}
