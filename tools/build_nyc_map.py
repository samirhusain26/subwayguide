"""Build the offline map from the public input archives listed in MAP_SOURCES.md.

Run with Pillow and pyshp 2.x. This is a developer tool, never run on the phone.
"""
import argparse
import csv
import fcntl
import io
import json
import hashlib
import os
import shutil
import tempfile
import time
from itertools import combinations
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

import shapefile
from PIL import Image, ImageDraw

NYC_COUNTIES = ('36005', '36047', '36061', '36081', '36085')
REGION_COUNTIES = NYC_COUNTIES + ('36059', '36119', '34003', '34017', '34023', '34039', '34013')


# Local equirectangular projection in metres. North is up.
def point(lon, lat):
    return round((float(lon) + 74.35) * 84300), round((41.01 - float(lat)) * 111100)


def pixel(coords):
    return [(x // 4, y // 4) for x, y in (point(*p[:2]) for p in coords)]


def geo_polygons(draw, geometry, color, hole, project=pixel):
    polygons = geometry['coordinates']
    if geometry['type'] == 'Polygon':
        polygons = [polygons]
    for exterior, *interiors in polygons:
        draw.polygon(project(exterior), fill=color)
        for ring in interiors:
            draw.polygon(project(ring), fill=hole)


def regional_records(path, bounds=(-74.36, 40.41, -73.56, 41.02)):
    reader = shapefile.Reader(str(path))
    yield from reader.iterShapeRecords(bbox=bounds)


def render_surroundings(inputs, output, palette):
    # The wide camera also sees land far west of the city behind the countdown.
    # Use the same 64-metre tile grid as the NYC overview, extended in all directions.
    bounds = (-76.4, 39.3, -72.7, 42.0)
    def regional_pixels(coords):
        return [(x // 64 + 2048, y // 64 + 1536) for x, y in (point(*p[:2]) for p in coords)]
    region = Image.new('P', (4096, 4096), 0)
    region.putpalette(palette)
    draw = ImageDraw.Draw(region)
    for entry in regional_records(inputs / 'states.zip', bounds):
        geo_polygons(draw, entry.shape.__geo_interface__, 1, 0, regional_pixels)
    for county in REGION_COUNTIES:
        for entry in regional_records(inputs / f'water-{county}.zip', bounds):
            geo_polygons(draw, entry.shape.__geo_interface__, 0, 1, regional_pixels)
    for state in ('ny', 'nj'):
        for entry in regional_records(inputs / f'{state}roads.zip', bounds):
            geom = entry.shape
            ends = list(geom.parts) + [len(geom.points)]
            for a, b in zip(ends, ends[1:]):
                draw.line(regional_pixels(geom.points[a:b]), fill=5)
    for col in range(-4, 4):
        for row in range(-3, 5):
            if 0 <= col < 2 and 0 <= row < 2:
                continue
            x, y = (col + 4) * 512, (row + 3) * 512
            region.crop((x, y, x + 512, y + 512)).save(output / f'64-{col}-{row}.png')


def render(inputs, output, shapes):
    # Each color index represents one map layer so local tiles stay small and crisp.
    colors = ['accfd4', 'e0ded2', 'efebdd', 'bfd2ae', 'dbd6c8', 'c5bcaa', 'a7a89c']
    atlas = Image.new('P', (16384, 16384), 0)
    atlas.putpalette([int(c[i:i+2], 16) for c in colors for i in (0, 2, 4)])
    render_surroundings(inputs, output, atlas.getpalette())
    draw = ImageDraw.Draw(atlas)
    for entry in regional_records(inputs / 'states.zip'):
        geo_polygons(draw, entry.shape.__geo_interface__, 1, 0)
    for feature in json.loads((inputs / 'borough.geojson').read_text())['features']:
        geo_polygons(draw, feature['geometry'], 2, 0)
    for feature in json.loads((inputs / 'parks.geojson').read_text())['features']:
        if not feature['properties'].get('retired'):
            geo_polygons(draw, feature['geometry'], 3, 2)
    # Administrative boundaries include riverbeds. Cut water out before adding bridges.
    water = Image.new('1', atlas.size)
    mask = ImageDraw.Draw(water)
    for path in (inputs / f'water-{county}.zip' for county in REGION_COUNTIES):
        for entry in regional_records(path):
            geo_polygons(mask, entry.shape.__geo_interface__, 1, 0)
    atlas.paste(0, (0, 0, *atlas.size), water)
    for path in [inputs / f'roads-{county}.zip' for county in REGION_COUNTIES] + [inputs / 'nyroads.zip', inputs / 'njroads.zip']:
        major = not path.name.startswith('roads-')
        for entry in regional_records(path):
            geom = entry.shape
            ends = list(geom.parts) + [len(geom.points)]
            for start, end in zip(ends, ends[1:]):
                draw.line(pixel(geom.points[start:end]), fill=5 if major else 4, width=4 if major else 2)
    for points in shapes.values():
        draw.line([(x // 4, y // 4) for x, y in points], fill=6, width=4)
    # Downsample from a four-metre drawing for smooth shores, roads and parks.
    # Work a tile at a time so the generator never needs the whole atlas in RGB.
    # Preserve smooth edges in a shared indexed palette, keeping offline tiles compact.
    rgb = [tuple(bytes.fromhex(c)) for c in colors]
    shades = rgb + [tuple((a * step + b * (8-step)) // 8 for a, b in zip(first, second))
                    for first, second in combinations(rgb, 2) for step in range(1, 8)]
    palette = Image.new('P', (1, 1))
    palette.putpalette([channel for shade in shades for channel in shade])
    overview = Image.new('RGB', (1024, 1024))
    for col in range(16):
        for row in range(16):
            x, y = col * 1024, row * 1024
            detail = atlas.crop((x, y, x + 1024, y + 1024)).convert('RGB')
            detail = detail.resize((512, 512), Image.Resampling.LANCZOS)
            indexed = detail.quantize(palette=palette, dither=Image.Dither.NONE)
            indexed.save(output / f'8-{col}-{row}.png')
            overview.paste(detail.resize((64, 64), Image.Resampling.LANCZOS), (col * 64, row * 64))
    for col in range(2):
        for row in range(2):
            x, y = col * 512, row * 512
            overview.crop((x, y, x + 512, y + 512)).save(output / f'64-{col}-{row}.png')


def simplify(points, tolerance=5):
    """Remove vertices within five metres of the route segment."""
    if len(points) <= 2:
        return points
    (x, y), (ex, ey) = points[0], points[-1]
    dx, dy = ex - x, ey - y
    length2 = dx * dx + dy * dy
    worst, pivot = 0, 0
    for i, (px, py) in enumerate(points[1:-1], 1):
        t = min(1, max(0, ((px - x) * dx + (py - y) * dy) / length2)) if length2 else 0
        error = (px - x - t * dx) ** 2 + (py - y - t * dy) ** 2
        if error > worst:
            worst, pivot = error, i
    if worst <= tolerance ** 2:
        return [points[0], points[-1]]
    return simplify(points[:pivot+1], tolerance)[:-1] + simplify(points[pivot:], tolerance)


def schedule(archive, catalog):
    def rows(name):
        with archive.open(name + '.txt') as raw:
            yield from csv.DictReader(io.TextIOWrapper(raw, encoding='utf-8-sig', newline=''))
    stops = {r['stop_id']: r for r in rows('stops')}
    station = {}
    for r in stops.values():
        if r['location_type'] == '1':
            station[r['stop_id']] = [r['stop_name'], *point(r['stop_lon'], r['stop_lat']),
                                      catalog.get(r['stop_id'], {}).get('area', '')]
    by_trip = defaultdict(list)
    for row in rows('stop_times'):
        if row.get('pickup_type') == '1' and row.get('drop_off_type') == '1':
            continue
        by_trip[row['trip_id']].append((int(row['stop_sequence']), row['stop_id']))
    patterns = Counter()
    for trip in rows('trips'):
        ordered = tuple(stop for _, stop in sorted(by_trip[trip['trip_id']]))
        if len(ordered) >= 2:
            patterns[(trip['route_id'], trip['shape_id'], trip['trip_headsign'], ordered)] += 1
    shapes = defaultdict(list)
    for r in rows('shapes'):
        shapes[r['shape_id']].append((int(r['shape_pt_sequence']), point(r['shape_pt_lon'], r['shape_pt_lat'])))
    shapes = {k: [xy for _, xy in sorted(v)] for k, v in shapes.items()}
    routes = defaultdict(list)
    coverage = set()
    for (route, shape, headsign, platforms), count in patterns.most_common():
        if shape not in shapes:
            raise ValueError(f'Missing shape {shape}')
        for stop in platforms:
            if stops[stop]['parent_station'] not in station or stop[-1] not in ('N', 'S'):
                raise ValueError(f'Unknown platform {stop}')
        routes[route].append([shape, headsign, list(platforms)])
        coverage.update((p[:-1], route, p[-1]) for p in platforms[:-1])
    required = {(sid, r, d) for sid, info in catalog.items() for r, dirs in info['routeDirections'].items() for d in dirs}
    if required - coverage:
        raise ValueError(f'Unmapped selections: {sorted(required - coverage)}')
    print(f'{len(station)} stations; {len(routes)} routes; {len(required)} selections; {len(patterns)} patterns')
    return {'stations': station, 'routes': dict(routes),
            'shapes': {k: [n for xy in simplify(pts) for n in xy] for k, pts in shapes.items()}}, shapes


def validate_atlas(output, metadata, manifest=None):
    """Check the complete runtime atlas before any generated files are promoted."""
    expected = {f'8-{col}-{row}.png' for col in range(16) for row in range(16)}
    expected |= {f'64-{col}-{row}.png' for col in range(-4, 4) for row in range(-3, 5)}
    actual = {p.name for p in output.glob('*.png')}
    if actual != expected:
        raise ValueError(f'Atlas tile set mismatch: missing={sorted(expected-actual)} unexpected={sorted(actual-expected)}')
    for name in sorted(expected):
        with Image.open(output / name) as tile:
            if tile.size != (512, 512):
                raise ValueError(f'Invalid dimensions for {name}: {tile.size}')
            tile.verify()
    stations = metadata.get('stations', {})
    shapes = metadata.get('shapes', {})
    for route, patterns in metadata.get('routes', {}).items():
        for pattern in patterns:
            if len(pattern) != 3 or pattern[0] not in shapes or len(pattern[2]) < 2:
                raise ValueError(f'Invalid scheduled pattern for {route}: {pattern[:2]}')
            if len(shapes[pattern[0]]) < 4 or len(shapes[pattern[0]]) % 2:
                raise ValueError(f'Invalid shape reference {pattern[0]}')
            for platform in pattern[2]:
                if platform[:-1] not in stations or platform[-1:] not in ('N', 'S'):
                    raise ValueError(f'Invalid platform reference {platform}')
    if manifest is None:
        manifest = json.loads((output / 'manifest.json').read_text())
    expected_files = expected | {'network.json'}
    if manifest.get('format') != 1 or not manifest.get('gtfsVersion'):
        raise ValueError('Atlas manifest is missing its format or GTFS version')
    recorded = manifest.get('files')
    if not isinstance(recorded, dict) or set(recorded) != expected_files:
        raise ValueError('Atlas manifest file list does not match the complete tile grid')
    for name, expected_digest in recorded.items():
        if digest(output / name) != expected_digest:
            raise ValueError(f'Atlas file digest mismatch: {name}')
    if manifest.get('gtfsSha256') is not None and len(manifest['gtfsSha256']) != 64:
        raise ValueError('Invalid GTFS archive digest')


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def build_manifest(inputs, gtfs, archive, staging):
    feed_version = 'not-provided'
    if 'feed_info.txt' in archive.namelist():
        with archive.open('feed_info.txt') as raw:
            info = next(csv.DictReader(io.TextIOWrapper(raw, encoding='utf-8-sig', newline='')), {})
            feed_version = info.get('feed_version') or info.get('feed_start_date') or feed_version
    sources = {}
    for path in sorted(p for p in inputs.iterdir() if p.is_file()):
        sources[path.name] = digest(path)
    files = {path.name: digest(path) for path in staging.iterdir() if path.name != 'manifest.json'}
    (staging / 'manifest.json').write_text(json.dumps({
        'format': 1, 'gtfsVersion': feed_version, 'gtfsSha256': digest(gtfs),
        'inputs': sources, 'files': files,
    }, sort_keys=True, indent=2) + '\n')


def recover_previous(output, root):
    """Restore the newest validated generator-owned backup if promotion was interrupted."""
    if output.exists():
        return
    for candidate in sorted(root.glob('.nyc-atlas-previous-*'), reverse=True):
        try:
            metadata = json.loads((candidate / 'network.json').read_text())
            manifest = json.loads((candidate / 'manifest.json').read_text())
            validate_atlas(candidate, metadata, manifest)
            os.replace(candidate, output)
            return
        except (OSError, ValueError, json.JSONDecodeError):
            continue


def cleanup_stale_staging(root):
    """Remove only interrupted staging dirs created under this generator's reserved prefix."""
    for candidate in root.glob('.nyc-atlas-staging-*'):
        if candidate.parent == root and candidate.is_dir() and not candidate.is_symlink():
            shutil.rmtree(candidate)


def promote(staging, output, root):
    previous = root / ('.nyc-atlas-previous-' + str(time.time_ns()))
    moved_old = False
    try:
        if output.exists():
            os.replace(output, previous)
            moved_old = True
        os.replace(staging, output)
    except Exception:
        if moved_old and not output.exists():
            os.replace(previous, output)
        raise


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('inputs', type=Path)
    p.add_argument('gtfs', type=Path)
    args = p.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = root / 'android/app/src/main/assets/nyc'
    output.parent.mkdir(parents=True, exist_ok=True)
    lock_path = root / '.nyc-atlas-build.lock'
    with lock_path.open('a') as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        cleanup_stale_staging(root)
        recover_previous(output, root)
        catalog = json.loads((root / 'server/stations.json').read_text())['stations']
        staging = Path(tempfile.mkdtemp(prefix='.nyc-atlas-staging-', dir=root))
        try:
            with zipfile.ZipFile(args.gtfs) as archive:
                metadata, shapes = schedule(archive, {s['id']: s for s in catalog})
                (staging / 'network.json').write_text(json.dumps(metadata, separators=(',', ':')))
                render(args.inputs, staging, shapes)
                build_manifest(args.inputs, args.gtfs, archive, staging)
            validate_atlas(staging, metadata)
            promote(staging, output, root)
        finally:
            if staging.exists(): shutil.rmtree(staging)
    # Keep the prior complete generation outside the APK asset tree for recovery.


if __name__ == '__main__':
    main()
