package com.openhtmltopdf.testcases;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
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
}
