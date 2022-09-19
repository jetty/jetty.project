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

package org.eclipse.jetty.ee10.osgi.annotations;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.ee10.osgi.boot.utils.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;

/**
 *
 */
public class AnnotationParser extends org.eclipse.jetty.ee10.annotations.AnnotationParser
{
    private Set<URI> _alreadyParsed = ConcurrentHashMap.newKeySet();

    private ConcurrentHashMap<URI, Bundle> _uriToBundle = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Bundle, Resource> _bundleToResource = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Resource, Bundle> _resourceToBundle = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Bundle, URI> _bundleToUri = new ConcurrentHashMap<>();

    public AnnotationParser(int javaPlatform)
    {
        super(javaPlatform);
    }

    /**
     * Keep track of a jetty URI Resource and its associated OSGi bundle.
     *
     * @param bundle the bundle to index
     * @return the resource for the bundle
     * @throws Exception if unable to create the resource reference
     */
    public Resource indexBundle(Bundle bundle) throws Exception
    {
        File bundleFile = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bundle);
        Resource resource = Resource.newResource(bundleFile.toURI());
        URI uri = resource.getURI();
        _uriToBundle.putIfAbsent(uri, bundle);
        _bundleToUri.putIfAbsent(bundle, uri);
        _bundleToResource.putIfAbsent(bundle, resource);
        _resourceToBundle.putIfAbsent(resource, bundle);
        return resource;
    }

    protected URI getURI(Bundle bundle)
    {
        return _bundleToUri.get(bundle);
    }

    protected Resource getResource(Bundle bundle)
    {
        return _bundleToResource.get(bundle);
    }

    protected Bundle getBundle(Resource resource)
    {
        return _resourceToBundle.get(resource);
    }

    /**
     *
     */
    @Override
    public void parse(Set<? extends Handler> handlers, URI[] uris)
        throws Exception
    {
        for (URI uri : uris)
        {
            Bundle associatedBundle = _uriToBundle.get(uri);
            if (associatedBundle == null)
            {
                if (!_alreadyParsed.add(uri))
                {
                    continue;
                }
                //a jar in WEB-INF/lib or the WEB-INF/classes
                //use the behavior of the super class for a standard jar.
                super.parse(handlers, new URI[]{uri});
            }
            else
            {
                parse(handlers, associatedBundle);
            }
        }
    }

    public void parse(Set<? extends Handler> handlers, Bundle bundle)
        throws Exception
    {
        URI uri = _bundleToUri.get(bundle);
        if (!_alreadyParsed.add(uri))
        {
            return;
        }

        String bundleClasspath = (String)bundle.getHeaders().get(Constants.BUNDLE_CLASSPATH);
        if (bundleClasspath == null)
        {
            bundleClasspath = ".";
        }
        //order the paths first by the number of tokens in the path second alphabetically.
        TreeSet<String> paths = new TreeSet<>(
            new Comparator<String>()
            {
                @Override
                public int compare(String o1, String o2)
                {
                    int paths1 = new StringTokenizer(o1, "/", false).countTokens();
                    int paths2 = new StringTokenizer(o2, "/", false).countTokens();
                    if (paths1 == paths2)
                    {
                        return o1.compareTo(o2);
                    }
                    return paths2 - paths1;
                }
            });
        boolean hasDotPath = false;
        StringTokenizer tokenizer = new StringTokenizer(bundleClasspath, StringUtil.DEFAULT_DELIMS, false);
        while (tokenizer.hasMoreTokens())
        {
            String token = tokenizer.nextToken().trim();
            if (!token.startsWith("/"))
            {
                token = "/" + token;
            }
            if (token.equals("/."))
            {
                hasDotPath = true;
            }
            else if (!FileID.isJavaArchive(token) && !token.endsWith("/"))
            {
                paths.add(token + "/");
            }
            else
            {
                paths.add(token);
            }
        }
        //support the development environment: maybe the classes are inside bin or target/classes
        //this is certainly not useful in production.
        //however it makes our life so much easier during development.
        if (bundle.getEntry("/.classpath") != null)
        {
            if (bundle.getEntry("/bin/") != null)
            {
                paths.add("/bin/");
            }
            else if (bundle.getEntry("/target/classes/") != null)
            {
                paths.add("/target/classes/");
            }
        }
        @SuppressWarnings("rawtypes")
        Enumeration classes = bundle.findEntries("/", "*.class", true);
        if (classes == null)
        {
            return;
        }
        while (classes.hasMoreElements())
        {
            URL classUrl = (URL)classes.nextElement();
            String path = classUrl.getPath();
            //remove the longest path possible:
            String name = null;
            for (String prefixPath : paths)
            {
                if (path.startsWith(prefixPath))
                {
                    name = path.substring(prefixPath.length());
                    break;
                }
            }
            if (name == null && hasDotPath)
            {
                //remove the starting '/'
                name = path.substring(1);
            }
            if (name == null)
            {
                //found some .class file in the archive that was not under one of the prefix paths
                //or the bundle classpath wasn't simply ".", so skip it
                continue;
            }

            if (!isValidClassFileName(name))
            {
                continue; //eg skip module-info.class 
            }
            
            //transform into a classname to pass to the resolver
            String shortName = StringUtil.replace(name, '/', '.').substring(0, name.length() - 6);

            addParsedClass(shortName, getResource(bundle));

            try (InputStream classInputStream = classUrl.openStream())
            {
                scanClass(handlers, getResource(bundle), classInputStream);
            }
        }
    }
}
