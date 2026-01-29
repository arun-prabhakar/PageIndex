package im.arun.pageindex.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Represents a single PDF page with its text content and token count.
 */
@Data
@AllArgsConstructor
public class PdfPage {
    private int pageNumber;
    private String text;
    private int tokenCount;
}
