package shortestpath.pathfinder;

import java.util.Arrays;

import net.runelite.api.Perspective;

/**
 * The moves the experimental sailing search uses for one boat speed.
 * <p>
 * A boat sails in one of 16 headings, and each tick it moves by that heading's velocity rounded to
 * quarter tiles, so the real direction of a heading depends on the speed: at speed 2, NNE moves 3
 * quarter tiles across and 7 up per tick (23.2 degrees, not 22.5). Holding a heading for 1, 2 or 4
 * ticks always lands a whole number of tiles away, with the boat at the same spot within its tile.
 * Each move here is one heading held for the fewest such ticks, so every leg of a path is a direction
 * the boat can actually sail. The search looks for the shortest route rather than the quickest, so each
 * move costs the distance it sails ({@link #length}).
 * <p>
 * Those moves can't wind through narrow channels, so in tight water the search also takes one-tile steps
 * ({@link #stepDx}, {@link #stepDy}), which don't depend on the speed. They go straight, diagonally, or one tile
 * across and two up (and so on) for the headings in between, which point a few degrees off those headings.
 * <p>
 * The search uses the boat's base speed for the whole route, since speed boosts are random and
 * temporary; {@link #ESTIMATE} is the fallback when the base speed can't be read.
 */
public final class SailingMoves
{
	private static final String[] HEADING_NAMES = {
		"S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW", "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE"
	};

	/**
	 * Fallback speed when the boat's base speed can't be read. Straight and diagonal headings go the same
	 * way at every speed; at speed 2 the in-between headings (NNE and so on) are within a couple of degrees
	 * of their direction at the common speeds 2 to 4.
	 */
	public static final double ESTIMATED_SPEED = 2.0;
	public static final SailingMoves ESTIMATE = forSpeed(ESTIMATED_SPEED);

	/**
	 * {@link #length} is in thousandths of a tile, so that adding lengths up is exact.
	 */
	public static final int LENGTH_UNITS_PER_TILE = 1000;

	// The one-tile steps for each heading 0 (south) to 15, in tiles: straight, diagonal, or (1, 2) and the like in
	// between, which points at 26.6 degrees where NNE is 22.5
	private static final int[] STEP_DX = {0, -1, -1, -2, -1, -2, -1, -1, 0, 1, 1, 2, 1, 2, 1, 1};
	private static final int[] STEP_DY = {-1, -2, -1, -1, 0, 1, 1, 2, 1, 2, 1, 1, 0, -1, -1, -2};
	private static final int[] STEP_LENGTHS = new int[STEP_DX.length];

	static
	{
		for (int heading = 0; heading < STEP_DX.length; heading++)
		{
			STEP_LENGTHS[heading] = (int) Math.ceil(Math.hypot(STEP_DX[heading], STEP_DY[heading]) * LENGTH_UNITS_PER_TILE);
		}
	}

	private final double speed;
	private final int[] dx;
	private final int[] dy;
	private final int[] ticks;
	private final int[] lengths;
	private final int[] headings;
	private final String[] names;

	private SailingMoves(double speed, int[] dx, int[] dy, int[] ticks, int[] lengths, int[] headings, String[] names)
	{
		this.speed = speed;
		this.dx = dx;
		this.dy = dy;
		this.ticks = ticks;
		this.lengths = lengths;
		this.headings = headings;
		this.names = names;
	}

