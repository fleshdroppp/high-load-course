package ru.quipy.common.utils

import org.slf4j.Logger
import org.slf4j.LoggerFactory

fun <T : Any> T.logger(): Logger {
    return LoggerFactory.getLogger(this::class.java)
}
