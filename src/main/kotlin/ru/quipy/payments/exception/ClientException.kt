package ru.quipy.payments.exception

class ClientException(
    override val message: String?,
) : RuntimeException()