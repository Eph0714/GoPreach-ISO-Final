package com.emfitsolutions.gopreach.platform

/** Marks the property that holds a record's document id (a plain marker annotation; the id is the record's key on the server). */
expect annotation class DocumentId()

/** Marks a property whose stored name differs from its Kotlin name (a plain marker annotation). */
expect annotation class PropertyName(val value: String)
