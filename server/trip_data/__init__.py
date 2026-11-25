"""Rider-facing directions for services which use opposite compass platforms."""
import json
from importlib.resources import files


_OVERRIDES = json.loads(files(__package__).joinpath("trip_direction_overrides.json").read_text(encoding="utf-8"))


def direction_name(station, route, platform):
    override = _OVERRIDES.get(station.get("id"), {})
    for name, mapping in override.get("directions", {}).items():
        if mapping.get(route.upper()) == platform:
            return name
    return station.get("directions", {}).get(platform) or {"N": "Northbound", "S": "Southbound"}[platform]
