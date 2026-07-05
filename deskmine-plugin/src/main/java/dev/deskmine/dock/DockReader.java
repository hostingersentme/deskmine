package dev.deskmine.dock;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reads the real Dock's pinned apps (spec 7) directly from macOS preferences
 * via `defaults export com.apple.dock -` (XML plist on stdout). This stands in
 * for the Swift helper for Dock data; the helper is still the plan for the
 * menu-bar phase, which needs the Accessibility API.
 */
public final class DockReader {

    private DockReader() {}

    /** Returns pinned Dock apps in order, or null if the Dock could not be read. */
    public static List<DockItem> read() {
        try {
            Process p = new ProcessBuilder("defaults", "export", "com.apple.dock", "-")
                    .redirectErrorStream(true)
                    .start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0) return null;

            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setValidating(false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(out));

            Element topDict = firstChildElement(doc.getDocumentElement(), "dict");
            if (topDict == null) return null;
            Element apps = valueForKey(topDict, "persistent-apps");
            if (apps == null) return List.of();

            List<DockItem> items = new ArrayList<>();
            for (Element tile = firstChildElement(apps, "dict"); tile != null;
                 tile = nextSiblingElement(tile, "dict")) {
                Element tileData = valueForKey(tile, "tile-data");
                if (tileData == null) continue;
                String label = text(valueForKey(tileData, "file-label"));
                String bundle = text(valueForKey(tileData, "bundle-identifier"));
                String path = null;
                Element fileData = valueForKey(tileData, "file-data");
                if (fileData != null) {
                    String url = text(valueForKey(fileData, "_CFURLString"));
                    if (url != null) path = urlToPath(url);
                }
                if (label == null && path != null) {
                    String base = path.substring(path.lastIndexOf('/') + 1);
                    label = base.endsWith(".app") ? base.substring(0, base.length() - 4) : base;
                }
                if (label != null && (path != null || bundle != null)) {
                    items.add(new DockItem(label, bundle, path));
                }
            }
            return items;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------- plist DOM utils

    /** In a plist <dict>, returns the value element following <key>name</key>. */
    private static Element valueForKey(Element dict, String name) {
        for (Node n = dict.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals("key")
                    && name.equals(e.getTextContent())) {
                for (Node v = e.getNextSibling(); v != null; v = v.getNextSibling()) {
                    if (v instanceof Element ve) return ve;
                }
            }
        }
        return null;
    }

    private static Element firstChildElement(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) return e;
        }
        return null;
    }

    private static Element nextSiblingElement(Element from, String tag) {
        for (Node n = from.getNextSibling(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) return e;
        }
        return null;
    }

    private static String text(Element e) {
        if (e == null) return null;
        String t = e.getTextContent();
        return (t == null || t.isBlank()) ? null : t.trim();
    }

    private static String urlToPath(String url) {
        try {
            String s = Paths.get(URI.create(url.trim())).toString();
            return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
        } catch (Exception e) {
            String s = url.trim();
            if (s.startsWith("file://")) s = s.substring("file://".length());
            s = URLDecoder.decode(s, StandardCharsets.UTF_8);
            return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
        }
    }
}
