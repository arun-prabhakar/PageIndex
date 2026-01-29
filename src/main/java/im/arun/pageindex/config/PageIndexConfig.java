package im.arun.pageindex.config;

import lombok.Data;

@Data
public class PageIndexConfig {
    private String model = "gpt-4o-2024-11-20";
    private int tocCheckPageNum = 20;
    private int maxPageNumEachNode = 10;
    private int maxTokenNumEachNode = 20000;
    private boolean addNodeId = true;
    private boolean addNodeSummary = true;
    private boolean addDocDescription = false;
    private boolean addNodeText = false;
}
