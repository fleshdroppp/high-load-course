package ru.quipy.common.utils

import org.slf4j.Logger
import org.slf4j.LoggerFactory

fun <T : Any> T.logger(): Logger {
    val kClazz = this::class

    if (kClazz.isCompanion) {
        return LoggerFactory.getLogger(kClazz.java.enclosingClass)
    }

    return LoggerFactory.getLogger(kClazz.java)
}
