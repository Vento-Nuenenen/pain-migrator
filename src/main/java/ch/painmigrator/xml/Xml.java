package ch.painmigrator.xml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/** Sicheres Parsen und Schreiben von XML sowie kleine DOM-Hilfen. */
public final class Xml {
    private Xml() {
    }

    private static DocumentBuilder builder() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setCoalescing(true);
            f.setIgnoringComments(true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // Zahlungsdateien brauchen keine DTD: DOCTYPE abzulehnen schliesst XXE vollständig aus.
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                }

                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            return b;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Document parse(byte[] data) throws SAXException {
        try {
            return builder().parse(new ByteArrayInputStream(data));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Document newDocument() {
        return builder().newDocument();
    }

    /** Schreibt UTF-8 ohne BOM, wie es die Swiss Payment Standards verlangen. */
    public static byte[] serialize(Document doc) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            tf.setAttribute("indent-number", 2);
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            doc.setXmlStandalone(true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (TransformerException e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                out.add(e);
            }
        }
        return out;
    }

    public static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Element e : children(parent)) {
            if (name.equals(e.getLocalName())) {
                out.add(e);
            }
        }
        return out;
    }

    public static Element child(Element parent, String name) {
        for (Node n = parent == null ? null : parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    /** Folgt einem Pfad wie {@code Id/Othr/Id}; liefert null, wenn ein Glied fehlt. */
    public static Element find(Element start, String path) {
        Element cur = start;
        for (String part : path.split("/")) {
            cur = child(cur, part);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    /** Wie {@link #find}, liefert aber alle Treffer, auch wenn ein Glied mehrfach vorkommt. */
    public static List<Element> findAll(Element start, String path) {
        List<Element> current = List.of(start);
        for (String part : path.split("/")) {
            List<Element> next = new ArrayList<>();
            for (Element e : current) {
                next.addAll(children(e, part));
            }
            current = next;
        }
        return current;
    }

    public static String text(Element start, String path) {
        Element e = find(start, path);
        return e == null ? null : e.getTextContent().trim();
    }

    public static String text(Element e) {
        return e == null ? null : e.getTextContent().trim();
    }

    public static void remove(Element e) {
        if (e != null && e.getParentNode() != null) {
            e.getParentNode().removeChild(e);
        }
    }

    /** Entfernt das Element und danach alle Vorfahren, die dadurch leer werden. */
    public static void removeAndPrune(Element e) {
        Node parent = e.getParentNode();
        remove(e);
        while (parent instanceof Element p && p.getParentNode() instanceof Element && children(p).isEmpty()
                && p.getTextContent().isBlank()) {
            Node next = p.getParentNode();
            remove(p);
            parent = next;
        }
    }

    /** Pfad aus lokalen Namen ab dem Wurzelelement, z. B. {@code /Document/CstmrCdtTrfInitn/GrpHdr}. */
    public static String path(Element e) {
        StringBuilder sb = new StringBuilder();
        for (Node n = e; n instanceof Element el; n = n.getParentNode()) {
            sb.insert(0, "/" + el.getLocalName());
        }
        return sb.toString();
    }
}
