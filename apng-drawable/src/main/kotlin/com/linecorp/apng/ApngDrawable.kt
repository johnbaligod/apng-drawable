//
// Copyright 2018 LINE Corporation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.linecorp.apng

import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.view.animation.AnimationUtils
import androidx.annotation.DrawableRes
import androidx.annotation.IntRange
import androidx.annotation.RawRes
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import com.linecorp.apng.ApngDrawable.Companion.decode
import com.linecorp.apng.decoder.Apng
import com.linecorp.apng.decoder.ApngException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * An animated [Drawable] that plays the frames of an animated PNG.
 *
 * This drawable holds [apng] which contains the width, height, image count, duration
 * and repeat count from APNG meta data. These meta data can be obtained with [decode]
 * function.
 *
 * Also [apng] has other responsibility to draw given frame to a canvas. [ApngDrawable]
 * delegates [apng] to draw a frame.
 */
class ApngDrawable @VisibleForTesting internal constructor(
    /**
     * Stored shared state and data between mutated ApngDrawables.
     */
    private var apngState: ApngState
) : Drawable(), Animatable2Compat {

    /**
     * The duration to animate one loop of APNG animation.
     */
    @IntRange(from = 0, to = Int.MAX_VALUE.toLong())
    val durationMillis: Int = apngState.apng.duration

    /**
     * The number of frames included in this APNG image.
     */
    @IntRange(from = 1, to = Int.MAX_VALUE.toLong())
    val frameCount: Int = apngState.apng.frameCount

    /**
     * The number of bytes required for the non-native layer.
     * In most cases, this is the number of bytes used to display one frame.
     */
    @IntRange(from = 0, to = Int.MAX_VALUE.toLong())
    val frameByteCount: Int = apngState.apng.byteCount

    /**
     * The size of memory required for this image, including all frames loaded in the native layer.
     */
    @IntRange(from = 0, to = Int.MAX_VALUE.toLong())
    val allocationByteCount: Long = apngState.apng.allFrameByteCount + frameByteCount

    /**
     * The number of times to loop this APNG image. The value must be a signed value or special
     * values.
     *
     * The special values are:
     *  - [LOOP_FOREVER] indicates infinite looping.
     *  - [LOOP_INTRINSIC] indicated the default looping count.
     */
    @IntRange(from = LOOP_INTRINSIC.toLong(), to = Int.MAX_VALUE.toLong())
    var loopCount: Int = apngState.apng.loopCount
        set(value) {
            if (value < LOOP_INTRINSIC) {
                throw IllegalArgumentException(
                    "`loopCount` must be a signed value or special values. (value = $value)"
                )
            }
            field = if (value == LOOP_INTRINSIC) apngState.apng.loopCount else value
        }

    /**
     * Whether this drawable has already been destroyed or not.
     */
    val isRecycled: Boolean = apngState.apng.isRecycled

    private val paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val animationCallbacks: MutableList<Animatable2Compat.AnimationCallback> = arrayListOf()
    private val currentRepeatCount: Int
        get() = (animationElapsedTimeMillis / durationMillis).toInt() + 1
    /**
     * [currentFrameIndex] is the index to indicate which frame should show at that time.
     * [currentFrameIndex] is calculated with APNG meta data and elapsed time after animation
     * started.
     * [durationMillis] is the duration to animate one loop of APNG animation. [frameCount]
     * is number of APNG frames. For example, if one loop duration is 1000ms, image count is 10 and
     * elapsed time is 2100ms, the frame index should be 1 of 3rd loop.
     * If this image isn't infinite looping image and [animationElapsedTimeMillis] is larger than
     * total duration of this image's animation, returns always last frame index.
     */
    private val currentFrameIndex: Int
        get() = if (loopCount != 0 && exceedsRepeatCountLimitation()) {
            frameCount - 1
        } else {
            (animationElapsedTimeMillis % durationMillis * frameCount / durationMillis).toInt()
        }

    private var scaledWidth: Int = apngState.width
    private var scaledHeight: Int = apngState.height
    private var isStarted: Boolean = false
    private var animationElapsedTimeMillis: Long = 0L
    private var animationPrevDrawTimeMillis: Long? = null

    /**
     * The density scale at which this drawable will be rendered.
     * If [sourceDensity] is [Bitmap.DENSITY_NONE], this value is ignored.
     */
    private var targetDensity: Int = DisplayMetrics.DENSITY_DEFAULT
        set(value) {
            if (field != value) {
                field = if (value == 0) DisplayMetrics.DENSITY_DEFAULT else value
                computeBitmapSize()
                invalidateSelf()
            }
        }

    init {
        bounds.set(0, 0, apngState.width, apngState.height)
    }

    override fun draw(canvas: Canvas) {
        if (isStarted) {
            progressAnimationElapsedTime()
        }
        val drawIntervalMillis = apngState.apng.drawWithIndex(
            currentFrameIndex,
            canvas,
            null,
            bounds,
            paint
        )
        if (isStarted) {
            scheduleSelf({ invalidateSelf() }, drawIntervalMillis.toLong())
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun getOpacity(): Int = PixelFormat.TRANSPARENT

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getIntrinsicWidth(): Int = scaledWidth

    override fun getIntrinsicHeight(): Int = scaledHeight

    override fun isRunning(): Boolean = isStarted

    override fun start() {
        isStarted = true
        animationPrevDrawTimeMillis = null
        invalidateSelf()
    }

    override fun stop() {
        isStarted = false
        invalidateSelf()
    }

    /**
     * Adds [callback] to listen animation start and end event of this apng image.
     * Animation end event will happen after exceeds [loopCount].
     *
     * **Note**:
     * The animation end callback will be called only when animation stops beyond the loop count
     * limit.
     * In order to prevent potential memory leak, unregister the callback or clear all callbacks.
     *
     * @param callback The [Animatable2Compat.AnimationCallback] to notify events.
     *
     */
    override fun registerAnimationCallback(callback: Animatable2Compat.AnimationCallback) {
        animationCallbacks.add(callback)
    }

    override fun unregisterAnimationCallback(
        callback: Animatable2Compat.AnimationCallback
    ): Boolean = animationCallbacks.remove(callback)

    override fun clearAnimationCallbacks() = animationCallbacks.clear()

    override fun getConstantState(): ConstantState? {
        return apngState
    }

    override fun mutate(): Drawable {
        apngState = ApngState(apngState)
        return this
    }

    /**
     * Sets the density scale at which this drawable will be rendered.
     * If [sourceDensity] is [Bitmap.DENSITY_NONE], this value is ignored.
     *
     * @param metrics The [DisplayMetrics] indicating the density scale for this drawable.
     */
    fun setTargetDensity(metrics: DisplayMetrics) {
        targetDensity = metrics.densityDpi
    }

    /**
     * Seeks frame to given position and show the image with target frame.
     * If given position is larger than total duration of animation,
     *
     * @param positionMillis Time to seek in milliseconds
     * @throws IllegalArgumentException If [positionMillis] is less than 0
     */
    fun seekTo(@IntRange(from = 0L, to = Long.MAX_VALUE) positionMillis: Long) {
        if (positionMillis < 0) {
            throw IllegalArgumentException("positionMillis must be positive value")
        }
        animationPrevDrawTimeMillis = null
        animationElapsedTimeMillis = positionMillis
        invalidateSelf()
    }

    /**
     * Releases resources managed by the native layer.
     * Call this image when it is no longer used.
     */
    fun recycle() = apngState.apng.recycle()

    private fun progressAnimationElapsedTime() {
        val currentTimeMillis = apngState.currentTimeProvider.invoke()
        val animationPrevDrawTimeMillisSnapShot = animationPrevDrawTimeMillis
        animationElapsedTimeMillis = if (animationPrevDrawTimeMillisSnapShot == null) {
            animationElapsedTimeMillis
        } else {
            animationElapsedTimeMillis + currentTimeMillis - animationPrevDrawTimeMillisSnapShot
        }
        animationPrevDrawTimeMillis = currentTimeMillis

        if (isStarted) {
            if (isFirstFrame() && isFirstLoop()) {
                animationCallbacks.forEach {
                    it.onAnimationStart(this)
                }
            } else if (isLastFrame() && isNotLastLoop()) {
                animationCallbacks.forEach {
                    (it as? WithRepeatAnimationCallback)
                        ?.onRepeat(this, loopCount, currentRepeatCount)
                }
            }
        }
        if (exceedsRepeatCountLimitation()) {
            isStarted = false
            animationCallbacks.forEach {
                it.onAnimationEnd(this)
            }
        }
    }

    private fun isFirstFrame(): Boolean = currentFrameIndex == 0

    private fun isLastFrame(): Boolean = currentFrameIndex == frameCount - 1

    private fun isFirstLoop(): Boolean = currentRepeatCount == 1

    private fun isNotLastLoop(): Boolean = currentRepeatCount < loopCount

    private fun exceedsRepeatCountLimitation(): Boolean {
        if (loopCount == 0) {
            return false
        }
        return currentRepeatCount > loopCount
    }

    private fun computeBitmapSize() {
        scaledWidth = scaleFromDensity(apngState.width, apngState.sourceDensity, targetDensity)
        scaledHeight = scaleFromDensity(apngState.height, apngState.sourceDensity, targetDensity)
        bounds.set(0, 0, scaledWidth, scaledHeight)
    }

    internal class ApngState(
        val apng: Apng,
        /**
         * The width size of this image in [sourceDensity].
         */
        @IntRange(from = 1, to = Int.MAX_VALUE.toLong())
        val width: Int,
        /**
         * The height size of this image in [sourceDensity].
         */
        @IntRange(from = 1, to = Int.MAX_VALUE.toLong())
        val height: Int,
        /**
         * The source image density.
         */
        val sourceDensity: Int = Bitmap.DENSITY_NONE,
        val currentTimeProvider: () -> Long = { AnimationUtils.currentAnimationTimeMillis() }
    ) : ConstantState() {

        constructor(apngState: ApngState) : this(
            apngState.apng.copy(),
            apngState.width,
            apngState.height,
            apngState.sourceDensity,
            apngState.currentTimeProvider
        )

        override fun newDrawable(): Drawable = ApngDrawable(ApngState(this))

        override fun getChangingConfigurations(): Int = 0
    }

    companion object {

        /**
         * The constant value for [loopCount] that indicates infinite looping.
         *
         * @see [loopCount]
         */
        const val LOOP_FOREVER = 0

        /**
         * The constant value for [loopCount] that indicates the original looping count.
         *
         * @see [loopCount]
         */
        const val LOOP_INTRINSIC = -1

        private fun scaleFromDensity(size: Int, sourceDensity: Int, targetDensity: Int): Int {
            return if (sourceDensity == Bitmap.DENSITY_NONE ||
                targetDensity == Bitmap.DENSITY_NONE ||
                sourceDensity == targetDensity
            ) {
                size
            } else {
                (size * targetDensity + (sourceDensity shr 1)) / sourceDensity
            }
        }

        // region `decode` functions
        /**
         * Creates [ApngDrawable] from resource.
         *
         * @param res The [Resources] instance to get resource from.
         * @param id Raw resource id or drawable resource id.
         * @param width An optional width value to specify width size manually.
         * @param height An optional height value to specify height size manually.
         *
         * @return Decoded drawable object.
         *
         * @throws ApngException When decoding failed.
         * @throws Resources.NotFoundException If the given id does not exist.
         * @throws IOException When opening failed.
         * @throws IllegalArgumentException When only width or height was specified and When
         *                                  specified width or height is less than 0
         */
        @WorkerThread
        @Throws(ApngException::class, Resources.NotFoundException::class, IOException::class)
        fun decode(
            res: Resources,
            @RawRes @DrawableRes id: Int,
            width: Int? = null,
            height: Int? = null
        ): ApngDrawable = res.openRawResource(id).buffered().use { decode(it, width, height) }

        /**
         * Creates [ApngDrawable] from asset.
         *
         * @param assetManager The [AssetManager] instance to get asset from.
         * @param assetName The name of the asset to open.
         * @param width An optional width value to specify width size manually.
         * @param height An optional height value to specify height size manually.
         *
         * @return Decoded drawable object.
         *
         * @throws ApngException When decoding failed.
         * @throws FileNotFoundException If the file with given [assetName] is not exist.
         * @throws IOException When opening failed.
         * @throws IllegalArgumentException When only width or height was specified and When
         *                                  specified width or height is less than 0
         */
        @WorkerThread
        @Throws(ApngException::class, IOException::class)
        fun decode(
            assetManager: AssetManager,
            assetName: String,
            width: Int? = null,
            height: Int? = null
        ): ApngDrawable = assetManager.open(assetName).buffered().use { decode(it, width, height) }

        /**
         * Creates [ApngDrawable] from given [filePath].
         *
         * This is equivalent to the following code:
         *
         * ```
         * decode(File(filePath),size)
         * ```
         *
         * @param filePath The file path to the Animated PNG file.
         * @param width An optional width value to specify width size manually.
         * @param height An optional height value to specify height size manually.
         *
         * @return Decoded drawable object.
         *
         * @throws ApngException When decoding failed.
         * @throws FileNotFoundException If the file with given [filePath] is not exist.
         * @throws IOException When opening failed.
         * @throws IllegalArgumentException When only width or height was specified and When
         *                                  specified width or height is less than 0
         */
        @WorkerThread
        @Throws(ApngException::class, FileNotFoundException::class, IOException::class)
        fun decode(
            filePath: String,
            width: Int? = null,
            height: Int? = null
        ): ApngDrawable = decode(File(filePath), width, height)

        /**
         * Creates [ApngDrawable] from given [file].
         *
         * @param file The Animated PNG file.
         * @param width An optional width value to specify width size manually.
         * @param height An optional height value to specify height size manually.
         *
         * @return Decoded drawable object.
         *
         * @throws ApngException When decoding failed.
         * @throws FileNotFoundException If the [file] is not exist.
         * @throws IOException When opening failed.
         * @throws IllegalArgumentException When only width or height was specified and When
         *                                  specified width or height is less than 0
         */
        @WorkerThread
        @Throws(ApngException::class, FileNotFoundException::class, IOException::class)
        fun decode(
            file: File,
            width: Int? = null,
            height: Int? = null
        ): ApngDrawable = file.inputStream().buffered().use { decode(it, width, height) }

        /**
         * Creates [ApngDrawable] from given [stream].
         *
         * @param stream The [InputStream] to get from.
         * @param width An optional width value to specify width size manually.
         * @param height An optional height value to specify height size manually.
         *
         * @return Decoded drawable object.
         *
         * @throws ApngException When decoding failed.
         * @throws IllegalArgumentException When only width or height was specified and When
         *                                  specified width or height is less than 0
         */
        @WorkerThread
        @Throws(ApngException::class)
        fun decode(
            stream: InputStream,
            @IntRange(from = 1, to = Int.MAX_VALUE.toLong()) width: Int? = null,
            @IntRange(from = 1, to = Int.MAX_VALUE.toLong()) height: Int? = null
        ): ApngDrawable {
            if ((width == null) xor (height == null)) {
                throw IllegalArgumentException(
                    "Can not specify only one side of size. width = $width, height = $height"
                )
            }
            if (width != null && width <= 0) {
                throw IllegalArgumentException(
                    "Can not specify 0 or negative as width value. width = $width"
                )
            }
            if (height != null && height <= 0) {
                throw IllegalArgumentException(
                    "Can not specify 0 or negative as height value. width = $height"
                )
            }
            val density = if (width == null && height == null) {
                DisplayMetrics.DENSITY_DEFAULT
            } else {
                Bitmap.DENSITY_NONE
            }
            val apng = Apng.decode(stream)
            return ApngDrawable(
                ApngState(
                    apng,
                    width ?: apng.width,
                    height ?: apng.height,
                    density
                )
            )
        }
        // endregion

        // region `isApng` functions
        /**
         * Determines whether or not the given data is APNG format.
         *
         * @param res The [Resources] instance to get resource from.
         * @param id Raw resource id or drawable resource id.
         *
         * @return `true` if data is APNG format. `false` if data is't APNG format or some error
         * has been happened.
         */
        @WorkerThread
        fun isApng(res: Resources, @RawRes @DrawableRes id: Int): Boolean = try {
            res.openRawResource(id).buffered().use { isApng(it) }
        } catch (ignored: Exception) {
            false
        }

        /**
         * Determines whether or not the given data is APNG format.
         *
         * @param assetManager The [AssetManager] instance to get asset from.
         * @param assetName The name of the asset to open.
         *
         * @return `true` if data is APNG format. `false` if data is't APNG format or some error
         * has been happened.
         */
        @WorkerThread
        fun isApng(assetManager: AssetManager, assetName: String): Boolean = try {
            assetManager.open(assetName).buffered().use { isApng(it) }
        } catch (ignored: Exception) {
            false
        }

        /**
         * Determines whether or not the given data is APNG format.
         * This is equivalent to the following code:
         *
         * ```
         * isApng(File(filePath))
         * ```
         *
         * @param filePath The file path to the Animated PNG file.
         *
         * @return `true` if data is APNG format. `false` if data is't APNG format or some error
         * has been happened.
         */
        @WorkerThread
        fun isApng(filePath: String): Boolean = try {
            isApng(File(filePath))
        } catch (ignored: Exception) {
            false
        }

        /**
         * Determines whether or not the given data is APNG format.
         *
         * @param file The Animated PNG file.
         *
         * @return `true` if data is APNG format. `false` if data is't APNG format or some error
         * has been happened.
         */
        @WorkerThread
        fun isApng(file: File): Boolean = try {
            file.inputStream().buffered().use { isApng(it) }
        } catch (ignored: Exception) {
            false
        }

        /**
         * Determines whether or not the given data is APNG format.
         *
         * @param stream The [InputStream] to get from.
         *
         * @return `true` if data is APNG format. `false` if data is't APNG format or some error
         * has been happened.
         */
        @WorkerThread
        fun isApng(stream: InputStream): Boolean = try {
            Apng.isApng(stream)
        } catch (ignored: ApngException) {
            false
        }
        // endregion
    }
}
