//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.osgi.annotations;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.annotations.ClassNameResolver;
import org.eclipse.jetty.osgi.boot.utils.BundleFileLocatorHelper;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.eclipse.jetty.util.resource.Resource;
import org.objectweb.asm.Opcodes;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;

/**
 * 
 */
public class AnnotationParser extends org.eclipse.jetty.annotations.AnnotationParser
{
    private Set<URI> _alreadyParsed = new ConcurrentHashSet<URI>();
    
    private ConcurrentHashMap<URI,Bundle> _uriToBundle = new ConcurrentHashMap<URI, Bundle>();
    private ConcurrentHashMap<Bundle,Resource> _bundleToResource = new ConcurrentHashMap<Bundle,Resource>();
    private ConcurrentHashMap<Resource, Bundle> _resourceToBundle = new ConcurrentHashMap<Resource, Bundle>();
    private ConcurrentHashMap<Bundle,URI> _bundleToUri = new ConcurrentHashMap<Bundle, URI>();
    
    static
    {
        //As of jetty 9.2.0, the impl of asm visitor classes is compatible with both asm4 and asm5.
        //We need to use asm4 with osgi, because we need to use aries spifly to support annotations,
        //and currently this only supports asm4. Therefore, we set the asm api version to be 4 for osgi.
        ASM_OPCODE_VERSION = Opcodes.ASM4;
    }
    
    /**
     * Keep track of a jetty URI Resource and its associated OSGi bundle.
     * @param uri
     * @param bundle
     * @throws Exception 
     */
    protected Resource indexBundle(Bundle bundle) throws Exception
    {
        File bundleFile = BundleFileLocatorHelper.DEFAULT.getBundleInstallLocation(bundle);
        Resource resource = Resource.newResource(bundleFile.toURI());
        URI uri = resource.getURI();
        _uriToBundle.putIfAbsent(uri,bundle);
        _bundleToUri.putIfAbsent(bundle,uri);
        _bundleToResource.putIfAbsent(bundle,resource);
        _resourceToBundle.putIfAbsent(resource,bundle);
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
    protected Bundle getBundle (Resource resource)
    {
        return _resourceToBundle.get(resource);
    }
    
    
    /**
     * 
     */
    @Override
    public void parse (Set<? extends Handler> handlers, URI[] uris, ClassNameResolver resolver)
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
                super.parse(handlers, new URI[] {uri},resolver);
            }
            else
            {
                parse(handlers, associatedBundle,resolver);
            }
        }
    }
    
    protected void parse(Set<? extends Handler> handlers, Bundle bundle, ClassNameResolver resolver)
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
        TreeSet<String> paths = new TreeSet<String>(
                new Comparator<String>()
                {
                    public int compare(String o1, String o2)
                    {
                        int paths1 = new StringTokenizer(o1,"/",false).countTokens();
                        int paths2 = new StringTokenizer(o2,"/",false).countTokens();
                        if (paths1 == paths2)
                        {
                            return o1.compareTo(o2);
                        }
                        return paths2 - paths1;
                    }
                });
        boolean hasDotPath = false;
        StringTokenizer tokenizer = new StringTokenizer(bundleClasspath, ",;", false);
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
            else if (!token.endsWith(".jar") && !token.endsWith("/"))
            {
                paths.add(token+"/");
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
        Enumeration classes = bundle.findEntries("/","*.class",true);
        if (classes == null)
        {
            return;
        }
        while (classes.hasMoreElements())
        {
            URL classUrl = (URL) classes.nextElement();
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
            //transform into a classname to pass to the resolver
            String shortName =  name.replace('/', '.').substring(0,name.length()-6);
            if ((resolver == null)|| (!resolver.isExcluded(shortName) && (!isParsed(shortName) || resolver.shouldOverride(shortName))))
                scanClass(handlers, getResource(bundle), classUrl.openStream());
        }
    }
    
}
