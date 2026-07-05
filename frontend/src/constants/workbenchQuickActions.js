import {
  Coin,
  FolderOpened,
  TrendCharts,
  Document,
  Connection,
  Link,
  Monitor,
  Delete,
  DataAnalysis,
  Files,
  RefreshRight,
  Warning,
  Timer,
  Clock
} from '@element-plus/icons-vue'

/** 助手快捷指令 */
export const WORKBENCH_QUICK_GROUPS = [
  {
    name: '自主',
    items: [
      { label: '全面检查', icon: DataAnalysis, cmd: '帮我全面检查系统状态，发现问题并给出修复计划' },
      { label: '自动运维', icon: RefreshRight, cmd: '自动运维：检查本机并处理问题' }
    ]
  },
  {
    name: '观测',
    items: [
      { label: '磁盘检查', icon: Coin, cmd: '检查磁盘使用情况' },
      { label: '磁盘热点', icon: FolderOpened, cmd: '扫描 /tmp 磁盘占用热点' },
      { label: '系统负载', icon: TrendCharts, cmd: '查看系统负载' },
      { label: '进程资源', icon: Monitor, cmd: '查看占用资源最多的进程' },
      { label: '网络诊断', icon: Link, cmd: '检查网络连通性 ping 8.8.8.8' }
    ]
  },
  {
    name: '诊断',
    items: [
      { label: '最近日志', icon: Document, cmd: '分析最近的系统日志' },
      { label: 'OS 日志', icon: Connection, cmd: '查看 journalctl 最近30分钟日志' },
      { label: '端口监听', icon: Connection, cmd: '查看本机监听端口概况' },
      { label: '磁盘综合', icon: DataAnalysis, cmd: '磁盘压力综合分析（含热点）' }
    ]
  },
  {
    name: '处置',
    items: [
      { label: '临时清理', icon: Delete, cmd: '清理7天前的临时文件' },
      { label: '日志清理', icon: Files, cmd: '预览清理 /var/log 下 30 天前的陈旧日志文件' },
      { label: '服务重启', icon: RefreshRight, cmd: '预览重启 nginx 服务' },
      { label: '系统清理', icon: Delete, cmd: '帮我清理系统垃圾' },
      { label: '巡检修复', icon: RefreshRight, cmd: '检查系统状态，发现问题后给出修复建议' },
      { label: '确认执行', icon: RefreshRight, cmd: '确认执行' },
      { label: '延时清理', icon: Timer, cmd: '30分钟之后帮我清理临时文件' },
      { label: '延时巡检', icon: Clock, cmd: '20分钟之后检查磁盘使用情况' }
    ]
  }
]

export const PATROL_QUICK_ITEM = {
  label: '处理巡检待办',
  icon: Warning,
  action: 'patrol'
}
