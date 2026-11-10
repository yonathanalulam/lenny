package com.skilledservices.platform

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchitectureBoundaryTest {
    @Test
    fun `marketplace and requests do not depend on verification persistence and controllers do not use repositories`() {
        val root = Path.of("src", "main", "kotlin", "com", "skilledservices", "platform")
        val marketplaceAndRequests = listOf(root.resolve("marketplace"), root.resolve("requests"))
            .flatMap { directory -> Files.walk(directory).use { paths -> paths.filter { it.extension == "kt" }.toList() } }
        marketplaceAndRequests.forEach { file ->
            val source = Files.readString(file)
            assertFalse(source.contains("profiles.infrastructure.persistence"), "$file must not depend on verification persistence")
            if (file.toString().contains("${java.io.File.separator}api${java.io.File.separator}")) {
                assertFalse(source.contains("JpaRepository"), "$file controller/DTO code must not depend on JPA repositories")
                assertFalse(source.contains("infrastructure.persistence"), "$file controller/DTO code must not depend on persistence")
            }
            if (file.toString().contains("${java.io.File.separator}domain${java.io.File.separator}")) {
                assertFalse(source.contains(".api."), "$file domain code must not depend on API classes")
                assertFalse(source.contains("org.springframework.web"), "$file domain code must not depend on web classes")
            }
        }
        val requestService = Files.readString(root.resolve("requests/application/RequestService.kt"))
        assertFalse(!requestService.contains("CustomerEligibilityService") || !requestService.contains("ProviderEligibilityService"), "Request module must use both eligibility interfaces")
        val requestSources = marketplaceAndRequests.filter { it.startsWith(root.resolve("requests")) }
        val providerSdkMarkers = listOf("com.stripe", "chapa", "telebirr", "paypal", "stripe")
        requestSources.filterNot { it.toString().contains("${java.io.File.separator}infrastructure${java.io.File.separator}") }.forEach { file ->
            val source = Files.readString(file).lowercase()
            providerSdkMarkers.forEach { marker -> assertFalse(source.contains(marker), "$file must not import or name a payment-provider SDK") }
        }
        val paymentDomain = Files.readString(root.resolve("requests/domain/PaymentProposalModels.kt"))
        assertTrue(paymentDomain.contains("interface PostingFeePaymentGateway"), "Payment gateway must remain a project-owned port")
        val participation = Files.readString(root.resolve("requests/application/MarketplaceParticipationServices.kt"))
        assertTrue(participation.contains("ProviderEligibilityService"), "Provider job feed must use the eligibility boundary")
        val bookingFiles = Files.walk(root.resolve("booking")).use { paths -> paths.filter { it.extension == "kt" }.toList() }
        assertTrue(bookingFiles.isEmpty(), "Milestone 4 must not implement Booking")
    }
}
