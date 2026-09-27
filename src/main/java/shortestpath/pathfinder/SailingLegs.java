package shortestpath.pathfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import shortestpath.WorldPointUtil;

/**
 * Merges the legs of a finished sailing path into fewer, longer legs.
 * <p>
 * Many sailing routes take exactly the same time. At speed 1.5, NNE and NNW move north exactly as
 * fast as N does, so zig-zagging between them reaches a target due north no later than sailing
 * straight N. The search only minimises time, so it can return such a zig-zag. This replaces each
 * stretch of the path with one or two straight legs of real headings, taking the longest stretch it
 * can, but only when the new legs take no more ticks than the stretch they replace and never cross a
 * blocked tile.
 */
public final class SailingLegs
{
	private SailingLegs()
	{
	}

	/**
	 * @param targets the search's targets; a sailing search arrives when it is next to one, so the last
	 *                leg may end on any tile next to the target it reached if that takes fewer legs
	 */
	public static List<PathStep> merge(List<PathStep> path, CollisionMap map, SailingMoves moves, Set<Integer> targets)
	{
		final int n = path.size();
		if (n < 3)
		{
			return path;
		}

		// Ticks from the start to each point, so a replacement can be compared with the stretch it replaces
		int[] ticksTo = new int[n];
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
			ticksTo[i] = ticksTo[i - 1] + moves.ticks(move);
		}

		List<Integer> otherEnds = otherArrivalTiles(path.get(n - 1).getPackedPosition(), targets);
		List<PathStep> merged = new ArrayList<>(n);
		merged.add(path.get(0));
		boolean bankVisited = path.get(0).isBankVisited();
		int i = 0;
		while (i < n - 1)
		{
			int next = i + 1;
			int[] legs = null;
			for (int j = n - 1; j > i + 1; j--)
			{
				int start = path.get(i).getPackedPosition();
				int maxTicks = ticksTo[j] - ticksTo[i];
				legs = fewestLegs(start, path.get(j).getPackedPosition(), maxTicks, map, moves);
				if (j == n - 1)
				{
					for (int end : otherEnds)
					{
						int[] option = fewestLegs(start, end, maxTicks, map, moves);
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
			}
			i = next;
		}
		return merged;
	}

	/**
	 * The fewest straight legs (one, else two) of real headings that sail from {@code from} to {@code to}
	 * in at most {@code maxTicks} ticks without crossing a blocked tile, as pairs of (move, count), or
	 * {@code null} if there are none. Among two-leg options the quickest is used.
	 */
	private static int[] fewestLegs(int from, int to, int maxTicks, CollisionMap map, SailingMoves moves)
	{
		final int x = WorldPointUtil.unpackWorldX(from);
		final int y = WorldPointUtil.unpackWorldY(from);
		final int z = WorldPointUtil.unpackWorldPlane(from);
		final int dx = WorldPointUtil.unpackWorldX(to) - x;
		final int dy = WorldPointUtil.unpackWorldY(to) - y;

		for (int m = 0; m < moves.size(); m++)
		{
			int count = repeats(dx, dy, moves.dx(m), moves.dy(m));
			if (count > 0 && count * moves.ticks(m) <= maxTicks && map.canSailLine(x, y, z, dx, dy))
			{
				return new int[]{m, count};
			}
		}

		int[] best = null;
		int bestTicks = Integer.MAX_VALUE;
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
				int ticks = a * moves.ticks(first) + b * moves.ticks(second);
				if (a < 1 || b < 1 || ticks > maxTicks)
				{
					continue;
				}
				int cornerX = x + a * moves.dx(first);
				int cornerY = y + a * moves.dy(first);
				// How far the corner is from the straight line (scaled): among equally quick options, the more direct wins
				long deviation = Math.abs((long) dx * (cornerY - y) - (long) dy * (cornerX - x));
				if (ticks > bestTicks || (ticks == bestTicks && deviation >= bestDeviation))
				{
					continue;
				}
				if (map.canSailLine(x, y, z, cornerX - x, cornerY - y)
					&& map.canSailLine(cornerX, cornerY, z, dx - (cornerX - x), dy - (cornerY - y)))
				{
					best = new int[]{first, a, second, b};
					bestTicks = ticks;
					bestDeviation = deviation;
				}
			}
		}
		return best;
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

	// Fewer legs wins; with as many legs, fewer ticks wins
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
		return ticks(option, moves) < ticks(current, moves);
	}

	private static int ticks(int[] legs, SailingMoves moves)
	{
		int ticks = 0;
		for (int leg = 0; leg < legs.length; leg += 2)
		{
			ticks += moves.ticks(legs[leg]) * legs[leg + 1];
		}
		return ticks;
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
