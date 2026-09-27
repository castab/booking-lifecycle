package io.github.castab.commerce.runtime.testing

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock tests move by hand, so expiry never depends on the wall clock. */
class MutableClock(
    var now: Instant,
) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

/**
 * Everything written to standard output while [block] runs, including log events: the
 * tests' Logback console appender writes through `System.out` on every event.
 */
inline fun capturingStandardOutput(block: () -> Unit): String {
    val original = System.out
    val captured = ByteArrayOutputStream()
    System.setOut(PrintStream(captured, true, Charsets.UTF_8))
    try {
        block()
    } finally {
        System.setOut(original)
    }
    return captured.toString(Charsets.UTF_8)
}
