package com.geeksville.mesh.inference

import kotlin.math.min
import kotlin.math.max

object RuleInference {

    // ---------- KB ----------
    data class HazardInfo(
        val code: String,
        val severity: Double,
        val keywords: List<String>
    )

    private val hazards: Map<String, HazardInfo> = mapOf(
        "landslide" to HazardInfo("LS", 0.85, listOf(
            "landslide", "mudslide", "slope collapsed", "rocks falling",
            "hill collapsed", "soil sliding", "earth slipped"
        )),
        "flood" to HazardInfo("FL", 0.75, listOf(
            "flood", "flooding", "water rising", "overflow", "inundated",
            "submerged", "river overflow"
        )),
        "fire" to HazardInfo("FR", 0.80, listOf(
            "fire", "burning", "smoke", "explosion", "blast"
        )),
        "collapse" to HazardInfo("CL", 0.80, listOf(
            "collapsed", "building down", "bridge down", "caved in",
            "structure collapsed"
        )),
        "storm" to HazardInfo("ST", 0.60, listOf(
            "storm", "cyclone", "strong wind", "heavy rain", "gusts"
        ))
    )

    private val needs: Map<String, List<String>> = mapOf(
        "medical" to listOf("medical", "ambulance", "doctor", "injury", "injured", "bleeding", "unconscious"),
        "rescue" to listOf("rescue", "help", "save", "search team", "evacuate us", "trapped"),
        "water" to listOf("water", "drinking water", "no water"),
        "food" to listOf("food", "hungry", "meals"),
        "shelter" to listOf("shelter", "camp", "temporary housing", "no place to stay"),
        "transport" to listOf("transport", "vehicle", "bus", "boat", "lift", "pickup"),
        "power" to listOf("power", "electricity", "no current", "blackout", "generator"),
        "comms" to listOf("signal", "no network", "no internet", "phone not working")
    )

    private val actions: Map<String, List<String>> = mapOf(
        "evacuate" to listOf("evacuate", "leave now", "run", "move out", "get out", "clear the area"),
        "avoid_route" to listOf("road blocked", "do not come", "avoid", "route closed", "bridge down"),
        "send_help" to listOf("send help", "need help", "please help", "urgent assistance")
    )

    // ---------- Regex ----------
    private val wsRe = Regex("\\s+")
    private val splitSentenceRe = Regex("(?<=[.!?])\\s+")
    private val phoneRe = Regex("(\\+?\\d[\\d\\s\\-]{7,}\\d)")
    private val countRe = Regex("\\b(\\d{1,4})\\s*(people|persons|kids|children|adults|injured|dead|trapped)\\b", RegexOption.IGNORE_CASE)
    private val locRe = Regex("\\b(at|near|in|around)\\s+([A-Za-z][A-Za-z\\s.\\-]{2,40})\\b", RegexOption.IGNORE_CASE)

    private val timePatterns: List<Regex> = listOf(
        Regex("\\b(in|within|next)\\s+(\\d{1,3})\\s*(minute|minutes|hour|hours)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(\\d{1,3})\\s*(minute|minutes|hour|hours)\\b", RegexOption.IGNORE_CASE)
    )

    private val nowPatterns: List<Regex> = listOf(
        Regex("\\bnow\\b", RegexOption.IGNORE_CASE),
        Regex("\\bimmediately\\b", RegexOption.IGNORE_CASE),
        Regex("\\basap\\b", RegexOption.IGNORE_CASE),
        Regex("\\burgent\\b", RegexOption.IGNORE_CASE)
    )

    // ---------- Output models ----------
    data class Gps(val lat: Double, val lon: Double)

    data class Sitrep(
        val hazard: String?,
        val hazardCode: String?,
        val hazardConf: Double,
        val urgency: String,
        val urgencyScore: Int,
        val timeWindowMin: Int?,
        val locationText: String?,
        val gps: Gps?,
        val needs: List<String>,
        val actions: List<String>,
        val counts: Map<String, Int>,
        val contact: String?,
        val confidence: Double
    )

    data class ExtractResult(
        val alertText: String,
        val sitrep: Sitrep,
        val fallbackText: String?
    )

    // ---------- Core logic ----------
    fun normalizeText(t: String): String {
        var x = t.trim()
        x = x.replace(wsRe, " ")
        x = x.replace("mins", "minutes").replace("min", "minutes")
        return x
    }

    fun splitSentences(t: String): List<String> =
        t.split(splitSentenceRe).map { it.trim() }.filter { it.isNotEmpty() }

