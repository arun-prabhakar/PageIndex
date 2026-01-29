package im.arun.pageindex.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents a node in the hierarchical document tree.
 * Maps to the Python dict structure for tree nodes.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TreeNode {
    
    @JsonProperty("title")
    private String title;
    
    @JsonProperty("node_id")
    private String nodeId;
    
    @JsonProperty("start_index")
    private Integer startIndex;
    
    @JsonProperty("end_index")
    private Integer endIndex;
    
    @JsonProperty("text")
    private String text;
    
    @JsonProperty("summary")
    private String summary;
    
    @JsonProperty("prefix_summary")
    private String prefixSummary;
    
    @JsonProperty("line_num")
    private Integer lineNum;
    
    @JsonProperty("nodes")
    private List<TreeNode> nodes;
}
