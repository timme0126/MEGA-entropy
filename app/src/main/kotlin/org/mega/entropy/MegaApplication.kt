package org.mega.entropy

import android.app.Application

/**
 * MEGA's Application class intentionally does no work at startup beyond
 * default Android initialization: no analytics SDK, no crash reporter, no
 * network client, nothing that could become a side channel for the
 * dice-derived entropy pipeline in :entropy-core.
 */
class MegaApplication : Application()
