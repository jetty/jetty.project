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
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;

import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/**
 * Normalize Attribute to String.
 * <p>Replaces and expands:
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
    private final Path _warPath;
    private final Path _jettyBasePath;
    private final Path _jettyHomePath;
    private final Path _userHomePath;
    private final Path _userDirPath;
    
    
    public AttributeNormalizer(Resource baseResource)
    {
        try
        {
            _warPath=baseResource==null?null:baseResource.getFile().toPath();
            _jettyBasePath=systemPath("jetty.base");
            _jettyHomePath=systemPath("jetty.home");
            _userHomePath=systemPath("user.home");
            _userDirPath=systemPath("user.dir");
        }
        catch(Exception e)
        {
            throw new IllegalArgumentException(e);
        }
    }
    
    private static Path systemPath(String property) throws Exception
    {
        String p=System.getProperty(property);
        if (p!=null)
            return new File(p).getAbsoluteFile().getCanonicalFile().toPath();
        return null;
    }
   
    public String normalize(Object o)
    {
        try
        {
            // Find a URI
            URI uri=null;
            if (o instanceof URI)
                uri=(URI)o;
            else if (o instanceof URL)
                uri = ((URL)o).toURI();
            else if (o instanceof File)
                uri = ((File)o).toURI();
            else
            {
                String s=o.toString();
                uri=new URI(s);
                if (uri.getScheme()==null)
                    return s;
            }
            
            if ("jar".equalsIgnoreCase(uri.getScheme()))
            {
                String raw = uri.getRawSchemeSpecificPart();
                int bang=raw.indexOf("!/");
                String normal=normalize(raw.substring(0,bang));
                String suffix=raw.substring(bang);
                return "jar:"+normal+suffix;
            }
            else if ("file".equalsIgnoreCase(uri.getScheme()))
            {
                return "file:"+normalizePath(new File(uri).toPath());
            }
            
        }
        catch(Exception e)
        {
            LOG.warn(e);
        }
        return String.valueOf(o);
    }
    
    public String normalizePath(Path path)
    {
        if (_warPath!=null && path.startsWith(_warPath))
            return URIUtil.addPaths("${WAR}",_warPath.relativize(path).toString());
        if (_jettyBasePath!=null && path.startsWith(_jettyBasePath))
            return URIUtil.addPaths("${jetty.base}",_jettyBasePath.relativize(path).toString());
        if (_jettyHomePath!=null && path.startsWith(_jettyHomePath))
            return URIUtil.addPaths("${jetty.home}",_jettyHomePath.relativize(path).toString());
        if (_userHomePath!=null && path.startsWith(_userHomePath))
            return URIUtil.addPaths("${user.home}",_userHomePath.relativize(path).toString());
        if (_userDirPath!=null && path.startsWith(_userDirPath))
            return URIUtil.addPaths("${user.dir}",_userDirPath.relativize(path).toString());
        
        return path.toString();
    }
    
    
    public String expand(String s)
    {
        int i=s.indexOf("${");
        if (i<0)
            return s;
        int e=s.indexOf('}',i+3);
        String prop=s.substring(i+2,e);
        switch(prop)
        {
            case "WAR":
                return s.substring(0,i)+_warPath+expand(s.substring(e+1));
            case "jetty.base":
                return s.substring(0,i)+_jettyBasePath+expand(s.substring(e+1));
            case "jetty.home":
                return s.substring(0,i)+_jettyHomePath+expand(s.substring(e+1));
            case "user.home":
                return s.substring(0,i)+_userHomePath+expand(s.substring(e+1));
            case "user.dir":
                return s.substring(0,i)+_userDirPath+expand(s.substring(e+1));
            default:
                return s;
        }
    }
}
