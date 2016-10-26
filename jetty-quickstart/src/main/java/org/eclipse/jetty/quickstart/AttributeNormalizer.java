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
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static class PathAttribute
    {
        public final Path path;
        public final String key;
        private int weight = -1;

        public PathAttribute(String key, Path path) throws IOException
        {
            this.key = key;
            this.path = toCanonicalPath(path);
            // TODO: Don't allow non-directory paths? (but what if the path doesn't exist?)
        }

        public PathAttribute(String key, String systemPropertyKey) throws IOException
        {
            this(key, toCanonicalPath(System.getProperty(systemPropertyKey)));
        }
        
        private static Path toCanonicalPath(String path) throws IOException
        {
            if (path == null)
            {
                return null;
            }
            return toCanonicalPath(FileSystems.getDefault().getPath(path));
        }
        
        private static Path toCanonicalPath(Path path) throws IOException
        {
            if (path == null)
            {
                return null;
            }
            if (Files.exists(path))
            {
                return path.toRealPath();
            }
            return path.toAbsolutePath();
        }

        public PathAttribute weight(int newweight)
        {
            this.weight = newweight;
            return this;
        }
        
        @Override
        public String toString()
        {
            return String.format("PathAttribute[%s=>%s,%d]",key,path,weight);
        }
    }

    private static class PathAttributeComparator implements Comparator<PathAttribute>
    {
        @Override
        public int compare(PathAttribute o1, PathAttribute o2)
        {
            if( (o1.path == null) && (o2.path != null) )
            {
                return -1;
            }
            
            if( (o1.path != null) && (o2.path == null) )
            {
                return 1;
            }
            
            if( (o1.path == null) && (o2.path == null) )
            {
                return 0;
            }
            
            // Different lengths?
            int diff = o2.path.getNameCount() - o1.path.getNameCount();
            if(diff != 0)
            {
                return diff;
            }
            
            // Different names?
            diff = o2.path.compareTo(o1.path);
            if(diff != 0)
            {
                return diff;
            }
            
            // The paths are the same, base now on weight
            return o2.weight - o1.weight;
        }
    }
    
    private static class PathAttributes extends ArrayList<AttributeNormalizer.PathAttribute>
    {
        @Override
        public boolean add(AttributeNormalizer.PathAttribute pathAttribute)
        {
            if (pathAttribute.path == null)
            {
                return false;
            }
            return super.add(pathAttribute);
        }
    }
    
    public static String uriSeparators(String path)
    {
        StringBuilder ret = new StringBuilder();
        for (char c : path.toCharArray())
        {
            if ((c == '/') || (c == '\\'))
            {
                ret.append('/');
            }
            else
            {
                ret.append(c);
            }
        }
        return ret.toString();
    }

    private URI warURI;
    private PathAttributes attributes = new PathAttributes();

    public AttributeNormalizer(Resource baseResource)
    {
        // WAR URI is always evaluated before paths.
        warURI = baseResource == null ? null : baseResource.getURI();
        // We don't normalize or resolve the baseResource URI
        if (!warURI.isAbsolute())
            throw new IllegalArgumentException("WAR URI is not absolute: " + warURI);
        try
        {
            // Track path attributes for expansion
            attributes.add(new PathAttribute("jetty.base", "jetty.base").weight(9));
            attributes.add(new PathAttribute("jetty.home", "jetty.home").weight(8));
            attributes.add(new PathAttribute("user.home", "user.home").weight(7));
            attributes.add(new PathAttribute("user.dir", "user.dir").weight(6));
            if(warURI != null && warURI.getScheme().equals("file"))
            {
                attributes.add(new PathAttribute("WAR", new File(warURI).toPath().toAbsolutePath()).weight(10));
            }
            
            Collections.sort(attributes, new PathAttributeComparator());

            if (LOG.isDebugEnabled())
            {
                int i = 0;
                for (PathAttribute attr : attributes)
                {
                    LOG.debug(" [{}] {}", i++, attr);
                }
            }
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    public String normalize(Object o)
    {
        try
        {
            // Find a URI
            URI uri = null;
            if (o instanceof URI)
                uri = (URI)o;
            else if (o instanceof URL)
                uri = ((URL)o).toURI();
            else if (o instanceof File)
                uri = ((File)o).toURI();
            else
            {
                String s = o.toString();
                uri = new URI(s);
                if (uri.getScheme() == null)
                    return s;
            }

            if ("jar".equalsIgnoreCase(uri.getScheme()))
            {
                String raw = uri.getRawSchemeSpecificPart();
                int bang = raw.indexOf("!/");
                String normal = normalize(raw.substring(0,bang));
                String suffix = raw.substring(bang);
                return "jar:" + normal + suffix;
            }
            else if ("file".equalsIgnoreCase(uri.getScheme()))
            {
                return "file:" + normalizePath(new File(uri.getRawSchemeSpecificPart()).toPath());
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
        String uriStr = uri.toASCIIString();
        String warStr = warURI.toASCIIString();
        if (uriStr.startsWith(warStr))
        {
            return "${WAR}" + uriStr.substring(warStr.length());
        }
        return uriStr;
    }

    public String normalizePath(Path path)
    {
        for (PathAttribute attr : attributes)
        {
            if (attr.path == null)
                continue;
            
            try
            {
                if (path.startsWith(attr.path) || path.equals(attr.path) || Files.isSameFile(path,attr.path))
                {
                    return uriSeparators(URIUtil.addPaths("${" + attr.key + "}",attr.path.relativize(path).toString()));
                }
            }
            catch (IOException ignore)
            {
                LOG.ignore(ignore);
            }
        }

        return uriSeparators(path.toString());
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
        if(property == null)
        {
            return null;
        }

        // Use known path attributes
        for (PathAttribute attr : attributes)
        {
            if (attr.key.equalsIgnoreCase(property))
            {
                String path = uriSeparators(attr.path.toString());
                if (path.endsWith("/"))
                {
                    return path.substring(0, path.length() - 1);
                }
                else
                {
                    return path;
                }
            }
        }

        // Use system properties next
        return System.getProperty(property);
    }
}