	/**
	 * @param tilesPerTick boat speed, e.g. 1.5 or 2.0; must be above zero
	 */
	public static SailingMoves forSpeed(double tilesPerTick)
	{
		int[] dx = new int[HEADING_NAMES.length];
		int[] dy = new int[HEADING_NAMES.length];
		int[] ticks = new int[HEADING_NAMES.length];
		int[] lengths = new int[HEADING_NAMES.length];
		int[] headings = new int[HEADING_NAMES.length];
		String[] names = new String[HEADING_NAMES.length];
		int count = 0;
		for (int heading = 0; heading < HEADING_NAMES.length; heading++)
		{
			// Headings are 128 angle units apart: 0 is south, 512 west, 1024 north, 1536 east
			int angle = heading * 128;
			int quarterX = quarterTiles(-Perspective.SINE[angle] * tilesPerTick / 512.0);
			int quarterY = quarterTiles(-Perspective.COSINE[angle] * tilesPerTick / 512.0);
			if (quarterX == 0 && quarterY == 0)
			{
				continue;
			}
			// Fewest ticks (1, 2 or 4) after which the boat is a whole number of tiles away
			int k = 1;
			while ((k * quarterX) % 4 != 0 || (k * quarterY) % 4 != 0)
			{
				k *= 2;
			}
			dx[count] = k * quarterX / 4;
			dy[count] = k * quarterY / 4;
			ticks[count] = k;
			// Rounded up, so a route never costs less than its real length and the search's estimate stays a lower bound
			lengths[count] = (int) Math.ceil(Math.hypot(dx[count], dy[count]) * LENGTH_UNITS_PER_TILE);
			headings[count] = heading;
			names[count] = HEADING_NAMES[heading];
			count++;
		}
		return new SailingMoves(tilesPerTick, Arrays.copyOf(dx, count), Arrays.copyOf(dy, count),
			Arrays.copyOf(ticks, count), Arrays.copyOf(lengths, count), Arrays.copyOf(headings, count),
			Arrays.copyOf(names, count));
	}

	// The game's velocity along one axis: rounded to local units (128 per tile), then to quarter tiles (32 each)
	private static int quarterTiles(double localUnits)
	{
		double local = Math.signum(localUnits) * Math.round(Math.abs(localUnits));
		return (int) (Math.signum(local) * Math.round(Math.abs(local) / 32.0));
	}

	public double speed()
	{
		return speed;
	}

	public int size()
	{
		return dx.length;
	}

	public int dx(int move)
	{
		return dx[move];
	}

	public int dy(int move)
	{
		return dy[move];
	}

	public int ticks(int move)
	{
		return ticks[move];
	}

	/**
	 * The name of a heading (0 is south, 4 west, 8 north and 12 east), such as "NNE".
	 */
	public static String headingName(int heading)
	{
		return HEADING_NAMES[heading];
	}

	/**
	 * The heading the move holds: 0 is south, 4 west, 8 north and 12 east, like the game's orientation divided by 128.
	 */
	public int heading(int move)
	{
		return headings[move];
	}

	public String name(int move)
	{
		return names[move];
	}

	/**
	 * How far the move sails in a straight line, in thousandths of a tile ({@link #LENGTH_UNITS_PER_TILE}), rounded up.
	 */
	public int length(int move)
	{
		return lengths[move];
	}

	/**
	 * How far the one-tile step facing {@code heading} goes across, in tiles (0 is south, 4 west, 8 north and 12 east).
	 */
	public static int stepDx(int heading)
	{
		return STEP_DX[heading];
	}

	/**
	 * How far the one-tile step facing {@code heading} goes up, in tiles.
	 */
	public static int stepDy(int heading)
	{
		return STEP_DY[heading];
	}

	/**
	 * How far the one-tile step facing {@code heading} goes, in thousandths of a tile, rounded up.
	 */
	public static int stepLength(int heading)
	{
		return STEP_LENGTHS[heading];
	}

	/**
	 * The heading of a step of (dx, dy) tiles between two points of a sailing path, which is a move or a one-tile step,
	 * or -1 if it's neither.
	 */
	public int headingOf(int dx, int dy)
	{
		int move = indexOf(dx, dy);
		if (move >= 0)
		{
			return headings[move];
		}
		for (int heading = 0; heading < STEP_DX.length; heading++)
		{
			if (STEP_DX[heading] == dx && STEP_DY[heading] == dy)
			{
				return heading;
			}
		}
		return -1;
	}

	/**
	 * How far a step of (dx, dy) tiles between two points of a sailing path goes, in thousandths of a tile (see
	 * {@link #length}), or -1 if it's neither a move nor a one-tile step.
	 */
	public int lengthOf(int dx, int dy)
	{
		int move = indexOf(dx, dy);
		if (move >= 0)
		{
			return lengths[move];
		}
		int heading = headingOf(dx, dy);
		return heading < 0 ? -1 : STEP_LENGTHS[heading];
	}

	/**
	 * Index of the move that goes (dx, dy) tiles, or -1 if there is none.
	 */
	public int indexOf(int dx, int dy)
	{
		for (int i = 0; i < this.dx.length; i++)
		{
			if (this.dx[i] == dx && this.dy[i] == dy)
			{
				return i;
			}
		}
		return -1;
	}
}
