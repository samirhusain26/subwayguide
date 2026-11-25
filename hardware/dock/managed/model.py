"""Cable-managed 2015 Moto G dock, in millimetres. Run to regenerate the three STLs."""
import math
from pathlib import Path
import sys

import manifold3d as cad
import numpy as np
import trimesh

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from model import box, build_dock as handset_seat, contour, position, ring_volume

# World axes: +Y is behind the display, +Z is up. Wings use handset-local
# coordinates so that the front of the shroud leans at the same 12° as the glass.
WIDTH = 175.6
WING_INNER, WING_OUTER = 70.5, 87.4
SIDE_WALL = 2.4
TRAY_HALF, TRAY_FRONT, TRAY_REAR = 84.3, 20.2, 55.7
WALL = 2.4
FLOOR_Z, RIM_Z, COVER_Z = 4.6, 18.5, 20.9
TAB_GAP = 0.4
INLET_X, INLET_REAR = 67.5, 38.5


def rounded_section(x0, x1, y0, y1, radius):
    inner = cad.CrossSection.square((x1-x0-2*radius, y1-y0-2*radius), center=True)
    return inner.offset(radius, circular_segments=64).translate(((x0+x1)/2, (y0+y1)/2))


def prism(section, low, high):
    return cad.Manifold.extrude(section, high-low).translate((0,0,low))


def right_cheek():
    """An open-backed L. The two visible surfaces screen the plug from front and side."""
    sections = []
    # Side returns sweep down into the base. Top corners draw in along a 5 mm arc.
    for h in [-12, 0, 12, 24, 32, 40, 46] + list(np.linspace(48, 53, 25)):
        rounding = 0 if h <= 48 else 5-math.sqrt(max(0, 25-(h-48)**2))
        left = WING_INNER + rounding
        right = WING_OUTER - rounding
        rear = np.interp(h, [-12,12,30,43,48,53], [26,26,24,19,15,10.5])
        outline = contour((right-left)/2, -4.15, rear, 2)
        sections.append((h, [(x+(left+right)/2, d) for x,d in outline]))
    outer = ring_volume(sections)
    # Round the cable-side internal junction. The cutter opens all the way out
    # of the back, including at the very top for inserting a pre-plugged phone.
    inside = rounded_section(55, WING_OUTER-SIDE_WALL, -.85, 55, 1.6)
    return position(outer - prism(inside, -14, 60)) ^ box(60,91,-25,50,2.5,75)


def tray_section(inset=0):
    return rounded_section(-TRAY_HALF+inset, TRAY_HALF-inset, TRAY_FRONT+inset,
                           TRAY_REAR-inset, 6-inset)


def front_inlets():
    # Drop in the entire terminated lead before putting the cover on.
    opening = rounded_section(INLET_X, 100, 5, INLET_REAR, 4)
    return opening + opening.mirror((1,0))


def tray_walls():
    walls = prism(tray_section()-tray_section(WALL), FLOOR_Z-.5, RIM_Z)
    outlet = box(-5.5,5.5,TRAY_REAR-4,TRAY_REAR+2,FLOOR_Z,22)
    return walls - prism(front_inlets(), FLOOR_Z, 28) - outlet


def assembled_cover():
    # The plate rests on the rim. Locator tabs clear both the rim and cable path.
    lift_scoop = cad.CrossSection.circle(10, 72).translate((0, TRAY_REAR+7))
    plate = prism(tray_section(.15)-front_inlets()-lift_scoop, RIM_Z, COVER_Z)
    tabs = []
    for y in (TRAY_FRONT+WALL+TAB_GAP, TRAY_REAR-WALL-TAB_GAP-1.8):
        for x in (-48,34):
            tabs.append(box(x,x+14,y,y+1.8,RIM_Z-2.8,RIM_Z+.35))
    for x in (-TRAY_HALF+WALL+TAB_GAP, TRAY_HALF-WALL-TAB_GAP-1.8):
        tabs.append(box(x,x+1.8,42,47,RIM_Z-2.8,RIM_Z+.35))
    return plate + cad.Manifold.batch_boolean(tabs, cad.OpType.Add)


def flip_cover(shape):
    """Bed orientation <-> assembled orientation (self-inverse)."""
    return shape.rotate((0,180,0)).translate((0,0,COVER_Z))


def build_managed_dock():
    base = ring_volume([(z,contour(WIDTH/2-i,-16.5+i,57.8-i,7-i))
                        for z,i in [(0,.4),(.5,0),(3.7,0),(FLOOR_Z,.9)]])
    right = right_cheek()
    return base + handset_seat() + right + right.mirror((1,0,0)) + tray_walls()


def charging_sample(dock):
    # A full-width gauge with the actual charging-side shroud, phone contacts,
    # right tray inlet, and rear exit. Sparse beams preserve their positions.
    right = box(58,89,-14,40,0,66)
    left = box(-75,-58,-11,14,0,29)
    center = box(-3.5,3.5,-11,15,0,32)
    span = box(-72,82,-6.5,-1.1,0,2.5)
    rear_beam = box(-5,78,32.5,37.5,0,2.5)
    exit_sample = box(-10,10,33,59,0,25)
    return dock ^ (right + left + center + span + rear_beam + exit_sample)


def export(shape, name):
    geom = shape.simplify(.001).to_mesh()
    mesh = trimesh.Trimesh(geom.vert_properties[:,:3], geom.tri_verts)
    path = HERE/name
    mesh.export(path)
    reopened = trimesh.load_mesh(path)
    if not reopened.is_volume or reopened.body_count != 1:
        raise ValueError(f"{name} must be a single watertight solid")
    print(name, reopened.extents.round(2),len(reopened.faces))


if __name__ == "__main__":
    dock = build_managed_dock()
    export(dock, "dock.stl")
    export(charging_sample(dock), "fit-sample.stl")
    export(flip_cover(assembled_cover()), "rear-cover.stl")
