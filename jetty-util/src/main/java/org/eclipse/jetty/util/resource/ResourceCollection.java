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

package org.eclipse.jetty.util.resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.StringTokenizer;

import org.eclipse.jetty.util.URIUtil;

/**
 * A collection of resources (dirs).
 * Allows webapps to have multiple (static) sources.
 * The first resource in the collection is the main resource.
 * If a resource is not found in the main resource, it looks it up in 
 * the order the resources were constructed.
 * 
 * 
 *
 */
public class ResourceCollection extends Resource
{
    private Resource[] _resources;

    /* ------------------------------------------------------------ */
    /**
     * Instantiates an empty resource collection.
     * 
     * This constructor is used when configuring jetty-maven-plugin.
     */
    public ResourceCollection()
    {
        _resources = new Resource[0];
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Instantiates a new resource collection.
     *
     * @param resources the resources to be added to collection
     */
    public ResourceCollection(Resource... resources)
    {
        List<Resource> list = new ArrayList<Resource>();
        for (Resource r : resources)
        {
            if (r==null)
                continue;
            if (r instanceof ResourceCollection)
            {
                for (Resource r2 : ((ResourceCollection)r).getResources())
                    list.add(r2);
            }
            else
                list.add(r);
        }
        _resources = list.toArray(new Resource[list.size()]);
        for(Resource r : _resources)
        {
            if(!r.exists() || !r.isDirectory())
                throw new IllegalArgumentException(r + " is not an existing directory.");
        }
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * Instantiates a new resource collection.
     *
     * @param resources the resource strings to be added to collection
     */
    public ResourceCollection(String[] resources)
    {
        _resources = new Resource[resources.length];
        try
        {
            for(int i=0; i<resources.length; i++)
            {
                _resources[i] = Resource.newResource(resources[i]);
                if(!_resources[i].exists() || !_resources[i].isDirectory())
                    throw new IllegalArgumentException(_resources[i] + " is not an existing directory.");
            }
        }
        catch(IllegalArgumentException e)
        {
            throw e;
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Instantiates a new resource collection.
     *
     * @param csvResources the string containing comma-separated resource strings
     */
    public ResourceCollection(String csvResources)
    {
        setResourcesAsCSV(csvResources);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Retrieves the resource collection's resources.
     * 
     * @return the resource array
     */
    public Resource[] getResources()
    {
        return _resources;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Sets the resource collection's resources.
     *
     * @param resources the new resource array
     */
    public void setResources(Resource[] resources)
    {
        _resources = resources != null ? resources : new Resource[0];
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the resources as string of comma-separated values.
     * This method should be used when configuring jetty-maven-plugin.
     *
     * @param csvResources the comma-separated string containing
     *                     one or more resource strings.
     */
    public void setResourcesAsCSV(String csvResources)
    {
        StringTokenizer tokenizer = new StringTokenizer(csvResources, ",;");
        int len = tokenizer.countTokens();
        if(len==0)
        {
            throw new IllegalArgumentException("ResourceCollection@setResourcesAsCSV(String) " +
                    " argument must be a string containing one or more comma-separated resource strings.");
        }
        
        _resources = new Resource[len];
        try
        {            
            for(int i=0; tokenizer.hasMoreTokens(); i++)
            {
                _resources[i] = Resource.newResource(tokenizer.nextToken().trim());
                if(!_resources[i].exists() || !_resources[i].isDirectory())
                    throw new IllegalArgumentException(_resources[i] + " is not an existing directory.");
            }
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param path The path segment to add
     * @return The contained resource (found first) in the collection of resources
     */
    @Override
    public Resource addPath(String path) throws IOException, MalformedURLException
    {
        if(_resources==null)
            throw new IllegalStateException("*resources* not set.");
        
        if(path==null)
            throw new MalformedURLException();
        
        if(path.length()==0 || URIUtil.SLASH.equals(path))
            return this;
        
        Resource resource=null;
        ArrayList<Resource> resources = null;
        int i=0;
        for(; i<_resources.length; i++)
        {
            resource = _resources[i].addPath(path);  
            if (resource.exists())
            {
                if (resource.isDirectory())
                    break;       
                return resource;
            }
        }  

        for(i++; i<_resources.length; i++)
        {
            Resource r = _resources[i].addPath(path); 
            if (r.exists() && r.isDirectory())
            {
                if (resource!=null)
                {
                    resources = new ArrayList<Resource>();
                    resources.add(resource);
                    resource=null;
                }
                resources.add(r);
            }
        }

        if (resource!=null)
            return resource;
        if (resources!=null)
            return new ResourceCollection(resources.toArray(new Resource[resources.size()]));
        return null;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param path
     * @return the resource(file) if found, returns a list of resource dirs if its a dir, else null.
     * @throws IOException
     * @throws MalformedURLException
     */
    protected Object findResource(String path) throws IOException, MalformedURLException
    {        
        Resource resource=null;
        ArrayList<Resource> resources = null;
        int i=0;
        for(; i<_resources.length; i++)
        {
            resource = _resources[i].addPath(path);  
            if (resource.exists())
            {
                if (resource.isDirectory())
                    break;
               
                return resource;
            }
        }  

        for(i++; i<_resources.length; i++)
        {
            Resource r = _resources[i].addPath(path); 
            if (r.exists() && r.isDirectory())
            {
                if (resource!=null)
                {
                    resources = new ArrayList<Resource>();
                    resources.add(resource);
                }
                resources.add(r);
            }
        }
        
        if (resource!=null)
            return resource;
        if (resources!=null)
            return resources;
        return null;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public boolean delete() throws SecurityException
    {
        throw new UnsupportedOperationException();
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public boolean exists()
    {
        if(_resources==null)
            throw new IllegalStateException("*resources* not set.");
        
        return true;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public File getFile() throws IOException
    {
        if(_resources==null)
            throw new IllegalStateException("*resources* not set.");
        
        for(Resource r : _resources)
        {
            File f = r.getFile();
            if(f!=null)
                return f;
        }
        return null;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public InputStream getInputStream() throws IOException
    {
        if(_resources==null)
            throw new IllegalStateException("*resources* not set.");
        
        for(Resource r : _resources)
        {
            InputStream is = r.getInputStream();
            if(is!=null)
                return is;
        }
        return null;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public String getName()
    {
        if(_resources==null)
            throw new IllegalStateException("*resources* not set.");
        
        for(Resource r : _resources)
        {
            String name = r.getName();
            if(name!=null)
                return name;
        }
        return null;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public OutputStream getOutputStream() throws IOException, SecurityException
    {
        if(_resources==null)
            throw new IllegalStateException("*resources* not set.");
        
        for(Resource r : _resources)
        {
            OutputStream os = r.getOutputStream();
            if(os!=null)
                return os;
        }
        return null;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public URL getURL()
    {
        if(_resources==null)
            throw new IllegalStateException("*resources* not set.");
        
        for(Resource r : _resources)
        {
            URL url = r.getURL();
            if(url!=null)
                return url;
        }
        return null;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public boolean isDirectory()
    {
        if(_resources==null)
            throw new IllegalStateException("*resources* not set.");
        
        return true;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public long lastModified()
    {
        if(_resources==null)
            throw new IllegalStateException("*resources* not set.");
        
        for(Resource r : _resources)
        {
            long lm = r.lastModified();
            if (lm!=-1)
                return lm;
        }
        return -1;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public long length()
    {
        return -1;
    }    
    
    /* ------------------------------------------------------------ */
    /**
     * @return The list of resource names(merged) contained in the collection of resources.
     */    
    @Override
    public String[] list()
    {
        if(_resources==null)
            throw new IllegalStateException("*resources* not set.");
        
        HashSet<String> set = new HashSet<String>();
        for(Resource r : _resources)
        {
            for(String s : r.list())
                set.add(s);
        }
        String[] result=set.toArray(new String[set.size()]);
        Arrays.sort(result);
        return result;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void release()
    {
        if(_resources==null)
            throw new IllegalStateException("*resources* not set.");
        
        for(Resource r : _resources)
            r.release();
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public boolean renameTo(Resource dest) throws SecurityException
    {
        throw new UnsupportedOperationException();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void copyTo(File destination)
        throws IOException
    {
        for (int r=_resources.length;r-->0;)
            _resources[r].copyTo(destination);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return the list of resources separated by a path separator
     */
    @Override
    public String toString()
    {
        if(_resources==null)
            return "[]";
        
        return String.valueOf(Arrays.asList(_resources));
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isContainedIn(Resource r) throws MalformedURLException
    {
        // TODO could look at implementing the semantic of is this collection a subset of the Resource r?
        return false;
    }

}
