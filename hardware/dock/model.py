"""Fitted landscape dock for the 2015 Moto G; units are millimetres.

Install or use a Python environment with manifold3d, numpy and trimesh.
Running this file rebuilds both binary STL files. Seat and shape settings
use the handset frame: landscape width along x, rearward depth along y,
up the 72.4 mm span along z. Bed = z=0 after assembly in position().
"""
from pathlib import Path
import math

import manifold3d as cad
import numpy as np
import trimesh

# Width, height and the minimum/maximum thickness are catalogue values.
WIDTH, HEIGHT = 142.1, 72.4
EDGE, MID = 6.1, 11.6
# Photo-derived handset corner radius is about 11–12 mm. A smaller radius
# in the pocket relieves the lower corners.
CORNER = 10.0
# Independent allowances behind the crown, in front of the glass and at the ends.
END_GAP, BACK_GAP, FACE_GAP = 0.60, 1.2, 0.5
LIP = 1.2
LEAN = 12
ORIGIN_Y, SEAT_Z = -5.0, 9.5
OUTPUT_DIR = Path(__file__).resolve().parent


def box(x0, x1, y0, y1, z0, z1):
    return cad.Manifold.cube((x1 - x0, y1 - y0, z1 - z0)).translate((x0, y0, z0))


def contour(halfwidth, front, rear, radius):
    """The same point count and corner phases allow a height-varying local width."""
    center = (rear + front) / 2
    points = []
    for i, (dx, dy) in enumerate(((1, 1), (-1, 1), (-1, -1), (1, -1))):
        for step in range(13):
            angle = math.pi / 2 * (i + step / 12)
            points.append((
                dx * (halfwidth - radius) + radius * math.cos(angle),
                center + dy * ((rear - front)/2 - radius) + radius * math.sin(angle),
            ))
    return points


def ring_volume(sections):
    """Close a rounded volume between stacked, convex contours."""
    def corners():
        for height, outline in sections:
            for x, y in outline:
                yield x, y, height

    sides = len(sections[0][1])
    faces = []
    for s in range(len(sections) - 1):
        for i in range(sides):
            v = s * sides + i
            w = s * sides + (i + 1) % sides
            faces.extend(((v, w, w + sides), (v, w + sides, v + sides)))
    # Central fans avoid collinear-ear triangles on the floor or the seat.
    vertices = list(corners())
    for start, direction in ((0, -1), (sides * (len(sections)-1), 1)):
        center = len(vertices)
        vertices.append(np.mean(vertices[start:start+sides], axis=0))
        for i in range(sides):
            faces.append((center, start+i, start+(i+direction)%sides))
    mesh = cad.Mesh(np.array(vertices, dtype='float32'), np.array(faces, dtype='uint32'))
    shape = cad.Manifold(mesh)
    if shape.status() != cad.Error.NoError:
        raise RuntimeError(shape.status())
    return shape


def profile_reach(width, floor, radius, height):
    """Fitted reach across the round-to-straight lower corner of the phone."""
    inset = np.clip(radius - (height-floor), 0, radius)
    return width/2 - radius + math.sqrt(max(0, radius*radius - inset*inset))


def rear_depth(height):
    # Parabolic approximation across the short axis of the crowned back.
    h = np.clip(height / (HEIGHT/2), 0, 1)
    return EDGE + (MID - EDGE) * (2*h - h*h)


def cradle_blank(is_pocket):
    floor = 0 if is_pocket else -7
    # End walls measure 2.5 mm beyond the nominal pocket.
    ceiling = 30 if is_pocket else 23.5
    sections = []
    # Equal-angle height intervals resolve rapid acceleration at the toe tangent.
    topwidth = WIDTH + 2*END_GAP + (0 if is_pocket else 5.0)
    r = CORNER if is_pocket else CORNER+2.5
    heights = [floor + r*(1-math.cos(v)) for v in np.linspace(0, np.pi/2, 27)]
    for height in heights + list(np.arange(floor+r+1.1, ceiling, 1.1)) + [ceiling]:
        half = profile_reach(topwidth, floor, r, height)
        depth = rear_depth(height) + BACK_GAP
        c = contour(half, -FACE_GAP if is_pocket else -3.4, depth if is_pocket else depth+3.2,
                    0.7 if is_pocket else 3)
        sections.append((height,c))
    return ring_volume(sections)


def front_opening():
    # Side cheeks rise gently behind the 1.2 mm lip. This depth-height
    # profile keeps the entire display and both speaker grilles exposed.
    profile = [(-180,LIP),(-0.7,LIP),(1.5,2.2),(5.1,6),(12.8,13.2),
               (15.6,15.0),(20,15.2),(40,15.2),(40,60),(-180,60)]
    cutter = cad.Manifold.extrude(cad.CrossSection([profile]), 220)
    # Section x,y = phone depth,height; extrusion along phone length.
    return cutter.transform(((0,0,1,-110),(1,0,0,0),(0,1,0,0)))


def position(shape):
    return shape.rotate((-LEAN,0,0)).translate((0,ORIGIN_Y,SEAT_Z))


def build_dock():
    outer = cradle_blank(False)
    pocket = cradle_blank(True)
    # A taller rear wall touches only the rear of the phone. Its rounded top
    # corners ease the transition into the low side cheeks.
    width = WIDTH + 2*END_GAP + 5
    rear_width = width - 19
    rear_mask = cad.CrossSection([contour(rear_width/2, -32, 23.5, 10)])
    rear_mask = cad.Manifold.extrude(rear_mask, 55).transform(
        ((1,0,0,0),(0,0,-1,30),(0,1,0,0))
    ) ^ box(-100,100,7.2,40,-40,50)
    front = outer - front_opening()
    shell = position((front + (outer ^ rear_mask)) - pocket)
    foot_width = width + 3
    base = ring_volume([(z,contour(foot_width/2-inset,-16+inset,34-inset,8-inset))
                       for z,inset in [(0,0.4),(.5,0),(3.0,0),(4.3,1.3)]])
    return base + (shell ^ box(-100,100,-30,40,2,80))


def fit_gauge(dock):
    # Actual right and left seating geometry, plus a central sample of the rear
    # curve; a low beam joins them at precisely the final printed spacing.
    half = WIDTH/2
    end = half-13
    mask = (box(-half-4,-end,-10,15,0,45) + box(end,half+4,-10,15,0,45)
            + box(-3.5,3.5,-10,15,0,45) + box(-half,half,-5,1,0,2.4))
    return dock ^ mask


def write_stl(shape, name):
    mesh = shape.simplify(0.001).to_mesh()
    solid = trimesh.Trimesh(vertices=mesh.vert_properties[:,:3], faces=mesh.tri_verts)
    # Check the final single-precision STL too, as coincident points can be
    # introduced when the exact boolean result passes through an STL exporter.
    dest = OUTPUT_DIR/name
    solid.export(dest)
    final = trimesh.load_mesh(dest)
    if not final.is_volume or final.body_count != 1:
        raise ValueError(f"{name}: exported mesh is not one closed solid")
    print(f"{name} mm {final.extents.round(2)}, solid volume {final.volume/1000:.2f} cm³")


if __name__ == "__main__":
    dock = build_dock()
    write_stl(dock, "dock.stl")
    write_stl(fit_gauge(dock), "fit-sample.stl")
