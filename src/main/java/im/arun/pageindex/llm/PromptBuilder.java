package im.arun.pageindex.llm;

/**
 * Builds prompts for LLM interactions.
 * Contains all prompts used in the PageIndex system matching the Python version.
 */
public class PromptBuilder {

    public String buildTocDetectionPrompt(String content) {
        return String.format("""
            Your job is to detect if there is a table of content provided in the given text.

            Given text: %s

            return the following JSON format:
            {
                "thinking": <why do you think there is a table of content in the given text>,
                "toc_detected": "<yes or no>",
            }

            Directly return the final JSON structure. Do not output anything else.
            Please note: abstract,summary, notation list, figure list, table list, etc. are not table of contents.""", content);
    }

    public String buildPageIndexDetectionPrompt(String tocContent) {
        return String.format("""
            You will be given a table of contents.

            Your job is to detect if there are page numbers/indices given within the table of contents.

            Given text: %s

            Reply format:
            {
                "thinking": <why do you think there are page numbers/indices given within the table of contents>
                "page_index_given_in_toc": "<yes or no>"
            }
            Directly return the final JSON structure. Do not output anything else.""", tocContent);
    }

    public String buildTocTransformationPrompt(String tocContent) {
        return """
            You are given a table of contents, You job is to transform the whole table of content into a JSON format included table_of_contents.

            structure is the numeric system which represents the index of the hierarchy section in the table of contents. For example, the first section has structure index 1, the first subsection has structure index 1.1, the second subsection has structure index 1.2, etc.

            The response should be in the following JSON format:
            {
            table_of_contents: [
                {
                    "structure": <structure index, "x.x.x" or None> (string),
                    "title": <title of the section>,
                    "page": <page number or None>,
                },
                ...
                ],
            }
            You should transform the full table of contents in one go.
            Directly return the final JSON structure, do not output anything else.""" + "\n Given table of contents\n:" + tocContent;
    }

    public String buildTocIndexExtractionPrompt(String toc, String content) {
        String prompt = """
            You are given a table of contents in a json format and several pages of a document, your job is to add the physical_index to the table of contents in the json format.

            The provided pages contains tags like <physical_index_X> and <physical_index_X> to indicate the physical location of the page X.

            The structure variable is the numeric system which represents the index of the hierarchy section in the table of contents. For example, the first section has structure index 1, the first subsection has structure index 1.1, the second subsection has structure index 1.2, etc.

            The response should be in the following JSON format:
            [
                {
                    "structure": <structure index, "x.x.x" or None> (string),
                    "title": <title of the section>,
                    "physical_index": "<physical_index_X>" (keep the format)
                },
                ...
            ]

            Only add the physical_index to the sections that are in the provided pages.
            If the section is not in the provided pages, do not add the physical_index to it.
            Directly return the final JSON structure. Do not output anything else.""";

        return prompt + "\nTable of contents:\n" + toc + "\nDocument pages:\n" + content;
    }

    public String buildTitleCheckPrompt(String title, String pageText) {
        return String.format("""
            Your job is to check if the given section appears or starts in the given page_text.

            Note: do fuzzy matching, ignore any space inconsistency in the page_text.

            The given section title is %s.
            The given page_text is %s.
           
            Reply format:
            {
               
                "thinking": <why do you think the section appears or starts in the page_text>
                "answer": "yes or no" (yes if the section appears or starts in the page_text, no otherwise)
            }
            Directly return the final JSON structure. Do not output anything else.""", title, pageText);
    }

    public String buildTitleStartCheckPrompt(String title, String pageText) {
        return String.format("""
            You will be given the current section title and the current page_text.
            Your job is to check if the current section starts in the beginning of the given page_text.
            If there are other contents before the current section title, then the current section does not start in the beginning of the given page_text.
            If the current section title is the first content in the given page_text, then the current section starts in the beginning of the given page_text.

            Note: do fuzzy matching, ignore any space inconsistency in the page_text.

            The given section title is %s.
            The given page_text is %s.
           
            reply format:
            {
                "thinking": <why do you think the section appears or starts in the page_text>
                "start_begin": "yes or no" (yes if the section starts in the beginning of the page_text, no otherwise)
            }
            Directly return the final JSON structure. Do not output anything else.""", title, pageText);
    }

    public String buildAddPageNumberPrompt(String part, String structure) {
        String fillPromptSeq = """
            You are given an JSON structure of a document and a partial part of the document. Your task is to check if the title that is described in the structure is started in the partial given document.

            The provided text contains tags like <physical_index_X> and <physical_index_X> to indicate the physical location of the page X.

            If the full target section starts in the partial given document, insert the given JSON structure with the "start": "yes", and "start_index": "<physical_index_X>".

            If the full target section does not start in the partial given document, insert "start": "no",  "start_index": None.

            The response should be in the following format.
                [
                    {
                        "structure": <structure index, "x.x.x" or None> (string),
                        "title": <title of the section>,
                        "start": "<yes or no>",
                        "physical_index": "<physical_index_X> (keep the format)" or None
                    },
                    ...
                ]   
            The given structure contains the result of the previous part, you need to fill the result of the current part, do not change the previous result.
            Directly return the final JSON structure. Do not output anything else.""";

        return fillPromptSeq + String.format("\n\nCurrent Partial Document:\n%s\n\nGiven Structure\n%s\n", part, structure);
    }

