package shortestpath.pathfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import shortestpath.WorldPointUtil;

/**
 * Merges the legs of a finished sailing path into fewer, longer legs.
 * <p>
 * Many sailing routes are exactly as short as each other: sailing E, SE, E, SE covers the same distance
 * as E, E, SE, SE, and the search only minimises distance, so it can return either. This replaces each
 * stretch of the path with one or two straight legs of real headings, taking the longest stretch it
 * can, but only when the new legs are no longer than the stretch they replace and never cross a
 * blocked tile. With a hull, the new legs and the turns between them must also fit the whole boat.
 */
public final class SailingLegs
{
	private SailingLegs()
	{
	}

	/**
	 * @param hull    the boat's hull, or {@code null} to only keep the boat's centre clear
	 * @param targets the search's targets; a sailing search arrives when it is next to one, so without a hull
	 *                the last leg may end on any tile next to the target it reached if that takes fewer legs
	 */
	public static List<PathStep> merge(List<PathStep> path, CollisionMap map, SailingMoves moves, BoatHull hull,
		Set<Integer> targets)
	{
		final int n = path.size();
		if (n < 3)
		{
			return path;
		}

		// Distance from the start to each point, so a replacement can be compared with the stretch it replaces
		int[] lengthTo = new int[n];
		for (int i = 1; i < n; i++)
		{
			PathStep from = path.get(i - 1);
			PathStep to = path.get(i);
			int move = moves.indexOf(dx(from, to), dy(from, to));
			if (move < 0 || from.isBankVisited() != to.isBankVisited() || plane(from) != plane(to))
			{
				// Only a path made entirely of sailing moves is merged
				return path;
			}
			lengthTo[i] = lengthTo[i - 1] + moves.length(move);
		}

		// With a hull, where the boat ends up next to the target depends on which way it faces, so the end stays put
		List<Integer> otherEnds = hull == null ? otherArrivalTiles(path.get(n - 1).getPackedPosition(), targets) : List.of();
		List<PathStep> merged = new ArrayList<>(n);
		merged.add(path.get(0));
		boolean bankVisited = path.get(0).isBankVisited();
		// The heading the boat faces arriving at point i, for checking the hull has room to turn; leaving the
		// start it can manoeuvre however it needs to, as in the search
		int heading = -1;
		int i = 0;
		while (i < n - 1)
		{
			int next = i + 1;
			int[] legs = null;
			for (int j = n - 1; j > i + 1; j--)
			{
				int start = path.get(i).getPackedPosition();
				int maxLength = lengthTo[j] - lengthTo[i];
				// The legs must also leave room to turn onto the path's next move, which may be kept as it is
				int after = j == n - 1 ? -1 : moves.heading(moveIndex(path, j + 1, moves));
				legs = fewestLegs(start, path.get(j).getPackedPosition(), maxLength, map, moves, hull, heading, after);
				if (j == n - 1)
				{
					for (int end : otherEnds)
					{
						int[] option = fewestLegs(start, end, maxLength, map, moves, hull, heading, after);
						if (isBetter(option, legs, moves))
						{
							legs = option;
						}
					}
				}
				if (legs != null)
				{
					next = j;
					break;
				}
			}
			if (legs == null)
			{
				merged.add(path.get(i + 1));
				heading = moves.heading(moveIndex(path, i + 1, moves));
			}
			else
			{
				int position = path.get(i).getPackedPosition();
				for (int leg = 0; leg < legs.length; leg += 2)
				{
					for (int k = 0; k < legs[leg + 1]; k++)
					{
						position = WorldPointUtil.dxdy(position, moves.dx(legs[leg]), moves.dy(legs[leg]));
						merged.add(new PathStep(position, bankVisited));
					}
				}
				heading = moves.heading(legs[legs.length - 2]);
			}
			i = next;
		}
		return merged;
	}

	// The move from path point i - 1 to point i
	private static int moveIndex(List<PathStep> path, int i, SailingMoves moves)
	{
		return moves.indexOf(dx(path.get(i - 1), path.get(i)), dy(path.get(i - 1), path.get(i)));
	}

