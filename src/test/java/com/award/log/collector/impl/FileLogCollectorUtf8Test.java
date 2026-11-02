package com.award.log.collector.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FileLogCollectorUtf8Test {
    @TempDir Path dir;
    FileLogCollector collector(Path path, int capacity) {
        return new FileLogCollector(path.toString(),"fixture",".log","",2,1000,capacity,false);
    }
    void read(FileLogCollector collector, Path path) {
        ReflectionTestUtils.invokeMethod(collector,"collectFromFile",path);
    }
    @Test void splitUtf8AndPartialLineAreNotPublishedUntilTerminated() throws Exception {
        Path path=dir.resolve("utf8.log");
        byte[] line="故障：连接超时 🔧\n".getBytes(StandardCharsets.UTF_8);
        Files.write(path,java.util.Arrays.copyOf(line,2));
        var collector=collector(path,100);
        read(collector,path);assertTrue(collector.collect().isEmpty());
        Files.write(path,java.util.Arrays.copyOfRange(line,2,line.length-1),StandardOpenOption.APPEND);
        read(collector,path);assertTrue(collector.collect().isEmpty());
        Files.writeString(path,"\n",StandardOpenOption.APPEND);read(collector,path);
        assertEquals(List.of("故障：连接超时 🔧"),collector.collect());
        read(collector,path);assertTrue(collector.collect().isEmpty());
        assertEquals(0L,collector.diagnostics().get("invalidUtf8Lines"));
    }
    @Test void crlfSplitAcrossPollsDoesNotCreateAnEmptyEventAndBomIsRemoved() throws Exception {
        Path path=Files.writeString(dir.resolve("crlf.log"),"\uFEFF首行\r");
        var collector=collector(path,100);read(collector,path);
        assertEquals(List.of("首行"),collector.collect());
        Files.writeString(path,"\n下一行\r\n\r\n末行\n",StandardOpenOption.APPEND);read(collector,path);
        assertEquals(List.of("下一行","","末行"),collector.collect());
    }
    @Test void bufferBackpressurePreservesOrderWithoutDuplicatingOrDroppingLines() throws Exception {
        Path path=dir.resolve("pressure.log");
        List<String> expected=new ArrayList<>();for(int i=0;i<253;i++)expected.add("日志-"+i);
        Files.writeString(path,String.join("\n",expected)+"\n");
        var collector=collector(path,100);List<String> actual=new ArrayList<>();
        for(int i=0;i<4;i++){read(collector,path);actual.addAll(collector.collect());}
        assertEquals(expected,actual);
        assertTrue(((Number)collector.diagnostics().get("backpressureEvents")).longValue()>0);
        assertEquals(0L,collector.diagnostics().get("bufferedBytes"));
    }
    @Test void oversizedLineAcrossTicksIsSkippedOnceAndNextValidLineIsCollected() throws Exception {
        Path path=Files.writeString(dir.resolve("large.log"),"x".repeat(2*1024*1024)+"\n正常日志\n");
        var collector=collector(path,100);
        read(collector,path);assertTrue(collector.collect().isEmpty());
        read(collector,path);assertTrue(collector.collect().isEmpty());
        read(collector,path);assertEquals(List.of("正常日志"),collector.collect());
        assertEquals(1L,collector.diagnostics().get("oversizedLines"));
    }
    @Test void malformedUtf8IsCountedAndDoesNotBlockTheNextLine() throws Exception {
        Path path=Files.write(dir.resolve("invalid.log"),new byte[]{(byte)0xc3,0x28,10});
        Files.writeString(path,"下一行\n",StandardOpenOption.APPEND);
        var collector=collector(path,100);read(collector,path);
        assertEquals(List.of("下一行"),collector.collect());
        assertEquals(1L,collector.diagnostics().get("invalidUtf8Lines"));
    }
    @Test void truncationDiscardsThePendingPartialLine() throws Exception {
        Path path=Files.writeString(dir.resolve("truncate.log"),"未写完的旧日志");
        var collector=collector(path,100);read(collector,path);assertTrue(collector.collect().isEmpty());
        Files.writeString(path,"新\n");read(collector,path);
        assertEquals(List.of("新"),collector.collect());
    }
    @Test void replacementFileDoesNotInheritTheOldFilesCursorOrPartialLine() throws Exception {
        Path path=Files.writeString(dir.resolve("rotate.log"),"旧日志未结束");
        var collector=collector(path,100);read(collector,path);
        Files.move(path,dir.resolve("rotated.log"));
        Files.writeString(path,"新文件的完整日志比旧文件长得多\n");
        read(collector,path);
        assertEquals(List.of("新文件的完整日志比旧文件长得多"),collector.collect());
    }
    @Test void byteBudgetBackpressureAlsoPreservesAllLines() throws Exception {
        String line="x".repeat(60_000);
        Path path=Files.writeString(dir.resolve("byte-budget.log"),(line+"\n").repeat(145));
        var collector=collector(path,8000);
        for(int i=0;i<12;i++)read(collector,path);
        assertTrue(((Number)collector.diagnostics().get("backpressureEvents")).longValue()>0);
        assertTrue(((Number)collector.diagnostics().get("bufferedBytes")).longValue()<=8*1024*1024);
        var result=new ArrayList<>(collector.collect());
        for(int i=0;i<3;i++){read(collector,path);result.addAll(collector.collect());}
        assertEquals(145,result.size());assertTrue(result.stream().allMatch(line::equals));
    }
    @Test void sameFileRewriteAndRegrowthResetThePendingLine() throws Exception {
        Path path=Files.writeString(dir.resolve("regrowth.log"),"old pending");
        var collector=collector(path,100);read(collector,path);
        Files.writeString(path,"new replacement text\n");read(collector,path);
        assertEquals(List.of("new replacement text"),collector.collect());
    }
    @Test void directoryPollingDrainsBacklogWithoutAnotherModification() throws Exception {
        Path path=dir.resolve("backlog.log");
        List<String> expected=new ArrayList<>();for(int i=0;i<220;i++)expected.add("row-"+i);
        Files.writeString(path,String.join("\n",expected)+"\n");
        var collector=collector(dir,100);List<String> actual=new ArrayList<>();
        try {
            collector.start();long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            while(actual.size()<expected.size() && System.nanoTime()<deadline){actual.addAll(collector.collect());Thread.sleep(25);}
            assertEquals(expected,actual);
        } finally {collector.stop();}
    }
}
