package com.blindvision.planning

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A* over a single floor grid that produces **centered, walkable** paths rather
 * than hugging walls.
 *
 * Plain shortest-path A* on a grid sticks to walls: there are many equal-length
 * routes and octile tie-breaking favors the ones that run along obstacles. To
 * get natural indoor pathways we follow the idea behind Voronoi / medial-axis
 * routing (paths equidistant from walls) — see
 * https://medium.com/@nickzuber/procedurally-generating-indoor-pathways-dbf7d7fe4ace
 * — but realize it directly on the grid:
 *
 *  1. Compute a distance-to-nearest-wall field (a chamfer distance transform).
 *     Its ridge is the medial axis / Voronoi skeleton.
 *  2. Add a **clearance cost** that penalizes cells close to walls, so A* is
 *     pulled toward the center of corridors. Open areas (clearance >= [clearanceCap])
 *     get zero penalty, so the planner does not take silly detours in big rooms.
 *  3. **Smooth** the result with line-of-sight shortcutting that refuses to cut
 *     through (or shave the corner of) a wall, removing the staircase look.
 *
 * @param allowDiagonal 8-connectivity with no corner-cutting through walls.
 * @param clearanceWeight strength of the wall-avoidance cost (0 = plain A*).
 * @param clearanceCap distance (in cells) beyond which a cell is "open" — no penalty.
 * @param smooth apply line-of-sight smoothing to the final path.
 * @param smoothMinClearance straightened segments must keep at least this clearance.
 */
