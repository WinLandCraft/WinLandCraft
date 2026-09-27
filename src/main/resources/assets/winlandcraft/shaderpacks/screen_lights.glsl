#if !defined INCLUDE_WINLANDCRAFT_SCREEN_LIGHTS
#define INCLUDE_WINLANDCRAFT_SCREEN_LIGHTS

// Basic/textured programs also draw late Fabric geometry; they must not depend on this binding.
#if (defined GBUFFERS_TERRAIN || defined GBUFFERS_BLOCK || defined GBUFFERS_ENTITIES || defined GBUFFERS_WATER) && !defined DH_TERRAIN && !defined DH_WATER && !defined VOXY_OPAQUE && !defined VOXY_TRANSLUCENT
#extension GL_ARB_shader_storage_buffer_object : enable

struct WinLandCraftScreen {
    vec4 centerHalfWidth;
    vec4 rightHalfHeight;
    vec4 upRange;
    vec4 normalIntensity;
    vec4 curveData;
    vec4 averageColor;
    vec4 colors[6];
};

layout(std430, binding = 7) readonly restrict buffer WinLandCraftScreenLights {
    uint winlandcraft_screenCount;
    uint winlandcraft_screenLightPad0;
    uint winlandcraft_screenLightPad1;
    uint winlandcraft_screenLightPad2;
    WinLandCraftScreen winlandcraft_screens[];
};

vec3 winlandcraft_screenColor(WinLandCraftScreen screen,vec2 uv,float distanceToScreen) {
    vec2 grid=clamp(uv*vec2(3.0,2.0)-0.5,vec2(0.0),vec2(2.0,1.0));
    int left=min(int(floor(grid.x)),1);
    float horizontalBlend=smoothstep(0.0,1.0,grid.x-float(left));
    float verticalBlend=smoothstep(0.0,1.0,grid.y);
    vec3 bottom=mix(screen.colors[left].rgb,screen.colors[left+1].rgb,horizontalBlend);
    vec3 top=mix(screen.colors[left+3].rgb,screen.colors[left+4].rgb,horizontalBlend);
    vec3 local=mix(bottom,top,verticalBlend);
    float halfDiagonal=length(max(vec2(screen.centerHalfWidth.w,screen.rightHalfHeight.w),vec2(0.0001)));
    float diffusion=mix(0.15,1.0,smoothstep(0.0,max(halfDiagonal*1.5,0.75),distanceToScreen));
    return mix(local,screen.averageColor.rgb,diffusion);
}

vec3 winlandcraft_screenLighting(vec3 fragmentPosition, vec3 normal) {
    vec3 lighting=vec3(0.0);
    uint count=min(winlandcraft_screenCount,2u);
    for(uint index=0u;index<count;index++) {
        WinLandCraftScreen screen=winlandcraft_screens[index];
        vec2 halfSize=max(vec2(screen.centerHalfWidth.w,screen.rightHalfHeight.w),vec2(0.0001));
        vec3 relative=fragmentPosition-screen.centerHalfWidth.xyz;
        float vertical=clamp(dot(relative,screen.upRange.xyz),-halfSize.y,halfSize.y);
        float horizontal;
        vec3 sourceNormal=screen.normalIntensity.xyz;
        vec3 source;
        if(screen.curveData.x>0.0) {
            // Match GroupCurve's bounded cylinder; horizontal remains arc length for stable UVs.
            float radius=screen.curveData.x;
            float facing=screen.curveData.y;
            vec3 cylinderCenter=screen.centerHalfWidth.xyz+sourceNormal*(facing*radius);
            vec3 radial=fragmentPosition-cylinderCenter;
            float angle=atan(dot(radial,screen.rightHalfHeight.xyz),-facing*dot(radial,sourceNormal));
            angle=clamp(angle,-halfSize.x/radius,halfSize.x/radius);
            horizontal=angle*radius;
            source=screen.centerHalfWidth.xyz
                +screen.rightHalfHeight.xyz*(radius*sin(angle))
                +sourceNormal*(facing*radius*(1.0-cos(angle)));
            sourceNormal=sourceNormal*cos(angle)-screen.rightHalfHeight.xyz*(facing*sin(angle));
        } else {
            horizontal=clamp(dot(relative,screen.rightHalfHeight.xyz),-halfSize.x,halfSize.x);
            source=screen.centerHalfWidth.xyz+screen.rightHalfHeight.xyz*horizontal;
        }
        source+=screen.upRange.xyz*vertical;
        vec3 fromLight=fragmentPosition-source;
        float distanceToLight=length(fromLight);
        if(distanceToLight>=screen.upRange.w||distanceToLight<0.001)continue;
        vec3 lightRay=fromLight/distanceToLight;
        float emission=max(dot(lightRay,sourceNormal),0.0);
        if(emission==0.0)continue;
        float diffuse=max(dot(normal,-lightRay)*0.9+0.1,0.0);
        float rangeFraction=distanceToLight/screen.upRange.w;
        float rangeSquared=rangeFraction*rangeFraction;
        float rangeWindow=1.0-rangeSquared*rangeSquared;
        rangeWindow*=rangeWindow;
        float area=4.0*halfSize.x*halfSize.y;
        float projectedArea=area*emission;
        float formFactor=projectedArea/(projectedArea+3.14159265*distanceToLight*distanceToLight);
        vec2 uv=vec2(horizontal/halfSize.x,vertical/halfSize.y)*0.5+0.5;
        vec3 color=winlandcraft_screenColor(screen,uv,distanceToLight);
        lighting+=color*(screen.normalIntensity.w*diffuse*rangeWindow*formFactor);
    }
    return lighting;
}

#else
vec3 winlandcraft_screenLighting(vec3 fragmentPosition, vec3 normal) { return vec3(0.0); }
#endif

#endif
