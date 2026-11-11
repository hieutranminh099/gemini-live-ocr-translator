// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Local modification (2026-10-26): fail closed on incompatible CTC output/dictionary.
package com.paddle.ocr.postprocess

import com.paddle.ocr.model.OCRError

object CTCDecoder {
    private const val BLANK_IDX = 0

    private fun validateShape(outputSize: Int, shape: LongArray, dictionarySize: Int) {
        fun invalid(reason: String): Nothing = throw OCRError.DecodeError("Invalid CTC output: $reason")
        if (shape.size != 3) invalid("expected [batch,time,classes]")
        if (dictionarySize == 0) invalid("empty character dictionary")
        var elements = 1L
        for (dimension in shape) {
            if (dimension <= 0 || dimension > Int.MAX_VALUE.toLong()) invalid("dimensions must be positive Int values")
            // Reject before multiplication: array indexing and offsets below are Int-based.
            if (elements > Int.MAX_VALUE.toLong() / dimension) invalid("tensor exceeds supported array size")
            elements *= dimension
        }
        if (shape[2] != dictionarySize.toLong() + 1L) invalid("classes must equal dictionary size plus blank")
        if (elements != outputSize.toLong()) invalid("tensor size does not match shape")
    }

    fun decode(output: FloatArray, shape: LongArray, characterList: List<String>): List<Pair<String, Float>> {
        validateShape(output.size, shape, characterList.size)
        val batchSize = shape[0].toInt()
        val timeSteps = shape[1].toInt()
        val numClasses = shape[2].toInt()

        val results = mutableListOf<Pair<String, Float>>()
        for (b in 0 until batchSize) {
            val baseOffset = b * timeSteps * numClasses

            val indices = IntArray(timeSteps)
            val probs = FloatArray(timeSteps)
            for (t in 0 until timeSteps) {
                val offset = baseOffset + t * numClasses
                var maxIdx = 0
                var maxVal = output[offset]
                for (c in 1 until numClasses) {
                    val v = output[offset + c]
                    if (v > maxVal) {
                        maxVal = v
                        maxIdx = c
                    }
                }
                indices[t] = maxIdx
                probs[t] = maxVal
            }

            val keptProbs = mutableListOf<Float>()
            val sb = StringBuilder()
            var prevIdx = -1
            for (t in 0 until timeSteps) {
                val idx = indices[t]
                if (idx != BLANK_IDX && idx != prevIdx) {
                    val charIdx = idx - 1
                    // Shape/dictionary validation makes every nonblank class resolvable.
                    sb.append(characterList[charIdx])
                    keptProbs.add(probs[t])
                }
                prevIdx = idx
            }

            val confidence = if (keptProbs.isNotEmpty()) keptProbs.average().toFloat() else 0f
            results.add(Pair(sb.toString(), confidence))
        }
        return results
    }
}
