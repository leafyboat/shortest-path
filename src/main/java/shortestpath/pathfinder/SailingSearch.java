package shortestpath.pathfinder;

import shortestpath.WorldPointUtil;

/**
 * One experimental sailing search's moves, boat, targets and where it counts as arriving (see {@link Pathfinder}).
 * <p>
 * The path ends as close to a target as the boat can get: the boat's own tile must get within a distance of it (counted
 * like king moves) that's the distance to the nearest tile where the boat fits facing some heading, joined to open
 * water. In open water that's the target itself; at a dock too tight for the hull, as close as it fits.
 * <p>
 * The hull is checked as if the boat sat at the centre of its tile. Where it doesn't quite fit, a move may shift the
 * boat a quarter tile off the centre in one of 8 directions, at an extra cost, so routes only shift where they have to,
 * such as between the islands at the top of the Elid Delta, which a skiff gets through in game. The hull keeps its size,
 * so a skiff still can't get past the stepping stone in the channel into the Lum Lagoon, which leaves 2 tiles of water
 * on one side: in game a raft gets through there but a skiff doesn't.
 */
final class SailingSearch
{
	// How far the boat may shift from the centre of its tile, in local units: a quarter tile, diagonally too
	static final int SHIFT = 32;
	// What a shifted move costs on top of the distance it sails, in thousandths of a tile (SailingMoves.length): a tile
	static final int SHIFT_COST = SailingMoves.LENGTH_UNITS_PER_TILE;
	// The directions it may shift in, across then up
	private static final int[][] SHIFT_DIRECTIONS = {{0, 1}, {1, 1}, {1, 0}, {1, -1}, {0, -1}, {-1, -1}, {-1, 0}, {-1, 1}};
	// How far from a target the search looks for somewhere the boat fits, in tiles
	private static final int MAX_ARRIVAL_DISTANCE = 32;
	// The area looked at for that is a little bigger, so tiles near its edge can be joined to the water outside it
	private static final int ARRIVAL_AREA_RADIUS = MAX_ARRIVAL_DISTANCE + 8;

	final SailingMoves moves;
	/** The boat's hull, or {@code null} to keep only the boat's centre clear. */
	final BoatHull hull;
	/** The hull shifted a quarter tile from the centre of its tile in each direction; empty without a hull. */
	final BoatHull[] shiftedHulls;
	final int[] targets;
	// How close to each target the boat's tile must get to arrive, and how far that can be from the target in a straight
	// line, for the estimate
	private int[] arrivalDistances = new int[0];
	private double arrivalReach;

	SailingSearch(SailingMoves moves, BoatHull hull, int[] targets)
	{
		this.moves = moves;
		this.hull = hull;
		this.shiftedHulls = hull == null ? new BoatHull[0] : shift(hull);
		this.targets = targets;
	}

	/**
	 * Gets ready to search from {@code start}: the tiles the hull overlaps there, shifted or not, count as open, and works
	 * out how close to each target the boat can get.
	 */
	void prepare(CollisionMap map, int start)
	{
		if (hull != null)
		{
			hull.allowStartOverlaps(map, start);
		}
		for (BoatHull shifted : shiftedHulls)
		{
			shifted.allowStartOverlaps(map, start);
		}
		arrivalDistances = new int[targets.length];
		int furthest = 0;
		for (int i = 0; i < targets.length; i++)
		{
			arrivalDistances[i] = arrivalDistance(map, targets[i], start);
			furthest = Math.max(furthest, arrivalDistances[i]);
		}
		// The boat's tile can be that far from the target each way
		arrivalReach = furthest * Math.sqrt(2);
	}

