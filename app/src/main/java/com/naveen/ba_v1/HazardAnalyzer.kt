package com.naveen.ba_v2

import kotlin.math.max

/**
 * Lightweight per-frame tracker to detect approaching hazards using YOLO boxes.
 * Heuristics:
 * - Treat vehicles and fast movers as hazardous (see harmfulClasses)
 * - If IoU matches across frames and area increases rapidly and/or cy moves down, flag hazard
 */
class HazardAnalyzer(private val harmfulClasses: Set<String>) {

	private data class Track(var clsName: String, var x1: Float, var y1: Float, var x2: Float, var y2: Float, var area: Float, var cy: Float, var lastSeenMs: Long)

	private val tracks = mutableListOf<Track>()

	fun updateAndHasApproachingHazard(boxes: List<BoundingBox>): Boolean {
		val now = System.currentTimeMillis()
		var hazard = false
		// Match current boxes to previous tracks by IoU and class
		for (b in boxes) {
			if (!harmfulClasses.contains(b.clsName)) continue
			val area = b.w * b.h
			val matched = tracks.maxByOrNull { iou(b, it) * if (it.clsName == b.clsName) 1.0f else 0.0f }
			if (matched != null && matched.clsName == b.clsName && iou(b, matched) > 0.3f) {
				val areaGrowth = if (matched.area > 1e-6f) (area - matched.area) / matched.area else 0f
				val cyDownward = b.cy - matched.cy
				val centerish = b.cx in (1f/3f)..(2f/3f)
				val frontHalf = b.cy > 0.45f // lower half of image tends to be closer on upright phone
				// Heuristic trigger
				if ((areaGrowth > 0.35f || cyDownward > 0.06f) && centerish && frontHalf) {
					hazard = true
				}
				// Update track
				matched.x1 = b.x1; matched.y1 = b.y1; matched.x2 = b.x2; matched.y2 = b.y2
				matched.area = area; matched.cy = b.cy; matched.lastSeenMs = now
			} else {
				tracks.add(Track(b.clsName, b.x1, b.y1, b.x2, b.y2, area, b.cy, now))
			}
		}
		// Drop stale tracks
		tracks.removeAll { now - it.lastSeenMs > Constants.HAZARD_TRACK_STALE_MS }
		return hazard
	}

	private fun iou(b: BoundingBox, t: Track): Float {
		val x1 = maxOf(b.x1, t.x1)
		val y1 = maxOf(b.y1, t.y1)
		val x2 = minOf(b.x2, t.x2)
		val y2 = minOf(b.y2, t.y2)
		val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
		val a1 = b.w * b.h
		val a2 = (t.x2 - t.x1) * (t.y2 - t.y1)
		val denom = a1 + a2 - inter
		return if (denom <= 0f) 0f else inter / denom
	}
}


