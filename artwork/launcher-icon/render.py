"""Builds and renders BeauTyXT's physically modeled document-and-pencil identity."""

import math
import xml.etree.ElementTree as xml
from pathlib import Path

import bmesh
import bpy
from bpy_extras.object_utils import world_to_camera_view
from mathutils import Vector


SCRIPT_DIRECTORY = Path(__file__).resolve().parent
PROJECT_DIRECTORY = SCRIPT_DIRECTORY.parents[1]
RESOURCE_DIRECTORY = PROJECT_DIRECTORY / "app" / "src" / "main" / "res"
SCENE_PATH = SCRIPT_DIRECTORY / "beautyxt-launcher.blend"
OUTPUT_NAME = "ic_launcher_foreground_color.png"
VECTOR_VIEWPORT = 108.0
VECTOR_CENTER = VECTOR_VIEWPORT / 2.0
VECTOR_UNIT_METERS = 0.001
ADAPTIVE_FOREGROUND_SCALE = 1.5
ADAPTIVE_SAFE_RADIUS = 33.0
ICON_PLANAR_SCALE = 1.06
CAMERA_HEIGHT = 0.31
CAMERA_LENS_MM = 100.0
CAMERA_SENSOR_WIDTH_MM = 36.0
FOCUS_DISTANCE = 0.307
BACKGROUND_COLOR = "#299F9F"
PAGE_COLOR = "#F7F1E6"
INK_COLOR = "#354A50"
PENCIL_COLOR = "#E98264"
WOOD_COLOR = "#E5C59F"
GRAPHITE_COLOR = "#38212D"
PAGE_BOTTOM_METERS = 0.00005
PAGE_DEPTH_METERS = 0.0018
PAGE_BEVEL_METERS = 0.00055
PLANAR_BEVEL_ANGLE_DEGREES = 30.0
MAX_PRISM_BEVEL_FRACTION = 0.45
INK_GAP_METERS = 0.00002
INK_DEPTH_METERS = 0.00008
INK_BEVEL_METERS = 0.000025
LINE_RADIUS = 2.4
LINE_SEGMENTS = 16
LINE_SPECIFICATIONS = (
    (40.0, 68.0, 33.5),
    (40.0, 65.5, 44.3),
    (40.0, 62.0, 55.1),
    (40.0, 57.5, 65.9),
)
PENCIL_TIP = (62.5, 64.7)
PENCIL_PLANAR_SCALE = 1.06
PENCIL_LENGTH = 31.7
PENCIL_WIDTH = 7.2
PENCIL_TIP_LENGTH = 8.4
PENCIL_HEIGHT_METERS = 0.00624
PENCIL_GAP_METERS = 0.00012
PENCIL_BEVEL_METERS = 0.00009
PENCIL_BEVEL_ANGLE_DEGREES = 20.0
PENCIL_GRAPHITE_FRACTION = 0.28
PENCIL_SIDE_COUNT = 6
MONOCHROME_PAGE_STROKE = 4.5
MONOCHROME_PENCIL_GAP = 3.0
CURVE_SEGMENTS = 16
RENDER_SAMPLES = 1024
RENDER_TARGETS = (
    ("mdpi", 108),
    ("hdpi", 162),
    ("xhdpi", 216),
    ("xxhdpi", 324),
    ("xxxhdpi", 432),
)
PAGE_PATH = (
    ("M", (43.5, 17.3)),
    ("L", (69.0, 17.3)),
    ("Q", (81.0, 17.3, 81.0, 29.8)),
    ("L", (81.0, 70.0)),
    ("Q", (81.0, 90.7, 60.5, 90.7)),
    ("L", (39.5, 90.7)),
    ("Q", (27.0, 90.7, 27.0, 76.5)),
    ("L", (27.0, 33.0)),
    ("Q", (27.0, 17.3, 43.5, 17.3)),
)


