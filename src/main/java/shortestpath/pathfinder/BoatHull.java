package shortestpath.pathfinder;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static net.runelite.api.Constants.REGION_SIZE;
import net.runelite.api.Perspective;
import shortestpath.WorldPointUtil;

/**
 * The hull of the player's boat, so that a sailing search only takes moves and turns the whole boat fits
 * through, not just its centre.
 * <p>
 * A boat is a rectangle in its own frame (the game's bounds for its world entity: the raft is 1 by 3 tiles,
 * the skiff 2 by 5 and the sloop 3 by 10, reaching further toward its bow), turned to face its heading. Holding
 * a heading sweeps the rectangle along the move, and turning on the spot swings it round to the new heading. A
 * move or turn is allowed only if no blocked tile overlaps what the hull covers. A tile blocks a boat if it's
 * blocked or has a wall on any side, as Chart Plotter treats them. Touching a blocked tile's edge is fine, so a
 * 3-wide sloop lined up exactly fits a 3-wide gap, as players have seen in game.
 * <p>
 * An instance belongs to one search: it caches the shapes it sweeps and the blocked tiles it has looked at.
 */
public final class BoatHull
{
	private static final int HEADINGS = 16;
	// The game's angles: 2048 per turn, 128 per heading
	private static final int ANGLES = 2048;
	private static final int ANGLES_PER_HEADING = ANGLES / HEADINGS;
	// Turns are checked every quarter of a heading, as the hull's ends swing a long way between headings
	private static final int TURN_STEP = ANGLES_PER_HEADING / 4;
	private static final double EPSILON = 1e-6;
	// How far from a target the search looks for somewhere the hull fits, in tiles
	private static final int MAX_ARRIVAL_DISTANCE = 32;
	// How many tiles the boat must be able to arrive on, so the search doesn't have to hit a lone tile exactly
	private static final int MIN_ARRIVAL_TILES = 8;
	private static final int REGION_SHIFT = Integer.numberOfTrailingZeros(REGION_SIZE);
	private static final int REGION_MASK = REGION_SIZE - 1;

	// The rectangle in the boat's own frame, in local units (128 per tile): its centre across and along the boat
	// (the bow is toward negative along) and its size
	private final int boundsX;
	private final int boundsY;
	private final int boundsWidth;
	private final int boundsHeight;
	// Where the boat sits within its tile, in local units from the tile's centre
	private final int pivotX;
	private final int pivotY;
	// The heading the boat faces where the search starts (0 is south, 4 west, 8 north, 12 east), or -1
	private final int startHeading;
	// The furthest the pivot is from any tile the hull covers, facing any heading
	private final double reach;

	// Tiles the hull covers, relative to the tile it starts on: {first row, then each row's first and last column}
	private final Map<Long, int[]> shapes = new HashMap<>();
	// Everything the hull covers turning all the way round on the spot
	private final int[] turningCircle;
	// Blocked tiles, as one bit per tile and one long per row of each region, filled in as the search reaches them
	private final long[][] blockedRows;
	private final SplitFlagMap.RegionExtent extent;
	// Whether the hull fits at each turn angle on the last tile a turn was checked on: 0 unknown, 1 yes, 2 no
	private int turnTile = WorldPointUtil.UNDEFINED;
	private boolean turnTileOpen;
	private final byte[] turnAngleFits = new byte[ANGLES / TURN_STEP];
	// The search's targets, and how close to each the hull must get to arrive (see prepareTargets)
	private int[] targets = new int[0];
	private int[] targetDistances = new int[0];
	private double arrivalReach;

