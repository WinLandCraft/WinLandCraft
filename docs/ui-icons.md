# Panel icons and hover states

Native world panels use a selected subset of [Pixelarticons](https://github.com/halfmage/pixelarticons), pinned to revision `8275e0af7c16aa40c54ea2b90b7af83b1fe4eb4c`. The source icons are MIT licensed and their notice is packaged as `META-INF/PIXELARTICONS-LICENSE.txt`.

The runtime asset is `assets/winlandcraft/textures/gui/pixelarticons.png`: an 8-column by 3-row atlas of white 24×24 icons with binary alpha. `PixelIcon` order is the atlas order. `PanelCanvas` applies color at the vertices, so normal, hover, active, and disabled states reuse the same texture without additional texture binds. Keep the atlas dimensions, enum order, packaged notice, and `verifyMixinPackaging` checks synchronized when changing it.

Local pointer coordinates are recorded once by `WorldPanel.pointerMoved` before the panel-specific hover callback. Native controls use `hovered` or `hoverColor`; browser content still receives its normal CEF mouse move. This keeps visual hit regions aligned with input regions and clears the state when the pointer leaves or the panel closes.

The resize cursor reuses the same atlas. Cyan diagonal arrows plus the expand icon identify viewport-resolution resize; yellow arrows plus the scale icon identify physical scaling.