def clear_scene() -> None:
    """Removes the previous scene and its unused geometry and materials."""
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    for datablocks in (
        bpy.data.meshes,
        bpy.data.curves,
        bpy.data.materials,
        bpy.data.cameras,
        bpy.data.lights,
    ):
        for datablock in list(datablocks):
            if datablock.users == 0:
                datablocks.remove(datablock)


def srgb_channel_to_linear(channel: float) -> float:
    """Converts one normalized sRGB channel to linear light."""
    assert 0.0 <= channel <= 1.0
    if channel <= 0.04045:
        return channel / 12.92
    return ((channel + 0.055) / 1.055) ** 2.4


def hex_to_linear_rgba(color: str) -> tuple[float, float, float, float]:
    """Converts an RGB hexadecimal color to a linear RGBA tuple."""
    normalized = color.removeprefix("#")
    assert len(normalized) == 6
    channels = tuple(
        int(normalized[offset : offset + 2], 16) / 255.0
        for offset in (0, 2, 4)
    )
    return tuple(srgb_channel_to_linear(channel) for channel in channels) + (1.0,)


def create_material(
    name: str,
    color: str,
    roughness: float = 0.35,
    coat_weight: float = 0.1,
) -> bpy.types.Material:
    """Creates a satin dielectric material with a physically based finish."""
    assert 0.0 <= roughness <= 1.0
    assert 0.0 <= coat_weight <= 1.0
    material = bpy.data.materials.new(name=name)
    shader = material.node_tree.nodes.get("Principled BSDF")
    assert shader is not None
    shader.inputs["Base Color"].default_value = hex_to_linear_rgba(color)
    shader.inputs["Metallic"].default_value = 0.0
    shader.inputs["Roughness"].default_value = roughness
    shader.inputs["IOR"].default_value = 1.47
    shader.inputs["Coat Weight"].default_value = coat_weight
    shader.inputs["Coat Roughness"].default_value = min(roughness + 0.08, 1.0)
    return material


def quadratic_points(
    start: tuple[float, float],
    control: tuple[float, float],
    end: tuple[float, float],
    segment_count: int,
) -> list[tuple[float, float]]:
    """Samples a quadratic Bezier segment without repeating its start point."""
    assert segment_count > 0
    sampled: list[tuple[float, float]] = []
    for segment_index in range(1, segment_count + 1):
        amount = segment_index / segment_count
        inverse = 1.0 - amount
        sampled.append(
            (
                inverse * inverse * start[0]
                + 2.0 * inverse * amount * control[0]
                + amount * amount * end[0],
                inverse * inverse * start[1]
                + 2.0 * inverse * amount * control[1]
                + amount * amount * end[1],
            )
        )
    return sampled


def vector_to_world(point: tuple[float, float]) -> tuple[float, float]:
    """Maps one 108-unit vector point to centered metric coordinates."""
    return (
        (point[0] - VECTOR_CENTER) * VECTOR_UNIT_METERS,
        (VECTOR_CENTER - point[1]) * VECTOR_UNIT_METERS,
    )


def signed_area(points: list[tuple[float, float]]) -> float:
    """Returns twice the signed area of a closed polygon."""
    assert len(points) >= 3
    area = 0.0
    for point_index, point in enumerate(points):
        next_point = points[(point_index + 1) % len(points)]
        area += point[0] * next_point[1] - next_point[0] * point[1]
    return area


def counterclockwise(
    points: list[tuple[float, float]],
) -> list[tuple[float, float]]:
    """Returns polygon points in counterclockwise order."""
    assert len(points) >= 3
    return points if signed_area(points) > 0.0 else list(reversed(points))


def page_outline() -> list[tuple[float, float]]:
    """Samples the document contour shared with the monochrome icon."""
    points: list[tuple[float, float]] = []
    for command, coordinates in PAGE_PATH:
        if command in ("M", "L"):
            points.append(coordinates)
            continue
        assert command == "Q" and points
        points.extend(
            quadratic_points(
                points[-1], coordinates[:2], coordinates[2:], CURVE_SEGMENTS
            )
        )
    assert points[0] == points[-1]
    return counterclockwise([vector_to_world(point) for point in points[:-1]])


