package com.r3corda.node.services.api

import com.r3corda.core.node.services.ServiceType

/**
 * Placeholder interface for regulator services.
 */
interface RegulatorService {
    companion object {
        val type = ServiceType.regulator
    }
}