	private BoatHull(int boundsX, int boundsY, int boundsWidth, int boundsHeight, int pivotX, int pivotY, int startHeading)
	{
		this.boundsX = boundsX;
		this.boundsY = boundsY;
		this.boundsWidth = boundsWidth;
		this.boundsHeight = boundsHeight;
		this.pivotX = pivotX;
		this.pivotY = pivotY;
		this.startHeading = startHeading;
		extent = SplitFlagMap.getRegionExtents();
		blockedRows = new long[(extent.getWidth() + 1) * (extent.getHeight() + 1) * 4][];

		double furthest = 0;
		for (int heading = 0; heading < HEADINGS; heading++)
		{
			int[] shape = sweep(heading, 0, 0);
			for (int row = 0; row < rows(shape); row++)
			{
				int dy = shape[0] + row;
				furthest = Math.max(furthest, Math.hypot(shape[1 + 2 * row], dy));
				furthest = Math.max(furthest, Math.hypot(shape[2 + 2 * row], dy));
			}
		}
		reach = furthest;
		arrivalReach = reach + Math.sqrt(2);

		// A many-sided polygon around the circle the hull's far corners trace, in tiles
		double radius = 0;
		for (int across = -1; across <= 1; across += 2)
		{
			for (int along = -1; along <= 1; along += 2)
			{
				radius = Math.max(radius, Math.hypot(boundsX + across * boundsWidth / 2.0, boundsY + along * boundsHeight / 2.0));
			}
		}
		final int sides = 64;
		radius = radius / Perspective.LOCAL_TILE_SIZE / Math.cos(Math.PI / sides);
		double[] circle = new double[2 * sides];
		for (int i = 0; i < sides; i++)
		{
			circle[2 * i] = (double) pivotX / Perspective.LOCAL_TILE_SIZE + radius * Math.cos(2 * Math.PI * i / sides);
			circle[2 * i + 1] = (double) pivotY / Perspective.LOCAL_TILE_SIZE + radius * Math.sin(2 * Math.PI * i / sides);
		}
		turningCircle = rasterize(circle);
	}

	/**
	 * @param boundsX      centre of the boat's rectangle across the boat, in local units (128 per tile)
	 * @param boundsY      centre of the rectangle along the boat, in local units; negative is toward the bow
	 * @param boundsWidth  width of the rectangle, in local units
	 * @param boundsHeight length of the rectangle, in local units
	 * @param pivotX       where the boat sits within its tile, in local units from the tile's centre
	 * @param pivotY       where the boat sits within its tile, in local units from the tile's centre
	 * @param startHeading the heading the boat faces where the search starts (0 is south, 4 west, 8 north and 12
	 *                     east), or -1 if unknown
	 * @return the hull, or {@code null} if the bounds are empty
	 */
	public static BoatHull fromBounds(int boundsX, int boundsY, int boundsWidth, int boundsHeight, int pivotX, int pivotY,
		int startHeading)
	{
		if (boundsWidth <= 0 || boundsHeight <= 0)
		{
			return null;
		}
		return new BoatHull(boundsX, boundsY, boundsWidth, boundsHeight, pivotX, pivotY, startHeading);
	}

	/**
	 * How far the boat's pivot can be from a target when the boat arrives, in tiles; for the search's
	 * estimate of the time left.
	 */
	public double arrivalReach()
	{
		return arrivalReach;
	}

	/**
	 * Treats the blocked tiles the hull overlaps where the search starts as open, such as a dock the boat is
	 * moored against: the boat is evidently there, so it can sail away. With the heading unknown, it treats
	 * everything the hull would overlap turning on the spot there as open instead.
	 */
	public void allowStartOverlaps(CollisionMap map, int start)
	{
		final int x = WorldPointUtil.unpackWorldX(start);
		final int y = WorldPointUtil.unpackWorldY(start);
		final int z = WorldPointUtil.unpackWorldPlane(start);
		final int[] shape = startHeading >= 0 ? sweep(startHeading, 0, 0) : turningCircle;
		for (int row = 0; row < rows(shape); row++)
		{
			int tileY = y + shape[0] + row;
			for (int tileX = x + shape[1 + 2 * row]; tileX <= x + shape[2 + 2 * row]; tileX++)
			{
				long[] rows = regionRows(map, tileX >> REGION_SHIFT, tileY >> REGION_SHIFT, z);
				if (rows != null)
				{
					rows[tileY & REGION_MASK] &= ~(1L << (tileX & REGION_MASK));
				}
			}
		}
	}

