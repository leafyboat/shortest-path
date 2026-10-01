package shortestpath.pathfinder;

import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.DBTableID;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import shortestpath.ShortestPathConfig;
import shortestpath.TeleportationItem;
import shortestpath.WorldPointUtil;

@RunWith(MockitoJUnitRunner.Silent.class)
public class BoatHullTest
{
	// The game's bounds for each boat, in local units: across, along (negative is toward the bow), width, length
	private static final int[] RAFT = {0, 0, 128, 384};
	private static final int[] SKIFF = {0, 0, 256, 640};
	private static final int[] SLOOP = {0, -256, 384, 1280};
	private static final int NORTH = 8;
	private static final int NORTH_NORTH_EAST = 9;
	private static final int EAST = 12;

	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;
	private PathfinderConfig pathfinderConfig;
	private CollisionMap map;

	@Before
	public void before()
	{
		when(config.calculationCutoff()).thenReturn(30);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.NONE);
		when(client.getDBTableRows(DBTableID.Quest.ID)).thenReturn(List.of());
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		pathfinderConfig = new TestPathfinderConfig(client, config);
		pathfinderConfig.refresh();
		map = pathfinderConfig.getMap();
	}

	@Test
	public void testSloopFitsAThreeTileGapOnlyWhenLinedUp()
	{
		// Rocks at (2630, 2548) and (2634, 2548) south of Pest Control leave a gap three tiles wide, which a sloop
		// fits through sailing straight north with the boat on a tile centre, touching the rocks either side
		assertTrue(hull(SLOOP, 0).canMove(map, 2632, 2538, 0, NORTH, 0, 12));
		assertFalse("A quarter tile off centre", hull(SLOOP, -32).canMove(map, 2632, 2538, 0, NORTH, 0, 12));
		assertFalse("Turned a heading", hull(SLOOP, 0).canMove(map, 2632, 2538, 0, NORTH_NORTH_EAST, 0, 12));
		assertFalse("No room to turn in the gap", hull(SLOOP, 0).canTurn(map, 2632, 2548, 0, NORTH, EAST));
		assertTrue("Room to turn in open sea", hull(SLOOP, 0).canTurn(map, 2958, 3095, 0, NORTH, EAST));
	}

	@Test
	public void testSqueezedSloopFitsTheGapOffCentre()
	{
		// A quarter tile off centre the sloop's hull overlaps a rock (see above); a quarter tile smaller each side,
		// it just touches it
		assertFalse(hull(SLOOP, -32).canMove(map, 2632, 2538, 0, NORTH, 0, 12));
		assertTrue(hull(SLOOP, -32).squeezed(32).canMove(map, 2632, 2538, 0, NORTH, 0, 12));
	}

	@Test
	public void testOnlyARaftFitsAOneTileChannel()
	{
		// Rocks either side of x 2796 from y 2962 to 2967 leave a channel one tile wide
		assertTrue(hull(RAFT, 0).canMove(map, 2796, 2963, 0, NORTH, 0, 5));
		assertFalse(hull(SKIFF, 0).canMove(map, 2796, 2963, 0, NORTH, 0, 5));
	}

	@Test
	public void testSloopRouteKeepsItsWholeHullClear()
	{
		// From open sea past the rocks off Mudskipper Point to the Pandemonium
		SailingMoves moves = SailingMoves.forSpeed(3.0);
		int start = WorldPointUtil.packWorldPoint(2948, 3074, 0);
		int target = WorldPointUtil.packWorldPoint(3069, 2983, 0);

		List<PathStep> pointPath = findPath(start, target, moves, null);
		List<PathStep> sloopPath = findPath(start, target, moves, hull(SLOOP, 0));

		assertNotNull("Steering only the boat's centre clear takes a sloop over rocks", hullHit(pointPath, moves, 1.5, 3, 7));
		assertNull(hullHit(sloopPath, moves, 1.5, 3, 7));
	}

	@Test
	public void testSloopEndsOnAnOpenSeaTarget()
	{
		// Open sea between Rimmington and Karamja: the boat itself gets to the target, not just its bow
		int target = WorldPointUtil.packWorldPoint(2963, 3109, 0);
		List<PathStep> path = findPath(WorldPointUtil.packWorldPoint(2948, 3074, 0), target, SailingMoves.forSpeed(1.5), hull(SLOOP, 0));

		assertTrue(WorldPointUtil.distanceBetween(path.get(path.size() - 1).getPackedPosition(), target) <= 1);
	}

	@Test
	public void testSkiffGetsPastTheElidDeltaIslands()
	{
		// From the harbour at the Ruins of Unkah to the top of the Elid Delta, where the channel between the islands
		// is a little narrower than a skiff with the boat on a tile's centre: whole moves can't wind through it, so the
		// search takes one-tile steps there and squeezes past
		int start = WorldPointUtil.packWorldPoint(3143, 2847, 0);
		int target = WorldPointUtil.packWorldPoint(3273, 2738, 0);
		for (double speed : new double[]{1.5, 3.0})
		{
			List<PathStep> path = findPath(start, target, SailingMoves.forSpeed(speed), hull(SKIFF, 0));
			assertTrue("At speed " + speed, WorldPointUtil.distanceBetween(path.get(path.size() - 1).getPackedPosition(), target) <= 1);
		}
	}

	@Test
	public void testRaftDoesntSqueezeWhereItFits()
	{
		// A raft fits past the Elid Delta islands with room to spare, so its route there keeps its whole hull clear
		SailingMoves moves = SailingMoves.forSpeed(1.5);
		List<PathStep> path = findPath(WorldPointUtil.packWorldPoint(3143, 2847, 0), WorldPointUtil.packWorldPoint(3273, 2738, 0),
			moves, hull(RAFT, 0));

		assertNull(hullHit(path, moves, 0.5, 1.5, 1.5, WorldPointUtil.packWorldPoint(3143, 2847, 0)));
	}

	@Test
	public void testSloopArrivesOffADockItCantReach()
	{
		// The Pandemonium's dock is too tight for a sloop's hull to get next to, so it arrives as close as it fits,
		// a few tiles off, instead of searching the whole sea for a way in
		int dock = WorldPointUtil.packWorldPoint(3069, 2983, 0);
		Pathfinder pathfinder = new Pathfinder(pathfinderConfig, WorldPointUtil.packWorldPoint(3048, 3184, 0), Set.of(dock), null,
			SailingMoves.forSpeed(3.0), hull(SLOOP, 0));
		pathfinder.run();

		assertTrue(pathfinder.getResult().isReached());
		List<PathStep> path = pathfinder.getPath();
		assertTrue("Ends near the dock", WorldPointUtil.distanceBetween(path.get(path.size() - 1).getPackedPosition(), dock) <= 4);
	}

	private static BoatHull hull(int[] bounds, int pivotX)
	{
		return BoatHull.fromBounds(bounds[0], bounds[1], bounds[2], bounds[3], pivotX, 0, -1);
	}

	private List<PathStep> findPath(int start, int target, SailingMoves moves, BoatHull hull)
	{
		Pathfinder pathfinder = new Pathfinder(pathfinderConfig, start, Set.of(target), null, moves, hull);
		pathfinder.run();
		assertTrue("Target should be reachable", pathfinder.getResult().isReached());
		return pathfinder.getPath();
	}

	private String hullHit(List<PathStep> path, SailingMoves moves, double halfWidth, double stern, double bow)
	{
		return hullHit(path, moves, halfWidth, stern, bow, WorldPointUtil.UNDEFINED);
	}

	// Checks every move independently of BoatHull: samples the hull's rectangle every quarter tile along the move
	// and tests it against each nearby tile's square, allowing edges to touch. Describes the first blocked tile the
	// hull runs over, or returns null if there are none. Blocked tiles within the hull's turning circle around the
	// start (if given) don't count, as the search treats them as open there.
	private String hullHit(List<PathStep> path, SailingMoves moves, double halfWidth, double stern, double bow, int start)
	{
		final double startRadius = Math.hypot(halfWidth, Math.max(stern, bow)) + 0.01;
		for (int i = 1; i < path.size(); i++)
		{
			int from = path.get(i - 1).getPackedPosition();
			int to = path.get(i).getPackedPosition();
			int x = WorldPointUtil.unpackWorldX(from);
			int y = WorldPointUtil.unpackWorldY(from);
			int dx = WorldPointUtil.unpackWorldX(to) - x;
			int dy = WorldPointUtil.unpackWorldY(to) - y;
			double angle = moves.headingOf(dx, dy) * Math.PI / 8;
			double forwardX = -Math.sin(angle);
			double forwardY = -Math.cos(angle);
			double halfLength = (stern + bow) / 2;
			int samples = (int) Math.ceil(Math.hypot(dx, dy) * 4);
			for (int s = 0; s <= samples; s++)
			{
				double centreX = x + dx * (double) s / samples + forwardX * (bow - stern) / 2;
				double centreY = y + dy * (double) s / samples + forwardY * (bow - stern) / 2;
				for (int tileX = (int) centreX - 8; tileX <= (int) centreX + 8; tileX++)
				{
					for (int tileY = (int) centreY - 8; tileY <= (int) centreY + 8; tileY++)
					{
						double ex = centreX - tileX;
						double ey = centreY - tileY;
						boolean overlaps = Math.abs(ex) < 0.5 + halfLength * Math.abs(forwardX) + halfWidth * Math.abs(forwardY) - 1e-9
							&& Math.abs(ey) < 0.5 + halfLength * Math.abs(forwardY) + halfWidth * Math.abs(forwardX) - 1e-9
							&& Math.abs(ex * forwardX + ey * forwardY) < halfLength + 0.5 * (Math.abs(forwardX) + Math.abs(forwardY)) - 1e-9
							&& Math.abs(ey * forwardX - ex * forwardY) < halfWidth + 0.5 * (Math.abs(forwardX) + Math.abs(forwardY)) - 1e-9;
						boolean nearStart = start != WorldPointUtil.UNDEFINED
							&& Math.hypot(Math.max(0, Math.abs(tileX - WorldPointUtil.unpackWorldX(start)) - 0.5),
							Math.max(0, Math.abs(tileY - WorldPointUtil.unpackWorldY(start)) - 0.5)) < startRadius;
						if (overlaps && !nearStart && map.isBlocked(tileX, tileY, 0))
						{
							return "Move " + i + " from (" + x + ", " + y + ") runs the hull over blocked tile (" + tileX + ", " + tileY + ")";
						}
					}
				}
			}
		}
		return null;
	}
}
