package com.tomdebres.hither.ui

import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Every recompose scope Compose runs again, in every composition the window's `Recomposer` knows (a lazy row's items are
 * subcompositions, and register with it too), and how many composition passes ran (A3, DECISIONS 321): a composition
 * recomposed on every frame of an animation runs a pass every frame.
 *
 * `ReportRecomposition` counts only the scopes Hither names. tv-material's `Surface` reads its own animations in
 * composition (its focus scale, 300 ms in and 500 ms out, and its z-index's spring), so a card can recompose inside
 * tv-material on every frame of a move while every probe stays quiet. This counts those too: a scope entered again
 * after the entry that created it is a recomposition.
 *
 * Start it in the screen's first composition (a `SideEffect`): every composition made after that (a lazy row's items)
 * is seen from its creation. Scopes the first composition had already entered are first seen when they next run, so
 * that one run is not counted; a test lets the screen settle and [take]s before the moves it counts.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
class ScopeCounter : CompositionObserver {
    private val seen: MutableSet<RecomposeScope> = Collections.newSetFromMap(IdentityHashMap())
    private val handles = mutableListOf<CompositionObserverHandle>()

    /** Scopes run again since the last [take]. */
    var recomposed = 0
        private set

    /** Composition passes since the last [take]: each composition that recomposed (or was first composed) once each. */
    private var passes = 0

    /** Main thread only (the rule's `runOnIdle`). */
    fun start() {
        for (info in Recomposer.runningRecomposers.value) {
            info.observe(
                object : CompositionRegistrationObserver {
                    override fun onCompositionRegistered(composition: ObservableComposition) {
                        handles.add(composition.setObserver(this@ScopeCounter))
                    }

                    override fun onCompositionUnregistered(composition: ObservableComposition) = Unit
                },
            )?.let { handles.add(it) }
        }
    }

    fun stop() {
        handles.forEach { it.dispose() }
        handles.clear()
    }

    /** Scopes recomposed and composition passes since the last take, and starts counting again. Main thread only. */
    fun take(): Pair<Int, Int> {
        val taken = recomposed to passes
        recomposed = 0
        passes = 0
        return taken
    }

    override fun onScopeEnter(scope: RecomposeScope) {
        // The first entry composes it; every later one recomposes it.
        if (!seen.add(scope)) recomposed++
    }

    override fun onBeginComposition(composition: ObservableComposition) {
        passes++
    }
    override fun onReadInScope(scope: RecomposeScope, value: Any) = Unit
    override fun onScopeExit(scope: RecomposeScope) = Unit
    override fun onEndComposition(composition: ObservableComposition) = Unit
    override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) = Unit
    override fun onScopeDisposed(scope: RecomposeScope) {
        seen.remove(scope)
    }
}
