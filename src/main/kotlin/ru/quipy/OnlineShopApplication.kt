package ru.quipy

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import
import ru.quipy.common.utils.MdcExecutorDecorator.Companion.decorateWithMdc
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.config.HttpConfiguration
import java.util.concurrent.Executors


@SpringBootApplication
@Import(
    HttpConfiguration::class,
)
class OnlineShopApplication {
    val log: Logger = LoggerFactory.getLogger(OnlineShopApplication::class.java)

    companion object {
        val appExecutor = Executors.newFixedThreadPool(64, NamedThreadFactory("main-app-executor"))
            .decorateWithMdc()
    }
}

fun main(args: Array<String>) {
    runApplication<OnlineShopApplication>(*args)
}
