package com.award.log.security.effect;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** 预览冻结的完整文件候选集；目录快照包含全部后代，不跟随链接。 */
public record FileApprovalManifest(Map<String, Target> targets, String digest) {
    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
    public FileApprovalManifest { targets = Map.copyOf(targets); }

    public static FileApprovalManifest capture(List<Path> paths, int maxEntries, long maxBytes) throws IOException {
        Map<String,Target> targets = new TreeMap<>();
        long[] budget = {Math.max(1,maxEntries),Math.max(0,maxBytes)};
        for (Path path : paths) {
            String key=normalize(path);
            if (targets.containsKey(key)) throw new IOException("审批候选路径重复");
            targets.put(key,captureTarget(path,budget));
        }
        return new FileApprovalManifest(targets,sha(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(targets)));
    }

    private static Target captureTarget(Path path, long[] budget) throws IOException {
        Path root=path.toAbsolutePath().normalize();
        if (!root.toRealPath().equals(root)) throw new IOException("审批路径含链接或路径重定向: "+root);
        Map<String,Identity> entries=new TreeMap<>();
        try (var stream=Files.walk(root)) {
            var iterator=stream.iterator();
            while(iterator.hasNext()) {
                Path item=iterator.next();
                if (--budget[0]<0) throw new IOException("审批对象过多，请缩小路径范围");
                if(!item.toRealPath().equals(item.toAbsolutePath().normalize())) throw new IOException("目录中存在路径重定向: "+item);
                var before=Files.readAttributes(item,BasicFileAttributes.class,NOFOLLOW);
                if (before.isSymbolicLink() || (!before.isRegularFile() && !before.isDirectory()))
                    throw new IOException("对象身份不可验证或为链接: "+item);
                String beforeKey=objectKey(item,before);
                String content="";
                if(before.isRegularFile()) {
                    budget[1]-=before.size();
                    if(budget[1]<0) throw new IOException("审批内容超过摘要预算，请缩小路径范围");
                    try(var in=Files.newInputStream(item,LinkOption.NOFOLLOW_LINKS)) {
                        MessageDigest md=digestInstance(); byte[] buffer=new byte[64*1024]; int n;
                        long read=0;
                        while((n=in.read(buffer))!=-1) {
                            read+=n;
                            if(read>before.size()) throw new IOException("对象在预览期间增长: "+item);
                            md.update(buffer,0,n);
                        }
                        if(read!=before.size()) throw new IOException("对象在预览期间变化: "+item);
                        content=HexFormat.of().formatHex(md.digest());
                    }
                }
                var after=Files.readAttributes(item,BasicFileAttributes.class,NOFOLLOW);
                Identity identity=identity(objectKey(item,after),after,content);
                if(!identity(beforeKey,before,content).equals(identity)) throw new IOException("对象在采样期间变化: "+item);
                entries.put(root.relativize(item).toString(),identity);
            }
        } catch(java.io.UncheckedIOException e) { throw e.getCause(); }
        return new Target(Collections.unmodifiableMap(entries));
    }

    private static String objectKey(Path path,BasicFileAttributes attributes) throws IOException {
        if(attributes.fileKey()!=null) return attributes.fileKey().toString();
        if(!com.sun.jna.Platform.isWindows()) throw new IOException("文件系统不提供稳定对象身份");
        var kernel=com.sun.jna.platform.win32.Kernel32.INSTANCE;
        var handle=kernel.CreateFile(path.toString(),com.sun.jna.platform.win32.WinNT.FILE_READ_ATTRIBUTES,
                com.sun.jna.platform.win32.WinNT.FILE_SHARE_READ|com.sun.jna.platform.win32.WinNT.FILE_SHARE_WRITE|com.sun.jna.platform.win32.WinNT.FILE_SHARE_DELETE,
                null,com.sun.jna.platform.win32.WinNT.OPEN_EXISTING,
                com.sun.jna.platform.win32.WinNT.FILE_FLAG_BACKUP_SEMANTICS|com.sun.jna.platform.win32.WinNT.FILE_FLAG_OPEN_REPARSE_POINT,null);
        if(com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE.equals(handle))
            throw new IOException("无法读取 Windows 文件身份，错误码="+kernel.GetLastError());
        try {
            var info=new com.sun.jna.platform.win32.WinBase.FILE_ID_INFO();
            if(!kernel.GetFileInformationByHandleEx(handle,com.sun.jna.platform.win32.WinBase.FileIdInfo,info.getPointer(),
                    new com.sun.jna.platform.win32.WinDef.DWORD(info.size())))
                throw new IOException("Windows 文件系统不支持 FileIdInfo，错误码="+kernel.GetLastError());
            info.read();
            return "windows:"+Long.toUnsignedString(info.VolumeSerialNumber)+":"+HexFormat.of().formatHex(info.FileId.getPointer().getByteArray(0,16));
        } finally { kernel.CloseHandle(handle); }
    }

    private static Identity identity(String key,BasicFileAttributes attrs,String content) {
        return new Identity(key,attrs.creationTime().toString(),attrs.isDirectory(),
                attrs.isDirectory()?0:attrs.size(),attrs.isDirectory()?"":attrs.lastModifiedTime().toString(),content);
    }
    public void verifyAll(int maxEntries,long maxBytes) throws IOException {
        var current=capture(targets.keySet().stream().map(Path::of).toList(),maxEntries,maxBytes);
        if(!digest.equals(current.digest())) throw new IOException("审批对象已变化，请重新预览确认");
    }
    public void verifySelection(List<Path> paths) throws IOException {
        Set<String> current=new HashSet<>(); for(Path path:paths) current.add(normalize(path));
        if(!current.equals(targets.keySet())) throw new IOException("审批候选集合已变化，请重新预览确认");
    }
    public Target target(Path path) throws IOException {
        Target target=targets.get(normalize(path));
        if(target==null) throw new IOException("对象不在审批清单中，拒绝写入");
        return target;
    }
    public Map<String,Object> summary() {
        return Map.of("digest",digest,"targetCount",targets.size(),"entryCount",targets.values().stream().mapToInt(t->t.entries().size()).sum(),
                "binding","FILE_KEY_AND_SHA256");
    }
    private static String normalize(Path path) { return path.toAbsolutePath().normalize().toString(); }
    private static String sha(byte[] bytes) { return HexFormat.of().formatHex(digestInstance().digest(bytes)); }
    private static MessageDigest digestInstance() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public record Identity(String fileKey,String created,boolean directory,long bytes,String modified,String sha256) { }
    public record Target(Map<String,Identity> entries) {
        public Target { entries=Collections.unmodifiableMap(new TreeMap<>(entries)); }
        public void verify(Path path,int maxEntries,long maxBytes) throws IOException {
            if(!entries.equals(captureTarget(path,new long[]{maxEntries,maxBytes}).entries()))
                throw new IOException("移动对象与审批对象不一致");
        }
    }
}
