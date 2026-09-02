# Panel rendering

WinLandCraft renders a panel in two distinct stages. Native UI and Chromium content are first composed into a private framebuffer at the panel's pixel resolution. The completed texture is then submitted as world geometry. Keeping that boundary explicit is required for both Iris compatibility and stable minification.

App surfaces no longer reserve a fixed titlebar strip. `floatingControls()` marks app eligibility independently of `titlebarHeight()`, so grouping and curvature still work with zero-height chrome. EdgeControls uses its own PanelSurface compositor and mip sampler, outside the app framebuffer and stream capture. Its final textured quad uses the foreground world-consumer layer. The panel-smoothing setting therefore applies to the complete pill, including glyphs and icons. Fade opacity is applied once to the composed texture. Discarding a pill closes its surface on the render thread. Its pick plane is tangent to the owner's cylinder at the selected edge; it stays stationary during a curve gesture to avoid moving the slider under the pointer.

## Iris boundary

Native apps inherit `NativePanel`: ordinary resizing changes their logical viewport at the current pixel density, while `scaleTo` leaves that viewport unchanged. Native layouts and pointer bounds must use `pixelWidth()`/`pixelHeight()` for available space rather than stretching a fixed 1280×720 canvas. This applies to grouped resizing and streaming capture as well as standalone windows. File Manager and Notepad derive visible rows from the viewport; Task Manager scrolls its metrics when the window is shorter.

Floating EdgeControls uses a foreground render-type wrapper around the normal world-consumer types. After the underlying type (including Iris setup) runs, it maps depth into [0, 0.0001], enables depth testing/writes, and restores the previous range/function/mask/test before normal teardown. Vertex positions and projection remain unchanged. Depth writes keep later window batches from covering the control; the normal polygon offsets still order its own background, text, and icons. This is local foreground UI, so visible controls also draw above world geometry; activation and input continue to respect the world ray's block/entity obstruction checks. Picking gives a visible control priority over neighboring panels, retaining the real ray distance for interaction range and dragging. No private world flush or global depth clear is used.

Only private off-screen composition runs inside `IrisOffscreenRender`. It temporarily enables Iris's immediate-mode bypass and clears its rendering-level flag, then restores both values in a `finally` path before any world geometry is submitted. The finished surface, backside, menus, resize handles, and other native world UI must use `WorldRenderContext.consumers()`.

Do not give the final surface a private `MultiBufferSource` and flush it manually. Iris selects shader programs and output masks when the render type is drawn, not when its vertices are created. A private late flush caused Iris to classify the vanilla surface program as an unsupported world pass; `DepthColorStorage.disableDepthColor` then set both color and depth writes off immediately before `glDrawElements`. Reasserting raw GL masks earlier could not fix it because Iris overwrote them at draw time. Submitting through Fabric's world consumers lets Iris own the final flush and choose a valid pack program.

The private compositor still owns a small immediate buffer because it must finish before mipmaps can be generated. Its render types rebind the private framebuffer after each wrapped vanilla setup call. All framebuffer, viewport, projection, fog, blend, depth, cull, scissor, draw-buffer, color-mask, and Iris state is restored even when rendering fails.

## Mipmaps and texture allocation

The composed texture uses a complete mip chain, trilinear minification, nearest magnification, edge clamping, and up to 16x anisotropic filtering. Near views therefore retain native pixel sharpness while oblique or distant views use the mip chain.

Settings > Panel smoothing (ON by default, persisted as `panelSmoothing`) controls this world-surface filtering. OFF uses nearest minification and magnification with 1x anisotropy, removing distance blur at the cost of aliasing/shimmer. Changes reconfigure each surface's sampler on its next composition, without reopening or resizing it. The full mip chain is still generated in either mode because screen-light sampling relies on it. Allocation/resize invalidation remains independent of the setting.

Sampler validity is tracked with `samplerDirty`, which is set whenever the framebuffer is allocated or resized. Never cache only `framebuffer.getColorTextureId()` to decide whether sampler state is current. OpenGL object names are recyclable: `TextureTarget.resize` can delete and recreate a texture with the same integer name. That exact reuse previously made WinLandCraft skip sampler setup, leaving point minification with no mip filtering and 1x anisotropy even though all mip levels existed.

`AbstractTexture.setFilter` and `setClamp` are intentionally ignored for this registered surface. Vanilla render types otherwise overwrite the owned mip sampler before each draw.

## RenderDoc diagnosis

When a panel disappears only with shaders, inspect the final world draw rather than the off-screen composition draw:

1. Confirm the private color attachment contains the completed panel.
2. At the final indexed draw, check the selected Iris program, framebuffer attachments, `GL_COLOR_WRITEMASK`, and `GL_DEPTH_WRITEMASK`.
3. Confirm the draw was submitted through the frame's Fabric consumers and that the Iris bypass scope has already closed.

When a panel becomes jagged after resizing, inspect the final texture sampler. A valid surface has all expected mip levels, a mipmapped linear minification filter, and anisotropy above 1 when the driver exposes it. Seeing the same numeric texture ID before and after resize does not prove the allocation survived.

These checks intentionally cover different stages: a correct private texture can still disappear during the world draw, and a visible world draw can still sample that texture poorly.

## API v2 submitted surfaces

PluginFrameMailbox copies top-down RGBA8/BGRA8 rows from a producer's ByteBuffer into one replaceable pending frame, with a 4096x4096 limit. PluginFrames drains it only while composing the panel on the render thread. Raw GL texture upload saves/restores the bound texture, unpack PBO, row length, skips, and alignment. Allocation and sampler configuration occur together, including same-byte-count shape changes. The owned texture is fitted into logical content dimensions with aspect-preserving letterboxing, then combined with native UI in the normal PanelSurface. Final world submission and stream capture are unchanged. Context teardown closes the mailbox before deleting GL resources, so late producers cannot revive a texture.


### Plugin API v2 GPU sources

Since 0.1.84-dev, PluginFrames accepts optional GpuSource producers. Acquire/copy/
release run on the render thread; GL 4.3 or ARB_copy_image copies RGBA8 level zero
into a host-owned texture before any buffered canvas draw references it. Producer
textures are borrowed, never registered/deleted by the host. Restore texture/PBO
bindings around validation/allocation. Preserve sampler initialization on resize.
Callbacks restore their own GL state and implement cross-context/API synchronization.
No CPU pixel transfer is used here; existing PanelSurface composition and final
WorldRenderContext consumers remain unchanged. CPU fallback remains available.
