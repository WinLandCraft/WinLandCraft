package dev.winlandcraft;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ScreenLightingChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        zonesKeepTheirScreenPosition();
        calibrationSuppressesBlackAndBoostsChroma();
        lightingControlsHavePhysicalBaselines();
        rangeFollowsPhysicalPanelSize();
        smoothingIsBounded();
        patchTextIsStrictAndIdempotent();
        panelProgramsDoNotReadTheLightBuffer();
        archivePatchIsNonDestructive();
        System.out.println("Screen lighting: "+checks+" sampling and Solas patch checks passed.");
    }

    private static void lightingControlsHavePhysicalBaselines() {
        require(close(ModSettings.DEFAULT_SCREEN_LIGHT_INTENSITY,3.5f),"default power is not a true 3.5x multiplier");
        require(close(ModSettings.MIN_SCREEN_LIGHT_RANGE,24f),"minimum light range is not 24 blocks");
        require(close(ModSettings.nextScreenLightIntensity(3f),3.5f),"power control skipped its default");
        require(close(ModSettings.nextScreenLightIntensity(8f),.5f),"power control does not wrap");
        require(close(ModSettings.nextScreenLightRange(12f),24f),"range control permits less than 24 blocks");
        require(close(ModSettings.nextScreenLightRange(24f),32f),"range control did not advance above its base");
        require(close(ModSettings.nextScreenLightRange(64f),24f),"range control does not wrap to its base");
    }

    private static void rangeFollowsPhysicalPanelSize() {
        require(close(ScreenLighting.effectiveRange(3.2f,1.8f,24f),24f),"reference browser changed the configured range");
        require(close(ScreenLighting.effectiveRange(.8f,.6f,24f),24f),"small panels fell below the configured range");
        require(close(ScreenLighting.effectiveRange(12.8f,7.2f,24f),48f),"large panel range did not scale with its diagonal");
        require(close(ScreenLighting.effectiveRange(320f,180f,24f),96f),"unbounded panel range escaped its safety cap");
    }

    private static void calibrationSuppressesBlackAndBoostsChroma() {
        float[] result=new float[3];
        ScreenColorSampler.calibrate(result,0,.2f,.1f,.1f);
        float beforeLuminance=.2f*.2126f+.1f*.7152f+.1f*.0722f;
        float afterLuminance=result[0]*.2126f+result[1]*.7152f+result[2]*.0722f;
        require(close(beforeLuminance,afterLuminance),"saturation changed emitted luminance");
        require((result[0]-result[1])/afterLuminance>.1f/beforeLuminance,"screen color was not strengthened");
        ScreenColorSampler.calibrate(result,0,.001f,.001f,.001f);
        require(close(result[0],0)&&close(result[1],0)&&close(result[2],0),"black level still emits light");
        ScreenColorSampler.calibrate(result,0,.02f,.02f,.02f);
        require(result[0]>.01f&&result[0]<.02f,"dark scene did not retain a dimmed mood-light contribution");
        ScreenColorSampler.calibrate(result,0,.2f,.2f,.2f);
        require(close(result[0],.2f),"bright scene did not retain its energy");
    }

    private static void zonesKeepTheirScreenPosition() {
        int width=6,height=4;
        ByteBuffer pixels=ByteBuffer.allocateDirect(width*height*4).order(ByteOrder.nativeOrder());
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int zone=(y/(height/2))*3+x/(width/3);
            int offset=(y*width+x)*4;
            pixels.put(offset,(byte)(zone==0?255:0));
            pixels.put(offset+1,(byte)(zone==4?255:0));
            pixels.put(offset+2,(byte)(zone==5?255:0));
            pixels.put(offset+3,(byte)255);
        }
        float[] colors=ScreenColorSampler.zones(pixels,width,height);
        require(close(colors[0],1)&&close(colors[1],0)&&close(colors[2],0),"bottom-left zone moved");
        require(close(colors[12+1],1),"top-middle zone moved");
        require(close(colors[15+2],1),"top-right zone moved");
    }

    private static void smoothingIsBounded() {
        float[] previous={0,1,.5f},sampled={1,0,.5f};
        float[] result=ScreenLighting.smooth(previous,sampled);
        require(close(result[0],.35f)&&close(result[1],.65f)&&close(result[2],.5f),"unexpected temporal smoothing");
        require(ScreenLighting.smooth(null,sampled)==sampled,"first sample should not fade from black");
    }

    private static void patchTextIsStrictAndIdempotent() throws Exception {
        String lighting="header\nvoid gbuffersLighting() {\n"+
                "    vec3 finalColor = diffuseAlbedo + specularHighlight * vanillaDiffuse;\n}\n";
        String patched=SolasShaderPatcher.patchLighting(lighting);
        require(patched.contains("WINLANDCRAFT_SCREEN_LIGHTS"),"lighting marker missing");
        require(patched.contains("winlandcraft_screenLighting(worldPos, worldNormal)"),"lighting call missing");
        require(SolasShaderPatcher.patchLighting(patched).equals(patched),"lighting patch is not idempotent");
        require(SolasShaderPatcher.patchProperties("iris.features.optional=CUSTOM_IMAGES\n").contains("CUSTOM_IMAGES SSBO"),"SSBO feature missing");
        require(SolasShaderPatcher.patchProperties("iris.features.optional=SSBO\n").equals("iris.features.optional=SSBO\n"),"SSBO feature duplicated");
    }

    private static void panelProgramsDoNotReadTheLightBuffer() throws Exception {
        try(var input=ScreenLightingChecks.class.getResourceAsStream("/assets/winlandcraft/shaderpacks/screen_lights.glsl")) {
            require(input!=null,"screen-light shader resource missing");
            String shader=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            require(shader.contains("defined GBUFFERS_TERRAIN")&&shader.contains("defined GBUFFERS_ENTITIES"),"world lighting passes are not selected");
            require(!shader.contains("defined GBUFFERS_BASIC ||")&&!shader.contains("defined GBUFFERS_TEXTURED ||"),"late panel passes must not read the SSBO");
            require(shader.contains("WinLandCraftScreen screen")&&shader.contains("distanceToScreen")&&shader.contains("averageColor"),"area-light model is missing");
            require(shader.contains("smoothstep(0.0,1.0,grid.x-float(left))")&&!shader.contains("fract(grid.x)"),"color interpolation is not smooth or can wrap at the right edge");
            require(shader.contains("rangeSquared*rangeSquared")&&shader.contains("projectedArea/(projectedArea+3.14159265"),"finite-area falloff model is missing");
            require(shader.contains("vec4 curveData")&&shader.contains("float angle=atan(")&&shader.contains("radius*sin(angle)"),"cylindrical area-light projection is missing");
        }
    }

    private static void archivePatchIsNonDestructive() throws Exception {
        var directory=Files.createTempDirectory("winlandcraft-screen-light-check");
        var source=directory.resolve("Solas.zip");
        var output=directory.resolve("Solas + WinLandCraft.zip");
        String lighting="void gbuffersLighting() {\n"+
                "    vec3 finalColor = diffuseAlbedo + specularHighlight * vanillaDiffuse;\n}\n";
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(source))) {
            entry(zip,"shaders/lib/lighting/gbuffersLighting.glsl",lighting.getBytes(StandardCharsets.UTF_8));
            entry(zip,"shaders/shaders.properties","profile=HIGH\n".getBytes(StandardCharsets.UTF_8));
            entry(zip,"untouched.bin",new byte[]{1,2,3});
        }
        byte[] original=Files.readAllBytes(source);
        SolasShaderPatcher.patch(source,output);
        require(java.util.Arrays.equals(original,Files.readAllBytes(source)),"source archive was modified");
        try(ZipFile zip=new ZipFile(output.toFile())) {
            require(zip.getEntry("shaders/lib/winlandcraft/screen_lights.glsl")!=null,"shader include missing");
            require(text(zip,"shaders/shaders.properties").startsWith("iris.features.optional=SSBO"),"properties were not patched");
            require(text(zip,"shaders/lib/lighting/gbuffersLighting.glsl").contains("WINLANDCRAFT_SCREEN_LIGHTS"),"archive lighting hook missing");
            require(java.util.Arrays.equals(zip.getInputStream(zip.getEntry("untouched.bin")).readAllBytes(),new byte[]{1,2,3}),"unrelated entry changed");
        }
        Files.delete(output);Files.delete(source);Files.delete(directory);
    }

    private static void entry(ZipOutputStream zip,String name,byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();
    }

    private static String text(ZipFile zip,String name) throws Exception {
        return new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(),StandardCharsets.UTF_8);
    }

    private static boolean close(float a,float b){return Math.abs(a-b)<.0001f;}
    private static void require(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