def pencil_vector_point(distance: float, side: float) -> tuple[float, float]:
    """Maps pencil-local coordinates to its diagonal vector silhouette."""
    diagonal_scale = math.sqrt(0.5) * PENCIL_PLANAR_SCALE
    return (
        PENCIL_TIP[0] + (distance + side) * diagonal_scale,
        PENCIL_TIP[1] + (-distance + side) * diagonal_scale,
    )


def pencil_point(distance: float, side: float) -> tuple[float, float]:
    """Maps pencil-local vector units to the silhouette's world plane."""
    return vector_to_world(pencil_vector_point(distance, side))


def create_extruded_polygon(
    name: str,
    points: list[tuple[float, float]],
    bottom_z: float,
    height: float,
    material: bpy.types.Material,
    bevel_width: float,
    bevel_segments: int,
) -> bpy.types.Object:
    """Creates a beveled prism from a counterclockwise planar polygon."""
    assert len(points) >= 3
    assert height > 0.0
    assert bevel_width >= 0.0
    polygon = counterclockwise(points)
    point_count = len(polygon)
    vertices = [
        (x_coordinate, y_coordinate, bottom_z)
        for x_coordinate, y_coordinate in polygon
    ]
    vertices.extend(
        (x_coordinate, y_coordinate, bottom_z + height)
        for x_coordinate, y_coordinate in polygon
    )
    faces: list[tuple[int, ...]] = [
        tuple(reversed(range(point_count))),
        tuple(range(point_count, point_count * 2)),
    ]
    for point_index in range(point_count):
        next_index = (point_index + 1) % point_count
        faces.append(
            (
                point_index,
                next_index,
                next_index + point_count,
                point_index + point_count,
            )
        )

    mesh = bpy.data.meshes.new(f"{name} mesh")
    mesh.from_pydata(vertices, [], faces)
    mesh.materials.append(material)
    mesh.update()

    object_3d = bpy.data.objects.new(name, mesh)
    bpy.context.collection.objects.link(object_3d)

    if bevel_width > 0.0:
        bevel = object_3d.modifiers.new(name="Physical edge bevel", type="BEVEL")
        bevel.width = min(bevel_width, height * MAX_PRISM_BEVEL_FRACTION)
        bevel.segments = bevel_segments
        bevel.limit_method = "ANGLE"
        bevel.angle_limit = math.radians(PLANAR_BEVEL_ANGLE_DEGREES)
        bevel.harden_normals = True
    return object_3d


