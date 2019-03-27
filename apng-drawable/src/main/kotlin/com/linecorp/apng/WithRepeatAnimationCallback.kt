package com.linecorp.apng

import androidx.vectordrawable.graphics.drawable.Animatable2Compat

/**
 * This class adds [onRepeat] callback to existing [Animatable2Compat.AnimationCallback]
 */
abstract class WithRepeatAnimationCallback : Animatable2Compat.AnimationCallback() {
    /**
     * This is called when animation is about to be repeated.
     * [loopCount] is the total number of times the animation will be repeated.
     * [currentLoop] is the loop count of the current animation.
     */
    abstract fun onRepeat(
        drawable: ApngDrawable,
        loopCount: Int,
        currentLoop: Int
    )
}
