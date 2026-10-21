package shortestpath;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Where the game's world maps draw the world. Most of it is drawn in place on
 * the ground floor, but some maps draw an area elsewhere or show a floor above
 * the ground floor: the Troll Stronghold map draws the stronghold's first floor
 * 64 tiles east and 32 tiles north of where it is. A map position is a packed
 * point at the x and y the map draws.
 *
 * <p>Loaded on first use from {@code /world-map-layout.tsv}, which
 * shortest-path-tooling generates from the game cache. Every real tile and
 * plane is drawn in at most one place, so {@link #toMapPoint} needs no map; the
 * same map position shows different places on different maps, so
 * {@link #toWorldPoint} needs the name of the open map. A missing resource
 * leaves every point where it is.
 */
@Slf4j
public final class WorldMapLayout
{
	private static final String RESOURCE = "/world-map-layout.tsv";

	private static volatile Layout layout;

	private WorldMapLayout()
	{
	}

	/**
	 * Where the world map draws a world point, keeping its plane; the point
	 * itself where the map draws it in place.
	 */
	public static int toMapPoint(int packedWorldPoint)
	{
		final int x = WorldPointUtil.unpackWorldX(packedWorldPoint);
		final int y = WorldPointUtil.unpackWorldY(packedWorldPoint);
		final Area[] areas = layout().byRegion.get(regionId(x, y));
		if (areas != null)
		{
			final int plane = WorldPointUtil.unpackWorldPlane(packedWorldPoint);
			for (Area area : areas)
			{
				if (area.covers(x, y, plane))
				{
					return WorldPointUtil.dxdy(packedWorldPoint, area.dx, area.dy);
				}
			}
		}
		return packedWorldPoint;
	}

	/**
	 * The world point the named map draws at a map position, on the lowest
	 * plane drawn there; the position itself where that map draws the ground
	 * floor in place, or when the map is unknown.
	 */
	public static int toWorldPoint(String mapName, int packedMapPoint)
	{
		final Area[] areas = layout().byMap.get(mapName);
		if (areas != null)
		{
			final int x = WorldPointUtil.unpackWorldX(packedMapPoint);
			final int y = WorldPointUtil.unpackWorldY(packedMapPoint);
			for (Area area : areas)
			{
				if (area.covers(x - area.dx, y - area.dy, area.plane))
				{
					return WorldPointUtil.packWorldPoint(x - area.dx, y - area.dy, area.plane);
				}
			}
		}
		return packedMapPoint;
	}

	/**
	 * Replaces the layout with a parsed copy of the supplied TSV body. Intended
	 * for tests. Pass {@code null} to load the resource again on next use.
	 */
	static synchronized void reload(String tsv)
	{
		layout = tsv == null ? null : parse(tsv);
	}

	private static Layout layout()
	{
		Layout snapshot = layout;
		if (snapshot == null)
		{
			synchronized (WorldMapLayout.class)
			{
				snapshot = layout;
				if (snapshot == null)
				{
					snapshot = load();
					layout = snapshot;
				}
			}
		}
		return snapshot;
	}

	private static Layout load()
	{
		try (InputStream in = WorldMapLayout.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("World map layout resource not found at {}; map positions are used as world points", RESOURCE);
				return parse("");
			}
			return parse(new String(Util.readAllBytes(in), StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.error("Failed to load the world map layout from {}", RESOURCE, e);
			return parse("");
		}
	}

	/**
	 * Rows are {@code World map, Origin (x y plane), Width, Height, Planes,
	 * Offset (dx dy)}; comment rows start with {@code #}. Malformed rows are
	 * skipped with a warning.
	 */
	private static Layout parse(String tsv)
	{
		Map<Integer, List<Area>> byRegion = new HashMap<>();
		Map<String, List<Area>> byMap = new HashMap<>();
		int lineNumber = 0;
		for (String line : tsv.split("\\R"))
		{
			lineNumber++;
			if (line.isEmpty() || line.startsWith("#"))
			{
				continue;
			}
			String[] fields = line.split("\t", -1);
			final Area area;
			try
			{
				area = Area.parse(fields);
			}
			catch (IllegalArgumentException e)
			{
				log.warn("Skipping malformed world map layout row {}: '{}'", lineNumber, line);
				continue;
			}
			byMap.computeIfAbsent(fields[0], k -> new ArrayList<>()).add(area);
			for (int rx = area.x >> 6; rx <= (area.x + area.width - 1) >> 6; rx++)
			{
				for (int ry = area.y >> 6; ry <= (area.y + area.height - 1) >> 6; ry++)
				{
					byRegion.computeIfAbsent((rx << 8) | ry, k -> new ArrayList<>()).add(area);
				}
			}
		}
		return new Layout(byRegion, byMap);
	}

	private static int regionId(int x, int y)
	{
		return ((x >> 6) << 8) | (y >> 6);
	}

	private static final class Layout
	{
		final PrimitiveIntHashMap<Area[]> byRegion;
		final Map<String, Area[]> byMap = new HashMap<>();

		Layout(Map<Integer, List<Area>> regions, Map<String, List<Area>> maps)
		{
			byRegion = new PrimitiveIntHashMap<>(regions.size());
			for (Map.Entry<Integer, List<Area>> region : regions.entrySet())
			{
				byRegion.put(region.getKey(), region.getValue().toArray(new Area[0]));
			}
			for (Map.Entry<String, List<Area>> map : maps.entrySet())
			{
				byMap.put(map.getKey(), map.getValue().toArray(new Area[0]));
			}
		}
	}

	/**
	 * A real rectangle of tiles on {@code planes} planes from {@code plane},
	 * drawn {@code (dx, dy)} away from where it is.
	 */
	private static final class Area
	{
		final int x;
		final int y;
		final int plane;
		final int width;
		final int height;
		final int planes;
		final int dx;
		final int dy;

		private Area(int x, int y, int plane, int width, int height, int planes, int dx, int dy)
		{
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.width = width;
			this.height = height;
			this.planes = planes;
			this.dx = dx;
			this.dy = dy;
		}

		static Area parse(String[] fields)
		{
			if (fields.length != 6)
			{
				throw new IllegalArgumentException("expected 6 columns");
			}
			String[] origin = fields[1].split(" ");
			String[] offset = fields[5].split(" ");
			if (origin.length != 3 || offset.length != 2)
			{
				throw new IllegalArgumentException("expected 'x y plane' and 'dx dy'");
			}
			return new Area(Integer.parseInt(origin[0]), Integer.parseInt(origin[1]), Integer.parseInt(origin[2]),
				Integer.parseInt(fields[2]), Integer.parseInt(fields[3]), Integer.parseInt(fields[4]),
				Integer.parseInt(offset[0]), Integer.parseInt(offset[1]));
		}

		boolean covers(int worldX, int worldY, int worldPlane)
		{
			return worldPlane >= plane && worldPlane < plane + planes
				&& worldX >= x && worldX < x + width
				&& worldY >= y && worldY < y + height;
		}
	}
}
