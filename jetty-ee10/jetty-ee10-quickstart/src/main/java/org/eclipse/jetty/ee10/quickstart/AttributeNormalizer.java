//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.quickstart;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Normalize Attribute to String.
 * <p>
 * Replaces and expands:
 * <ul>
 * <li>${WAR}</li>
 * <li>${WAR.path}</li>
 * <li>${WAR.uri}</li>
 * <li>${jetty.base}</li>
 * <li>${jetty.base.uri}</li>
 * <li>${jetty.home}</li>
 * <li>${jetty.home.uri}</li>
 * <li>${user.home}</li>
 * <li>${user.home.uri}</li>
 * <li>${user.dir}</li>
 * <li>${user.dir.uri}</li>
 * </ul>
 */
public class AttributeNormalizer
{
    private static final Logger LOG = LoggerFactory.getLogger(AttributeNormalizer.class);
    private static final Pattern __propertyPattern = Pattern.compile("(?<=[^$]|^)\\$\\{([^}]*)\\}");

    private static class Attribute
    {
        final String key;
        final String value;
        final int weight;

        public Attribute(String key, String value, int weight)
        {
            this.key = key;
            this.value = value;
            this.weight = weight;
        }
    }

    public static URI toCanonicalURI(URI uri)
    {
        uri = uri.normalize();
        String path = uri.getPath();
        if (path != null && path.length() > 1 && path.endsWith("/"))
        {
            try
            {
                String ascii = uri.toASCIIString();
                uri = new URI(ascii.substring(0, ascii.length() - 1));
            }
            catch (URISyntaxException e)
            {
                throw new IllegalArgumentException(e);
            }
        }
        return uri;
    }

    public static String toCanonicalURI(String uri)
    {
        if (uri != null && uri.length() > 1 && uri.endsWith("/"))
        {
            return uri.substring(0, uri.length() - 1);
        }
        return uri;
    }

    public static Path toCanonicalPath(String path)
    {
        if (path == null)
            return null;
        if (path.length() > 1 && path.endsWith("/"))
            path = path.substring(0, path.length() - 1);
        return toCanonicalPath(FileSystems.getDefault().getPath(path));
    }

    private static Path toCanonicalPath(Path path)
    {
        if (path == null)
        {
            return null;
        }
        if (Files.exists(path))
        {
            try
            {
                return path.toRealPath();
            }
            catch (IOException e)
            {
                throw new IllegalArgumentException(e);
            }
        }
        return path.toAbsolutePath();
    }

    private static class PathAttribute extends Attribute
    {
        public final Path path;

        public PathAttribute(String key, Path path, int weight)
        {
            super(key, path.toString(), weight);
            this.path = path;
        }

        @Override
        public String toString()
        {
            return String.format("PathAttribute[%s=>%s]", key, path);
        }
    }

    private static class URIAttribute extends Attribute
    {
        public final URI uri;

        public URIAttribute(String key, URI uri, int weight)
        {
            super(key, toCanonicalURI(uri.toASCIIString()), weight);
            this.uri = toCanonicalURI(uri);
        }

        @Override
        public String toString()
        {
            return String.format("URIAttribute[%s=>%s]", key, uri);
        }
    }

    private static Comparator<Attribute> attrComparator = new Comparator<Attribute>()
    {
        @Override
        public int compare(Attribute o1, Attribute o2)
        {
            if ((o1.value == null) && (o2.value != null))
            {
                return -1;
            }

            if ((o1.value != null) && (o2.value == null))
            {
                return 1;
            }

            if ((o1.value == null) && (o2.value == null))
            {
                return 0;
            }

            // Different lengths?
            int diff = o2.value.length() - o1.value.length();
            if (diff != 0)
            {
                return diff;
            }

            // Different names?
            diff = o2.value.compareTo(o1.value);
            if (diff != 0)
            {
                return diff;
            }

            // The paths are the same, base now on weight
            return o2.weight - o1.weight;
        }
    };

    private URI warURI;
    private Map<String, Attribute> attributes = new HashMap<>();
    private List<PathAttribute> paths = new ArrayList<>();
    private List<URIAttribute> uris = new ArrayList<>();

    public AttributeNormalizer(Resource baseResource)
    {
        if (baseResource == null)
            throw new IllegalArgumentException("No base resource!");

        System.err.println(baseResource);

        warURI = toCanonicalURI(baseResource.getURI());
        if (!warURI.isAbsolute())
            throw new IllegalArgumentException("WAR URI is not absolute: " + warURI);

        addSystemProperty("jetty.base", 9);
        addSystemProperty("jetty.home", 8);
        addSystemProperty("user.home", 7);
        addSystemProperty("user.dir", 6);

        if (warURI.getScheme().equalsIgnoreCase("file"))
            paths.add(new PathAttribute("WAR.path", toCanonicalPath(new File(warURI).toString()), 10));
        uris.add(new URIAttribute("WAR.uri", warURI, 9)); // preferred encoding
        uris.add(new URIAttribute("WAR", warURI, 8)); // legacy encoding

        Collections.sort(paths, attrComparator);
        Collections.sort(uris, attrComparator);

        Stream.concat(paths.stream(), uris.stream()).forEach(a -> attributes.put(a.key, a));

        if (LOG.isDebugEnabled())
        {
            for (Attribute attr : attributes.values())
            {
                LOG.debug(attr.toString());
            }
        }
    }

