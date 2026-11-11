package com.gemini.ocr.ocr

/** Counts only: no recognized strings, image bytes, API payloads or credentials. */
data class OcrLineDiagnostics(
    val detected: Int,
    val noiseRejected: Int,
    val confidenceRejected: Int,
    val policyRejected: Int,
    val geometryRejected: Int,
    val budgetRejected: Int,
    val eligible: Int,
    val returned: Int,
) {
    init {
        require(listOf(detected, noiseRejected, confidenceRejected, policyRejected,
            geometryRejected, budgetRejected, eligible, returned).all { it >= 0 })
        require(detected == noiseRejected + confidenceRejected + policyRejected +
            geometryRejected + budgetRejected + eligible)
        require(returned <= eligible)
    }

    fun fields(): Array<Pair<String, Any?>> = arrayOf(
        "detected_lines" to detected, "noise_rejected" to noiseRejected,
        "confidence_rejected" to confidenceRejected, "policy_rejected" to policyRejected,
        "geometry_rejected" to geometryRejected, "budget_rejected" to budgetRejected,
        "eligible_lines" to eligible, "returned_lines" to returned,
        "line_cap_dropped" to eligible - returned,
    )
}
