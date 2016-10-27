//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.quickstart;

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
import java.util.jar.Attributes;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/**
 * Normalize Attribute to String.
 * <p>
 * Replaces and expands:
 * <ul>
 * <li>${WAR}</li>
 * <li>${jetty.base}</li>
 * <li>${jetty.home}</li>
 * <li>${user.home}</li>
 * <li>${user.dir}</li>
 * </ul>
 */
public class AttributeNormalizer
{
    private static final Logger LOG = Log.getLogger(AttributeNormalizer.class);
    private static final Pattern __propertyPattern = Pattern.compile("(?<=[^$]|^)\\$\\{([^}]*)\\}");
    
    private static class Attribute
    {
        final String key;
        final String value;
        
        public Attribute(String key, String value)
        {
            this.key = key;
            this.value = value;
        }
    }
    
    private static Path toCanonicalPath(String path)
    {
        if (path == null)
        {
            return null;
        }
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
        
        public PathAttribute(String key, Path path)
        {
            super(key,path.toString());
            this.path = path;
        }
        
        @Override
        public String toString()
        {
            return String.format("PathAttribute[%s=>%s]",key,path);
        }
    }
    
    private static class URIAttribute extends Attribute
    {
        public final URI uri;
        
        public URIAttribute(String key, URI uri)
        {
            super(key,uri.toASCIIString());
            this.uri = uri;
        }
        
        @Override
        public String toString()
        {
            return String.format("URIAttribute[%s=>%s]",key,uri);
        }
    }
    
    private static void addPath(List<PathAttribute>paths,String key)
    {
        String value = System.getProperty(key);
        if (value!=null)
            paths.add(new PathAttribute(key,toCanonicalPath(value)));
    }

    private URI warURI;
    private Map<String,Attribute> attributes = new HashMap<>();
    private List<PathAttribute> paths = new ArrayList<>();
    private List<URIAttribute> uris = new ArrayList<>();

    
    public AttributeNormalizer(Resource baseResource)
    {
        if (baseResource==null)
            throw new IllegalArgumentException("No base resource!");
            
        warURI = baseResource.getURI().normalize();
        if (!warURI.isAbsolute())
            throw new IllegalArgumentException("WAR URI is not absolute: " + warURI);
        
        addPath(paths,"jetty.base");
        addPath(paths,"jetty.home");
        addPath(paths,"user.home");
        addPath(paths,"user.dir");
        
        uris.add(new URIAttribute("WAR", warURI));
        
        Stream.concat(paths.stream(),uris.stream()).forEach(a->attributes.put(a.key,a));        

        if (LOG.isDebugEnabled())
        {
            for (Attribute attr : attributes.values())
            {
                LOG.debug(attr.toString());
            }
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
            if (o instanceof URI)
                uri = ((URI)o).normalize();
            else if (o instanceof URL)
                uri = ((URL)o).toURI().normalize();
            else if (o instanceof File)
                uri = ((File)o).toURI().normalize();
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
                catch(URISyntaxException e)
                {
                    // This path occurs for many reasons, but most common is when this
                    // is executed on MS Windows, on a string like "D:\jetty"
                    // and the new URI() fails for
                    // java.net.URISyntaxException: Illegal character in opaque part at index 2: D:\jetty
                    return s;
                }
            }

            if ("jar".equalsIgnoreCase(uri.getScheme()))
            {
                String raw = uri.getRawSchemeSpecificPart();
                int bang = raw.indexOf("!/");
                String normal = normalize(raw.substring(0,bang));
                String suffix = raw.substring(bang);
                return "jar:" + normal + suffix;
            }
            else
            {
                if(uri.isAbsolute())
                {
                    return normalizeUri(uri);
                }
            }
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
        return String.valueOf(o);
    }
    
    public String normalizeUri(URI uri)
    {
        for (URIAttribute a : uris)
        {
            try
            {
                if (uri.compareTo(a.uri)==0)
                    return String.format("${%s}",a.key);

                if (!a.uri.getScheme().equalsIgnoreCase(uri.getScheme()))
                    continue;
                if (a.uri.getHost()==null && uri.getHost()!=null)
                    continue;
                if (a.uri.getHost()!=null && !a.uri.getHost().equals(uri.getHost()))
                    continue;

                if (a.uri.getPath().equals(uri.getPath()))
                    return a.value;

                if (!uri.getPath().startsWith(a.uri.getPath()))
                    continue;

                String s = uri.getPath().substring(a.uri.getPath().length());

                if (s.charAt(0)!='/')
                    continue;

                return String.format("${%s}%s",a.key,new URI(s).toASCIIString());
            }
            catch(URISyntaxException e)
            {
                LOG.ignore(e);
            }
        }
        return uri.toASCIIString();
    }

    public String normalizePath(Path path)
    {
        for (PathAttribute a : paths)
        {
            try
            {
                if (path.startsWith(a.path) || path.equals(a.path) || Files.isSameFile(path,a.path))
                    return String.format("${%s}%s",a.key,a.path.relativize(path).toString());
            }
            catch (IOException ignore)
            {
                LOG.ignore(ignore);
            }
        }

        return path.toString();
    }

    public String expand(String str)
    {
        return expand(str,new Stack<String>());
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
            expanded.append(str.subSequence(offset,mat.start()));
            // get property value
            value = getString(property);
            if (value == null)
            {
                if(LOG.isDebugEnabled())
                    LOG.debug("Unable to expand: {}",property);
                expanded.append(mat.group());
            }
            else
            {
                // recursively expand
                value = expand(value,seenStack);
                expanded.append(value);
            }
            // update offset
            offset = mat.end();
        }

        // leftover
        expanded.append(str.substring(offset));

        // special case for "$$"
        if (expanded.indexOf("$$") >= 0)
        {
            return expanded.toString().replaceAll("\\$\\$","\\$");
        }

        return expanded.toString();
    }

    private String getString(String property)
    {
        if(property==null)
        {
            return null;
        }

        Attribute a = attributes.get(property);
        if (a!=null)
            return a.value;

        // Use system properties next
        return System.getProperty(property);
    }
}
