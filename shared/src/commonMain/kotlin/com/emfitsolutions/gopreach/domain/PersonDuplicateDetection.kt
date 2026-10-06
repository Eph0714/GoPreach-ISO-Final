package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.Person

/**
 * "Check the same name of the elder in a congregation and consider it as
 * one person" / "Check if there are duplicate names, evaluate it if they
 * are the same person" — two Person docs for the same real individual (a
 * duplicate enrollment) still share one full name; this is the normalized
 * comparison key used to catch that, everywhere this app needs to ("Total
 * Elders" counting in [com.emfitsolutions.gopreach.ui.screens.dashboard
 * .DashboardStats], and possible-duplicate flagging on the Manage
 * Publishers list).
 */
fun Person.duplicateNameKey(): String = fullName.trim().uppercase().replace(Regex("\\s+"), " ")

/**
 * "You can use their username and password as reference" — a real password
 * is never available to compare once an account leaves its temporary-
 * credential state (Firebase Auth never exposes it back to the app, even to
 * a Super-Admin), so this is the practical stand-in: [Person.username] is
 * always auto-generated as `firstname.lastname` (see
 * [com.emfitsolutions.gopreach.domain.CredentialGenerator.baseUsername]),
 * with a numeric suffix appended only on a collision with an *existing*
 * username. "juan.delacruz" and "juan.delacruz1" stripping to the same
 * "juan.delacruz" base is the strongest signal available that two Person
 * docs were auto-enrolled from the exact same name at different times.
 */
fun Person.duplicateUsernameKey(): String = username.trim().lowercase().trimEnd { it.isDigit() }
