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
        val bookingFiles = Files.walk(root.resolve("booking")).use { paths -> paths.filter { it.extension == "kt" }.toList() }
        (marketplaceAndRequests + bookingFiles).forEach { file ->
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
        val discovery = Files.readString(root.resolve("marketplace/application/MarketplaceServices.kt"))
        assertTrue(discovery.contains("ProviderBookingAvailabilityQuery"), "Marketplace discovery must use the Booking-owned availability query")
        assertFalse(discovery.contains("booking.infrastructure"), "Marketplace discovery must not access Booking persistence")
        val bookingService = Files.readString(root.resolve("booking/application/BookingServices.kt"))
        assertTrue(bookingService.contains("CustomerEligibilityService") && bookingService.contains("ProviderEligibilityService"), "Booking must use both eligibility boundaries")
        assertFalse(bookingService.contains("verification.infrastructure"), "Booking must not access verification persistence")
        bookingFiles.forEach { file ->
            val source = Files.readString(file)
            listOf(".communication.", ".messaging.", ".notification.", ".support.").forEach { forbidden ->
                assertFalse(source.contains(forbidden), "$file must not leak later communication/support modules into Booking")
            }
        }
        val identityApplication = Files.walk(root.resolve("identity/application")).use { paths -> paths.filter { it.extension == "kt" }.toList() }
        identityApplication.forEach { file ->
            val source = Files.readString(file)
            assertFalse(source.contains("com.nimbusds") || source.contains("jose.jwt"), "$file auth application code must not depend on Nimbus internals")
        }
        val googleAdapter = root.resolve("identity/infrastructure/google/GoogleIdentityVerifierAdapter.kt")
        assertTrue(Files.exists(googleAdapter), "Google provider implementation must remain infrastructure-only")
    }
}
