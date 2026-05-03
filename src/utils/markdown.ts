// Markdown 解析配置
import MarkdownIt from 'markdown-it';

// 创建 markdown-it 实例
const md = new MarkdownIt({
  html: true, // 允许 HTML 标签
  linkify: true, // 自动链接
  typographer: true, // 智能替换（如：(c) → ©）
  breaks: true // 自动换行
});

/**
 * 解析 Markdown 文本为 HTML
 */
export const parseMarkdown = (text: string): string => {
  return md.render(text);
};

/**
 * 高亮代码块（可选，可扩展）
 */
export const highlightCode = (code: string, lang: string): string => {
  // 这里可以集成代码高亮库，如 highlight.js 或 Prism
  return `<pre><code class="language-${lang}">${code}</code></pre>`;
};