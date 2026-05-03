package com.award.log.service.impl;

import com.award.log.mapper.LogAlarmMapper;
import com.award.log.model.LogAlarm;
import com.award.log.service.AlarmLifecycleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class AlarmLifecycleServiceImpl implements AlarmLifecycleService {

    private final LogAlarmMapper logAlarmMapper;
    private final AtomicReference<LocalDateTime> silenceStart = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> silenceEnd = new AtomicReference<>();

    public AlarmLifecycleServiceImpl(LogAlarmMapper logAlarmMapper) {
        this.logAlarmMapper = logAlarmMapper;
    }

    @Override
    public boolean acknowledge(String alarmId, String operator) {
        return logAlarmMapper.updateLifecycle(alarmId, "ACKNOWLEDGED", operator) > 0;
    }

    @Override
    public boolean handle(String alarmId, String operator) {
        return logAlarmMapper.updateLifecycle(alarmId, "HANDLED", operator) > 0;
    }

    @Override
    public boolean close(String alarmId, String operator) {
        return logAlarmMapper.updateLifecycle(alarmId, "CLOSED", operator) > 0;
    }

    @Override
    public Map<String, Object> silenceWindow(String startTime, String endTime) {
        silenceStart.set(LocalDateTime.parse(startTime));
        silenceEnd.set(LocalDateTime.parse(endTime));
        return Map.of("startTime", startTime, "endTime", endTime, "enabled", true);
    }

    @Scheduled(fixedDelay = 60000)
    public void autoEscalate() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = silenceStart.get();
        LocalDateTime end = silenceEnd.get();
        if (start != null && end != null && now.isAfter(start) && now.isBefore(end)) {
            return;
        }
        List<LogAlarm> alarms = logAlarmMapper.selectNeedEscalation(15);
        for (LogAlarm alarm : alarms) {
            logAlarmMapper.increaseEscalation(alarm.getAlarmId());
            log.info("告警自动升级: alarmId={}, level={}", alarm.getAlarmId(), alarm.getEscalationLevel());
        }
    }
}