	/**
	 * Whether a boat on the tile has got as close to a target as it can.
	 */
	boolean hasArrived(int packedPosition)
	{
		for (int i = 0; i < targets.length; i++)
		{
			// Counted like king moves, and never close on another plane
			if (WorldPointUtil.distanceBetween(targets[i], packedPosition) <= arrivalDistances[i])
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The furthest from its target any arrival can be, in tiles counted like king moves.
	 */
	int arrivalDistance()
	{
		int furthest = 0;
		for (int distance : arrivalDistances)
		{
			furthest = Math.max(furthest, distance);
		}
		return furthest;
	}

	/**
	 * A lower bound on the distance left from tile (x, y), in the units of {@link SailingMoves#length}: the straight-line
	 * distance to the nearest target, less how far from it the search may arrive. Rounded down, so it never overestimates.
	 */
	int estimate(int x, int y)
	{
		long best = Long.MAX_VALUE;
		for (int target : targets)
		{
			long dx = WorldPointUtil.unpackWorldX(target) - x;
			long dy = WorldPointUtil.unpackWorldY(target) - y;
			best = Math.min(best, dx * dx + dy * dy);
		}
		if (best == Long.MAX_VALUE)
		{
			return 0;
		}
		double tilesLeft = Math.max(0, Math.sqrt(best) - arrivalReach);
		return (int) (SailingMoves.LENGTH_UNITS_PER_TILE * tilesLeft);
	}

	// The hull shifted a quarter tile in each direction, diagonals a quarter tile in all
	private static BoatHull[] shift(BoatHull hull)
	{
		final int diagonal = (int) Math.round(SHIFT / Math.sqrt(2));
		BoatHull[] hulls = new BoatHull[SHIFT_DIRECTIONS.length];
		for (int i = 0; i < hulls.length; i++)
		{
			int[] direction = SHIFT_DIRECTIONS[i];
			int distance = direction[0] != 0 && direction[1] != 0 ? diagonal : SHIFT;
			hulls[i] = hull.shifted(direction[0] * distance, direction[1] * distance);
		}
		return hulls;
	}

	// Whether the boat can sit at the tile, shifting if it has to: the hull fits facing some heading, or without a hull,
	// the tile isn't blocked
	private boolean fits(CollisionMap map, int x, int y, int z)
	{
		if (hull == null)
		{
			return !map.isBlocked(x, y, z);
		}
		if (hull.fitsSomeHeading(map, x, y, z))
		{
			return true;
		}
		for (BoatHull shifted : shiftedHulls)
		{
			if (shifted.fitsSomeHeading(map, x, y, z))
			{
				return true;
			}
		}
		return false;
	}

	// How close the boat's tile can get to the target: the distance to the nearest tile where it fits, and at most
	// MAX_ARRIVAL_DISTANCE. Only counts tiles joined to open water further out or to the start, as a boat can't sail
	// into a pocket it doesn't fit through.
	private int arrivalDistance(CollisionMap map, int target, int start)
	{
		final int targetX = WorldPointUtil.unpackWorldX(target);
		final int targetY = WorldPointUtil.unpackWorldY(target);
		final int z = WorldPointUtil.unpackWorldPlane(target);
		final int radius = ARRIVAL_AREA_RADIUS;
		final int size = 2 * radius + 1;
		boolean[] fit = new boolean[size * size];
		for (int dy = -radius; dy <= radius; dy++)
		{
			for (int dx = -radius; dx <= radius; dx++)
			{
				fit[(dy + radius) * size + dx + radius] = fits(map, targetX + dx, targetY + dy, z);
			}
		}

		// Flood from the tiles the boat fits on along the edge of the area, and from the start
		boolean[] joined = new boolean[size * size];
		int[] queue = new int[size * size];
		int queued = 0;
		for (int i = 0; i < size * size; i++)
		{
			int row = i / size;
			int column = i % size;
			boolean edge = row == 0 || column == 0 || row == size - 1 || column == size - 1;
			boolean isStart = WorldPointUtil.packWorldPoint(targetX + column - radius, targetY + row - radius, z) == start;
			if ((edge || isStart) && fit[i])
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
					if (r >= 0 && c >= 0 && r < size && c < size && !joined[r * size + c] && fit[r * size + c])
					{
						joined[r * size + c] = true;
						queue[queued++] = r * size + c;
					}
				}
			}
		}

		int closest = MAX_ARRIVAL_DISTANCE;
		for (int i = 0; i < size * size; i++)
		{
			if (joined[i])
			{
				closest = Math.min(closest, Math.max(Math.abs(i / size - radius), Math.abs(i % size - radius)));
			}
		}
		return closest;
	}
}
