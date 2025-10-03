package ru.quipy.common.utils

interface ParallelRequestsLimiter {

    fun tryToAddRequest(timeout: Long): Boolean

    fun releaseRequest()

    fun awaitingQueueSize(): Int

}