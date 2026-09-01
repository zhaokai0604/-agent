from pathlib import Path
from docx import Document
from docx.shared import Cm, Pt
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.section import WD_SECTION
from docx.oxml import OxmlElement
from docx.oxml.ns import qn


ROOT = Path(__file__).resolve().parent
OUT = ROOT / "ThreshCore智能运维与日志分析平台-软件源程序鉴别材料-满版提交版.docx"


CORE_FILES = [
    "src/main/java/com/award/log/AwardWinningLogApplication.java",
    "src/main/java/com/award/log/common/Result.java",
    "src/main/java/com/award/log/common/PageResult.java",
    "src/main/java/com/award/log/task/TaskInfo.java",
    "src/main/java/com/award/log/controller/LogAnalysisController.java",
    "src/main/java/com/award/log/service/impl/LogAnalysisServiceImpl.java",
    "src/main/java/com/award/log/analyzer/RealTimeLogAnalyzer.java",
    "src/main/java/com/award/log/analyzer/DrainParser.java",
    "src/main/java/com/award/log/analysis/LogLineParseSupport.java",
    "src/main/java/com/award/log/analysis/LogAnomalyHeuristics.java",
    "src/main/java/com/award/log/analysis/AiDiagnosisContextBuilder.java",
    "src/main/java/com/award/log/dto/EnhancedLogParseResultEntity.java",
    "src/main/java/com/award/log/decision/CollaborativeDecisionService.java",
    "src/main/java/com/award/log/decision/DecisionInput.java",
    "src/main/java/com/award/log/decision/DecisionResult.java",
    "src/main/java/com/award/log/decision/RuleEngineV2.java",
    "src/main/java/com/award/log/decision/RandomForestDecisionEngine.java",
    "src/main/java/com/award/log/decision/LlmDecisionEngine.java",
    "src/main/java/com/award/log/agent/AssistantOrchestrator.java",
    "src/main/java/com/award/log/agent/AutonomousOpsOrchestrator.java",
    "src/main/java/com/award/log/agent/AgentSkillPlan.java",
    "src/main/java/com/award/log/agent/ActionGuardrailService.java",
    "src/main/java/com/award/log/agent/awm/WorkflowExecutionService.java",
    "src/main/java/com/award/log/agent/awm/WorkflowRetriever.java",
    "src/main/java/com/award/log/agent/RemediationClosureSupport.java",
    "src/main/java/com/award/log/service/OpsAuditTraceService.java",
    "src/main/java/com/award/log/service/OpsAutoRemediationService.java",
]

SECURITY_FILES = [
    "src/main/java/com/award/log/mcp/McpToolRegistry.java",
    "src/main/java/com/award/log/service/mcp/McpExecutionService.java",
    "src/main/java/com/award/log/service/mcp/McpSecurityService.java",
    "src/main/java/com/award/log/security/AgenticRiskScoreEngine.java",
    "src/main/java/com/award/log/security/HighRiskCommandRule.java",
    "src/main/java/com/award/log/security/ApiKeyAuthFilter.java",
    "src/main/java/com/award/log/security/RequestUserResolver.java",
    "src/main/java/com/award/log/security/effect/ToolEffectResolver.java",
    "src/main/java/com/award/log/security/effect/RemediationContractFactory.java",
    "src/main/java/com/award/log/security/effect/RemediationVerifyEvaluator.java",
    "src/main/java/com/award/log/mcp/tools/LogAnalysisTool.java",
    "src/main/java/com/award/log/mcp/tools/ProcessTool.java",
    "src/main/java/com/award/log/mcp/tools/NetworkTool.java",
    "src/main/java/com/award/log/mcp/tools/PortHealthTool.java",
    "src/main/java/com/award/log/mcp/tools/ServiceRestartTool.java",
    "src/main/java/com/award/log/mcp/tools/DiskAnalyzeTool.java",
    "src/main/java/com/award/log/mcp/tools/ConfigDriftTool.java",
    "src/main/java/com/award/log/controller/McpExecuteController.java",
    "src/main/java/com/award/log/controller/AuditController.java",
    "src/main/java/com/award/log/controller/UnifiedAssistantController.java",
    "src/main/java/com/award/log/service/SystemConfigService.java",
    "frontend/src/components/LogAnalysis.vue",
    "frontend/src/components/ToolConsole.vue",
    "frontend/src/components/AuditCenter.vue",
    "frontend/src/components/OpsTraceCenter.vue",
    "frontend/src/components/SystemConfigCenter.vue",
    "frontend/src/utils/mcpRiskConfirm.js",
    "frontend/src/utils/mcpToolExecute.js",
    "frontend/src/api/index.js",
]

# 将申报中最能体现平台技术特色的安全与闭环代码置于源码汇编末段，
# 确保“后30页”能够稳定覆盖这些核心实现。
FOCUS_FILES = [
    "src/main/java/com/award/log/analyzer/DrainPlusParser.java",
    "src/main/java/com/award/log/decision/IntelligentRouter.java",
    "src/main/java/com/award/log/mcp/McpToolCatalog.java",
    "src/main/java/com/award/log/governance/OpsExecutionPipeline.java",
    "src/main/java/com/award/log/security/effect/EvidenceContractValidator.java",
]


def read_code(relative):
    path = ROOT / relative
    if not path.exists():
        return []
    lines = path.read_text(encoding="utf-8").splitlines()
    meaningful = [line.rstrip() for line in lines if line.strip()]
    # 交存版不添加文件路径、文件名等定位标记，保留源码原有非空行。
    return meaningful


