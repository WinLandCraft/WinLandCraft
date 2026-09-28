# Panel rendering

WinLandCraft renders a panel in two logical stages. Native UI and Chromium content are drawn as separate submit-model layers at the panel's pixel resolution, composited by depth and submit order in the world pass. The completed layers are then visible as world geometry. Keeping layer submission explicit is required for both Iris compatibility and stable minification.

App surfaces no longer reserve a fixed titlebar strip. `floatingControls()` marks app eligibility independently of `titlebarHeight()`, so grouping and curvature still work with zero-height chrome. EdgeControls draws its own pill, outside the app framebuffer and stream capture. Its final textured quad uses a late submit order. The panel-smoothing setting therefore applies to the complete pill, including glyphs and icons. Fade opacity is applied once per draw. Discarding a pill closes its surface on the render thread. Its pick plane is tangent to the owner's cylinder at the selected edge; it stays stationary during a curve gesture to avoid moving the slider under the pointer.

## Iris boundary

Native apps inherit `NativePanel`: ordinary resizing changes their logical viewport at the current pixel density, while `scaleTo` leaves that viewport unchanged. `NativePanel.render` owns surface acquisition, front-face selection, and cleanup; individual apps supply `drawSurface`, which is also used by streaming capture. Native layouts and pointer bounds must use `pixelWidth()`/`pixelHeight()` for available space rather than stretching a fixed 1280×720 canvas. This applies to grouped resizing and streaming capture as well as standalone windows. File Manager and Notepad derive visible rows from the viewport; Task Manager scrolls its metrics when the window is shorter.

Floating EdgeControls uses a late submit order around the normal world layers. After the underlying draws run, it keeps depth testing/writes enabled with a reserved near range before normal teardown. Vertex positions and projection remain unchanged. Depth writes keep later window batches from covering the control; the normal layer offsets still order its own background, text, and icons. This is local foreground UI, so visible controls also draw above world geometry; activation and input continue to respect the world ray's block/entity obstruction checks. Picking gives a visible control priority over neighboring panels, retaining the real ray distance for interaction range and dragging. No private world flush or global depth clear is used.

Only browser texture uploads run outside the world pass. The finished surface, backside, menus, resize handles, and other native world UI are submitted through the level submit collector. The 2D desktop hybrid redraws the same `drawSurface` calls through the GUI canvas instead of blitting a composite.

Do not give the final surface a private submit scope and flush it manually. Iris selects shader programs and output masks when the render type is drawn, not when its vertices are created. Submitting through the level collector lets Iris own the final flush and choose a valid pack program.

The world canvas owns no immediate buffer: every draw call records directly into the frame graph. All framebuffer, viewport, projection, fog, blend, depth, cull, scissor, draw-buffer, color-mask, and Iris state is managed by the render pipeline.

## Mipmaps and texture allocation

Browser textures are single-level RGBA8 with linear minification/magnification and edge clamping. Near views retain native pixel sharpness; oblique or distant views can shimmer because the old trilinear mip chain has no RenderPearl upload equivalent yet. The screen-light sampler reads level zero.

Settings > Panel smoothing (ON by default, persisted as `panelSmoothing`) controls this world-surface filtering. OFF uses nearest minification and magnification, removing distance blur at the cost of aliasing/shimmer. Changes reconfigure each surface's sampler on its next draw, without reopening or resizing it.

Sampler validity is tracked per texture wrapper, which re-syncs its view whenever the browser reallocates on resize. OpenGL object names are recyclable: never infer that an allocation survived because a numeric handle did not change.

`AbstractTexture` subclasses now wrap RenderPearl views directly. Vanilla render types resolve the sampler through the texture entry at draw time.

## RenderDoc diagnosis

When a panel disappears only with shaders, inspect the final world draw rather than the browser upload:

1. Confirm the browser texture contains the completed page.
2. At the final indexed draw, check the selected Iris program, framebuffer attachments, `GL_COLOR_WRITEMASK`, and `GL_DEPTH_WRITEMASK`.
3. Confirm the draw was submitted through the frame's level collector after the entity pass.

When a panel becomes jagged after resizing, inspect the final texture sampler. A valid surface has linear minification filtering. Seeing the same texture view before and after resize does not prove the allocation survived.

These checks intentionally cover different stages: a correct browser texture can still disappear during the world draw, and a visible world draw can still sample that texture poorly.

## API v2 submitted surfaces

PluginFrameMailbox copies top-down RGBA8/BGRA8 rows from a producer's ByteBuffer into one replaceable pending frame, with a 4096x4096 limit. PluginFrames drains it only while composing the panel on the render thread. CPU uploads copy into an owned RenderPearl texture (BGRA is swizzled in the staging copy because RenderPearl has no BGRA format). Allocation and sampler configuration occur together, including same-byte-count shape changes. The owned texture is fitted into logical content dimensions with aspect-preserving letterboxing, then combined with native UI in the normal panel draw. Final world submission and stream capture are unchanged. Context teardown closes the mailbox before deleting GPU resources, so late producers cannot revive a texture.


### Plugin API v2 GPU sources

Since 0.1.84-dev, PluginFrames accepts optional GpuSource producers. Acquire/copy/
release run on the render thread; on the OpenGL backend the producer texture is copied
via `glCopyImageSubData` into a host-owned texture before any buffered canvas draw references it. Producer textures are borrowed, never registered/deleted by the host. On non-GL backends the GPU path reports an error and the CPU fallback remains available. Callbacks restore their own GL state and implement cross-context/API synchronization.
No CPU pixel transfer is used here; existing panel composition and final
level-collector submission remain unchanged. CPU fallback remains available.
