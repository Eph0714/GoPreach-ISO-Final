package com.emfitsolutions.gopreach.platform

/**
 * Marks the property that receives a record's document id. On Android this IS Firebase's `@DocumentId` (so Firestore's
 * object mapping keeps working while Firebase is still in use); on iOS, and once the backend replaces Firestore, it is a
 * harmless marker. The data models live in common code and must not import Firebase.
 */
expect annotation class DocumentId()

/** Firebase's `@PropertyName` on Android (needed for `isSuperAdmin`-style names); a no-op marker elsewhere. */
expect annotation class PropertyName(val value: String)
