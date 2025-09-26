package ru.quipy.common.utils

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OngoingWindow(maxWinSize: Int) : ParallelRequestsLimiter {
    private val window = Semaphore(maxWinSize)

    override fun tryToAddRequest(timeout: Long) : Boolean {
        return window.tryAcquire(timeout, TimeUnit.SECONDS)
    }

    override fun releaseRequest() = window.release()

    override fun awaitingQueueSize() = window.queueLength

    fun acquire() = window.acquire()
}

class NonBlockingOngoingWindow(
    private val maxWinSize: Int
) {
    private val winSize = AtomicInteger()

    fun putIntoWindow(): WindowResponse {
        while (true) {
            val currentWinSize = winSize.get()
            if (currentWinSize >= maxWinSize) {
                return WindowResponse.Fail(currentWinSize)
            }

            if (winSize.compareAndSet(currentWinSize, currentWinSize + 1)) {
                break
            }
        }
        return WindowResponse.Success(winSize.get())
    }

    fun releaseWindow() = winSize.decrementAndGet()


    sealed class WindowResponse(val currentWinSize: Int) {
        public class Success(
            currentWinSize: Int
        ) : WindowResponse(currentWinSize)

        public class Fail(
            currentWinSize: Int
        ) : WindowResponse(currentWinSize)
    }
}