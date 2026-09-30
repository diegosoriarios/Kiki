package com.diego.kiki.hls

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Remuxes a concatenated MPEG-TS file to MP4 with stream copy (-c copy, NO re-encode).
 *
 * NOTE ON ffmpeg-kit-next: this class targets the `com.arthenica.ffmpegkit` API that
 * arthenica/ffmpeg-kit-next exposes. The current dependency is the maintained
 * Maven Central drop-in (dev.ffmpegkit-maintained:ffmpeg-kit-https) because
 * ffmpeg-kit-next is source-only. Once ffmpeg-kit-next is built locally
 * (Nix workflow, see PLAN.md Phase 4 notes), swap the dependency — no code change.
 */
object Remuxer {

    sealed interface Result {
        data class Success(val output: File) : Result
        data class Failed(val message: String) : Result
    }

    /**
     * -c copy stream copy; aac_adtstoasc converts ADTS AAC frames to the MP4-compatible
     * ASC format (required for TS audio in MP4); +faststart moves the moov atom to the
     * front for instant playback.
     */
    suspend fun remuxTsToMp4(inputTs: File, outputMp4: File): Result =
        suspendCancellableCoroutine { continuation ->
            outputMp4.delete()
            val command = buildString {
                append("-y -i \"${inputTs.absolutePath}\" ")
                append("-c copy ")
                append("-bsf:a aac_adtstoasc ")
                append("-movflags +faststart ")
                append("\"${outputMp4.absolutePath}\"")
            }

            val session = FFmpegKit.executeAsync(command) { s ->
                when {
                    ReturnCode.isSuccess(s.returnCode) ->
                        continuation.resume(Result.Success(outputMp4))

                    ReturnCode.isCancel(s.returnCode) ->
                        continuation.resume(Result.Failed("Remux cancelled"))

                    else -> continuation.resume(
                        Result.Failed(
                            "Remux failed (rc ${s.returnCode}). " +
                                    "The .ts file is kept and still plays in VLC/MX Player."
                        )
                    )
                }
            }

            continuation.invokeOnCancellation {
                FFmpegKit.cancel(session.sessionId)
            }
        }
}