    private fun scoreKeywords(textLower: String, keywords: List<String>): Double {
        var score = 0.0
        for (kw in keywords) {
            val k = kw.lowercase()
            if (textLower.contains(k)) {
                score += 1.0 + min(k.length / 30.0, 0.75)
            }
        }
        return score
    }

    private fun detectHazard(text: String): Triple<String?, String?, Double> {
        val tl = text.lowercase()
        var bestHazard: String? = null
        var bestCode: String? = null
        var bestScore = 0.0

        for ((hazard, info) in hazards) {
            val s = scoreKeywords(tl, info.keywords)
            if (s > bestScore) {
                bestScore = s
                bestHazard = hazard
                bestCode = info.code
            }
        }

        if (bestScore <= 0.0) return Triple(null, null, 0.0)
        val conf = min(0.35 + 0.18 * bestScore, 0.99)
        return Triple(bestHazard, bestCode, conf)
    }

    private fun detectLabels(text: String, labelMap: Map<String, List<String>>): List<String> {
        val tl = text.lowercase()
        val found = ArrayList<String>()
        for ((label, kws) in labelMap) {
            for (kw in kws) {
                if (tl.contains(kw.lowercase())) {
                    found.add(label)
                    break
                }
            }
        }
        return found
    }

    private fun extractTimeWindowMin(text: String): Pair<Int?, Double> {
        val tl = text.lowercase()

        for (p in nowPatterns) {
            if (p.containsMatchIn(tl)) return Pair(0, 0.85)
        }

        for (pat in timePatterns) {
            val m = pat.find(tl)
            if (m != null) {
                val groups = m.groupValues
                val minutes: Int
                val conf: Double

                if (groups.size == 4 && (groups[1] == "in" || groups[1] == "within" || groups[1] == "next")) {
                    val num = groups[2].toInt()
                    val unit = groups[3]
                    minutes = if (unit.contains("hour")) num * 60 else num
                    conf = 0.75
                } else {
                    // pattern 2: (num)(unit)
                    val num = groups[1].toInt()
                    val unit = groups[2]
                    minutes = if (unit.contains("hour")) num * 60 else num
                    conf = 0.55
                }

                val clipped = max(0, min(minutes, 24 * 60))
                return Pair(clipped, conf)
            }
        }

        return Pair(null, 0.0)
    }

    private fun extractPhone(text: String): String? {
        val m = phoneRe.find(text) ?: return null
        return m.groupValues[1].replace(Regex("[\\s\\-]+"), "")
    }

    private fun extractCounts(text: String): Map<String, Int> {
        val out = mutableMapOf("affected" to 0, "injured" to 0, "trapped" to 0, "dead" to 0)

        for (m in countRe.findAll(text)) {
            val n = m.groupValues[1].toInt()
            val what = m.groupValues[2].lowercase()
            when {
                what.contains("injured") -> out["injured"] = (out["injured"] ?: 0) + n
                what.contains("trapped") -> out["trapped"] = (out["trapped"] ?: 0) + n
                what.contains("dead") -> out["dead"] = (out["dead"] ?: 0) + n
                else -> out["affected"] = (out["affected"] ?: 0) + n
            }
        }
        return out
    }

    private fun extractLocationText(text: String): String? {
        val m = locRe.find(text) ?: return null
        var loc = m.groupValues[2].trim()
        loc = loc.replace(Regex("\\b(right now|immediately|please|urgent)\\b.*$", RegexOption.IGNORE_CASE), "").trim()
        return if (loc.length >= 3) loc else null
    }