    public String buildGenerateTocInitPrompt(String part) {
        String prompt = """
            You are an expert in extracting hierarchical tree structure, your task is to generate the tree structure of the document.

            The structure variable is the numeric system which represents the index of the hierarchy section in the table of contents. For example, the first section has structure index 1, the first subsection has structure index 1.1, the second subsection has structure index 1.2, etc.

            For the title, you need to extract the original title from the text, only fix the space inconsistency.

            The provided text contains tags like <physical_index_X> and <physical_index_X> to indicate the start and end of page X.

            For the physical_index, you need to extract the physical index of the start of the section from the text. Keep the <physical_index_X> format.

            The response should be in the following format.
                [
                    {
                        "structure": <structure index, "x.x.x"> (string),
                        "title": <title of the section, keep the original title>,
                        "physical_index": "<physical_index_X> (keep the format)"
                    },
                   
                ],


            Directly return the final JSON structure. Do not output anything else.""";

        return prompt + "\nGiven text\n:" + part;
    }

    public String buildGenerateTocContinuePrompt(String tocContent, String part) {
        String prompt = """
            You are an expert in extracting hierarchical tree structure.
            You are given a tree structure of the previous part and the text of the current part.
            Your task is to continue the tree structure from the previous part to include the current part.

            The structure variable is the numeric system which represents the index of the hierarchy section in the table of contents. For example, the first section has structure index 1, the first subsection has structure index 1.1, the second subsection has structure index 1.2, etc.

            For the title, you need to extract the original title from the text, only fix the space inconsistency.

            The provided text contains tags like <physical_index_X> and <physical_index_X> to indicate the start and end of page X. \\
           
            For the physical_index, you need to extract the physical index of the start of the section from the text. Keep the <physical_index_X> format.

            The response should be in the following format.
                [
                    {
                        "structure": <structure index, "x.x.x"> (string),
                        "title": <title of the section, keep the original title>,
                        "physical_index": "<physical_index_X> (keep the format)"
                    },
                    ...
                ]   

            Directly return the additional part of the final JSON structure. Do not output anything else.""";

        return prompt + "\nGiven text\n:" + part + "\nPrevious tree structure\n:" + tocContent;
    }

    public String buildTocItemFixerPrompt(String sectionTitle, String content) {
        String tocExtractorPrompt = """
            You are given a section title and several pages of a document, your job is to find the physical index of the start page of the section in the partial document.

            The provided pages contains tags like <physical_index_X> and <physical_index_X> to indicate the physical location of the page X.

            Reply in a JSON format:
            {
                "thinking": <explain which page, started and closed by <physical_index_X>, contains the start of this section>,
                "physical_index": "<physical_index_X>" (keep the format)
            }
            Directly return the final JSON structure. Do not output anything else.""";

        return tocExtractorPrompt + "\nSection Title:\n" + sectionTitle + "\nDocument pages:\n" + content;
    }

    public String buildNodeSummaryPrompt(String nodeText) {
        return String.format("""
            You are given a part of a document, your task is to generate a description of the partial document about what are main points covered in the partial document.

            Partial Document Text: %s
           
            Directly return the description, do not include any other text.
            """, nodeText);
    }

    public String buildDocDescriptionPrompt(String structure) {
        return String.format("""
            Your are an expert in generating descriptions for a document.
            You are given a structure of a document. Your task is to generate a one-sentence description for the document, which makes it easy to distinguish the document from other documents.
               
            Document Structure: %s
           
            Directly return the description, do not include any other text.
            """, structure);
    }

    public String buildTocExtractionPrompt(String content) {
        return String.format("""
            Your job is to extract the full table of contents from the given text, replace ... with :

            Given text: %s

            Directly return the full table of contents content. Do not output anything else.""", content);
    }

    public String buildTocExtractionCompletionCheckPrompt(String content, String toc) {
        String prompt = """
            You are given a partial document  and a  table of contents.
            Your job is to check if the  table of contents is complete, which it contains all the main sections in the partial document.

            Reply format:
            {
                "thinking": <why do you think the table of contents is complete or not>
                "completed": "yes" or "no"
            }
            Directly return the final JSON structure. Do not output anything else.""";

        return prompt + "\n Document:\n" + content + "\n Table of contents:\n" + toc;
    }

    public String buildTocTransformationCompletionCheckPrompt(String content, String toc) {
        String prompt = """
            You are given a raw table of contents and a  table of contents.
            Your job is to check if the  table of contents is complete.

            Reply format:
            {
                "thinking": <why do you think the cleaned table of contents is complete or not>
                "completed": "yes" or "no"
            }
            Directly return the final JSON structure. Do not output anything else.""";

        return prompt + "\n Raw Table of contents:\n" + content + "\n Cleaned Table of contents:\n" + toc;
    }
}
