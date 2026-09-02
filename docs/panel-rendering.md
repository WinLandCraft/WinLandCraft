# Panel rendering

WinLandCraft renders a panel in two distinct stages. Native UI and Chromium content are first composed into a private framebuffer at the panel's pixel resolution. The completed texture is then submitted as world geometry. Keeping that boundary explicit is required for both Iris compatibility and stable minification.

## Iris boundary

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
