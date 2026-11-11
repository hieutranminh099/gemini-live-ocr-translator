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

package com.paddle.ocr

data class EngineConfig(
    val numThreads: Int = 4,
    val allowSpinning: Boolean = true,
)

/** Per-session policy, never modifies the shared process environment or global thread pool. */
object OrtThreadPoolConfig {
    fun entries(allowSpinning: Boolean): Map<String, String> {
        val value = if (allowSpinning) "1" else "0"
        return mapOf("session.intra_op.allow_spinning" to value, "session.inter_op.allow_spinning" to value)
    }
}
