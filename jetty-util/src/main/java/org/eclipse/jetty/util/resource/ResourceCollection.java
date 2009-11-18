// ========================================================================
// Copyright (c) 2007-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

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
    
    public ResourceCollection()
    {
        
    }
    
    /* ------------------------------------------------------------ */
    public ResourceCollection(Resource[] resources)
    {
        setResources(resources);
    }
    
    /* ------------------------------------------------------------ */
    public ResourceCollection(String[] resources)
    {
        setResources(resources);
    }
    
    /* ------------------------------------------------------------ */
    public ResourceCollection(String csvResources)
    {
        setResources(csvResources);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * 
     * @param resources Resource array
     */
    public void setResources(Resource[] resources)
    {
        if(_resources!=null)
            throw new IllegalStateException("*resources* already set.");
        
        if(resources==null)
            throw new IllegalArgumentException("*resources* must not be null.");
        
        if(resources.length==0)
            throw new IllegalArgumentException("arg *resources* must be one or more resources.");
        
        _resources = resources;
        for(Resource r : _resources)
        {
            if(!r.exists() || !r.isDirectory())
                throw new IllegalArgumentException(r + " is not an existing directory.");
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * 
     * @param resources String array
     */
    public void setResources(String[] resources)
    {
        if(_resources!=null)
            throw new IllegalStateException("*resources* already set.");
        
        if(resources==null)
            throw new IllegalArgumentException("*resources* must not be null.");
        
        if(resources.length==0)
            throw new IllegalArgumentException("arg *resources* must be one or more resources.");
        
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
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * 
     * @param csvResources Comma separated values
     */
    public void setResources(String csvResources)
    {
        if(_resources!=null)
            throw new IllegalStateException("*resources* already set.");
        
        if(csvResources==null)
            throw new IllegalArgumentException("*csvResources* must not be null.");
        
        StringTokenizer tokenizer = new StringTokenizer(csvResources, ",;");
        int len = tokenizer.countTokens();
        if(len==0)
            throw new IllegalArgumentException("arg *resources* must be one or more resources.");
        
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
     * 
     * @param csvResources Comma separated values
     */
    public void setResourcesAsCSV(String csvResources)
    {
        setResources(csvResources);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * 
     * @return the resource array
     */
    public Resource[] getResources()
    {
        return _resources;
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
            return "";
        
        StringBuilder buffer = new StringBuilder();
        for(Resource r : _resources)
            buffer.append(r.toString()).append(';');
        return buffer.toString();
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isContainedIn(Resource r) throws MalformedURLException
    {
        // TODO could look at implementing the semantic of is this collection a subset of the Resource r?
        return false;
    }

}