def is_effective_code_line(line):
    stripped = line.strip()
    return bool(stripped) and not stripped.startswith(("//", "/*", "*", "*/"))


def pack_continuous_pages(lines, page_count):
    """按源码原顺序连续分装，每页至少70条非空行、至少50条非注释代码行。"""
    pages = []
    cursor = 0
    for _ in range(page_count):
        page = []
        effective = 0
        while cursor < len(lines) and (len(page) < 70 or effective < 50):
            line = lines[cursor]
            cursor += 1
            page.append(line)
            if is_effective_code_line(line):
                effective += 1
        if len(page) < 70 or effective < 50:
            raise RuntimeError("连续源码不足以满足每页70条源码行和50条有效代码行")
        pages.append(page)
    return pages, cursor


def build_front_and_back_pages(source):
    front_pages, front_used = pack_continuous_pages(source, 30)
    reverse_pages, back_used = pack_continuous_pages(list(reversed(source)), 30)
    back_pages = [list(reversed(page)) for page in reversed(reverse_pages)]
    return front_pages, back_pages, front_used, back_used


def set_cell_or_paragraph_no_wrap(paragraph):
    ppr = paragraph._p.get_or_add_pPr()
    no_wrap = OxmlElement("w:noWrap")
    ppr.append(no_wrap)


def add_page_number(paragraph):
    paragraph.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    run = paragraph.add_run("第 ")
    fld = OxmlElement("w:fldSimple")
    fld.set(qn("w:instr"), "PAGE")
    run._r.addnext(fld)
    paragraph.add_run(" 页")


def main():
    source = []
    focus_set = set(FOCUS_FILES)
    ordered_files = [
        relative
        for relative in CORE_FILES + SECURITY_FILES
        if relative not in focus_set
    ] + FOCUS_FILES
    for relative in ordered_files:
        source.extend(read_code(relative))

    # 交存版每页编排60条真实源码行，满足每页不少于50行并增强版面填充。
    page_count = 60
    front_pages, back_pages, front_used, back_used = build_front_and_back_pages(source)
    pages = front_pages + back_pages

    document = Document()
    section = document.sections[0]
    section.top_margin = Cm(0.65)
    section.bottom_margin = Cm(0.60)
    section.left_margin = Cm(1.05)
    section.right_margin = Cm(1.05)
    section.header_distance = Cm(0.35)
    section.footer_distance = Cm(0.35)

    header = section.header.paragraphs[0]
    header.text = ""
    header_table = section.header.add_table(rows=1, cols=2, width=Cm(18.9))
    header._element.getparent().remove(header._element)
    header_table.autofit = False
    header_table.columns[0].width = Cm(14.0)
    header_table.columns[1].width = Cm(4.9)
    left = header_table.cell(0, 0).paragraphs[0]
    left.alignment = WD_ALIGN_PARAGRAPH.LEFT
    left_run = left.add_run("ThreshCore智能运维与日志分析平台 V1.0")
    left_run.font.name = "Microsoft YaHei"
    left_run.font.size = Pt(8)
    right = header_table.cell(0, 1).paragraphs[0]
    right.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    right_run = right.add_run("第 ")
    right_run.font.name = "Microsoft YaHei"
    right_run.font.size = Pt(8)
    fld = OxmlElement("w:fldSimple")
    fld.set(qn("w:instr"), "PAGE")
    right._p.append(fld)
    right_run2 = right.add_run(" 页")
    right_run2.font.name = "Microsoft YaHei"
    right_run2.font.size = Pt(8)

    footer = section.footer.paragraphs[0]
    footer.text = "著作权人：赵文凯、贺英、于聪、柴子涵    第 "
    footer_run = footer.add_run()
    footer_field = OxmlElement("w:fldSimple")
    footer_field.set(qn("w:instr"), "PAGE")
    footer_run._r.append(footer_field)
    footer.add_run(" 页 共 60 页")
    footer.alignment = WD_ALIGN_PARAGRAPH.CENTER
    for run in footer.runs:
        run.font.name = "Microsoft YaHei"
        run.font.size = Pt(8)

    normal = document.styles["Normal"]
    normal.font.name = "Consolas"
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), "等线")
    normal.font.size = Pt(8.5)
    normal.paragraph_format.space_before = Pt(0)
    normal.paragraph_format.space_after = Pt(0)
    normal.paragraph_format.line_spacing = 1.0

    for index, page in enumerate(pages):
        if index:
            document.add_page_break()
        for line in page:
            paragraph = document.add_paragraph(style="Normal")
            paragraph.paragraph_format.keep_together = True
            paragraph.paragraph_format.widow_control = False
            set_cell_or_paragraph_no_wrap(paragraph)
            run = paragraph.add_run(line)
            run.font.name = "Consolas"
            run._element.rPr.rFonts.set(qn("w:eastAsia"), "等线")
            run.font.size = Pt(8.5)

    document.core_properties.title = "ThreshCore智能运维与日志分析平台软件源程序鉴别材料"
    document.core_properties.subject = "计算机软件著作权登记源程序鉴别材料"
    document.core_properties.author = "赵文凯、贺英、于聪、柴子涵"
    document.save(OUT)
    print(f"created: {OUT}")
    print(f"pages: {page_count}; front lines: {front_used}; back lines: {back_used}")


if __name__ == "__main__":
    main()
