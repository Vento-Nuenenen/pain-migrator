package ch.painmigrator.validate;

import ch.painmigrator.xml.Xml;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/** Zugriff auf die eingebetteten offiziellen SIX-Schemas. */
public final class Schemas {
    public static final String SOURCE = "pain.001.001.03.ch.02.xsd";
    public static final String TARGET = "pain.001.001.09.ch.03.xsd";

    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final Map<String, Schema> CACHE = new HashMap<>();
    private static Set<String> targetPaths;

    private Schemas() {
    }

    private static InputStream open(String name) {
        InputStream in = Schemas.class.getResourceAsStream("/xsd/" + name);
        if (in == null) {
            throw new IllegalStateException("Schema fehlt im Programm: " + name);
        }
        return in;
    }

    private static synchronized Schema schema(String name) {
        return CACHE.computeIfAbsent(name, n -> {
            try (InputStream in = open(n)) {
                SchemaFactory f = SchemaFactory.newInstance(XS);
                f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                f.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                return f.newSchema(new StreamSource(in));
            } catch (SAXException | IOException e) {
                throw new IllegalStateException("Schema nicht lesbar: " + n, e);
            }
        });
    }

    /** Prüft XML gegen ein Schema und liefert alle Fehler. Leer bedeutet gültig. */
    public static List<String> validate(String schemaName, byte[] xml) {
        List<String> errors = new ArrayList<>();
        try {
            Validator v = schema(schemaName).newValidator();
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            v.setErrorHandler(new ErrorHandler() {
                private void add(SAXParseException e) {
                    errors.add("Zeile " + e.getLineNumber() + ": " + e.getMessage());
                }

                @Override
                public void warning(SAXParseException e) {
                }

                @Override
                public void error(SAXParseException e) {
                    add(e);
                }

                @Override
                public void fatalError(SAXParseException e) {
                    add(e);
                }
            });
            v.validate(new StreamSource(new ByteArrayInputStream(xml)));
        } catch (SAXException e) {
            if (errors.isEmpty()) {
                errors.add(e.getMessage());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return errors;
    }

    /** Alle Elementpfade, die das Zielschema kennt, z. B. {@code /Document/CstmrCdtTrfInitn/GrpHdr/MsgId}. */
    public static synchronized Set<String> targetPaths() {
        if (targetPaths == null) {
            try (InputStream in = open(TARGET)) {
                targetPaths = collectPaths(Xml.parse(in.readAllBytes()));
            } catch (IOException | SAXException e) {
                throw new IllegalStateException(e);
            }
        }
        return targetPaths;
    }

    private static Set<String> collectPaths(Document xsd) {
        Map<String, Element> types = new HashMap<>();
        Element root = xsd.getDocumentElement();
        Element document = null;
        for (Element e : Xml.children(root)) {
            if ("complexType".equals(e.getLocalName())) {
                types.put(e.getAttribute("name"), e);
            } else if ("element".equals(e.getLocalName())) {
                document = e;
            }
        }
        if (document == null) {
            throw new IllegalStateException("Schema ohne Wurzelelement");
        }
        Set<String> paths = new HashSet<>();
        String top = "/" + document.getAttribute("name");
        paths.add(top);
        walk(types, types.get(document.getAttribute("type")), top, paths, new HashSet<>());
        return paths;
    }

    private static void walk(Map<String, Element> types, Element node, String prefix, Set<String> paths,
            Set<String> stack) {
        if (node == null) {
            return;
        }
        for (Element c : Xml.children(node)) {
            if ("element".equals(c.getLocalName())) {
                String path = prefix + "/" + c.getAttribute("name");
                paths.add(path);
                String type = c.getAttribute("type");
                if (types.containsKey(type) && stack.add(type)) {
                    walk(types, types.get(type), path, paths, stack);
                    stack.remove(type);
                }
            } else {
                walk(types, c, prefix, paths, stack);
            }
        }
    }
}
