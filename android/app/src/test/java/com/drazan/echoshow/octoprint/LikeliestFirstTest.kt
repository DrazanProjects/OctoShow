package com.drazan.echoshow.octoprint

import com.drazan.echoshow.octoprint.octo.LocalNet
import com.drazan.echoshow.octoprint.octo.likeliestFirst
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

/**
 * A Pi with Tailscale on it answers for octoprint.local with its Tailscale address as well as
 * its home one, Tailscale's first. A display that isn't on Tailscale has to try the home one first.
 */
class LikeliestFirstTest {
    private fun ip(s: String): InetAddress = InetAddress.getByName(s)
    private val home = listOf(LocalNet(ip("192.168.1.240"), 24))

    @Test
    fun theHomeNetworkComesBeforeTailscale() {
        // the order the Pi announced them in, as seen on a real one
        val announced = listOf(ip("100.101.102.103"), ip("192.168.1.42"))
        assertEquals(listOf(ip("192.168.1.42"), ip("100.101.102.103")), likeliestFirst(announced, home))
    }

    @Test
    fun otherPrivateAddressesComeBeforeSharedAndLinkLocalOnes() {
        val announced = listOf(ip("169.254.10.2"), ip("100.64.0.1"), ip("10.0.0.5"), ip("192.168.1.42"))
        assertEquals(
            listOf(ip("192.168.1.42"), ip("10.0.0.5"), ip("169.254.10.2"), ip("100.64.0.1")),
            likeliestFirst(announced, home),
        )
    }

    @Test
    fun anOrderWithNothingToChooseBetweenIsKept() {
        val announced = listOf(ip("192.168.1.42"), ip("192.168.1.43"))
        assertEquals(announced, likeliestFirst(announced, home))
        assertEquals(announced, likeliestFirst(announced, emptyList()))
        // 100.128.x.x is outside the shared range, and an ordinary address
        assertEquals(listOf(ip("100.128.0.1"), ip("100.100.0.1")), likeliestFirst(listOf(ip("100.100.0.1"), ip("100.128.0.1")), home))
    }
}
