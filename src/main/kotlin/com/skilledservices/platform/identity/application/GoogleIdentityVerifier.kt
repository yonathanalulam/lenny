package com.skilledservices.platform.identity.application

data class VerifiedGoogleIdentity(
    val subject: String,
    val email: String,
)

fun interface GoogleIdentityVerifier {
    fun verifyIdToken(idToken: String): VerifiedGoogleIdentity
}
