package im.arun.pageindex.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents the complete tree structure of a document.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TreeStructure {
    
    @JsonProperty("doc_name")
    private String docName;
    
    @JsonProperty("doc_description")
    private String docDescription;
    
    @JsonProperty("structure")
    private List<TreeNode> structure;
    
    public TreeStructure(List<TreeNode> structure) {
        this.structure = structure;
    }
}
