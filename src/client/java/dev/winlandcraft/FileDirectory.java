package dev.winlandcraft;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Read-only filesystem model. Call from the directory worker, never from rendering. */
final class FileDirectory {
    record Entry(Path path,String name,boolean directory,boolean link,long size,long modified) {}
    record Location(String name,Path path) {}
    record Listing(List<Entry> entries,List<Location> locations,boolean truncated,String error) {}
    static Path home(){return Path.of(System.getProperty("user.home",".")).toAbsolutePath().normalize();}
    static Listing read(Path path) {
        var entries=new ArrayList<Entry>();boolean truncated=false;
        try(var stream=Files.newDirectoryStream(path)) {
            for(var file:stream) {
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();
                if(entries.size()>=10000){truncated=true;break;}
                boolean link=Files.isSymbolicLink(file);
                try {
                    var attr=Files.readAttributes(file,BasicFileAttributes.class);
                    entries.add(new Entry(file,file.getFileName().toString(),attr.isDirectory(),link,attr.size(),attr.lastModifiedTime().toMillis()));
                } catch(java.io.IOException | SecurityException unavailable) {
                    entries.add(new Entry(file,file.getFileName().toString(),false,link,-1,0));
                }
            }
            entries.sort(Comparator.comparing(Entry::directory).reversed().thenComparing(Entry::name,String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::name));
            return new Listing(List.copyOf(entries),locations(),truncated,"");
        } catch(Exception error) {
            String message=error instanceof AccessDeniedException||error instanceof SecurityException?"Access denied.":error instanceof NoSuchFileException?"Folder not found.":error instanceof NotDirectoryException?"This path is not a folder.":"Could not read this folder.";
            return new Listing(List.of(),locations(),false,message);
        }
    }
    static List<Location> locations() {
        Path home=home();var result=new ArrayList<Location>();result.add(new Location("Home",home));
        Map<String,Path> configured=new HashMap<>();
        if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("linux")) {
            String config=System.getenv("XDG_CONFIG_HOME");
            Path directory=config==null||config.isBlank()?home.resolve(".config"):Path.of(config);
            try {for(String line:Files.readAllLines(directory.resolve("user-dirs.dirs"))) {
                var match=java.util.regex.Pattern.compile("XDG_([A-Z]+)_DIR=\"([^\"]*)\"").matcher(line.trim());
                if(match.matches()) {
                    String value=match.group(2).replace("$HOME",home.toString());
                    if(!value.contains("$")&&Path.of(value).isAbsolute())configured.put(match.group(1),Path.of(value));
                }
            }} catch(Exception ignored){}
        }
        String[] names={"Desktop","Downloads","Documents","Pictures","Music","Videos"};
        for(String name:names) {
            String key=name.equals("Downloads")?"DOWNLOAD":name.toUpperCase(Locale.ROOT);
            Path path=configured.getOrDefault(key,home.resolve(name));
            if(name.equals("Videos")&&!Files.isDirectory(path)&&Files.isDirectory(home.resolve("Movies")))path=home.resolve("Movies");
            if(Files.isDirectory(path))result.add(new Location(name,path));
        }
        for(Path root:FileSystems.getDefault().getRootDirectories())result.add(new Location(root.toString(),root));
        return List.copyOf(result);
    }
}
