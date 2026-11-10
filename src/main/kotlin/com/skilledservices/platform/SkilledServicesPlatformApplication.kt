package com.skilledservices.platform

import com.skilledservices.platform.identity.infrastructure.security.SecurityProperties
import com.skilledservices.platform.shared.config.CorsProperties
import com.skilledservices.platform.shared.storage.ProtectedStorageProperties
import com.skilledservices.platform.requests.infrastructure.payment.PostingFeeProperties
import com.skilledservices.platform.identity.infrastructure.bootstrap.DevAdminProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(SecurityProperties::class, CorsProperties::class, ProtectedStorageProperties::class, PostingFeeProperties::class, DevAdminProperties::class)
class SkilledServicesPlatformApplication

fun main(args: Array<String>) {
    runApplication<SkilledServicesPlatformApplication>(*args)
}
