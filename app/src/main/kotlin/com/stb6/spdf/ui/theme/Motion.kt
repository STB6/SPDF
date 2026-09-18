package com.stb6.spdf.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween

fun AnimatedContentTransitionScope<*>.pageEnter(): EnterTransition =
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(SLIDE_MILLIS))

fun AnimatedContentTransitionScope<*>.pageExit(): ExitTransition =
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(SLIDE_MILLIS))

fun AnimatedContentTransitionScope<*>.pagePopEnter(): EnterTransition =
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(SLIDE_MILLIS))

fun AnimatedContentTransitionScope<*>.pagePopExit(): ExitTransition =
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(SLIDE_MILLIS))

private const val SLIDE_MILLIS = 250
