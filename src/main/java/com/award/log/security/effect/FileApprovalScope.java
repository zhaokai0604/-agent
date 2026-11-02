package com.award.log.security.effect;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** 只由服务端执行管线设置；外部工具参数不能注入审批清单。 */
public final class FileApprovalScope implements AutoCloseable {
    private static final ThreadLocal<FileApprovalScope> CURRENT=new ThreadLocal<>();
    private final FileApprovalScope previous;
    private final boolean capture;
    private final int maxEntries;
    private final long maxBytes;
    private FileApprovalManifest manifest;
    private boolean selected;
    private String failure="";
    private FileApprovalScope(boolean capture,FileApprovalManifest manifest,int maxEntries,long maxBytes) {
        this.previous=CURRENT.get(); this.capture=capture; this.manifest=manifest;
        this.maxEntries=maxEntries; this.maxBytes=maxBytes; CURRENT.set(this);
    }
    public static FileApprovalScope capture(int maxEntries,long maxBytes) { return new FileApprovalScope(true,null,maxEntries,maxBytes); }
    public static FileApprovalScope execute(FileApprovalManifest manifest,int maxEntries,long maxBytes) throws IOException {
        manifest.verifyAll(maxEntries,maxBytes);
        return new FileApprovalScope(false,manifest,maxEntries,maxBytes);
    }
    public static void candidates(List<Path> paths) throws IOException {
        var scope=CURRENT.get(); if(scope==null) return;
        if(scope.selected) throw new IOException("一次审批不能混用多个候选扫描");
        if(scope.capture) scope.manifest=FileApprovalManifest.capture(paths,scope.maxEntries,scope.maxBytes);
        else scope.manifest.verifySelection(paths);
        scope.selected=true;
    }
    public static void reject(String reason) {
        var scope=CURRENT.get(); if(scope!=null && !scope.capture) scope.failure=reason==null?"审批执行未完成":reason;
    }
    public String failure() { return failure; }

    public static List<String> frozenCandidates() {
        var scope=CURRENT.get();
        return scope==null || scope.capture ? null : scope.manifest.targets().keySet().stream().sorted().toList();
    }

    public static FileApprovalManifest.Target expected(Path path) throws IOException {
        var scope=CURRENT.get();
        if(scope==null || scope.capture) return null;
        if(!scope.selected) throw new IOException("实写前尚未复核审批候选集合");
        return scope.manifest.target(path);
    }
    public FileApprovalManifest manifest() { return manifest; }
    public boolean selected() { return selected; }
    @Override public void close() { if(previous==null) CURRENT.remove(); else CURRENT.set(previous); }
}