def create_pencil(
    bottom_z: float,
    body_material: bpy.types.Material,
    wood_material: bpy.types.Material,
    graphite_material: bpy.types.Material,
) -> bpy.types.Object:
    """Creates a watertight faceted pencil with a tapered wood and graphite tip."""
    half_width = PENCIL_WIDTH / 2.0
    half_height = PENCIL_HEIGHT_METERS / 2.0
    center_z = bottom_z + half_height
    vertical_extent = math.sin(math.pi / 3.0)
    section = [
        (
            half_width * math.cos(side_index * math.tau / PENCIL_SIDE_COUNT),
            half_height
            * math.sin(side_index * math.tau / PENCIL_SIDE_COUNT)
            / vertical_extent,
        )
        for side_index in range(PENCIL_SIDE_COUNT)
    ]
    ring_specs = (
        (PENCIL_TIP_LENGTH * PENCIL_GRAPHITE_FRACTION, PENCIL_GRAPHITE_FRACTION),
        (PENCIL_TIP_LENGTH, 1.0),
        (PENCIL_LENGTH, 1.0),
    )
    tip_x, tip_y = pencil_point(0.0, 0.0)
    vertices = [(tip_x, tip_y, center_z)]
    for distance, scale in ring_specs:
        for side, height in section:
            world_x, world_y = pencil_point(distance, side * scale)
            vertices.append((world_x, world_y, center_z + height * scale))

    body_index, wood_index, graphite_index = range(3)
    faces: list[tuple[int, ...]] = []
    material_indices: list[int] = []
    for side_index in range(PENCIL_SIDE_COUNT):
        next_side = (side_index + 1) % PENCIL_SIDE_COUNT
        faces.append((0, 1 + next_side, 1 + side_index))
        material_indices.append(graphite_index)
    for ring_index, material_index in enumerate((wood_index, body_index)):
        near_start = 1 + ring_index * PENCIL_SIDE_COUNT
        far_start = near_start + PENCIL_SIDE_COUNT
        for side_index in range(PENCIL_SIDE_COUNT):
            next_side = (side_index + 1) % PENCIL_SIDE_COUNT
            faces.append(
                (
                    near_start + side_index,
                    near_start + next_side,
                    far_start + next_side,
                    far_start + side_index,
                )
            )
            material_indices.append(material_index)
    cap_start = 1 + (len(ring_specs) - 1) * PENCIL_SIDE_COUNT
    faces.append(tuple(cap_start + side for side in range(PENCIL_SIDE_COUNT)))
    material_indices.append(body_index)

    mesh = bpy.data.meshes.new("Pencil mesh")
    mesh.from_pydata(vertices, [], faces)
    for material in (body_material, wood_material, graphite_material):
        mesh.materials.append(material)
    for polygon, material_index in zip(mesh.polygons, material_indices, strict=True):
        polygon.material_index = material_index

    editable_mesh = bmesh.new()
    try:
        editable_mesh.from_mesh(mesh)
        bmesh.ops.recalc_face_normals(editable_mesh, faces=editable_mesh.faces)
        editable_mesh.to_mesh(mesh)
    finally:
        editable_mesh.free()
    mesh.update()

    pencil = bpy.data.objects.new("Coral pencil", mesh)
    bpy.context.collection.objects.link(pencil)
    bevel = pencil.modifiers.new(name="Physical pencil edge bevel", type="BEVEL")
    bevel.width = PENCIL_BEVEL_METERS
    bevel.segments = 3
    bevel.limit_method = "ANGLE"
    bevel.angle_limit = math.radians(PENCIL_BEVEL_ANGLE_DEGREES)
    bevel.harden_normals = True
    return pencil


def capsule_outline(
    start_x: float,
    end_x: float,
    vector_y: float,
    radius: float,
    semicircle_segments: int = LINE_SEGMENTS,
) -> list[tuple[float, float]]:
    """Returns a horizontal vector capsule converted to world coordinates."""
    assert start_x < end_x
    assert radius > 0.0
    points: list[tuple[float, float]] = []
    for segment_index in range(semicircle_segments + 1):
        angle = -math.pi / 2.0 + math.pi * segment_index / semicircle_segments
        points.append(
            (
                end_x + radius * math.cos(angle),
                vector_y - radius * math.sin(angle),
            )
        )
    for segment_index in range(semicircle_segments + 1):
        angle = math.pi / 2.0 + math.pi * segment_index / semicircle_segments
        points.append(
            (
                start_x + radius * math.cos(angle),
                vector_y - radius * math.sin(angle),
            )
        )
    return counterclockwise([vector_to_world(point) for point in points])


def add_area_light(
    name: str,
    location: tuple[float, float, float],
    power_watts: float,
    size_meters: float,
) -> None:
    """Adds a physical studio light aimed at the symbol."""
    assert power_watts >= 0.0 and size_meters > 0.0
    light = bpy.data.lights.new(name=name, type="AREA")
    light.energy = power_watts
    light.shape = "DISK"
    light.size = size_meters
    object_3d = bpy.data.objects.new(name, light)
    object_3d.location = location
    object_3d.rotation_euler = (-Vector(location)).to_track_quat("-Z", "Y").to_euler()
    bpy.context.collection.objects.link(object_3d)


