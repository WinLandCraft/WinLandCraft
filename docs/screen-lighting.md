# Screen lighting

WinLandCraft can turn Browser, webapp, and remote-stream surfaces into low-cost colored area lights. The implementation targets Iris shader packs because Minecraft's vanilla light engine stores one scalar block-light value, not RGB light, and cannot shade nearby geometry with the screen's changing colors. Without Iris or a compatible pack, panels retain their normal emissive appearance but do not illuminate the world.

## Rendering path

`PanelSurface` already composites each complete window into one mipmapped OpenGL texture. Every 100 ms, `ScreenColorSampler` reads a tiny mip level (at most 6×4 pixels) through one of two pixel-buffer objects. A fence is polled with a zero timeout on later frames, so the render thread never waits for the GPU. If both slots are busy, the sample is skipped instead of building latency.

The composition pass temporarily uses Iris's public rendering-state escape hatch: shader-pack program substitution and the extended world vertex format are disabled only while WinLandCraft draws into its private framebuffer. The previous Iris state is restored before the resulting surface is submitted to the world, so Solas still shades the panel normally. Without that boundary, Iris treats the private framebuffer as part of its deferred world pipeline and the composed surface can disappear entirely.

The pixels are converted from sRGB to linear light and averaged into a 3×2 grid. Near-black samples are suppressed so a dark grey page does not behave like a lamp, while a luminance-preserving saturation adjustment keeps colorful scenes from washing toward white. The black transition reaches full output at 3% linear luminance, retaining some mood light in genuinely dark scenes without making black bars glow. Temporal smoothing prevents cuts and flashing pages from producing harsh single-frame changes. The closest two illuminated panels contribute one area emitter each; the six samples describe a smooth color field across each flat or curved surface rather than six separate bulbs.

Immediately after vanilla updates the frame camera—but before Iris begins its world pipeline—the zones become camera-relative area-light samples in one shader-storage buffer. This placement is deliberate: a Fabric world-render callback runs after Iris has claimed its indexed GL bindings, so uploading there can corrupt later shader-pack draws. When lighting is disabled, the hook performs no GL work.

| Offset | Data |
| --- | --- |
| 0 | Panel count followed by three padding integers |
| 16 + 192n | Camera-relative center XYZ and half width |
| 32 + 192n | Unit right vector XYZ and half height |
| 48 + 192n | Unit up vector XYZ and effective range |
| 64 + 192n | Unit front normal XYZ and intensity |
| 80 + 192n | Cylinder radius, bend facing, and padding |
| 96 + 192n | Average linear RGB and padding |
| 112 + 192n | Six padded linear-RGB color samples |

The buffer uses `std430` layout at binding 7 and contains no more than two panels. For each fragment, the shader finds the nearest point on the screen rectangle, interpolates the local 3×2 color field with continuous-slope cubic weights, and blends toward the whole-screen average as distance diffuses the image. A small amount of that diffusion is present even at the surface, avoiding six hard color islands. Horizontal interpolation derives its weight from the selected segment rather than taking the fractional part of the clamped grid coordinate: the latter wraps at the right endpoint and creates a visible color seam.

