package com.walterdeane.lore.exception

import java.lang.Exception


/** Thrown when an uploaded file's extension isn't one of the supported [com.walterdeane.lore.model.SourceType]s. */
class UnsupportedDocumentTypeException(message: String) : Exception(message)