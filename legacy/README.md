# PageIndex Python (Legacy)

> **⚠️ This is the legacy Python implementation of PageIndex.**  
> **For the current Java implementation, see the main project README in the parent directory.**

This folder contains the original Python codebase for reference purposes only.

## Legacy Python Usage

If you need to use the Python version:

### 1. Install dependencies

```bash
cd legacy
pip3 install --upgrade -r requirements.txt
```

### 2. Set your OpenAI API key

Create a `.env` file in the legacy directory:

```bash
CHATGPT_API_KEY=your_openai_key_here
```

### 3. Run PageIndex on your PDF

```bash
python3 run_pageindex.py --pdf_path /path/to/your/document.pdf
```

### Optional parameters

```
--model                 OpenAI model to use (default: gpt-4o-2024-11-20)
--toc-check-pages       Pages to check for table of contents (default: 20)
--max-pages-per-node    Max pages per node (default: 10)
--max-tokens-per-node   Max tokens per node (default: 20000)
--if-add-node-id        Add node ID (yes/no, default: yes)
--if-add-node-summary   Add node summary (yes/no, default: yes)
--if-add-doc-description Add doc description (yes/no, default: yes)
```

### Markdown support

```bash
python3 run_pageindex.py --md_path /path/to/your/document.md
```

---

**For new projects, please use the Java library instead.** See the main README for Java usage instructions.
