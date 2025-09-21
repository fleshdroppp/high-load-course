package ru.quipy.common.utils

import org.slf4j.MDC
import java.util.concurrent.Executor

class MdcExecutorDecorator(
    private val decorated: Executor,
) : Executor {
    override fun execute(command: Runnable) {
        val parentThreadMdc = MDC.getCopyOfContextMap()

        if (parentThreadMdc == null) {
            decorated.execute(command)
            return
        }

        decorated.execute {
            MDC.setContextMap(parentThreadMdc)
            command.run()
            MDC.clear()
        }
    }

    companion object {
        fun Executor.decorateWithMdc(): Executor {
            return MdcExecutorDecorator(this)
        }
    }
}