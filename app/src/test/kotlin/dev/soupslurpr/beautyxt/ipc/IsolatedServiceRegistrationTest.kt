package dev.soupslurpr.beautyxt.ipc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises Android's registration-before-return boundary without starting a worker. */
class IsolatedServiceRegistrationTest {
    @Test
    fun unregistersFalseAndThrowingAttempts() {
        for (throws in listOf(false, true)) {
            var registered = false
            var releases = 0
            val registration = IsolatedServiceRegistration {
                assertTrue(registered)
                registered = false
                releases++
            }
            val attempt = {
                registration.bind {
                    registered = true
                    if (throws) throw SecurityException("synthetic post-registration failure")
                    false
                }
            }
            if (throws) assertThrows(SecurityException::class.java) { attempt() }
            else assertFalse(attempt())
            registration.close()
            assertFalse(registered)
            assertEquals(1, releases)
        }
    }

    @Test
    fun closeDuringBindReleasesTheLaterRegistrationOnce() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var releases = 0
        val registration = IsolatedServiceRegistration { releases++ }
        val attempt = FutureTask {
            registration.bind {
                entered.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
                true
            }
        }
        val thread = Thread(attempt, "binding regression").apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            registration.close()
            assertEquals(0, releases)
        } finally {
            finish.countDown()
            thread.join(5_000)
        }
        assertTrue(attempt.get(5, TimeUnit.SECONDS))
        registration.close()
        assertEquals(1, releases)
    }

    @Test
    fun acceptedRegistrationSurvivesUntilClose() {
        var releases = 0
        val registration = IsolatedServiceRegistration { releases++ }
        assertTrue(registration.bind { true })
        assertEquals(0, releases)
        registration.close()
        registration.close()
        assertEquals(1, releases)
    }
}
