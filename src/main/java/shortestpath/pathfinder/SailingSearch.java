package shortestpath.pathfinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import shortestpath.WorldPointUtil;

/**
 * One experimental sailing search's moves, boat, targets and when it counts as arriving (see {@link Pathfinder}).
 * <p>
 * The path ends once the boat's hull, sitting where the path ends and facing the way it sailed there, covers a target
 * tile; without a hull, once the boat is on the target. If it never can, such as for a target on land or on a dock, the
 * target can't be reached, as on foot, and the path goes as close as the search got.
 * <p>
 * In game a boat sits on a grid of quarter tiles, 16 spots to a tile, and its hull is checked where it sits: a sloop
 * only gets through the 3-wide gap south of Pest Control centred across its tile, and a skiff only gets past the
 * stepping stone in the channel into the Lum Lagoon on its tile's south edge. Every move lands whole tiles away, so the
 * boat keeps its spot for the whole route, and the hull is checked there. Where it doesn't fit, a move may use another
 * of the tile's spots instead, at an extra cost standing in for the player lining the boat up, so routes only do that
 * where they have to.
 */
final class SailingSearch
{
	// Where the game puts a boat within its tile, across and up, in local units from the tile's centre: quarter tiles
	// from the tile's west or south edge to a quarter tile short of its east or north edge, which is the next tile's
	private static final int[] SPOTS = {-64, -32, 0, 32};
	// What a move at another spot costs on top of the distance it sails, in thousandths of a tile (SailingMoves.length):
	// a tile
	static final int SHIFT_COST = SailingMoves.LENGTH_UNITS_PER_TILE;
	private static final int HEADINGS = 16;

	final SailingMoves moves;
	/** The boat's hull, or {@code null} to keep only the boat's centre clear. */
	final BoatHull hull;
	/** The hull at each of the other 15 spots in its tile, nearest first; empty without a hull. */
	final BoatHull[] shiftedHulls;
	final int[] targets;
	// How far from the boat's tile a target can be while the hull covers it, in tiles, for the estimate: the hull's
	// reach plus half a tile's diagonal
	private final double arrivalReach;

	SailingSearch(SailingMoves moves, BoatHull hull, int[] targets)
	{
		this.moves = moves;
		this.hull = hull;
		this.shiftedHulls = hull == null ? new BoatHull[0] : otherSpots(hull);
		this.targets = targets;
		this.arrivalReach = hull == null ? 0 : hull.reach() + Math.sqrt(2) / 2;
	}

	/**
	 * Gets ready to search from {@code start}: the tiles the hull overlaps there, at any spot, count as open.
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
	}

	/**
	 * Whether the boat's hull, on the tile facing {@code heading}, covers a target tile; with the heading unknown (-1),
	 * facing any heading. Without a hull it never does: the boat arrives on the target itself.
	 */
	boolean hasArrived(int packedPosition, int heading)
	{
		if (hull == null)
		{
			return false;
		}
		for (int target : targets)
		{
			if (WorldPointUtil.unpackWorldPlane(target) != WorldPointUtil.unpackWorldPlane(packedPosition))
			{
				continue;
			}
			int dx = WorldPointUtil.unpackWorldX(target) - WorldPointUtil.unpackWorldX(packedPosition);
			int dy = WorldPointUtil.unpackWorldY(target) - WorldPointUtil.unpackWorldY(packedPosition);
			for (int facing = heading < 0 ? 0 : heading; facing < (heading < 0 ? HEADINGS : heading + 1); facing++)
			{
				if (hull.covers(facing, dx, dy))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * A lower bound on the distance left from tile (x, y), in the units of {@link SailingMoves#length}: the straight-line
	 * distance to the nearest target, less how far from it the boat's tile can be while its hull covers it. Rounded down,
	 * so it never overestimates.
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

	// The hull at each of the other spots in its tile, nearest first, so a move that fits close to where the boat is
	// is found sooner
	private static BoatHull[] otherSpots(BoatHull hull)
	{
		List<int[]> shifts = new ArrayList<>();
		for (int x : SPOTS)
		{
			for (int y : SPOTS)
			{
				if (x != hull.pivotX() || y != hull.pivotY())
				{
					shifts.add(new int[]{x - hull.pivotX(), y - hull.pivotY()});
				}
			}
		}
		shifts.sort(Comparator.comparingInt(shift -> shift[0] * shift[0] + shift[1] * shift[1]));
		BoatHull[] hulls = new BoatHull[shifts.size()];
		for (int i = 0; i < hulls.length; i++)
		{
			hulls[i] = hull.shifted(shifts.get(i)[0], shifts.get(i)[1]);
		}
		return hulls;
	}
}
