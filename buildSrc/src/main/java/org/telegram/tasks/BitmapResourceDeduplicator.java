/* Modifications Copyright (C) 2026 Ettacent */
package org.telegram.tasks;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

public final class BitmapResourceDeduplicator {
    private BitmapResourceDeduplicator() {}

    public static final class Plan {
        public final Set<String> excludedPaths = new TreeSet<>();
        public final Map<String, String> aliasFiles = new TreeMap<>();
        public final Map<String, String> aliases = new TreeMap<>();
        public long duplicateBytes;
    }

    private static final class Family {
        final String type, name;
        final Map<String, Path> files = new TreeMap<>();
        Family(String type, String name) { this.type = type; this.name = name; }
        String key() { return type + "/" + name; }
    }

    public static Plan plan(Path input, Collection<Path> overlays) throws Exception {
        input = input.toAbsolutePath().normalize();
        if (!Files.isDirectory(input)) throw new IOException("Missing resource directory: " + input);
        Map<String, Family> families = new TreeMap<>();
        Set<String> blocked = new HashSet<>();
        List<Path> sourceFiles = new ArrayList<>();
        try (var dirs = Files.list(input)) {
            for (Path dir : (Iterable<Path>) dirs.sorted()::iterator) {
                if (!Files.isDirectory(dir)) continue;
                if (Files.isSymbolicLink(dir)) throw new IOException("Symlink resource directory: " + dir);
                try (var paths = Files.list(dir)) {
                    paths.filter(Files::isRegularFile).sorted().forEach(sourceFiles::add);
                }
            }
        }
        for (Path file : sourceFiles) {
            String folder = file.getParent().getFileName().toString();
            String type = folder.split("-", 2)[0];
            String filename = file.getFileName().toString();
            if (type.equals("values") && filename.endsWith(".xml")) {
                blockValueResources(file, blocked);
                continue;
            }
            if (!type.equals("drawable")) continue;
            String name = filename.split("\\.", 2)[0];
            String key = type + "/" + name;
            Family family = families.computeIfAbsent(key, k -> new Family(type, name));
            if (!name.matches("[a-z0-9_]+") || Files.isSymbolicLink(file)
                    || filename.endsWith(".9.png") || !filename.matches(".*\\.(png|webp|jpg|jpeg)$")) {
                blocked.add(key);
            } else if (family.files.put(folder, file) != null) {
                blocked.add(key);
            }
        }
        for (Path file : overlays) {
            if (!Files.isRegularFile(file)) continue;
            String folder = file.getParent().getFileName().toString();
            String filename = file.getFileName().toString();
            String type = folder.split("-", 2)[0];
            if (type.equals("values") && filename.endsWith(".xml")) {
                blockValueResources(file, blocked);
            } else if (type.equals("drawable")) {
                blocked.add(type + "/" + filename.split("\\.", 2)[0]);
            }
        }

        Map<String, List<Family>> groups = new TreeMap<>();
        for (Family family : families.values()) {
            String pack = pack(family.name);
            if (pack == null || blocked.contains(family.key()) || family.files.isEmpty()) continue;
            StringBuilder signature = new StringBuilder(pack);
            for (var entry : family.files.entrySet()) {
                String filename = entry.getValue().getFileName().toString();
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(entry.getValue()));
                signature.append('|').append(entry.getKey()).append(':')
                        .append(filename.substring(filename.lastIndexOf('.'))).append(':')
                        .append(HexFormat.of().formatHex(hash));
            }
            groups.computeIfAbsent(signature.toString(), k -> new ArrayList<>()).add(family);
        }

        Plan plan = new Plan();
        Map<String, SortedMap<String, String>> configs = new TreeMap<>();
        for (List<Family> group : groups.values()) {
            group.sort(Comparator.comparingInt((Family f) -> f.name.length()).thenComparing(f -> f.name));
            Family canonical = group.get(0);
            for (int i = 1; i < group.size(); i++) {
                Family alias = group.get(i);
                for (var entry : alias.files.entrySet()) {
                    Path twin = canonical.files.get(entry.getKey());
                    if (Files.mismatch(entry.getValue(), twin) != -1) {
                        throw new IOException("Bitmap family changed during planning: " + alias.key());
                    }
                    String relative = input.relativize(entry.getValue()).toString().replace('\\', '/');
                    plan.excludedPaths.add(relative);
                    plan.duplicateBytes += Files.size(entry.getValue());
                    String config = "values" + entry.getKey().substring("drawable".length());
                    configs.computeIfAbsent(config, k -> new TreeMap<>()).put(alias.name, canonical.name);
                }
                plan.aliases.put(alias.key(), canonical.key());
            }
        }
        for (var config : configs.entrySet()) {
            String relative = config.getKey() + "/ng_generated_bitmap_aliases.xml";
            if (Files.exists(input.resolve(relative))) throw new IOException("Reserved generated path: " + relative);
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n");
            for (var alias : config.getValue().entrySet()) {
                xml.append("    <item type=\"drawable\" name=\"").append(alias.getKey())
                        .append("\">@drawable/").append(alias.getValue()).append("</item>\n");
            }
            plan.aliasFiles.put(relative, xml.append("</resources>\n").toString());
        }
        return plan;
    }

    private static String pack(String name) {
        for (String pack : List.of("liquid", "plumpy", "solar")) {
            if (name.endsWith("_" + pack)) return pack;
        }
        return null;
    }

    private static void blockValueResources(Path file, Set<String> blocked) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Element root = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element)) continue;
            Element element = (Element) node;
            String type = element.getTagName().equals("item") ? element.getAttribute("type") : element.getTagName();
            if (type.equals("drawable")) blocked.add(type + "/" + element.getAttribute("name"));
        }
    }
}
