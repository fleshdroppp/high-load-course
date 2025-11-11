package ru.quipy.common.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

fun onlineShopObjectMapper(): ObjectMapper {
    return ObjectMapper().registerKotlinModule()
}