def configure_render_device(scene: bpy.types.Scene) -> None:
    """Selects OptiX when available and otherwise uses the CPU."""
    preferences = bpy.context.preferences.addons["cycles"].preferences
    try:
        preferences.compute_device_type = "OPTIX"
        preferences.get_devices()
    except (RuntimeError, TypeError):
        scene.cycles.device = "CPU"
        return
    optix_found = False
    for device in preferences.devices:
        device.use = device.type == "OPTIX"
        optix_found = optix_found or device.use
    scene.cycles.device = "GPU" if optix_found else "CPU"


def configure_cycles(scene: bpy.types.Scene) -> None:
    """Configures reproducible physical rendering with a transparent background."""
    scene.render.engine = "CYCLES"
    configure_render_device(scene)
    scene.cycles.samples = RENDER_SAMPLES
    scene.cycles.seed = 0
    scene.cycles.use_animated_seed = False
    scene.cycles.use_adaptive_sampling = True
    scene.cycles.adaptive_threshold = 0.002
    scene.cycles.use_denoising = False
    scene.cycles.max_bounces = 12
    scene.cycles.diffuse_bounces = 4
    scene.cycles.glossy_bounces = 8
    scene.cycles.transparent_max_bounces = 8
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    scene.render.image_settings.color_depth = "8"
    scene.render.image_settings.compression = 100
    scene.render.film_transparent = True
    scene.view_settings.view_transform = "Standard"
    scene.view_settings.look = "None"
    scene.view_settings.exposure = -7.0


def create_camera(scene: bpy.types.Scene) -> None:
    """Creates a product camera accounting for Android's adaptive foreground."""
    camera = bpy.data.cameras.new(name="Product camera")
    camera.type = "PERSP"
    camera.lens = CAMERA_LENS_MM
    camera.sensor_width = CAMERA_SENSOR_WIDTH_MM
    camera.dof.use_dof = True
    camera.dof.focus_distance = FOCUS_DISTANCE * ADAPTIVE_FOREGROUND_SCALE
    camera.dof.aperture_fstop = 11.0
    camera.dof.aperture_blades = 9
    object_3d = bpy.data.objects.new("Product camera", camera)
    object_3d.location = (0.0, 0.0, CAMERA_HEIGHT * ADAPTIVE_FOREGROUND_SCALE)
    bpy.context.collection.objects.link(object_3d)
    scene.camera = object_3d


def monochrome_scale() -> float:
    """Matches the flattened vector to the camera projection of the page."""
    camera_distance = CAMERA_HEIGHT * ADAPTIVE_FOREGROUND_SCALE
    surface_distance = camera_distance - PAGE_BOTTOM_METERS - PAGE_DEPTH_METERS
    visible_width = surface_distance * CAMERA_SENSOR_WIDTH_MM / CAMERA_LENS_MM
    return VECTOR_UNIT_METERS * ICON_PLANAR_SCALE * VECTOR_VIEWPORT / visible_width


def page_path_data() -> str:
    """Returns the document contour as Android vector path data."""
    return "".join(
        command + ",".join(f"{coordinate:g}" for coordinate in coordinates)
        for command, coordinates in PAGE_PATH
    ) + "Z"


def lines_path_data() -> str:
    """Returns the same line centers as the raised ink in the color icon."""
    return "".join(
        f"M{start_x:g},{vector_y:g}H{end_x:g}"
        for start_x, end_x, vector_y in LINE_SPECIFICATIONS
    )


def pencil_path_data(padding: float = 0.0, reverse_winding: bool = False) -> str:
    """Returns the pencil contour with optional clearance and reversed winding."""
    assert padding >= 0.0
    half_width = PENCIL_WIDTH / 2.0 + padding
    points = (
        pencil_vector_point(-padding, 0.0),
        pencil_vector_point(PENCIL_TIP_LENGTH, -half_width),
        pencil_vector_point(PENCIL_LENGTH + padding, -half_width),
        pencil_vector_point(PENCIL_LENGTH + padding, half_width),
        pencil_vector_point(PENCIL_TIP_LENGTH, half_width),
    )
    contour = reversed(points) if reverse_winding else points
    return "M" + "L".join(
        f"{point[0]:.5f},{point[1]:.5f}" for point in contour
    ) + "Z"


