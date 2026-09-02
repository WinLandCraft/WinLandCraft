package dev.winlandcraft;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Creates a non-destructive Solas copy containing WinLandCraft's light hook. */
final class SolasShaderPatcher {
    private static final String LIGHTING="shaders/lib/lighting/gbuffersLighting.glsl";
    private static final String PROPERTIES="shaders/shaders.properties";
    private static final String INCLUDE="shaders/lib/winlandcraft/screen_lights.glsl";
    private static final String MARKER="WINLANDCRAFT_SCREEN_LIGHTS";
    private static final String FUNCTION="void gbuffersLighting(";
    private static final String RESULT="    vec3 finalColor = diffuseAlbedo + specularHighlight * vanillaDiffuse;";

    record Result(boolean success,String message,Path output){}

    private SolasShaderPatcher(){}

    static Result install() {
        Path directory=FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
        try {
            Files.createDirectories(directory);
        } catch(IOException error) {
            return failed(error);
        }
        try(var files=Files.list(directory)) {
            Path source=files.filter(Files::isRegularFile).filter(SolasShaderPatcher::unpatchedSolas)
                    .max(Comparator.comparingLong(SolasShaderPatcher::modified)).orElse(null);
            if(source==null)return new Result(false,"No unmodified Solas .zip was found.",null);
            String name=source.getFileName().toString();
            Path output=source.resolveSibling(name.substring(0,name.length()-4)+" + WinLandCraft.zip");
            patch(source,output);
            return new Result(true,"Created "+output.getFileName()+". Select it in Iris.",output);
        } catch(Exception error) {
            return failed(error);
        }
    }

    private static Result failed(Exception error) {
        WinLandCraftClient.LOGGER.error("Could not create the Solas screen-lighting pack",error);
        return new Result(false,"Could not patch Solas; see latest.log.",null);
    }

    private static boolean unpatchedSolas(Path path) {
        String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".zip")&&name.contains("solas")&&!name.contains("winlandcraft");
    }

    private static long modified(Path path) {
        try{return Files.getLastModifiedTime(path).toMillis();}catch(IOException ignored){return 0;}
    }

    static void patch(Path source,Path output) throws IOException {
        if(source.equals(output))throw new IOException("Source and destination shader packs must differ");
        Path temporary=Files.createTempFile(output.getParent(),"winlandcraft-solas-",".zip");
        boolean moved=false;
        try {
            try(ZipFile input=new ZipFile(source.toFile());ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(temporary))) {
                Set<String> seen=new HashSet<>();
                var entries=input.entries();
                while(entries.hasMoreElements()) {
                    ZipEntry entry=entries.nextElement();
                    String name=entry.getName();
                    if(!seen.add(name)||name.equals(INCLUDE))continue;
                    ZipEntry copy=new ZipEntry(name);copy.setTime(entry.getTime());zip.putNextEntry(copy);
                    if(!entry.isDirectory())try(InputStream data=input.getInputStream(entry)) {
                        if(name.equals(LIGHTING))zip.write(patchLighting(new String(data.readAllBytes(),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                        else if(name.equals(PROPERTIES))zip.write(patchProperties(new String(data.readAllBytes(),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                        else data.transferTo(zip);
                    }
                    zip.closeEntry();
                }
                if(!seen.contains(LIGHTING)||!seen.contains(PROPERTIES))throw new IOException("Solas lighting files are missing");
                ZipEntry include=new ZipEntry(INCLUDE);zip.putNextEntry(include);zip.write(includeSource());zip.closeEntry();
            }
            try {
                Files.move(temporary,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);moved=true;
            } catch(AtomicMoveNotSupportedException ignored) {
                Files.move(temporary,output,StandardCopyOption.REPLACE_EXISTING);moved=true;
            }
        } finally {
            if(!moved)Files.deleteIfExists(temporary);
        }
    }

    static String patchLighting(String source) throws IOException {
        if(source.contains(MARKER))return source;
        source=insert(source,FUNCTION,"#define "+MARKER+"\n#include \"/lib/winlandcraft/screen_lights.glsl\"\n\n",false);
        return insert(source,RESULT,"\n\n    finalColor += albedo.rgb * (1.0 - metalness) * max(1.0 - emission, 0.0)\n        * winlandcraft_screenLighting(worldPos, worldNormal);",true);
    }

    static String patchProperties(String source) {
        String[] lines=source.split("\\R",-1);
        for(int i=0;i<lines.length;i++)if(lines[i].startsWith("iris.features.optional=")) {
            if(!(" "+lines[i].substring(lines[i].indexOf('=')+1)+" ").contains(" SSBO "))lines[i]+=" SSBO";
            return String.join("\n",lines);
        }
        return "iris.features.optional=SSBO\n"+source;
    }

    private static String insert(String source,String anchor,String addition,boolean after) throws IOException {
        int first=source.indexOf(anchor);
        if(first<0||source.indexOf(anchor,first+anchor.length())>=0)throw new IOException("Solas patch anchor is missing or ambiguous: "+anchor);
        int at=after?first+anchor.length():first;
        return source.substring(0,at)+addition+source.substring(at);
    }

    private static byte[] includeSource() throws IOException {
        try(InputStream input=SolasShaderPatcher.class.getResourceAsStream("/assets/winlandcraft/shaderpacks/screen_lights.glsl")) {
            if(input==null)throw new IOException("Bundled screen-light shader is missing");
            return input.readAllBytes();
        }
    }
}
