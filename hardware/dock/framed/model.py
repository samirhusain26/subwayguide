"""A removable screen bezel and snug seat for the 2015 Moto G. Units: mm.

The faceplate prints front down. Its assembly transform is printed_to_world().
Run this script to rebuild dock.stl, faceplate.stl and fit-sample.stl.
The matching rear cover is the existing managed/rear-cover.stl.
"""
import importlib.util
import sys
from pathlib import Path

import manifold3d as cad
import trimesh

HERE = Path(__file__).resolve().parent
CAD_ROOT = HERE.parent
sys.path.insert(0, str(CAD_ROOT))
import model as stock


def load_cad(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


managed = load_cad("managed_cable_dock", CAD_ROOT/"managed/model.py")
# Use an isolated copy of the seat parameters. The two preceding versions
# retain their original clearances when imported in the same process.
snug = load_cad("snug_seat", CAD_ROOT/"model.py")
snug.END_GAP = 0.28
snug.BACK_GAP = 0.55
snug.FACE_GAP = 0.40
managed.handset_seat = snug.build_dock

SCREEN_W, SCREEN_H = 110.7, 62.3
SCREEN_X, SCREEN_HC = 1.45, 36.2
WINDOW_MARGIN = 0.90
FACE_DEPTH, GLASS_CLEARANCE = -4.45, 0.65
PLATE_THICK = -GLASS_CLEARANCE - FACE_DEPTH
POST_X = 78.5
KEY_FLOOR, KEY_TOP = 3.6, 23.0
KEY_GAP = 0.30


def cut_window():
    """Splayed rear throat; a viewing ray sees a little more space at the front."""
    rings = []
    for t, extra in [(-1.0, 4.0), (0, 3.0), (PLATE_THICK-.35, 0.0),
                     (PLATE_THICK+1, 0.0)]:
        halfwidth = SCREEN_W/2 + WINDOW_MARGIN + extra
        halfheight = SCREEN_H/2 + WINDOW_MARGIN + extra
        outline = stock.contour(halfwidth, -halfheight, halfheight, .55+extra)
        rings.append((t, [(x+SCREEN_X, h+SCREEN_HC) for x,h in outline]))
    return stock.ring_volume(rings)


def key_section(gap):
    """Male dovetail and its matching female slot in local length/depth.

    The slot is open to the front and to the top of each receiver.
    """
    points = [(-2.05,-3.25), (2.05,-3.25), (2.05,-2.1),
              (3.25,-.35), (3.25,.55), (-3.25,.55),
              (-3.25,-.35), (-2.05,-2.1)]
    profile = cad.CrossSection([points])
    if gap:
        profile = profile.offset(gap, circular_segments=24)
    return profile


def paired(shape):
    right = shape.translate((POST_X,0,0))
    return right + right.mirror((1,0,0))


def dock_body():
    original = managed.build_managed_dock()
    # Make one flat mounting plane across the previous cheek fronts. Keep
    # the lower retaining lip until the inserted faceplate takes over above it.
    shave = stock.box(-89,89,-32,-.25,1.6,76) + stock.box(-89,89,-32,-2.0,-3.95,1.601)
    original -= stock.position(shave)
    receiver = managed.prism(managed.rounded_section(-5.9,5.9,-2.42,7.8,1.0),
                              -14,KEY_TOP)
    slot = managed.prism(key_section(KEY_GAP), KEY_FLOOR, KEY_TOP+2)
    local = paired(receiver)
    # The previous cheek and its base can enter the bottom of the slot.
    # Remove the complete slot from the final union, including its front mouth.
    # The side of the original pocket follows the rounded lower corners.
    # These lands extend up to the straight handset edges so the specified
    # end clearance sets the actual lateral play. The taper guides insertion.
    inner = stock.WIDTH/2 + snug.END_GAP
    lands = []
    for height, lead in [(-12,0),(17.5,0),(21.5,.85)]:
        left, right = inner+lead, 74.9
        profile = stock.contour((right-left)/2,2.0,7.7,1.2)
        lands.append((height, [(x+(left+right)/2,b) for x,b in profile]))
    land = stock.ring_volume(lands)
    local += land + land.mirror((1,0,0))
    blocks = stock.position(local) ^ stock.box(-89,89,-19,20,2.0,60)
    return (original + blocks) - stock.position(paired(slot))


def plate_local():
    # Build the planar face in x/height/thickness first. Outer corners taper
    # at the front by 0.65 mm for a small perimeter bevel.
    sections = []
    for t, inset in [(0,.65),(.75,0),(PLATE_THICK,0)]:
        sections.append((t,stock.contour(87.4-inset,-3.5+inset,74.8-inset,8-inset)))
    slab = stock.ring_volume(sections) - cut_window()
    # Convert the design sections into handset-local x/depth/height.
    face = slab.transform(((1,0,0,0),(0,0,1,FACE_DEPTH),(0,1,0,0)))
    # Set the lower edge forward to clear the original retaining lip. Shallow
    # rear flutes clear the raised speaker bars and open toward the top.
    face -= stock.box(-90,90,-2.35,2,-5,2.2)
    flute = managed.rounded_section(61,70.7,19,81,3.2)
    relief = managed.prism(flute,0,4).transform(((1,0,0,0),(0,0,1,-2.4),(0,1,0,0)))
    face -= relief + relief.mirror((1,0,0))
    # Recess the receiver track in the print so even the widest part of
    # each traveling key stays in front of the right-angle USB housing.
    receiver_relief = stock.box(72.15,84.85,-2.75,3,-5,23.6)
    face -= receiver_relief + receiver_relief.mirror((1,0,0))
    keys = paired(managed.prism(key_section(0),KEY_FLOOR,KEY_TOP-.4))
    return face + keys


def printed_to_world(shape):
    return stock.position(shape.rotate((-90,0,0)).translate((0,FACE_DEPTH,0)))


def printable_plate():
    return plate_local().translate((0,-FACE_DEPTH,0)).rotate((90,0,0))


def fit_sample(dock):
    # Real interfaces at their installed spacings: both receivers, lower-corner
    # seat and central back, plus the shield and plug clearance on the USB side.
    ends = stock.box(58,89,-14,20,0,64) + stock.box(-89,-58,-14,18,0,35)
    center = stock.box(-4,4,-11,15,0,31)
    bridge = stock.box(-83,83,-6.6,-.6,0,2.6)
    return dock ^ (ends+center+bridge)


def export(shape, name):
    data = shape.simplify(.001).to_mesh()
    mesh = trimesh.Trimesh(data.vert_properties[:,:3], data.tri_verts)
    # Coincident boolean vertices can round to a single STL float. Drop
    # collapsed triangles, then check the serialized topology below.
    mesh.update_faces(mesh.nondegenerate_faces(height=1e-9) & mesh.unique_faces())
    mesh.remove_unreferenced_vertices()
    path = HERE/name
    mesh.export(path)
    checked = trimesh.load_mesh(path)
    if not checked.is_volume or checked.body_count != 1 or abs(checked.bounds[0,2])>.001:
        raise ValueError(f"Invalid print solid: {name}")
    print(f"{name}: {checked.extents.round(2)} mm; {len(checked.faces)} faces")


if __name__ == "__main__":
    dock = dock_body()
    export(dock, "dock.stl")
    export(printable_plate(), "faceplate.stl")
    export(fit_sample(dock), "fit-sample.stl")