def pencil_clearance_path_data() -> str:
    """Cuts the page strokes away from the pencil in the themed icon."""
    # Reverse the inner contour: Android clip paths use nonzero winding.
    return (
        f"M0,0H{VECTOR_VIEWPORT:g}V{VECTOR_VIEWPORT:g}H0Z"
        + pencil_path_data(MONOCHROME_PENCIL_GAP, reverse_winding=True)
    )


def validate_scene(scene: bpy.types.Scene) -> None:
    """Checks watertight outward-facing solids and Android's adaptive safe area."""
    assert scene.render.resolution_x == scene.render.resolution_y
    bpy.context.view_layer.update()
    graph = bpy.context.evaluated_depsgraph_get()
    for object_3d in scene.objects:
        if object_3d.type != "MESH" or object_3d.is_shadow_catcher:
            continue
        evaluated = object_3d.evaluated_get(graph)
        mesh = evaluated.to_mesh()
        editable = bmesh.new()
        try:
            editable.from_mesh(mesh)
            assert all(edge.is_manifold for edge in editable.edges), (
                f"non-manifold surface: {object_3d.name}"
            )
            assert all(
                face.calc_area() > 0.0 for face in editable.faces
            ), f"degenerate surface: {object_3d.name}"
            assert editable.calc_volume(signed=True) > 0.0, (
                f"inward-facing surface: {object_3d.name}"
            )
            for vertex in mesh.vertices:
                projected = world_to_camera_view(
                    scene, scene.camera, object_3d.matrix_world @ vertex.co
                )
                radius = math.hypot(projected.x - 0.5, projected.y - 0.5)
                assert radius * VECTOR_VIEWPORT < ADAPTIVE_SAFE_RADIUS, (
                    f"outside adaptive safe area: {object_3d.name}"
                )
        finally:
            editable.free()
            evaluated.to_mesh_clear()


def validate_android_resources() -> None:
    """Checks that Android's monochrome silhouette and palette match the scene."""
    attribute = "{http://schemas.android.com/apk/res/android}"
    vector = xml.parse(
        RESOURCE_DIRECTORY / "drawable" / "ic_launcher_monochrome.xml"
    ).getroot()
    for axis in ("viewportWidth", "viewportHeight"):
        assert float(vector.attrib[attribute + axis]) == VECTOR_VIEWPORT
    group = vector.find("group")
    assert group is not None
    for axis in ("scaleX", "scaleY"):
        assert math.isclose(
            float(group.attrib[attribute + axis]), monochrome_scale(), abs_tol=1e-7
        )
    for axis in ("pivotX", "pivotY"):
        assert float(group.attrib[attribute + axis]) == VECTOR_CENTER
    paper_group = group.find("group")
    assert paper_group is not None
    clip = paper_group.find("clip-path")
    assert clip is not None
    assert attribute + "fillType" not in clip.attrib
    assert clip.attrib[attribute + "pathData"] == pencil_clearance_path_data()
    paths = paper_group.findall("path")
    assert len(paths) == 2
    assert paths[0].attrib[attribute + "pathData"] == page_path_data()
    assert paths[1].attrib[attribute + "pathData"] == lines_path_data()
    for path, width in zip(
        paths, (MONOCHROME_PAGE_STROKE, LINE_RADIUS * 2.0), strict=True
    ):
        assert float(path.attrib[attribute + "strokeWidth"]) == width
        assert path.attrib[attribute + "strokeColor"] == "#FFFFFF"
        assert path.attrib[attribute + "strokeLineCap"] == "round"
    pencil = group.find("path")
    assert pencil is not None
    assert pencil.attrib[attribute + "pathData"] == pencil_path_data()
    assert pencil.attrib[attribute + "fillColor"] == "#FFFFFF"
    colors = xml.parse(RESOURCE_DIRECTORY / "values" / "colors.xml").getroot()
    background = colors.find("color[@name='launcher_background']")
    assert background is not None and background.text == BACKGROUND_COLOR


