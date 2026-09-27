package dev.winlandcraft.api.v2;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
/** Immutable registration; use the builder so optional additions preserve the v2 ABI. */
public final class AppDefinition {
    private final String id,name,icon;
    private final AppKind kind;
    private final int width,height;
    private final Set<String> extensions,names;
    private final Supplier<? extends App> factory;
    private AppDefinition(Builder b){id=b.id;name=b.name;icon=b.icon;kind=b.kind;width=b.width;height=b.height;extensions=Set.copyOf(b.extensions);names=Set.copyOf(b.names);factory=b.factory;}
    public static Builder builder(String id,String name,AppKind kind,Supplier<? extends App> factory){return new Builder(id,name,kind,factory);}
    public String id(){return id;} public String name(){return name;} public String icon(){return icon;}
    public AppKind kind(){return kind;} public int width(){return width;} public int height(){return height;}
    public Set<String> extensions(){return extensions;} public Set<String> fileNames(){return names;}
    public App create(){return Objects.requireNonNull(factory.get(),"App factory returned null");}
    public boolean accepts(Path path){String n=path.getFileName().toString().toLowerCase(Locale.ROOT);int dot=n.lastIndexOf('.');return names.contains(n)||dot>=0&&extensions.contains(n.substring(dot+1));}
    public static final class Builder {
        private final String id,name;private final AppKind kind;private final Supplier<? extends App> factory;
        private String icon;private int width=1280,height=720;
        private final Set<String> extensions=new LinkedHashSet<>(),names=new LinkedHashSet<>();
        private Builder(String id,String name,AppKind kind,Supplier<? extends App> factory){
            if(id==null||!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Expected namespace:app_id");
            if(name==null||name.isBlank()||name.length()>128)throw new IllegalArgumentException("App name must contain 1-128 characters");
            for(String part:id.substring(id.indexOf(':')+1).split("/"))if(part.equals(".")||part.equals(".."))throw new IllegalArgumentException("Invalid app id path");
            this.id=id;this.name=name;this.kind=Objects.requireNonNull(kind);this.factory=Objects.requireNonNull(factory);
        }
        public Builder icon(String resource){if(resource==null||!resource.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Expected texture resource identifier");icon=resource;return this;}
        public Builder size(int width,int height){if(width<320||height<180||width>4096||height>4096)throw new IllegalArgumentException("Initial viewport must be 320x180 through 4096x4096");this.width=width;this.height=height;return this;}
        public Builder extensions(String... values){for(String value:values){String v=Objects.requireNonNull(value).toLowerCase(Locale.ROOT);if(v.startsWith("."))v=v.substring(1);if(!v.matches("[a-z0-9_-]+"))throw new IllegalArgumentException("Invalid extension");extensions.add(v);}return this;}
        public Builder fileNames(String... values){for(String value:values){if(value==null||value.isBlank()||value.contains("/")||value.contains("\\"))throw new IllegalArgumentException("Expected file name");names.add(value.toLowerCase(Locale.ROOT));}return this;}
        public AppDefinition build(){return new AppDefinition(this);}
    }
}
