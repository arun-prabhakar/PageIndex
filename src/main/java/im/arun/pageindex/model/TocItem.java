package im.arun.pageindex.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single item in the table of contents.
 * Maps to the Python dict structure used in TOC processing.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TocItem {
    
    @JsonProperty("structure")
    private String structure;
    
    @JsonProperty("title")
    private String title;
    
    @JsonProperty("page")
    private Integer page;
    
    @JsonProperty("physical_index")
    private String physicalIndex;
    
    @JsonProperty("list_index")
    private Integer listIndex;
    
    @JsonProperty("appear_start")
    private String appearStart;
    
    @JsonProperty("start_index")
    private Integer startIndex;
    
    @JsonProperty("end_index")
    private Integer endIndex;
}