	/**
	 * The fewest straight legs (one, else two) of real headings that sail from {@code from} to {@code to}
	 * no further than {@code maxLength} (in the units of {@link SailingMoves#length}) without crossing a
	 * blocked tile, as pairs of (move, count), or {@code null} if there are none. Among two-leg options the
	 * shortest is used. With a hull, the legs and the turns onto them from {@code before}, between them, and
	 * onto {@code after} must fit the whole boat.
	 */
	private static int[] fewestLegs(int from, int to, int maxLength, CollisionMap map, SailingMoves moves, BoatHull hull,
		int before, int after)
	{
		final int x = WorldPointUtil.unpackWorldX(from);
		final int y = WorldPointUtil.unpackWorldY(from);
		final int z = WorldPointUtil.unpackWorldPlane(from);
		final int dx = WorldPointUtil.unpackWorldX(to) - x;
		final int dy = WorldPointUtil.unpackWorldY(to) - y;

		for (int m = 0; m < moves.size(); m++)
		{
			int count = repeats(dx, dy, moves.dx(m), moves.dy(m));
			if (count > 0 && (long) count * moves.length(m) <= maxLength && map.canSailLine(x, y, z, dx, dy)
				&& fitsHull(hull, map, x, y, z, before, moves.heading(m), dx, dy, after))
			{
				return new int[]{m, count};
			}
		}

		int[] best = null;
		long bestLength = Long.MAX_VALUE;
		long bestDeviation = Long.MAX_VALUE;
		for (int first = 0; first < moves.size(); first++)
		{
			for (int second = 0; second < moves.size(); second++)
			{
				// Solve a * first + b * second = (dx, dy) for whole numbers a, b >= 1
				long det = (long) moves.dx(first) * moves.dy(second) - (long) moves.dy(first) * moves.dx(second);
				if (first == second || det == 0)
				{
					continue;
				}
				long aNumerator = (long) dx * moves.dy(second) - (long) dy * moves.dx(second);
				long bNumerator = (long) moves.dx(first) * dy - (long) moves.dy(first) * dx;
				if (aNumerator % det != 0 || bNumerator % det != 0)
				{
					continue;
				}
				int a = (int) (aNumerator / det);
				int b = (int) (bNumerator / det);
				long length = (long) a * moves.length(first) + (long) b * moves.length(second);
				if (a < 1 || b < 1 || length > maxLength)
				{
					continue;
				}
				int cornerX = x + a * moves.dx(first);
				int cornerY = y + a * moves.dy(first);
				// How far the corner is from the straight line (scaled): among equally short options, the more direct wins
				long deviation = Math.abs((long) dx * (cornerY - y) - (long) dy * (cornerX - x));
				if (length > bestLength || (length == bestLength && deviation >= bestDeviation))
				{
					continue;
				}
				if (map.canSailLine(x, y, z, cornerX - x, cornerY - y)
					&& map.canSailLine(cornerX, cornerY, z, dx - (cornerX - x), dy - (cornerY - y))
					&& fitsHull(hull, map, x, y, z, before, moves.heading(first), cornerX - x, cornerY - y, -1)
					&& fitsHull(hull, map, cornerX, cornerY, z, moves.heading(first), moves.heading(second),
					dx - (cornerX - x), dy - (cornerY - y), after))
				{
					best = new int[]{first, a, second, b};
					bestLength = length;
					bestDeviation = deviation;
				}
			}
		}
		return best;
	}

	// Whether the hull has room to turn from before onto heading at (x, y), sail (dx, dy), then turn onto after
	private static boolean fitsHull(BoatHull hull, CollisionMap map, int x, int y, int z, int before, int heading, int dx,
		int dy, int after)
	{
		return hull == null || (hull.canTurn(map, x, y, z, before, heading) && hull.canMove(map, x, y, z, heading, dx, dy)
			&& hull.canTurn(map, x + dx, y + dy, z, heading, after));
	}

	// The other tiles that also count as arriving: those next to the target the path ended next to
	private static List<Integer> otherArrivalTiles(int end, Set<Integer> targets)
	{
		List<Integer> tiles = new ArrayList<>(8);
		for (int target : targets)
		{
			if (WorldPointUtil.distanceBetween(end, target) > 1)
			{
				continue;
			}
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					int tile = WorldPointUtil.dxdy(target, dx, dy);
					if (tile != end && !tiles.contains(tile))
					{
						tiles.add(tile);
					}
				}
			}
			break;
		}
		return tiles;
	}

	// Fewer legs wins; with as many legs, the shorter wins
	private static boolean isBetter(int[] option, int[] current, SailingMoves moves)
	{
		if (option == null)
		{
			return false;
		}
		if (current == null || option.length != current.length)
		{
			return current == null || option.length < current.length;
		}
		return length(option, moves) < length(current, moves);
	}

	private static long length(int[] legs, SailingMoves moves)
	{
		long length = 0;
		for (int leg = 0; leg < legs.length; leg += 2)
		{
			length += (long) moves.length(legs[leg]) * legs[leg + 1];
		}
		return length;
	}

	// How many times (mx, my) fits exactly into (dx, dy) in the same direction, or 0 if it doesn't
	private static int repeats(int dx, int dy, int mx, int my)
	{
		int count;
		if (mx != 0)
		{
			if (dx % mx != 0)
			{
				return 0;
			}
			count = dx / mx;
		}
		else
		{
			if (dx != 0 || dy % my != 0)
			{
				return 0;
			}
			count = dy / my;
		}
		return count > 0 && count * my == dy ? count : 0;
	}

	private static int dx(PathStep from, PathStep to)
	{
		return WorldPointUtil.unpackWorldX(to.getPackedPosition()) - WorldPointUtil.unpackWorldX(from.getPackedPosition());
	}

	private static int dy(PathStep from, PathStep to)
	{
		return WorldPointUtil.unpackWorldY(to.getPackedPosition()) - WorldPointUtil.unpackWorldY(from.getPackedPosition());
	}

	private static int plane(PathStep step)
	{
		return WorldPointUtil.unpackWorldPlane(step.getPackedPosition());
	}
}
