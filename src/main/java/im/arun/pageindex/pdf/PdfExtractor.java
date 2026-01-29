package im.arun.pageindex.pdf;

import im.arun.pageindex.model.PdfPage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF text extractor using Apache PDFBox.
 * Extracts text from PDF files and counts tokens per page.
 */
public class PdfExtractor {
    private static final Logger logger = LoggerFactory.getLogger(PdfExtractor.class);
    private final TokenCounter tokenCounter;

    public PdfExtractor() {
        this.tokenCounter = new TokenCounter();
    }

    /**
     * Extract pages from a PDF file.
     * 
     * @param pdfPath Path to the PDF file
     * @param model   Model name for token counting
     * @return List of PDF pages with text and token counts
     * @throws IOException If PDF cannot be read
     */
    public List<PdfPage> extractPages(Path pdfPath, String model) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            return extractPagesFromDocument(document, model);
        }
    }

    /**
     * Extract pages from a PDF byte array (equivalent to Python's BytesIO).
     * 
     * @param pdfBytes PDF file as byte array
     * @param model    Model name for token counting
     * @return List of PDF pages with text and token counts
     * @throws IOException If PDF cannot be read
     */
    public List<PdfPage> extractPages(byte[] pdfBytes, String model) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return extractPagesFromDocument(document, model);
        }
    }

    /**
     * Extract pages from a PDF input stream.
     * 
     * @param inputStream PDF input stream
     * @param model       Model name for token counting
     * @return List of PDF pages with text and token counts
     * @throws IOException If PDF cannot be read
     */
    public List<PdfPage> extractPages(InputStream inputStream, String model) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return extractPagesFromDocument(document, model);
        }
    }

    private List<PdfPage> extractPagesFromDocument(PDDocument document, String model) throws IOException {
        List<PdfPage> pages = new ArrayList<>();
        int totalPages = document.getNumberOfPages();

        PDFTextStripper stripper = new PDFTextStripper();

        for (int i = 0; i < totalPages; i++) {
            // PDFBox pages are 1-indexed
            stripper.setStartPage(i + 1);
            stripper.setEndPage(i + 1);

            String text = stripper.getText(document);
            int tokenCount = tokenCounter.countTokens(text, model);

            pages.add(new PdfPage(i + 1, text, tokenCount));
        }

        logger.info("Extracted {} pages from PDF", totalPages);
        return pages;
    }

    /**
     * Get the total number of pages in a PDF.
     * 
     * @param pdfPath Path to the PDF file
     * @return Number of pages
     * @throws IOException If PDF cannot be read
     */
    public int getPageCount(Path pdfPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            return document.getNumberOfPages();
        }
    }

    /**
     * Extract text from a specific page range.
     * 
     * @param pages      List of all pages
     * @param startPage  Start page (1-indexed, inclusive)
     * @param endPage    End page (1-indexed, inclusive)
     * @return Combined text from the page range
     */
    public String getTextOfPages(List<PdfPage> pages, int startPage, int endPage) {
        StringBuilder text = new StringBuilder();
        for (int i = startPage - 1; i < endPage && i < pages.size(); i++) {
            text.append(pages.get(i).getText());
        }
        return text.toString();
    }

    /**
     * Extract text from a specific page range with page index labels.
     * 
     * @param pages      List of all pages
     * @param startPage  Start page (1-indexed, inclusive)
     * @param endPage    End page (1-indexed, inclusive)
     * @return Combined text with page markers
     */
    public String getTextOfPagesWithLabels(List<PdfPage> pages, int startPage, int endPage) {
        StringBuilder text = new StringBuilder();
        for (int i = startPage - 1; i < endPage && i < pages.size(); i++) {
            int pageNum = i + 1;
            text.append(String.format("<physical_index_%d>\n%s\n<physical_index_%d>\n\n",
                    pageNum, pages.get(i).getText(), pageNum));
        }
        return text.toString();
    }
}
