package com.emfitsolutions.gopreach.data.repository

/**
 * Login is by username (spec §5.1/§5.2), but Firebase Auth accounts are keyed by
 * email. Each Person gets a synthetic Auth-only address derived from their
 * Firestore document id; [Person.email] stays a separate, optional, real-world
 * contact field never touched by Auth.
 */
private const val AUTH_EMAIL_DOMAIN = "gopreach.internal"

fun authEmailFor(personId: String): String = "$personId@$AUTH_EMAIL_DOMAIN"

fun personIdFromAuthEmail(email: String?): String? =
    email?.takeIf { it.endsWith("@$AUTH_EMAIL_DOMAIN") }?.substringBefore("@")
