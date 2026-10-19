package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.ui.LatestSession
import com.fioiu8.dshpocket.ui.LatestTitle
import com.fioiu8.dshpocket.ui.latestTitleState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The latest-session card has three states, not two.
 *
 * An untitled session is the case that regressed: once `LatestSession.title` became
 * nullable the naive `session?.title ?: noSession` chain rendered "no session" for a
 * session that plainly exists. This pins the middle state so it cannot collapse again.
 */
class LatestTitleStateTest {

    private fun session(title: String?) = LatestSession(title = title, running = false, turns = 0)

    @Test
    fun `no session at all`() {
        assertEquals(LatestTitle.NoSession, latestTitleState(null))
    }

    @Test
    fun `a session that exists but has no title is neither of the other two`() {
        assertEquals(LatestTitle.Untitled, latestTitleState(session(null)))
    }

    @Test
    fun `a titled session`() {
        assertEquals(LatestTitle.Titled, latestTitleState(session("Refactor engine")))
    }

    @Test
    fun `a blank title is untitled, not a real title`() {
        assertEquals(LatestTitle.Untitled, latestTitleState(session("")))
    }
}
