package app.fitness.core

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream

/**
 * A small element tree over `XmlPullParser`, which is built into Android (on the JVM, kxml2 supplies the same API).
 * Names are the local part only, so namespace versions and prefixes (`gpxtpx:hr`, TCX v1/v2) do not matter.
 */
class XmlNode(val name: String, val attrs: Map<String, String>) {
    val children = ArrayList<XmlNode>()

    /** Text before the first child element, like ElementTree's `.text`; null when there is none. */
    var text: String? = null
        internal set

    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }
    fun childrenNamed(name: String): List<XmlNode> = children.filter { it.name == name }

    /** This element and every descendant, in document order. */
    fun descendantsAndSelf(): Sequence<XmlNode> = sequence {
        yield(this@XmlNode)
        for (c in children) yieldAll(c.descendantsAndSelf())
    }

    /** First descendant with this name, excluding this element (PC: `_find`). */
    fun find(name: String): XmlNode? = descendantsAndSelf().drop(1).firstOrNull { it.name == name }

    /** The element's text as a number, or null (PC: `_num`). */
    fun number(): Double? = text?.trim()?.toDoubleOrNull()?.takeIf { !it.isNaN() }

    companion object {
        fun parse(bytes: ByteArray): XmlNode {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(ByteArrayInputStream(bytes), null)
            val stack = ArrayList<XmlNode>()
            var root: XmlNode? = null
            val text = StringBuilder()
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val attrs = HashMap<String, String>(parser.attributeCount)
                        for (i in 0 until parser.attributeCount) attrs[parser.getAttributeName(i).substringAfter(':')] = parser.getAttributeValue(i)
                        val node = XmlNode(parser.name.substringAfter(':'), attrs)
                        stack.lastOrNull()?.children?.add(node)
                        if (root == null) root = node
                        stack += node
                        text.setLength(0)
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                        val top = stack.lastOrNull()
                        if (top != null && top.children.isEmpty()) text.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> {
                        val node = stack.removeAt(stack.size - 1)
                        if (node.children.isEmpty() && text.isNotEmpty()) node.text = text.toString()
                        text.setLength(0)
                    }
                }
                ev = parser.nextToken()
            }
            return root ?: error("empty XML document")
        }
    }
}
