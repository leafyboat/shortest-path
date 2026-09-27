package shortestpath.pathfinder;

import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarbitID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
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
public class SailingMovesTest
{
	// (2946, 3072) to (2971, 3117) is open sea between Rimmington and Karamja
	private static final int OPEN_SEA_START = WorldPointUtil.packWorldPoint(2948, 3074, 0);

	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;
	private PathfinderConfig pathfinderConfig;

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
	}

	@Test
	public void testEstimateUsesTheGameHeadingsAtSpeedTwo()
	{
		SailingMoves moves = SailingMoves.ESTIMATE;
		assertEquals(16, moves.size());
		// Per tick at speed 2 the game moves N (0,8), NNE (3,7), NE (6,6), ENE (7,3), E (8,0) quarter tiles
		assertMove(moves, "N", 0, 2, 1);
		assertMove(moves, "NNE", 3, 7, 4);
		assertMove(moves, "NE", 3, 3, 2);
		assertMove(moves, "ENE", 7, 3, 4);
		assertMove(moves, "E", 2, 0, 1);
		assertMove(moves, "SSW", -3, -7, 4);
	}

	@Test
	public void testHeadingsDependOnSpeed()
	{
		// NNE is (2,6) quarter tiles per tick at speed 1.5 and (5,13) at 3.5; NE is (9,9) at 3
		assertMove(SailingMoves.forSpeed(1.5), "NNE", 1, 3, 2);
		assertMove(SailingMoves.forSpeed(3.5), "NNE", 5, 13, 4);
		assertMove(SailingMoves.forSpeed(3.0), "NE", 9, 9, 4);
	}

	@Test
	public void testSailingSpeedIsTheBoatsBaseSpeed()
	{
		when(client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_BASESPEED)).thenReturn(192);
		pathfinderConfig.refresh();
		assertEquals(1.5, pathfinderConfig.getSailingSpeed(), 0);

		when(client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_BASESPEED)).thenReturn(0);
		pathfinderConfig.refresh();
		assertEquals("Falls back to the estimate", SailingMoves.ESTIMATED_SPEED, pathfinderConfig.getSailingSpeed(), 0);
	}

	@Test
	public void testOpenSeaTakesTheDirectHeading()
	{
		// 15 across and 35 up is 5 NNE moves (20 ticks); N, N, NE five times takes as long but has more legs
		List<PathStep> path = findPath(OPEN_SEA_START, WorldPointUtil.packWorldPoint(2963, 3109, 0), SailingMoves.ESTIMATE);

		assertEquals(6, path.size());
		for (int i = 1; i < path.size(); i++)
		{
			assertEquals("Move " + i + " should be NNE", "NNE", SailingMoves.ESTIMATE.name(moveIndex(path, i)));
		}
	}

	@Test
	public void testRouteAroundLandOnlyUsesBoatHeadings()
	{
		// Port Sarim to The Pandemonium, around Mudskipper Point
		List<PathStep> path = findPath(WorldPointUtil.packWorldPoint(3048, 3184, 0), WorldPointUtil.packWorldPoint(3069, 2983, 0),
			SailingMoves.ESTIMATE);

		CollisionMap map = pathfinderConfig.getMap();
		for (int i = 1; i < path.size(); i++)
		{
			int a = path.get(i - 1).getPackedPosition();
			assertNotEquals("Move " + i + " should be a boat heading", -1, moveIndex(path, i));
			assertTrue("Move " + i + " should not pass over a blocked tile", map.canSailLine(WorldPointUtil.unpackWorldX(a),
				WorldPointUtil.unpackWorldY(a), WorldPointUtil.unpackWorldPlane(a), dx(path, i), dy(path, i)));
		}
	}

	@Test
	public void testSearchWithoutSailingMovesStillWalks()
	{
		List<PathStep> path = findPath(OPEN_SEA_START, WorldPointUtil.packWorldPoint(2968, 3114, 0), null);

		assertEquals("40 walking steps plus the start tile", 41, path.size());
		for (int i = 1; i < path.size(); i++)
		{
			assertEquals("Step " + i + " should move one tile", 1, Math.max(Math.abs(dx(path, i)), Math.abs(dy(path, i))));
		}
	}

	private List<PathStep> findPath(int start, int target, SailingMoves sailingMoves)
	{
		Pathfinder pathfinder = new Pathfinder(pathfinderConfig, start, Set.of(target), null, sailingMoves);
		pathfinder.run();
		assertTrue("Target should be reachable", pathfinder.getResult().isReached());
		return pathfinder.getPath();
	}

	private static void assertMove(SailingMoves moves, String name, int dx, int dy, int ticks)
	{
		int index = moves.indexOf(dx, dy);
		assertNotEquals(name + " should move (" + dx + ", " + dy + ")", -1, index);
		assertEquals(name, moves.name(index));
		assertEquals(name + " ticks", ticks, moves.ticks(index));
	}

	private static int moveIndex(List<PathStep> path, int i)
	{
		return SailingMoves.ESTIMATE.indexOf(dx(path, i), dy(path, i));
	}

	private static int dx(List<PathStep> path, int i)
	{
		return WorldPointUtil.unpackWorldX(path.get(i).getPackedPosition()) - WorldPointUtil.unpackWorldX(path.get(i - 1).getPackedPosition());
	}

	private static int dy(List<PathStep> path, int i)
	{
		return WorldPointUtil.unpackWorldY(path.get(i).getPackedPosition()) - WorldPointUtil.unpackWorldY(path.get(i - 1).getPackedPosition());
	}
}
