from pathlib import Path
import zipfile
import xml.etree.ElementTree as ET
import re
import math

ROOT = Path(__file__).resolve().parent
NS = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}


def paragraphs(docx):
    with zipfile.ZipFile(docx) as z:
        root = ET.fromstring(z.read("word/document.xml"))
    return ["".join(t.text or "" for t in p.findall(".//w:t", NS)) for p in root.findall(".//w:p", NS)]


def logical_page_line_counts(docx):
    with zipfile.ZipFile(docx) as z:
        root = ET.fromstring(z.read("word/document.xml"))
    pages = [[]]
    for paragraph in root.findall(".//w:body/w:p", NS):
        text = "".join(t.text or "" for t in paragraph.findall(".//w:t", NS))
        if text.strip():
            pages[-1].append(text)
        if any(br.get("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}type") == "page"
               for br in paragraph.findall(".//w:br", NS)):
            pages.append([])
    return [len(page) for page in pages]


def effective_code_counts(docx):
    with zipfile.ZipFile(docx) as z:
        root = ET.fromstring(z.read("word/document.xml"))
    pages = [[]]
    for paragraph in root.findall(".//w:body/w:p", NS):
        text = "".join(t.text or "" for t in paragraph.findall(".//w:t", NS)).strip()
        if text:
            is_comment = text.startswith(("//", "/*", "*", "*/"))
            pages[-1].append(not is_comment)
        if any(br.get("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}type") == "page"
               for br in paragraph.findall(".//w:br", NS)):
            pages.append([])
    return [sum(page) for page in pages]


def all_text(docx):
    with zipfile.ZipFile(docx) as z:
        root = ET.fromstring(z.read("word/document.xml"))
    return "\n".join("".join(t.text or "" for t in p.findall(".//w:t", NS)) for p in root.findall(".//w:p", NS))


def main():
    material = ROOT / "ThreshCore智能运维与日志分析平台-软件源程序鉴别材料-满版提交版.docx"
    manual = ROOT.parent / "ThreshCore智能运维与日志分析平台-用户手册.docx"
    with zipfile.ZipFile(material) as z:
        xml = z.read("word/document.xml").decode("utf-8")
        header = z.read("word/header1.xml").decode("utf-8")
        footer = z.read("word/footer1.xml").decode("utf-8")
    print("logical_pages", xml.count('w:type="page"') + 1)
    print("page_breaks", xml.count('w:type="page"'))
    print("header_has_name", "ThreshCore智能运维与日志分析平台" in header)
    print("header_has_version", "V1.0" in header)
    print("header_has_page_field", "PAGE" in header)
    print("footer_has_copyright", "赵文凯" in footer and "贺英" in footer and "于聪" in footer and "柴子涵" in footer)
    print("footer_has_page_field", "PAGE" in footer and "共 60 页" in footer)
    ps = paragraphs(material)
    code = [p for p in ps if p.strip()]
    print("nonblank_code_paragraphs", len(code))
    print("expected_30_page_lines", len(code) >= 3000)
    print("has_source_location_marker", "SOURCE FILE" in all_text(material))
    page_counts = logical_page_line_counts(material)
    print("logical_page_line_counts", len(page_counts), min(page_counts), max(page_counts))
    print("every_logical_page_at_least_50", len(page_counts) == 60 and min(page_counts) >= 50)
    print("each_logical_page_has_at_least_70_lines", len(page_counts) == 60 and min(page_counts) >= 70)
    effective_counts = effective_code_counts(material)
    print("effective_code_line_counts", len(effective_counts), min(effective_counts), max(effective_counts))
    print("every_page_has_50_effective_code_lines", len(effective_counts) == 60 and min(effective_counts) >= 50)
    print("manual_exists", manual.exists())
    if manual.exists():
        text = all_text(manual)
        checks = {
            "log_analysis": "日志分析" in text,
            "async_task": "异步" in text or "进度" in text,
            "tool_console": "工具控制台" in text,
            "dry_run": "Dry-Run" in text,
            "audit": "审计" in text,
            "config": "系统配置" in text,
            "api_key": "API Key" in text,
        }
        print("manual_claims", checks)
    print("source_feature_markers", {k: (k in all_text(material)) for k in ["DrainPlusParser", "IntelligentRouter", "McpToolCatalog", "OpsExecutionPipeline", "EvidenceContractValidator"]})
    mapping = {
        "4.3 日志分析": ["LogAnalysisController", "LogAnalysisServiceImpl", "RealTimeLogAnalyzer", "DrainPlusParser", "TaskInfo"],
        "4.4 报告导出": ["LogAnalysisController", "LogAnalysisServiceImpl"],
        "4.6 工具执行": ["McpToolCatalog", "McpExecuteController", "OpsExecutionPipeline"],
        "4.7 审计闭环": ["OpsAuditTraceService", "OpsAutoRemediationService"],
    }
    material_lines = [p for p in ps if p.strip()]
    print("manual_core_mapping")
    for section_name, identifiers in mapping.items():
        result = {}
        for identifier in identifiers:
            positions = [i + 1 for i, line in enumerate(material_lines) if identifier in line]
            result[identifier] = (
                f"pages {math.ceil(min(positions) / 60)}-{math.ceil(max(positions) / 60)}"
                if positions else "MISSING"
            )
        print(section_name, result)


if __name__ == "__main__":
    main()