def create_icon_scene() -> bpy.types.Scene:
    """Creates the ivory document and coral pencil on a jade surface."""
    clear_scene()
    scene = bpy.context.scene
    scene.unit_settings.system = "METRIC"
    scene.unit_settings.length_unit = "MILLIMETERS"
    configure_cycles(scene)
    background_material = create_material(
        "Receiving surface", BACKGROUND_COLOR, 0.58, 0.0
    )
    bpy.ops.mesh.primitive_plane_add(size=0.4)
    background = bpy.context.object
    background.name = "Shadow receiving surface"
    background.data.materials.append(background_material)
    background.is_shadow_catcher = True
    create_extruded_polygon(
        "Document tile",
        page_outline(),
        PAGE_BOTTOM_METERS,
        PAGE_DEPTH_METERS,
        create_material("Ivory satin", PAGE_COLOR, 0.31, 0.16),
        bevel_width=PAGE_BEVEL_METERS,
        bevel_segments=8,
    )
    ink_material = create_material("Ink satin", INK_COLOR, 0.27, 0.12)
    ink_bottom = PAGE_BOTTOM_METERS + PAGE_DEPTH_METERS + INK_GAP_METERS
    for line_number, (start_x, end_x, vector_y) in enumerate(
        LINE_SPECIFICATIONS, start=1
    ):
        create_extruded_polygon(
            f"Text line {line_number}",
            capsule_outline(start_x, end_x, vector_y, LINE_RADIUS),
            ink_bottom,
            INK_DEPTH_METERS,
            ink_material,
            bevel_width=INK_BEVEL_METERS,
            bevel_segments=3,
        )
    create_pencil(
        PAGE_BOTTOM_METERS + PAGE_DEPTH_METERS + PENCIL_GAP_METERS,
        create_material("Pencil satin", PENCIL_COLOR, 0.31, 0.1),
        create_material("Warm wood", WOOD_COLOR, 0.55, 0.0),
        create_material("Graphite", GRAPHITE_COLOR, 0.52, 0.0),
    )
    for object_3d in scene.objects:
        if object_3d.type == "MESH" and not object_3d.is_shadow_catcher:
            object_3d.scale.x = ICON_PLANAR_SCALE
            object_3d.scale.y = ICON_PLANAR_SCALE
    add_area_light("Upper-left softbox", (-0.13, 0.15, 0.23), 115.0, 0.2)
    add_area_light("Lower-right fill", (0.12, -0.1, 0.18), 31.0, 0.18)
    create_camera(scene)
    world = scene.world
    assert world is not None
    background_node = world.node_tree.nodes.get("Background")
    assert background_node is not None
    background_node.inputs["Color"].default_value = (1.0, 1.0, 1.0, 1.0)
    background_node.inputs["Strength"].default_value = 0.08
    return scene


def output_path(density: str) -> Path:
    """Returns the Android foreground path for one resource density."""
    return RESOURCE_DIRECTORY / f"drawable-{density}" / OUTPUT_NAME


def relative_output_path(density: str) -> str:
    """Returns the density path relative to the saved Blender scene."""
    return f"//../../app/src/main/res/drawable-{density}/{OUTPUT_NAME}"


def main() -> None:
    """Saves the reproducible scene and renders every Android density asset."""
    scene = create_icon_scene()
    largest_density, largest_size = RENDER_TARGETS[-1]
    scene.render.resolution_x = largest_size
    scene.render.resolution_y = largest_size
    scene.render.filepath = relative_output_path(largest_density)
    validate_android_resources()
    validate_scene(scene)
    bpy.ops.wm.save_as_mainfile(filepath=str(SCENE_PATH), compress=True)
    for density, size in RENDER_TARGETS:
        path = output_path(density)
        path.parent.mkdir(parents=True, exist_ok=True)
        scene.render.resolution_x = size
        scene.render.resolution_y = size
        scene.render.filepath = str(path)
        bpy.ops.render.render(write_still=True)
        assert path.exists()


if __name__ == "__main__":
    main()