    private void addSystemProperty(String key, int weight)
    {
        String value = System.getProperty(key);
        if (value != null)
        {
            Path path = toCanonicalPath(value);
            paths.add(new PathAttribute(key, path, weight));
            uris.add(new URIAttribute(key + ".uri", path.toUri(), weight));
        }
    }

    /**
     * Normalize a URI, URL, or File reference by replacing known attributes with ${key} attributes.
     *
     * @param o the object to normalize into a string
     * @return the string representation of the object, with expansion keys.
     */
    public String normalize(Object o)
    {
        try
        {
            // Find a URI
            URI uri = null;
            Path path = null;
            if (o instanceof URI)
                uri = toCanonicalURI(((URI)o));
            else if (o instanceof Resource)
                uri = toCanonicalURI(((Resource)o).getURI());
            else if (o instanceof URL)
                uri = toCanonicalURI(((URL)o).toURI());
            else if (o instanceof File)
                path = ((File)o).getAbsoluteFile().getCanonicalFile().toPath();
            else if (o instanceof Path)
                path = (Path)o;
            else
            {
                String s = o.toString();
                try
                {
                    uri = new URI(s);
                    if (uri.getScheme() == null)
                    {
                        // Unknown scheme? not relevant to normalize
                        return s;
                    }
                }
                catch (URISyntaxException e)
                {
                    // This path occurs for many reasons, but most common is when this
                    // is executed on MS Windows, on a string like "D:\jetty"
                    // and the new URI() fails for
                    // java.net.URISyntaxException: Illegal character in opaque part at index 2: D:\jetty
                    return s;
                }
            }

            if (uri != null)
            {
                if ("jar".equalsIgnoreCase(uri.getScheme()))
                {
                    String raw = uri.getRawSchemeSpecificPart();
                    int bang = raw.indexOf("!/");
                    String normal = normalize(raw.substring(0, bang));
                    String suffix = raw.substring(bang);
                    return "jar:" + normal + suffix;
                }
                else
                {
                    if (uri.isAbsolute())
                    {
                        return normalizeUri(uri);
                    }
                }
            }
            else if (path != null)
                return normalizePath(path);
        }
        catch (Exception e)
        {
            LOG.warn("Failed to normalize {}", o, e);
        }
        return String.valueOf(o);
    }

    protected String normalizeUri(URI uri)
    {
        for (URIAttribute a : uris)
        {
            if (uri.compareTo(a.uri) == 0)
                return String.format("${%s}", a.key);

            if (!a.uri.getScheme().equalsIgnoreCase(uri.getScheme()))
                continue;
            if (a.uri.getHost() == null && uri.getHost() != null)
                continue;
            if (a.uri.getHost() != null && !a.uri.getHost().equals(uri.getHost()))
                continue;

            String aPath = a.uri.getPath();
            String uPath = uri.getPath();
            if (aPath.equals(uPath))
                return a.value;

            if (!uPath.startsWith(aPath))
                continue;

            if (uPath.length() == aPath.length())
                return String.format("${%s}", a.key);

            String s = uPath.substring(aPath.length());
            if (s.length() > 0 && s.charAt(0) != '/')
                continue;

            return String.format("${%s}%s", a.key, s);
        }
        return uri.toASCIIString();
    }

    protected String normalizePath(Path path)
    {
        for (PathAttribute a : paths)
        {
            try
            {
                if (path.equals(a.path) || Files.isSameFile(path, a.path))
                    return String.format("${%s}", a.key);
            }
            catch (IOException ignore)
            {
                LOG.trace("IGNORED", ignore);
            }

            if (path.startsWith(a.path))
                return String.format("${%s}%c%s", a.key, File.separatorChar, a.path.relativize(path).toString());
        }

        return path.toString();
    }

    public String expand(String str)
    {
        return expand(str, new Stack<String>());
    }

    public String expand(String str, Stack<String> seenStack)
    {
        if (str == null)
        {
            return str;
        }

        if (str.indexOf("${") < 0)
        {
            // Contains no potential expressions.
            return str;
        }

        Matcher mat = __propertyPattern.matcher(str);
        StringBuilder expanded = new StringBuilder();
        int offset = 0;
        String property;
        String value;

        while (mat.find(offset))
        {
            property = mat.group(1);

            // Loop detection
            if (seenStack.contains(property))
            {
                StringBuilder err = new StringBuilder();
                err.append("Property expansion loop detected: ");
                int idx = seenStack.lastIndexOf(property);
                for (int i = idx; i < seenStack.size(); i++)
                {
                    err.append(seenStack.get(i));
                    err.append(" -> ");
                }
                err.append(property);
                throw new RuntimeException(err.toString());
            }

            seenStack.push(property);

            // find property name
            expanded.append(str.subSequence(offset, mat.start()));
            // get property value
            value = getString(property);
            if (value == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Unable to expand: {}", property);
                expanded.append(mat.group());
            }
            else
            {
                // recursively expand
                value = expand(value, seenStack);
                expanded.append(value);
            }
            // update offset
            offset = mat.end();
        }

        // leftover
        expanded.append(str.substring(offset));

        return StringUtil.replace(expanded.toString(), "$$", "$");
    }

    private String getString(String property)
    {
        if (property == null)
        {
            return null;
        }

        Attribute a = attributes.get(property);
        if (a != null)
            return a.value;

        // Use system properties next
        return System.getProperty(property);
    }
}
