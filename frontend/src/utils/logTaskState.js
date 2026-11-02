export const logTaskStatusText = (status) => ({
  PENDING: '等待中', PROCESSING: '处理中', PAUSED: '已暂停',
  RECOVERY_REQUIRED: '中断待恢复', COMPLETED: '已完成', FAILED: '已失败', CANCELLED: '已取消'
}[status] || status || '未知状态')

export const canRecoverLogTask = (task) => task?.status === 'RECOVERY_REQUIRED'
  && task?.runtime?.recoverable === true

export const shouldPollLogTask = (status) => ['PENDING', 'PROCESSING'].includes(status)