	/**
	 * Works out how close to each target the hull can get: next to it if there's room, otherwise as close as it
	 * fits, such as off the dock when the target is on land. The boat arrives when it gets that close.
	 *
	 * @param start where the search starts, which is always reachable
	 */
	public void prepareTargets(CollisionMap map, int[] targets, int start)
	{
		this.targets = targets.clone();
		targetDistances = new int[targets.length];
		int furthest = 1;
		for (int i = 0; i < targets.length; i++)
		{
			targetDistances[i] = arrivalDistance(map, targets[i], start);
			furthest = Math.max(furthest, targetDistances[i]);
		}
		arrivalReach = reach + furthest * Math.sqrt(2);
	}

	/**
	 * Whether the boat, at tile (x, y, z), can sit there as close to one of the targets as the hull gets (see
	 * prepareTargets), facing whichever way that takes; the player turns it to moor.
	 */
	public boolean hasArrived(CollisionMap map, int x, int y, int z)
	{
		for (int i = 0; i < targets.length; i++)
		{
			int dx = WorldPointUtil.unpackWorldX(targets[i]) - x;
			int dy = WorldPointUtil.unpackWorldY(targets[i]) - y;
			if (WorldPointUtil.unpackWorldPlane(targets[i]) != z
				|| Math.max(Math.abs(dx), Math.abs(dy)) > reach + targetDistances[i])
			{
				continue;
			}
			for (int heading = 0; heading < HEADINGS; heading++)
			{
				int[] shape = sweep(heading, 0, 0);
				if (distance(shape, dx, dy) <= targetDistances[i] && isClear(map, x, y, z, shape))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Whether the hull, facing {@code heading}, can sail (dx, dy) tiles from tile (x, y, z) without overlapping a
	 * blocked tile anywhere along the way.
	 */
	public boolean canMove(CollisionMap map, int x, int y, int z, int heading, int dx, int dy)
	{
		return isClear(map, x, y, z, sweep(heading, dx, dy));
	}

	/**
	 * Whether the hull has room at tile (x, y, z) to turn on the spot from facing {@code from} to facing
	 * {@code to}, the shorter way round (either way for a half turn). Always true if either heading is -1
	 * (unknown).
	 */
	public boolean canTurn(CollisionMap map, int x, int y, int z, int from, int to)
	{
		if (from < 0 || to < 0 || from == to)
		{
			return true;
		}
		int tile = WorldPointUtil.packWorldPoint(x, y, z);
		if (tile != turnTile)
		{
			turnTile = tile;
			turnTileOpen = isClear(map, x, y, z, turningCircle);
			Arrays.fill(turnAngleFits, (byte) 0);
		}
		if (turnTileOpen)
		{
			return true;
		}
		int steps = Math.floorMod(to - from, HEADINGS);
		if (steps == HEADINGS / 2)
		{
			return canTurnWay(map, x, y, z, from, to, 1) || canTurnWay(map, x, y, z, from, to, -1);
		}
		return canTurnWay(map, x, y, z, from, to, steps < HEADINGS / 2 ? 1 : -1);
	}

	private boolean canTurnWay(CollisionMap map, int x, int y, int z, int from, int to, int direction)
	{
		// Where it starts and ends facing are covered by the moves before and after the turn
		final int end = to * ANGLES_PER_HEADING;
		for (int angle = Math.floorMod(from * ANGLES_PER_HEADING + direction * TURN_STEP, ANGLES); angle != end;
			angle = Math.floorMod(angle + direction * TURN_STEP, ANGLES))
		{
			int index = angle / TURN_STEP;
			if (turnAngleFits[index] == 0)
			{
				turnAngleFits[index] = isClear(map, x, y, z, shape(angle, 0, 0)) ? (byte) 1 : (byte) 2;
			}
			if (turnAngleFits[index] == 2)
			{
				return false;
			}
		}
		return true;
	}

	// How close to the target the hull must get to arrive: the smallest distance from the target to the hull
	// that the boat can sit at on at least MIN_ARRIVAL_TILES tiles, and at least 1. Only counts tiles joined up
	// with open water further out or with the start, as a boat can't sail into a pocket it doesn't fit through.
	private int arrivalDistance(CollisionMap map, int target, int start)
	{
		final int targetX = WorldPointUtil.unpackWorldX(target);
		final int targetY = WorldPointUtil.unpackWorldY(target);
		final int z = WorldPointUtil.unpackWorldPlane(target);
		final int radius = MAX_ARRIVAL_DISTANCE + (int) Math.ceil(reach);
		final int size = 2 * radius + 1;
		// For each tile around the target: the closest the hull gets to the target facing a way it fits, or -1
		// if it doesn't fit facing any way
		int[] closest = new int[size * size];
		for (int dy = -radius; dy <= radius; dy++)
		{
			for (int dx = -radius; dx <= radius; dx++)
			{
				int best = -1;
				for (int heading = 0; heading < HEADINGS; heading++)
				{
					int[] shape = sweep(heading, 0, 0);
					int distance = Math.max(1, distance(shape, -dx, -dy));
					if ((best < 0 || distance < best) && isClear(map, targetX + dx, targetY + dy, z, shape))
					{
						best = distance;
					}
				}
				closest[(dy + radius) * size + dx + radius] = best;
			}
		}

		// Flood from the tiles the hull fits on along the edge of the area, and from the start
		boolean[] joined = new boolean[size * size];
		int[] queue = new int[size * size];
		int queued = 0;
		for (int i = 0; i < size * size; i++)
		{
			int row = i / size;
			int column = i % size;
			boolean edge = row == 0 || column == 0 || row == size - 1 || column == size - 1;
			boolean isStart = WorldPointUtil.packWorldPoint(targetX + column - radius, targetY + row - radius, z) == start;
			if ((edge || isStart) && closest[i] >= 0)
			{
				joined[i] = true;
				queue[queued++] = i;
			}
		}
		for (int head = 0; head < queued; head++)
		{
			int row = queue[head] / size;
			int column = queue[head] % size;
			for (int dy = -1; dy <= 1; dy++)
			{
				for (int dx = -1; dx <= 1; dx++)
				{
					int r = row + dy;
					int c = column + dx;
					if (r >= 0 && c >= 0 && r < size && c < size && !joined[r * size + c] && closest[r * size + c] >= 0)
					{
						joined[r * size + c] = true;
						queue[queued++] = r * size + c;
					}
				}
			}
		}

		int[] tilesAtDistance = new int[MAX_ARRIVAL_DISTANCE + 1];
		for (int i = 0; i < size * size; i++)
		{
			if (joined[i] && closest[i] <= MAX_ARRIVAL_DISTANCE)
			{
				tilesAtDistance[closest[i]]++;
			}
		}
		int tiles = 0;
		for (int distance = 1; distance <= MAX_ARRIVAL_DISTANCE; distance++)
		{
			tiles += tilesAtDistance[distance];
			if (tiles >= MIN_ARRIVAL_TILES)
			{
				return distance;
			}
		}
		return MAX_ARRIVAL_DISTANCE;
	}

	// The distance, counted in tiles like king moves, from the tile (dx, dy) to the nearest tile in a shape
	private static int distance(int[] shape, int dx, int dy)
	{
		int best = Integer.MAX_VALUE;
		for (int row = 0; row < rows(shape); row++)
		{
			int across = Math.max(0, Math.max(shape[1 + 2 * row] - dx, dx - shape[2 + 2 * row]));
			best = Math.min(best, Math.max(across, Math.abs(shape[0] + row - dy)));
		}
		return best;
	}

	private static int rows(int[] shape)
	{
		return (shape.length - 1) / 2;
	}

	private boolean isClear(CollisionMap map, int x, int y, int z, int[] shape)
	{
		for (int row = 0; row < rows(shape); row++)
		{
			if (anyBlocked(map, x + shape[1 + 2 * row], x + shape[2 + 2 * row], y + shape[0] + row, z))
			{
				return false;
			}
		}
		return true;
	}

	// Whether any tile from (x0, y) to (x1, y) blocks the boat
	private boolean anyBlocked(CollisionMap map, int x0, int x1, int y, int z)
	{
		for (int x = x0; x <= x1; )
		{
			int last = Math.min(x1, x | REGION_MASK);
			long[] rows = regionRows(map, x >> REGION_SHIFT, y >> REGION_SHIFT, z);
			if (rows == null)
			{
				return true;
			}
			int lo = x & REGION_MASK;
			long mask = (-1L >>> (63 - ((last & REGION_MASK) - lo))) << lo;
			if ((rows[y & REGION_MASK] & mask) != 0)
			{
				return true;
			}
			x = last + 1;
		}
		return false;
	}

	private long[] regionRows(CollisionMap map, int regionX, int regionY, int z)
	{
		if (regionX < extent.getMinX() || regionX > extent.getMaxX() || regionY < extent.getMinY()
			|| regionY > extent.getMaxY() || z < 0 || z > 3)
		{
			return null;
		}
		int index = ((regionX - extent.getMinX()) + (regionY - extent.getMinY()) * (extent.getWidth() + 1)) * 4 + z;
		long[] rows = blockedRows[index];
		if (rows == null)
		{
			rows = new long[REGION_SIZE];
			final int baseX = regionX << REGION_SHIFT;
			final int baseY = regionY << REGION_SHIFT;
			// Which tiles are blocked, including a border one tile wide around the region
			final int size = REGION_SIZE + 2;
			boolean[] blocked = new boolean[size * size];
			for (int row = 0; row < size; row++)
			{
				for (int column = 0; column < size; column++)
				{
					blocked[row * size + column] = map.isBlocked(baseX + column - 1, baseY + row - 1, z);
				}
			}
			for (int row = 0; row < REGION_SIZE; row++)
			{
				long bits = 0;
				for (int column = 0; column < REGION_SIZE; column++)
				{
					final int x = baseX + column;
					final int y = baseY + row;
					final int i = (row + 1) * size + column + 1;
					// A side that can't be crossed into an open tile has a wall on it
					boolean wall = (!map.n(x, y, z) && !blocked[i + size]) || (!map.s(x, y, z) && !blocked[i - size])
						|| (!map.e(x, y, z) && !blocked[i + 1]) || (!map.w(x, y, z) && !blocked[i - 1]);
					if (blocked[i] || wall)
					{
						bits |= 1L << column;
					}
				}
				rows[row] = bits;
			}
			blockedRows[index] = rows;
		}
		return rows;
	}

	// The tiles the hull overlaps while sailing (dx, dy) tiles facing heading, relative to the tile it starts on
	private int[] sweep(int heading, int dx, int dy)
	{
		return shape(heading * ANGLES_PER_HEADING, dx, dy);
	}

	// The tiles the hull overlaps while sailing (dx, dy) tiles facing the game's angle (2048 per turn, 0 south,
	// 512 west, 1024 north), relative to the tile it starts on
	private int[] shape(int angle, int dx, int dy)
	{
		long key = ((long) angle << 40) | ((long) (dx & 0xFFFFF) << 20) | (dy & 0xFFFFF);
		int[] shape = shapes.get(key);
		if (shape == null)
		{
			shape = rasterize(corners(angle, dx, dy));
			shapes.put(key, shape);
		}
		return shape;
	}

	// The hull's corners where it starts and where it ends, in tiles around the start tile's centre:
	// {x0, y0, x1, y1, ...}. They're turned in whole local units with the game's sine table, the way the game
	// turns models, so they're exact and edges that line up with tile edges do so exactly.
	private double[] corners(int angle, int dx, int dy)
	{
		final int cos = Perspective.COSINE[angle];
		final int sin = Perspective.SINE[angle];
		double[] points = new double[16];
		int i = 0;
		for (int end = 0; end <= 1; end++)
		{
			for (int across = -1; across <= 1; across += 2)
			{
				for (int along = -1; along <= 1; along += 2)
				{
					int modelX = boundsX + across * boundsWidth / 2;
					int modelY = boundsY + along * boundsHeight / 2;
					int x = pivotX + end * dx * Perspective.LOCAL_TILE_SIZE + ((modelX * cos + modelY * sin) >> 16);
					int y = pivotY + end * dy * Perspective.LOCAL_TILE_SIZE + ((modelY * cos - modelX * sin) >> 16);
					points[i++] = (double) x / Perspective.LOCAL_TILE_SIZE;
					points[i++] = (double) y / Perspective.LOCAL_TILE_SIZE;
				}
			}
		}
		return points;
	}

	// The tiles a convex shape (the hull of the given points) overlaps by more than touching, as rows
	private static int[] rasterize(double[] points)
	{
		double[] hull = convexHull(points);
		int n = hull.length / 2;
		double minY = Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		for (int i = 0; i < n; i++)
		{
			minY = Math.min(minY, hull[2 * i + 1]);
			maxY = Math.max(maxY, hull[2 * i + 1]);
		}
		// Tile row r covers y from r - 0.5 to r + 0.5, and column c covers x from c - 0.5 to c + 0.5
		int firstRow = (int) Math.floor(minY - 0.5 + EPSILON) + 1;
		int lastRow = (int) Math.ceil(maxY + 0.5 - EPSILON) - 1;
		int[] columns = new int[2 * Math.max(0, lastRow - firstRow + 1)];
		int rows = 0;
		int shapeFirstRow = firstRow;
		for (int row = firstRow; row <= lastRow; row++)
		{
			double[] range = xRange(hull, n, row - 0.5 + EPSILON, row + 0.5 - EPSILON);
			int first = (int) Math.floor(range[0] - 0.5 + EPSILON) + 1;
			int last = (int) Math.ceil(range[1] + 0.5 - EPSILON) - 1;
			if (first > last)
			{
				// Only the rows at either end can be empty, where the shape barely reaches them
				if (rows == 0)
				{
					shapeFirstRow = row + 1;
				}
				continue;
			}
			columns[2 * rows] = first;
			columns[2 * rows + 1] = last;
			rows++;
		}
		int[] shape = new int[1 + 2 * rows];
		shape[0] = shapeFirstRow;
		System.arraycopy(columns, 0, shape, 1, 2 * rows);
		return shape;
	}

	// The smallest and largest x of a convex polygon between two heights
	private static double[] xRange(double[] polygon, int n, double low, double high)
	{
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		for (int i = 0; i < n; i++)
		{
			double x1 = polygon[2 * i];
			double y1 = polygon[2 * i + 1];
			double x2 = polygon[2 * ((i + 1) % n)];
			double y2 = polygon[2 * ((i + 1) % n) + 1];
			if (y1 >= low && y1 <= high)
			{
				min = Math.min(min, x1);
				max = Math.max(max, x1);
			}
			for (double level : new double[]{low, high})
			{
				if ((y1 - level) * (y2 - level) < 0)
				{
					double x = x1 + (level - y1) * (x2 - x1) / (y2 - y1);
					min = Math.min(min, x);
					max = Math.max(max, x);
				}
			}
		}
		return new double[]{min, max};
	}

	// Andrew's monotone chain; returns the hull's corners in order as {x0, y0, x1, y1, ...}
	private static double[] convexHull(double[] points)
	{
		int n = points.length / 2;
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++)
		{
			order[i] = i;
		}
		Arrays.sort(order, (a, b) -> points[2 * a] != points[2 * b]
			? Double.compare(points[2 * a], points[2 * b]) : Double.compare(points[2 * a + 1], points[2 * b + 1]));
		int[] hull = new int[2 * n + 1];
		int k = 0;
		for (int j = 0; j < n; j++)
		{
			while (k >= 2 && cross(points, hull[k - 2], hull[k - 1], order[j]) <= 0)
			{
				k--;
			}
			hull[k++] = order[j];
		}
		int lower = k + 1;
		for (int j = n - 2; j >= 0; j--)
		{
			while (k >= lower && cross(points, hull[k - 2], hull[k - 1], order[j]) <= 0)
			{
				k--;
			}
			hull[k++] = order[j];
		}
		// The last corner is the first one again
		k--;
		double[] result = new double[2 * k];
		for (int i = 0; i < k; i++)
		{
			result[2 * i] = points[2 * hull[i]];
			result[2 * i + 1] = points[2 * hull[i] + 1];
		}
		return result;
	}

	private static double cross(double[] p, int o, int a, int b)
	{
		return (p[2 * a] - p[2 * o]) * (p[2 * b + 1] - p[2 * o + 1]) - (p[2 * a + 1] - p[2 * o + 1]) * (p[2 * b] - p[2 * o]);
	}
}
