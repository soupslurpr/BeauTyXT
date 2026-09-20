# Launcher icon source

`render.py` constructs the color launcher icon as physically modeled geometry and
renders its adaptive foreground with Blender Cycles. The receiving surface is a
shadow catcher, so Android supplies the solid adaptive background independently.
The monochrome themed icon remains an Android vector resource.

The icon is an original ivory document and coral pencil on jade. Its palette
is jade `#299F9F`, ivory `#F7F1E6`, blue-gray ink `#354A50`, and coral `#E98264`.
The rounded page, raised ink, and hexagonal pencil are closed
solids with real thickness.
The pencil has a tapered wood and graphite tip, with the same depth above and
outside the page. Soft studio lights produce the shading and contact shadows;
no painted depth, external fonts, or third-party assets are used.

The monochrome vector flattens the same contours onto the page plane without
reproducing the lighting. Its scale follows the camera projection through
`monochrome_scale()`. The page outline, text lines, and pencil remain distinct
in one color: a small cutout keeps the page strokes clear of the pencil.
The cutout reverses its contour winding; Android's framework clip paths do not
read the `fillType` attribute.
`page_path_data()`, `lines_path_data()`, and `pencil_path_data()` expose the
shared shapes. Keep the vector and `launcher_background` color synchronized when
changing the geometry or palette. Home reads that same Android background color.
The renderer verifies their consistency, closed solid geometry, and adaptive
safe-area bounds before saving or rendering assets.

From the project root, run the renderer with Blender 5.2 LTS:

```sh
blender --background --python-exit-code 1 --python artwork/launcher-icon/render.py
```

The script saves `beautyxt-launcher.blend` beside itself and writes each Android
density asset directly into `app/src/main/res`.
