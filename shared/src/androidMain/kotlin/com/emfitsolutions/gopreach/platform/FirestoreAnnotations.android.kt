package com.emfitsolutions.gopreach.platform

// Plain marker annotations: nothing reads them at run time any more (the data comes from, and goes to, the GoPreach server as JSON).
actual annotation class DocumentId
actual annotation class PropertyName(actual val value: String)
