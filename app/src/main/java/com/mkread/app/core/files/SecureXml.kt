package com.mkread.app.core.files

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException

object SecureXml {
    fun parse(bytes: ByteArray): Document {
        try {
            rejectDoctype(bytes)
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                disableXInclude()
                disableEntityExpansion()
                setOptionalSecurityFeature(DISALLOW_DOCTYPE, true)
                setOptionalSecurityFeature(EXTERNAL_GENERAL_ENTITIES, false)
                setOptionalSecurityFeature(EXTERNAL_PARAMETER_ENTITIES, false)
                setOptionalSecurityFeature(LOAD_EXTERNAL_DTD, false)
                setOptionalSecurityAttribute(ACCESS_EXTERNAL_DTD, "")
                setOptionalSecurityAttribute(ACCESS_EXTERNAL_SCHEMA, "")
            }
            return factory.newDocumentBuilder().apply {
                setErrorHandler(ThrowingErrorHandler)
                setEntityResolver { _, _ ->
                    throw SAXException("External XML entities are forbidden")
                }
            }.parse(ByteArrayInputStream(bytes))
        } catch (failure: Exception) {
            throw SecureXmlException("XML is malformed or uses forbidden external content", failure)
        }
    }

    private object ThrowingErrorHandler : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit

        override fun error(exception: SAXParseException) {
            throw exception
        }

        override fun fatalError(exception: SAXParseException) {
            throw exception
        }
    }

    private fun DocumentBuilderFactory.disableXInclude() {
        try {
            isXIncludeAware = false
        } catch (_: UnsupportedOperationException) {
            // Android's parser has no XInclude implementation, so it is already disabled.
        }
    }

    private fun DocumentBuilderFactory.disableEntityExpansion() {
        try {
            isExpandEntityReferences = false
        } catch (_: UnsupportedOperationException) {
            // A rejected DOCTYPE leaves no user-defined entity to expand.
        }
    }

    private fun DocumentBuilderFactory.setOptionalSecurityFeature(
        name: String,
        value: Boolean,
    ) {
        try {
            setFeature(name, value)
        } catch (_: Exception) {
            // Pre-scan and the rejecting EntityResolver enforce the same policy fail-closed.
        }
    }

    private fun DocumentBuilderFactory.setOptionalSecurityAttribute(
        name: String,
        value: String,
    ) {
        try {
            setAttribute(name, value)
        } catch (_: IllegalArgumentException) {
            // Android omits JAXP access properties; mandatory SAX entity features remain disabled.
        } catch (_: UnsupportedOperationException) {
            // Unsupported external access cannot be enabled by this parser.
        }
    }

    private fun rejectDoctype(bytes: ByteArray) {
        if (
            bytes.containsSequence(DOCTYPE_ASCII) ||
            bytes.containsSequence(DOCTYPE_UTF16_LE) ||
            bytes.containsSequence(DOCTYPE_UTF16_BE)
        ) {
            throw SAXException("DOCTYPE is forbidden")
        }
    }

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
        if (sequence.isEmpty() || size < sequence.size) return false
        for (start in 0..size - sequence.size) {
            if (sequence.indices.all { offset -> this[start + offset] == sequence[offset] }) {
                return true
            }
        }
        return false
    }

    private fun utf16Pattern(ascii: ByteArray, littleEndian: Boolean): ByteArray =
        ByteArray(ascii.size * 2) { index ->
            val byteIndex = index / 2
            val isCharacterByte = if (littleEndian) index % 2 == 0 else index % 2 == 1
            if (isCharacterByte) ascii[byteIndex] else 0
        }

    private const val ACCESS_EXTERNAL_DTD =
        "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA =
        "http://javax.xml.XMLConstants/property/accessExternalSchema"
    private const val DISALLOW_DOCTYPE =
        "http://apache.org/xml/features/disallow-doctype-decl"
    private const val EXTERNAL_GENERAL_ENTITIES =
        "http://xml.org/sax/features/external-general-entities"
    private const val EXTERNAL_PARAMETER_ENTITIES =
        "http://xml.org/sax/features/external-parameter-entities"
    private const val LOAD_EXTERNAL_DTD =
        "http://apache.org/xml/features/nonvalidating/load-external-dtd"
    private val DOCTYPE_ASCII = "<!DOCTYPE".toByteArray(StandardCharsets.US_ASCII)
    private val DOCTYPE_UTF16_LE = utf16Pattern(DOCTYPE_ASCII, littleEndian = true)
    private val DOCTYPE_UTF16_BE = utf16Pattern(DOCTYPE_ASCII, littleEndian = false)
}

class SecureXmlException(
    message: String,
    cause: Throwable,
) : Exception(message, cause)
