package com.linecorp.apng

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.animation.AnimationUtils
import androidx.annotation.IntRange
import com.linecorp.apng.decoder.Apng

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
) : Drawable.ConstantState() {

    constructor(apngState: ApngState) : this(
        apngState.apng.copy(),
        apngState.width,
        apngState.height,
        apngState.sourceDensity,
        apngState.currentTimeProvider
    )

    override fun newDrawable(): Drawable =
        ApngDrawable(ApngState(this))

    override fun getChangingConfigurations(): Int = 0
}