The diffuse area-light approximation follows the *most representative point* approach described in [Moving Frostbite to Physically Based Rendering 3.0](https://cgvr.cs.uni-bremen.de/teaching/cg_literatur/Moving%20Frostbite%20to%20Physically%20Based%20Rendering%203.0%2C%202014%2C%20Sebastien%20Lagarde.pdf). The panel's projected area is converted to a disk-equivalent form factor, so a large nearby display fills more of a surface's visible hemisphere while edge-on emission naturally fades. A wrapped diffuse response keeps the result soft. Range uses the finite quartic window documented by [Filament's physically based renderer](https://google.github.io/filament/Filament.md.html): it preserves useful energy through most of the selected radius, reaches exactly zero at the boundary, and has no abrupt edge. Together these make the panel behave like one extended luminous surface instead of three or six point-light hotspots. The source lies on the visible surface rather than floating in front of it.

Flat panels use the nearest point on their rectangle. Curved panels use the same cylindrical radius, facing, tangent frame, and 110-degree maximum arc as `GroupCurve`. The shader analytically projects each fragment onto that bounded cylinder, then derives the source position, screen UV, and local emitting normal from the resulting angle. This avoids both the incorrect flat rectangle and a faceted row of independent light strips; curve quality does not depend on panel resolution or a subdivision count.

An exact glossy rectangular-light solution such as [linearly transformed cosines](https://eheitzresearch.wordpress.com/415-2/) would require BRDF lookup tables and pack-specific specular integration. WinLandCraft deliberately keeps the cheaper diffuse model: two short arithmetic evaluations, no per-light texture lookup, and no additional render pass. Proper occlusion would likewise require shadow-map or scene-depth integration that the shared Solas material hook does not expose. Light can therefore pass through walls.

Only Solas's terrain, block, entity, and water material programs reference the buffer. Its basic, textured, and hand programs remain untouched so late Fabric world geometry—including WinLandCraft's own panels—cannot inherit or depend on the light-buffer binding. The implementation deliberately does not allocate shadow maps, voxelize the world, trace rays, or perform synchronous full-resolution texture readback.

IRLights also owns binding 7 and patches the same Solas resources. WinLandCraft disables its own bridge when `irl-core` is present rather than letting two incompatible layouts alias one binding.

## Solas integration

Iris cannot merge arbitrary mod fragment code into an active third-party shader pack. WinLandCraft therefore ships a narrow source hook for Solas rather than a replacement renderer or a fork of the entire pack.

Open **Options > WinLandCraft...** and choose **Create Solas lighting-compatible copy**. The patcher finds the newest unmodified Solas ZIP and creates a sibling named `Solas ... + WinLandCraft.zip`; it never edits the original. Select the generated copy in Iris. The patcher:

- adds the bundled screen-light include;
- inserts one diffuse-light contribution in Solas's shared `gbuffersLighting` function;
- declares Iris's optional `SSBO` feature;
- refuses packs whose expected anchors are absent or ambiguous.

The source copy is intentional. A Solas update can change the lighting function, so regenerate the compatible copy after replacing Solas. A failed strict anchor check is safer than silently inserting code at the wrong point.

Settings control whether sampling/upload is active, light intensity, and base range. `1.0x` is the calibrated emitter baseline and the power value is passed into the lighting equation exactly once, so the default `3.5x` really emits 3.5 times that baseline. Available power steps run from 0.5x through 8x.

Range starts at 24 blocks and can only be increased to 32, 48, or 64 before cycling back to 24. The selected value is the minimum for a reference 3.2×1.8-block Browser. Larger panels multiply it by the square root of their diagonal ratio, capped at 4x; smaller panels never reduce it. For example, scaling both dimensions by four changes a base range of 24 to an effective range of 48. The sublinear rule gives very large displays proportionally broader ambience without letting an accidental extreme resize illuminate the entire loaded world. Distance is measured from the nearest point on the flat or curved emitting surface, not from its center. Hiding the Minecraft GUI also suppresses projected light because it hides the panels themselves.

## Performance and validation

The CPU processes at most 24 RGBA pixels per active sample and uploads at most 400 bytes per frame. It precomputes the whole-screen average rather than repeating six additions per fragment. GPU cost is bounded by two unshadowed area-emitter evaluations for fragments that use Solas's shared material lighting. Flat panels use only scalar/vector arithmetic. A curved panel adds one inverse tangent and a sine/cosine pair but remains one evaluation—there are no curve subdivisions, light lookup textures, or extra texture samples. Replacing as many as 12 point evaluations makes the model both smoother and generally cheaper. Actual cost still depends primarily on screen coverage and shader-pack resolution.

Automated checks cover zone orientation, temporal smoothing, relative-range bounds, cylindrical shader presence, strict/idempotent source patching, archive preservation, and packaged shader presence. Native validation must still be performed in game:

1. Generate and select the compatible Solas copy, then restart or reload the pack.
2. Open a bright, colorful video in a dark enclosed room.
3. Check that nearby floor, walls, blocks, and entities receive changing RGB light, and that dark scenes become visibly dimmer than bright scenes.
4. Move, rotate, resize, curve, and close the panel; verify the light follows and disappears.
5. Toggle lighting, power, range, and F1 GUI hiding.
6. Move the panel close to a floor and confirm no three-column hotspot pattern or detached light source is visible.
7. Repeat with two panels and confirm a third distant panel does not cause a frame-time spike.
8. Re-select the original Solas ZIP and verify it remains unchanged and functional.
