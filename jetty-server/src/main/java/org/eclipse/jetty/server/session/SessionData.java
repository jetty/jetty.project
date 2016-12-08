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


package org.eclipse.jetty.server.session;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * SessionData
 *
 * The data associated with a session. A Session object has a 1:1 relationship
 * with a SessionData object. The behaviour of sessions is implemented in the
 * Session object (eg calling listeners, keeping timers etc). A Session's
 * associated SessionData is the object which can be persisted, serialized etc.
 */
public class SessionData implements Serializable
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
   
    private static final long serialVersionUID = 1L;

    protected String _id;
    protected String _contextPath;
    protected String _vhost;
    protected String _lastNode;
    protected long _expiry; //precalculated time of expiry in ms since epoch
    protected long _created;
    protected long _cookieSet;
    protected long _accessed;         // the time of the last access
    protected long _lastAccessed;     // the time of the last access excluding this one
    protected long _maxInactiveMs;
    protected Map<String,Object> _attributes;
    protected boolean _dirty;
    protected long _lastSaved; //time in msec since last save
    
    public SessionData (String id, String cpath, String vhost, long created, long accessed, long lastAccessed, long maxInactiveMs)
    {
       this(id, cpath, vhost, created, accessed, lastAccessed, maxInactiveMs, new ConcurrentHashMap<String, Object>());
    }

    public SessionData (String id, String cpath, String vhost, long created, long accessed, long lastAccessed, long maxInactiveMs, Map<String,Object> attributes)
    {
        _id = id;
        setContextPath(cpath);
        setVhost(vhost);
        _created = created;
        _accessed = accessed;
        _lastAccessed = lastAccessed;
        _maxInactiveMs = maxInactiveMs;
        calcAndSetExpiry();
        _attributes = attributes;
    }
    
    /**
     * Copy the info from the given sessiondata
     * 
     * @param data the sessiondata to be copied
     */
    public void copy (SessionData data)
    {
        if (data == null)
            return; //don't copy if no data

        if (data.getId() == null || !(getId().equals(data.getId())))
            throw new IllegalStateException ("Can only copy data for same session id");

        if (data == this)
            return; //don't copy ourself
        
        setLastNode(data.getLastNode());
        setContextPath(data.getContextPath());
        setVhost(data.getVhost());
        setCookieSet(data.getCookieSet());
        setCreated(data.getCreated());
        setAccessed(data.getAccessed());
        setLastAccessed(data.getLastAccessed());
        setMaxInactiveMs(data.getMaxInactiveMs());
        setExpiry(data.getExpiry());
        setLastSaved(data.getLastSaved());
        clearAllAttributes();
        putAllAttributes(data.getAllAttributes());
    }

    /**
     * @return time at which session was last written out
     */
    public long getLastSaved()
    {
        return _lastSaved;
    }


    public void setLastSaved(long lastSaved)
    {
        _lastSaved = lastSaved;
    }


    /**
     * @return true if a session needs to be written out
     */
    public boolean isDirty()
    {
        return _dirty;
    }

    public void setDirty(boolean dirty)
    {
        _dirty = dirty;
    }
    
    /**
     * @param name the name of the attribute
     * @return the value of the attribute named
     */
    public Object getAttribute (String name)
    {
        return _attributes.get(name);
    }

    /**
     * @return a Set of attribute names
     */
    public Set<String> getKeys()
    {
        return _attributes.keySet();
    }
    
    public Object setAttribute (String name, Object value)
    {
        Object old = (value==null?_attributes.remove(name):_attributes.put(name,value));
        if (value == null && old == null)
            return old; //if same as remove attribute but attribute was already removed, no change
        
        setDirty (name);
       return old;
    }
    
    public void setDirty (String name)
    {
        setDirty (true);
    }
    
    public void putAllAttributes (Map<String,Object> attributes)
    {
        _attributes.putAll(attributes);
    }
    
    /**
     * Remove all attributes
     */
    public void clearAllAttributes ()
    {
        _attributes.clear();
    }
    
    /**
     * @return an unmodifiable map of the attributes
     */
    public Map<String,Object> getAllAttributes()
    {
        return Collections.unmodifiableMap(_attributes);
    }
    
    /**
     * @return the id of the session
     */
    public String getId()
    {
        return _id;
    }

    public void setId(String id)
    {
        _id = id;
    }

    /**
     * @return the context path associated with this session
     */
    public String getContextPath()
    {
        return _contextPath;
    }

    public void setContextPath(String contextPath)
    {
        _contextPath = contextPath;
    }

    /**
     * @return virtual host of context associated with session
     */
    public String getVhost()
    {
        return _vhost;
    }

    public void setVhost(String vhost)
    {
        _vhost = vhost;
    }

    /**
     * @return last node to manage the session
     */
    public String getLastNode()
    {
        return _lastNode;
    }

    public void setLastNode(String lastNode)
    {
        _lastNode = lastNode;
    }

    /**
     * @return time at which session expires
     */
    public long getExpiry()
    {
        return _expiry;
    }

    public void setExpiry(long expiry)
    {
        _expiry = expiry;
    }
    
    public long calcExpiry ()
    {
        return calcExpiry(System.currentTimeMillis());
    }
    
    public long calcExpiry (long time)
    {
        return (getMaxInactiveMs() <= 0 ? 0 : (time + getMaxInactiveMs()));
    }
    
    public void calcAndSetExpiry (long time)
    {
        setExpiry(calcExpiry(time));
    }
    
    public void calcAndSetExpiry ()
    {
        setExpiry(calcExpiry());
    }

    public long getCreated()
    {
        return _created;
    }

    public void setCreated(long created)
    {
        _created = created;
    }

    /**
     * @return time cookie was set
     */
    public long getCookieSet()
    {
        return _cookieSet;
    }

    public void setCookieSet(long cookieSet)
    {
        _cookieSet = cookieSet;
    }

    /**
     * @return time session was accessed
     */
    public long getAccessed()
    {
        return _accessed;
    }

    public void setAccessed(long accessed)
    {
        _accessed = accessed;
    }

    /**
     * @return previous time session was accessed
     */
    public long getLastAccessed()
    {
        return _lastAccessed;
    }

    public void setLastAccessed(long lastAccessed)
    {
        _lastAccessed = lastAccessed;
    }

    public long getMaxInactiveMs()
    {
        return _maxInactiveMs;
    }

    public void setMaxInactiveMs(long maxInactive)
    {
        _maxInactiveMs = maxInactive;
    }

    private void writeObject(java.io.ObjectOutputStream out) throws IOException
    {  
        out.writeUTF(_id); //session id
        out.writeUTF(_contextPath); //context path
        out.writeUTF(_vhost); //first vhost

        out.writeLong(_accessed);//accessTime
        out.writeLong(_lastAccessed); //lastAccessTime
        out.writeLong(_created); //time created
        out.writeLong(_cookieSet);//time cookie was set
        out.writeUTF(_lastNode); //name of last node managing
  
        out.writeLong(_expiry); 
        out.writeLong(_maxInactiveMs);
        out.writeObject(_attributes);
    }
    
    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException
    {
        _id = in.readUTF();
        _contextPath = in.readUTF();
        _vhost = in.readUTF();
        
        _accessed = in.readLong();//accessTime
        _lastAccessed = in.readLong(); //lastAccessTime
        _created = in.readLong(); //time created
        _cookieSet = in.readLong();//time cookie was set
        _lastNode = in.readUTF(); //last managing node
        _expiry = in.readLong(); 
        _maxInactiveMs = in.readLong();
        _attributes = (Map<String,Object>)in.readObject();
    }
    
    public boolean isExpiredAt (long time)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Testing expiry on session {}: expires at {} now {} maxIdle {}", _id, getExpiry(), time, getMaxInactiveMs());
        if (getMaxInactiveMs() <= 0)
            return false; //never expires
        return (getExpiry() <= time);
    }
    
    /** 
     * @see java.lang.Object#toString()
     */
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("id="+_id);
        builder.append(", contextpath="+_contextPath);
        builder.append(", vhost="+_vhost);
        builder.append(", accessed="+_accessed);
        builder.append(", lastaccessed="+_lastAccessed);
        builder.append(", created="+_created);
        builder.append(", cookieset="+_cookieSet);
        builder.append(", lastnode="+_lastNode);
        builder.append(", expiry="+_expiry);
        builder.append(", maxinactive="+_maxInactiveMs);
        return builder.toString();
    }
}
