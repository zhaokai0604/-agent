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
      { label: '网络诊断', icon: Link, cmd: '检查网络连通性' }
    ]
  },
  {
    name: '诊断',
    items: [
      { label: '最近日志', icon: Document, cmd: '分析最近的系统日志' },
      { label: 'OS 日志', icon: Connection, cmd: '查看 journalctl 最近30分钟日志' },
      { label: '端口套接字', icon: Connection, cmd: '查看本机监听端口与网络套接字概况' },
      {
        label: '跨平台磁盘',
        icon: Folder,
        cmd: '请对比说明 Linux 与 Windows 服务器上如何排查磁盘占满，包括常用命令、临时目录路径与安全注意点'
      },
      { label: '磁盘综合', icon: DataAnalysis, cmd: '做一次磁盘压力综合分析，包含热点扫描' }
    ]
  },
  {
    name: '处置',
    items: [
      { label: '临时清理', icon: Delete, cmd: '清理 7 天前的临时文件' },
      { label: '日志清理预览', icon: Files, cmd: '预览清理 /var/log 中 30 天前的旧日志文件' },
      { label: '服务重启预览', icon: RefreshRight, cmd: '预览重启 nginx 服务，保持 dry-run' },
      { label: '闭环清理', icon: Delete, cmd: '帮我清理系统垃圾' },
      { label: '自主运维', icon: RefreshRight, cmd: '请执行全自动运维：先感知问题，再自动修复' },
      { label: '延时清理', icon: Timer, cmd: '30 分钟后预览清理临时文件' },
      { label: '延时巡检', icon: Clock, cmd: '20 分钟后检查磁盘使用情况' }
    ]
  }
]

export const PATROL_QUICK_ITEM = {
  label: '处理巡检待办',
  icon: Warning,
  action: 'patrol'
}
