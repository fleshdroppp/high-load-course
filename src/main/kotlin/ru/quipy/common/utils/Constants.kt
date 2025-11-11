package ru.quipy.common.utils

import org.slf4j.MDC

@JvmInline
value class MdcKey(val inner: String)

private fun String.mdcKey(): MdcKey = MdcKey(this)

object MdcKeys {
    val REQUEST_ID = "rid".mdcKey()
    val PAYMENT_ID = "payment-id".mdcKey()
    val TRANSACTION_ID = "transaction-id".mdcKey()
}

inline fun <T> withMdc(vararg mdc: Pair<MdcKey, Any?>, action: () -> T): T {
    try {
        mdc.forEach { (k, v) ->
            v?.let {
                MDC.put(k.inner, it.toString())
            }
        }
        return action()
    } finally {
        mdc.forEach { (k, _) -> MDC.remove(k.inner) }
    }
}