class AStarGridPlanner(
    private val allowDiagonal: Boolean = true,
    private val clearanceWeight: Double = 2.5,
    private val clearanceCap: Double = 12.0,
    private val smooth: Boolean = true,
    private val smoothMinClearance: Double = 1.5,
) : GridPlanner {

    /** Distance-to-wall field cached per floor (planner is reused across calls). */
    private val clearanceCache = HashMap<Floor, DoubleArray>()

    override fun plan(floor: Floor, start: GridPos, goal: GridPos): List<GridPos>? {
        if (!floor.traversable(start.x, start.y)) return null
        if (!floor.traversable(goal.x, goal.y)) return null
        val w = floor.width
        val h = floor.height
        val n = w * h
        fun id(x: Int, y: Int) = x * h + y
        val startId = id(start.x, start.y)
        val goalId = id(goal.x, goal.y)
        if (startId == goalId) return listOf(start)

        val useClearance = clearanceWeight > 0.0
        val clear: DoubleArray? = if (useClearance || smooth) clearanceField(floor) else null

        fun penalty(cellId: Int): Double {
            if (!useClearance || clear == null) return 0.0
            val c = clear[cellId]
            return clearanceWeight * (1.0 - min(c, clearanceCap) / clearanceCap)
        }

        val gScore = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val cameFrom = IntArray(n) { -1 }
        val closed = BooleanArray(n)
        gScore[startId] = 0.0

        fun heuristic(x: Int, y: Int): Double {
            val dx = abs(x - goal.x); val dy = abs(y - goal.y)
            return if (allowDiagonal) (dx + dy) + (DIAG - 2.0) * min(dx, dy) else (dx + dy).toDouble()
        }

        val open = PriorityQueue<Node>(compareBy { it.f })
        open.add(Node(startId, heuristic(start.x, start.y)))
        val dirs = if (allowDiagonal) NEIGHBORS8 else NEIGHBORS4

        while (open.isNotEmpty()) {
            val cur = open.poll()
            val cid = cur.id
            if (closed[cid]) continue
            closed[cid] = true
            if (cid == goalId) {
                val raw = reconstruct(cameFrom, startId, goalId, h)
                return if (smooth && clear != null && raw.size > 2) smoothPath(floor, raw, clear, h) else raw
            }
            val cx = cid / h; val cy = cid % h
            for ((dx, dy) in dirs) {
                val nx = cx + dx; val ny = cy + dy
                if (!floor.traversable(nx, ny)) continue
                if (dx != 0 && dy != 0) {
                    if (!floor.traversable(cx + dx, cy) || !floor.traversable(cx, cy + dy)) continue
                }
                val nid = id(nx, ny)
                if (closed[nid]) continue
                val step = if (dx != 0 && dy != 0) DIAG else 1.0
                val tentative = gScore[cid] + step + penalty(nid)
                if (tentative < gScore[nid]) {
                    gScore[nid] = tentative
                    cameFrom[nid] = cid
                    open.add(Node(nid, tentative + heuristic(nx, ny)))
                }
            }
        }
        return null
    }

    private fun reconstruct(cameFrom: IntArray, startId: Int, goalId: Int, h: Int): List<GridPos> {
        val path = ArrayList<GridPos>()
        var cur = goalId
        while (cur != -1) {
            path.add(GridPos(cur / h, cur % h))
            if (cur == startId) break
            cur = cameFrom[cur]
        }
        path.reverse()
        return path
    }

    // --- smoothing -------------------------------------------------------------

    /** Greedy line-of-sight string-pulling, then re-rasterized to adjacent cells. */
    private fun smoothPath(floor: Floor, path: List<GridPos>, clear: DoubleArray, h: Int): List<GridPos> {
        val way = ArrayList<GridPos>()
        way.add(path[0])
        var anchor = 0
        var i = 1
        while (i < path.size - 1) {
            if (!lineOfSight(floor, path[anchor], path[i + 1], clear, h)) {
                way.add(path[i]); anchor = i
            }
            i++
        }
        way.add(path[path.size - 1])

        val out = ArrayList<GridPos>()
        out.add(way[0])
        for (k in 1 until way.size) {
            val line = bresenham(way[k - 1], way[k])
            for (j in 1 until line.size) out.add(line[j])
        }
        return out
    }

    /** True if a straight line a->b stays walkable, keeps clearance, and never shaves a wall corner. */
    private fun lineOfSight(floor: Floor, a: GridPos, b: GridPos, clear: DoubleArray, h: Int): Boolean {
        var x = a.x; var y = a.y
        val x1 = b.x; val y1 = b.y
        val dx = abs(x1 - x); val dy = abs(y1 - y)
        val sx = if (x < x1) 1 else -1
        val sy = if (y < y1) 1 else -1
        var err = dx - dy
        while (true) {
            if (!floor.traversable(x, y)) return false
            val isEndpoint = (x == a.x && y == a.y) || (x == x1 && y == y1)
            if (!isEndpoint && clear[x * h + y] < smoothMinClearance) return false
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            var mx = false; var my = false
            if (e2 > -dy) { err -= dy; x += sx; mx = true }
            if (e2 < dx) { err += dx; y += sy; my = true }
            if (mx && my) {
                // diagonal move: both orthogonal neighbors must be open (no corner cut)
                if (!floor.traversable(x - sx, y) || !floor.traversable(x, y - sy)) return false
            }
        }
        return true
    }

    private fun bresenham(a: GridPos, b: GridPos): List<GridPos> {
        val pts = ArrayList<GridPos>()
        var x = a.x; var y = a.y
        val dx = abs(b.x - x); val dy = abs(b.y - y)
        val sx = if (x < b.x) 1 else -1
        val sy = if (y < b.y) 1 else -1
        var err = dx - dy
        while (true) {
            pts.add(GridPos(x, y))
            if (x == b.x && y == b.y) break
            val e2 = 2 * err
            if (e2 > -dy) { err -= dy; x += sx }
            if (e2 < dx) { err += dx; y += sy }
        }
        return pts
    }

    // --- distance transform ----------------------------------------------------

    /**
     * Chamfer (3,4) distance transform: clearance of each walkable cell to the
     * nearest wall, in cell units. The grid border counts as a wall. Cached.
     */
    private fun clearanceField(floor: Floor): DoubleArray = clearanceCache.getOrPut(floor) {
        val w = floor.width; val h = floor.height; val n = w * h
        val ortho = 3.0; val diag = 4.0
        val inf = 1e9
        val d = DoubleArray(n) { i -> if (floor.traversable(i / h, i % h)) inf else 0.0 }
        fun idx(x: Int, y: Int) = x * h + y

        for (x in 0 until w) for (y in 0 until h) {
            if (d[idx(x, y)] == 0.0) continue
            var m = d[idx(x, y)]
            if (x > 0) m = min(m, d[idx(x - 1, y)] + ortho)
            if (y > 0) m = min(m, d[idx(x, y - 1)] + ortho)
            if (x > 0 && y > 0) m = min(m, d[idx(x - 1, y - 1)] + diag)
            if (x < w - 1 && y > 0) m = min(m, d[idx(x + 1, y - 1)] + diag)
            d[idx(x, y)] = m
        }
        for (x in w - 1 downTo 0) for (y in h - 1 downTo 0) {
            if (d[idx(x, y)] == 0.0) continue
            var m = d[idx(x, y)]
            if (x < w - 1) m = min(m, d[idx(x + 1, y)] + ortho)
            if (y < h - 1) m = min(m, d[idx(x, y + 1)] + ortho)
            if (x < w - 1 && y < h - 1) m = min(m, d[idx(x + 1, y + 1)] + diag)
            if (x > 0 && y < h - 1) m = min(m, d[idx(x - 1, y + 1)] + diag)
            d[idx(x, y)] = m
        }
        // chamfer units -> cells, and cap by distance to the grid border.
        for (x in 0 until w) for (y in 0 until h) {
            val i = idx(x, y)
            if (d[i] == 0.0) continue
            val border = min(min(x + 1, w - x), min(y + 1, h - y)).toDouble()
            d[i] = min(d[i] / ortho, border)
        }
        d
    }

    private class Node(val id: Int, val f: Double)

    private companion object {
        val DIAG = sqrt(2.0)
        val NEIGHBORS4 = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        val NEIGHBORS8 = arrayOf(
            1 to 0, -1 to 0, 0 to 1, 0 to -1,
            1 to 1, 1 to -1, -1 to 1, -1 to -1,
        )
    }
}
