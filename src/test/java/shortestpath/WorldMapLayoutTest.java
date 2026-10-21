package shortestpath;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WorldMapLayoutTest
{
	// Test map draws planes 1-2 of a 16x8 area 64 east and 32 south of it, and
	// planes 1-3 of an 8x8 area in place; Other map draws plane 0 of the first
	// area's west half 128 east of it.
	private static final String LAYOUT = "# World map\tOrigin\tWidth\tHeight\tPlanes\tOffset\n"
		+ "Test map\t1600 6400 1\t16\t8\t2\t64 -32\n"
		+ "Test map\t1700 6400 1\t8\t8\t3\t0 0\n"
		+ "Other map\t1600 6400 0\t8\t8\t1\t128 0\n";

	@After
	public void restoreBundledLayout()
	{
		WorldMapLayout.reload(null);
	}

	@Test
	public void drawnTilesMoveToWhereTheMapDrawsThemOnTheirOwnPlane()
	{
		WorldMapLayout.reload(LAYOUT);

		assertEquals(point(1669, 6371, 1), WorldMapLayout.toMapPoint(point(1605, 6403, 1)));
		assertEquals(point(1679, 6375, 2), WorldMapLayout.toMapPoint(point(1615, 6407, 2)));
		assertEquals(point(1733, 6403, 0), WorldMapLayout.toMapPoint(point(1605, 6403, 0)));
	}

	@Test
	public void tilesDrawnInPlaceOrNotCoveredStayWhereTheyAre()
	{
		WorldMapLayout.reload(LAYOUT);

		for (int point : new int[]{
			point(1605, 6403, 3), // above the planes the area covers
			point(1616, 6403, 1), // east of the area
			point(1609, 6403, 0), // east of Other map's area
			point(1703, 6403, 2), // drawn in place
			WorldPointUtil.UNDEFINED})
		{
			assertEquals(point, WorldMapLayout.toMapPoint(point));
		}
	}

	@Test
	public void mapPositionsResolveOnTheNamedMapOnly()
	{
		WorldMapLayout.reload(LAYOUT);
		int click = point(1669, 6371, 0);

		assertEquals(point(1605, 6403, 1), WorldMapLayout.toWorldPoint("Test map", click));
		assertEquals(click, WorldMapLayout.toWorldPoint("Other map", click));
		assertEquals(click, WorldMapLayout.toWorldPoint("Unknown map", click));
		assertEquals(click, WorldMapLayout.toWorldPoint(null, click));
		assertEquals(point(1500, 6000, 0), WorldMapLayout.toWorldPoint("Test map", point(1500, 6000, 0)));
		assertEquals(WorldPointUtil.UNDEFINED, WorldMapLayout.toWorldPoint("Test map", WorldPointUtil.UNDEFINED));
	}

	@Test
	public void clicksResolveToTheLowestPlaneDrawn()
	{
		WorldMapLayout.reload(LAYOUT);

		assertEquals(point(1615, 6407, 1), clickOn("Test map", point(1615, 6407, 2)));
		assertEquals(point(1703, 6403, 1), WorldMapLayout.toWorldPoint("Test map", point(1703, 6403, 0)));
	}

	@Test
	public void malformedRowsAreSkipped()
	{
		WorldMapLayout.reload("Test map\t1600 6400 1\t16\t8\t2\n"
			+ "Test map\t1600 6400\t16\t8\t2\t64 -32\n"
			+ "Test map\t1600 6400 1\tsixteen\t8\t2\t64 -32\n"
			+ "Test map\t1700 6400 1\t8\t8\t3\t0 0\n");

		assertEquals(point(1605, 6403, 1), WorldMapLayout.toMapPoint(point(1605, 6403, 1)));
		assertEquals(point(1703, 6403, 1), WorldMapLayout.toWorldPoint("Test map", point(1703, 6403, 0)));
	}

	@Test
	public void clickingTrollStrongholdFirstFloorFindsUg()
	{
		int ug = point(2827, 10064, 1);
		assertEquals(ug, clickOn("Troll Stronghold", ug));
	}

	@Test
	public void clickingChasmOfFireGreaterDemonFloorFindsIt()
	{
		// #772: the Kourend Underground map draws each Chasm of Fire floor beside the others
		int greaterDemonFloor = point(1438, 10093, 1);
		assertEquals(greaterDemonFloor, clickOn("Kourend Underground", greaterDemonFloor));
	}

	@Test
	public void clickingCamTorumFindsItsFloor()
	{
		// #289: Cam Torum is drawn in place but lies on plane 1
		int camTorum = point(1440, 9550, 1);
		assertEquals(camTorum, clickOn("Cam Torum", camTorum));
	}

	@Test
	public void surfaceClicksAreUnchanged()
	{
		int lumbridge = point(3222, 3218, 0);
		assertEquals(lumbridge, WorldMapLayout.toMapPoint(lumbridge));
		assertEquals(lumbridge, WorldMapLayout.toWorldPoint("Gielinor Surface", lumbridge));
	}

	/**
	 * The world point a right-click on the named map resolves to, where that
	 * map draws the given point; map clicks carry no plane.
	 */
	private static int clickOn(String map, int worldPoint)
	{
		int drawn = WorldMapLayout.toMapPoint(worldPoint);
		return WorldMapLayout.toWorldPoint(map,
			point(WorldPointUtil.unpackWorldX(drawn), WorldPointUtil.unpackWorldY(drawn), 0));
	}

	private static int point(int x, int y, int plane)
	{
		return WorldPointUtil.packWorldPoint(x, y, plane);
	}
}