    private fun computeUrgency(
        hazard: String?,
        hazardConf: Double,
        timeMin: Int?,
        needs: List<String>,
        actions: List<String>,
        counts: Map<String, Int>,
        text: String
    ): Pair<String, Int> {
        var score = 0

        // hazard severity
        if (hazard != null) {
            val sev = hazards[hazard]?.severity ?: 0.0
            score += (20 * sev).toInt()
        }

        // time window
        if (timeMin != null) {
            score += when {
                timeMin == 0 -> 30
                timeMin <= 60 -> 25
                timeMin <= 180 -> 15
                else -> 5
            }
        }

        // injuries / trapped / dead
        if ((counts["injured"] ?: 0) > 0) score += 25
        if ((counts["trapped"] ?: 0) > 0) score += 30
        if ((counts["dead"] ?: 0) > 0) score += 30

        // actions
        if (actions.contains("evacuate")) score += 25
        if (actions.contains("send_help")) score += 15

        // needs
        if (needs.contains("medical")) score += 15
        if (needs.contains("rescue")) score += 15

        if (Regex("\\burgent\\b|\\bimmediately\\b|\\bnow\\b|\\basap\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            score += 10
        }

        score = max(0, min(score, 100))
        val level = when {
            score >= 65 -> "HIGH"
            score >= 35 -> "MED"
            else -> "LOW"
        }
        return Pair(level, score)
    }

    private fun computeConfidence(
        hazardConf: Double,
        timeConf: Double,
        hasLocation: Boolean,
        hasNeedsOrActions: Boolean
    ): Double {
        var c = 0.0
        c += 0.30 * hazardConf
        c += 0.25 * timeConf
        c += 0.20 * (if (hasLocation) 1.0 else 0.0)
        c += 0.25 * (if (hasNeedsOrActions) 1.0 else 0.0)
        c = min(max(c, 0.0), 1.0)
        return String.format("%.2f", c).toDouble()
    }

    private fun shortenTranscript(text: String, maxChars: Int = 220): String {
        val t = normalizeText(text)
        if (t.length <= maxChars) return t
        return t.take(maxChars - 1).trimEnd() + "…"
    }

    private fun buildAlertString(
        hazardCode: String?,
        urgency: String,
        timeMin: Int?,
        locText: String?,
        needs: List<String>,
        actions: List<String>,
        counts: Map<String, Int>,
        gps: Gps?
    ): String {
        val parts = ArrayList<String>()
        parts.add("[ALERT]")
        parts.add("H=${hazardCode ?: "OT"}")
        parts.add("U=$urgency")

        if (timeMin != null) {
            parts.add(if (timeMin == 0) "T=NOW" else "T=${timeMin}m")
        }

        if (!locText.isNullOrBlank()) parts.add("LOC=${locText.take(28)}")

        val affected = counts["affected"] ?: 0
        val injured = counts["injured"] ?: 0
        val trapped = counts["trapped"] ?: 0

        if (affected > 0) parts.add("PPL=$affected")
        if (injured > 0) parts.add("INJ=$injured")
        if (trapped > 0) parts.add("TRP=$trapped")

        if (needs.isNotEmpty()) parts.add("NEED=" + needs.take(3).joinToString(",") { it.uppercase().take(4) })
        if (actions.isNotEmpty()) parts.add("ACT=" + actions.take(2).joinToString(",") { it.uppercase().take(5) })

        if (gps != null) parts.add("GPS=${"%.4f".format(gps.lat)},${"%.4f".format(gps.lon)}")

        return parts.joinToString("|")
    }

    fun extract(transcript: String, gps: Gps? = null): ExtractResult {
        val t = normalizeText(transcript)
        splitSentences(t) // kept for parity (not required yet)

        val (hazard, hazardCode, hazardConf) = detectHazard(t)
        val needsFound = detectLabels(t, needs)
        val actionsFound = detectLabels(t, actions)

        val (timeMin, timeConf) = extractTimeWindowMin(t)
        val locText = extractLocationText(t)
        val phone = extractPhone(t)
        val counts = extractCounts(t)

        val (urgency, urgencyScore) = computeUrgency(
            hazard = hazard,
            hazardConf = hazardConf,
            timeMin = timeMin,
            needs = needsFound,
            actions = actionsFound,
            counts = counts,
            text = t
        )

        val conf = computeConfidence(
            hazardConf = hazardConf,
            timeConf = timeConf,
            hasLocation = !locText.isNullOrBlank() || gps != null,
            hasNeedsOrActions = needsFound.isNotEmpty() || actionsFound.isNotEmpty()
        )

        val alertText = buildAlertString(
            hazardCode = hazardCode,
            urgency = urgency,
            timeMin = timeMin,
            locText = locText,
            needs = needsFound,
            actions = actionsFound,
            counts = counts,
            gps = gps
        )

        val fallback = if (conf < 0.55) shortenTranscript(t, 220) else null

        val sitrep = Sitrep(
            hazard = hazard,
            hazardCode = hazardCode,
            hazardConf = hazardConf,
            urgency = urgency,
            urgencyScore = urgencyScore,
            timeWindowMin = timeMin,
            locationText = locText,
            gps = gps,
            needs = needsFound,
            actions = actionsFound,
            counts = counts,
            contact = phone,
            confidence = conf
        )

        return ExtractResult(alertText = alertText, sitrep = sitrep, fallbackText = fallback)
    }
}
