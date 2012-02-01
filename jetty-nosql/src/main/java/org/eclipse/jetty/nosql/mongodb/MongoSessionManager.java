package org.eclipse.jetty.nosql.mongodb;
//========================================================================
//Copyright (c) 2011 Intalio, Inc.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.nosql.NoSqlSession;
import org.eclipse.jetty.nosql.NoSqlSessionManager;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoException;

public class MongoSessionManager extends NoSqlSessionManager
{
    private static final Logger LOG = Log.getLogger(MongoSessionManager.class);
  
    private final static Logger __log = Log.getLogger("org.eclipse.jetty.server.session");
   
    /*
     * strings used as keys or parts of keys in mongo
     */
    private final static String __METADATA = "__metadata__";

    public final static String __ID = "id";
    private final static String __CREATED = "created";
    public final static String __VALID = "valid";
    public final static String __INVALIDATED = "invalidated";
    public final static String __ACCESSED = "accessed";
    private final static String __CONTEXT = "context";   
    public final static String __VERSION = __METADATA + ".version";

    /**
    * the context id is only set when this class has been started
    */
    private String _contextId = null;

    
    private DBCollection _sessions;
    private DBObject __version_1;


    /* ------------------------------------------------------------ */
    public MongoSessionManager() throws UnknownHostException, MongoException
    {
        
    }
    
    
    
    /*------------------------------------------------------------ */
    @Override
    public void doStart() throws Exception
    {
        super.doStart();
        String[] hosts = getContextHandler().getVirtualHosts();
        if (hosts == null || hosts.length == 0)
            hosts = getContextHandler().getConnectorNames();
        if (hosts == null || hosts.length == 0)
            hosts = new String[]
            { "::" }; // IPv6 equiv of 0.0.0.0

        String contextPath = getContext().getContextPath();
        if (contextPath == null || "".equals(contextPath))
        {
            contextPath = "*";
        }

        _contextId = createContextId(hosts,contextPath);

        __version_1 = new BasicDBObject(getContextKey(__VERSION),1);
    }

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#setSessionIdManager(org.eclipse.jetty.server.SessionIdManager)
     */
    @Override
    public void setSessionIdManager(SessionIdManager metaManager)
    {
        MongoSessionIdManager msim = (MongoSessionIdManager)metaManager;
        _sessions=msim.getSessions();
        super.setSessionIdManager(metaManager);
        
    }

    /* ------------------------------------------------------------ */
    @Override
    protected synchronized Object save(NoSqlSession session, Object version, boolean activateAfterSave)
    {
        try
        {
            __log.debug("MongoSessionManager:save:" + session);
            session.willPassivate();
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(bout);

            // Form query for upsert
            BasicDBObject key = new BasicDBObject(__ID,session.getClusterId());
            key.put(__VALID,true);

            // Form updates
            BasicDBObject update = new BasicDBObject();
            boolean upsert = false;
            BasicDBObject sets = new BasicDBObject();
            BasicDBObject unsets = new BasicDBObject();

            // handle new or existing
            if (version == null)
            {
                // New session
                upsert = true;
                version = new Long(1);
                sets.put(__CREATED,session.getCreationTime());
                sets.put(getContextKey(__VERSION),version);
            }
            else
            {
                version = new Long(((Long)version).intValue() + 1);
                update.put("$inc",__version_1); 
            }

            // handle valid or invalid
            if (session.isValid())
            {
                sets.put(__ACCESSED,session.getAccessed());
                Set<String> names = session.takeDirty();
                if (isSaveAllAttributes() || upsert)
                {
                    names.addAll(session.getNames()); // note dirty may include removed names
                }
                    
                for (String name : names)
                {
                    Object value = session.getAttribute(name);
                    if (value == null)
                        unsets.put(getContextKey() + "." + encodeName(name),1);
                    else
                        sets.put(getContextKey() + "." + encodeName(name),encodeName(out,bout,value));
                }
            }
            else
            {
                sets.put(__VALID,false);
                sets.put(__INVALIDATED, System.currentTimeMillis());
                unsets.put(getContextKey(),1); 
            }

            // Do the upsert
            if (!sets.isEmpty())
                update.put("$set",sets);
            if (!unsets.isEmpty())
                update.put("$unset",unsets);

            _sessions.update(key,update,upsert,false);
            __log.debug("MongoSessionManager:save:db.sessions.update(" + key + "," + update + ",true)");

            if (activateAfterSave)
                session.didActivate();

            return version;
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
        return null;
    }

    /*------------------------------------------------------------ */
    @Override
    protected Object refresh(NoSqlSession session, Object version)
    {
        __log.debug("MongoSessionManager:refresh " + session);

        // check if our in memory version is the same as what is on the disk
        if (version != null)
        {
            DBObject o = _sessions.findOne(new BasicDBObject(__ID,session.getClusterId()),__version_1);

            if (o != null)
            {
                Object saved = getNestedValue(o, getContextKey(__VERSION));
                
                if (saved != null && saved.equals(version))
                {
                    __log.debug("MongoSessionManager:refresh not needed");
                    return version;
                }
                version = saved;
            }
        }

        // If we are here, we have to load the object
        DBObject o = _sessions.findOne(new BasicDBObject(__ID,session.getClusterId()));

        // If it doesn't exist, invalidate
        if (o == null)
        {
            __log.debug("MongoSessionManager:refresh:marking invalid, no object");
            session.invalidate();
            return null;
        }
        
        // If it has been flagged invalid, invalidate
        Boolean valid = (Boolean)o.get(__VALID);
        if (valid == null || !valid)
        {
            __log.debug("MongoSessionManager:refresh:marking invalid, valid flag " + valid);
            session.invalidate();
            return null;
        }

        // We need to update the attributes. We will model this as a passivate,
        // followed by bindings and then activation.
        session.willPassivate();
        try
        {
            session.clearAttributes();
            
            DBObject attrs = (DBObject)getNestedValue(o,getContextKey());
            
            if (attrs != null)
            {
                for (String name : attrs.keySet())
                {
                    if ( __METADATA.equals(name) )
                    {
                        continue;
                    }
                    
                    String attr = decodeName(name);
                    Object value = decodeValue(attrs.get(name));
                    session.doPutOrRemove(attr,value);
                    session.bindValue(attr,value);
                }
            }

            session.didActivate();
            
            
            return version;
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }

        return null;
    }

    /*------------------------------------------------------------ */
    @Override
    protected synchronized NoSqlSession loadSession(String clusterId)
    {
        DBObject o = _sessions.findOne(new BasicDBObject(__ID,clusterId));
        
        __log.debug("MongoSessionManager:loaded " + o);
        
        if (o == null)
        {
            return null;
        }
        
        Boolean valid = (Boolean)o.get(__VALID);
        if (valid == null || !valid)
        {
            return null;
        }
        
        try
        {
            Object version = o.get(getContextKey(__VERSION));
            Long created = (Long)o.get(__CREATED);
            Long accessed = (Long)o.get(__ACCESSED);
          
            NoSqlSession session = new NoSqlSession(this,created,accessed,clusterId,version);

            // get the attributes for the context
            DBObject attrs = (DBObject)getNestedValue(o,getContextKey());

            __log.debug("MongoSessionManager:attrs: " + attrs);
            if (attrs != null)
            {
                for (String name : attrs.keySet())
                {
                    if ( __METADATA.equals(name) )
                    {
                        continue;
                    }
                    
                    String attr = decodeName(name);
                    Object value = decodeValue(attrs.get(name));

                    session.doPutOrRemove(attr,value);
                    session.bindValue(attr,value);
                    
                }
            }
            session.didActivate();

            return session;
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
        return null;
    }

    /*------------------------------------------------------------ */
    @Override
    protected boolean remove(NoSqlSession session)
    {
        __log.debug("MongoSessionManager:remove:session " + session.getClusterId());

        /*
         * Check if the session exists and if it does remove the context
         * associated with this session
         */
        BasicDBObject key = new BasicDBObject(__ID,session.getClusterId());
        
        DBObject o = _sessions.findOne(key,__version_1);

        if (o != null)
        {
            BasicDBObject remove = new BasicDBObject();
            BasicDBObject unsets = new BasicDBObject();
            unsets.put(getContextKey(),1);
            remove.put("$unsets",unsets);
            _sessions.update(key,remove);

            return true;
        }
        else
        {
            return false;
        }
    }

    /*------------------------------------------------------------ */
    @Override
    protected void invalidateSession(String idInCluster)
    {
        __log.debug("MongoSessionManager:invalidateSession:invalidating " + idInCluster);
        
        super.invalidateSession(idInCluster);
        
        /*
         * pull back the 'valid' value, we can check if its false, if is we don't need to
         * reset it to false
         */
        DBObject validKey = new BasicDBObject(__VALID, true);       
        DBObject o = _sessions.findOne(new BasicDBObject(__ID,idInCluster), validKey);
        
        if (o != null && (Boolean)o.get(__VALID))
        {
            BasicDBObject update = new BasicDBObject();
            BasicDBObject sets = new BasicDBObject();
            sets.put(__VALID,false);
            sets.put(__INVALIDATED, System.currentTimeMillis());
            update.put("$set",sets);
                        
            BasicDBObject key = new BasicDBObject(__ID,idInCluster);

            _sessions.update(key,update);
        }       
    }
    
    /*------------------------------------------------------------ */
    protected String encodeName(String name)
    {
        return name.replace("%","%25").replace(".","%2E");
    }

    /*------------------------------------------------------------ */
    protected String decodeName(String name)
    {
        return name.replace("%2E",".").replace("%25","%");
    }

    /*------------------------------------------------------------ */
    protected Object encodeName(ObjectOutputStream out, ByteArrayOutputStream bout, Object value) throws IOException
    {
        if (value instanceof Number || value instanceof String || value instanceof Boolean || value instanceof Date)
        {
            return value;
        }
        else if (value.getClass().equals(HashMap.class))
        {
            BasicDBObject o = new BasicDBObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>)value).entrySet())
            {
                if (!(entry.getKey() instanceof String))
                {
                    o = null;
                    break;
                }
                o.append(encodeName(entry.getKey().toString()),encodeName(out,bout,entry.getValue()));
            }

            if (o != null)
                return o;
        }

        out.reset();
        out.writeUnshared(value);
        out.flush();
        return bout.toByteArray();
    }

    /*------------------------------------------------------------ */
    protected Object decodeValue(Object value) throws IOException, ClassNotFoundException
    {
        if (value == null || value instanceof Number || value instanceof String || value instanceof Boolean || value instanceof Date)
        {
            return value;
        }
        else if (value instanceof byte[])
        {
            ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream((byte[])value));
            return in.readObject();
        }
        else if (value instanceof DBObject)
        {
            Map<String, Object> map = new HashMap<String, Object>();
            for (String name : ((DBObject)value).keySet())
            {
                String attr = decodeName(name);
                map.put(attr,decodeValue(((DBObject)value).get(name)));
            }
            return map;
        }
        else
        {
            throw new IllegalStateException(value.getClass().toString());
        }
    }

   
    /*------------------------------------------------------------ */
    private String getContextKey()
    {
    	return __CONTEXT + "." + _contextId;
    }
    
    /*------------------------------------------------------------ */
    private String getContextKey(String keybit)
    {
    	return __CONTEXT + "." + _contextId + "." + keybit;
    }
    
    public void purge()
    {   
        ((MongoSessionIdManager)_sessionIdManager).purge();
    }
    
    public void purgeFully()
    {   
        ((MongoSessionIdManager)_sessionIdManager).purgeFully();
    }
    
    public void scavenge()
    {
        ((MongoSessionIdManager)_sessionIdManager).scavenge();
    }
    
    public void scavengeFully()
    {
        ((MongoSessionIdManager)_sessionIdManager).scavengeFully();
    }
    
    /*------------------------------------------------------------ */
    /**
     * returns the total number of session objects in the session store
     * 
     * the count() operation itself is optimized to perform on the server side
     * and avoid loading to client side.
     */
    public long getSessionStoreCount()
    {
        return _sessions.find().count();      
    }
    
    /*------------------------------------------------------------ */
    /**
     * MongoDB keys are . delimited for nesting so .'s are protected characters
     * 
     * @param virtualHosts
     * @param contextPath
     * @return
     */
    private String createContextId(String[] virtualHosts, String contextPath)
    {
        String contextId = virtualHosts[0] + contextPath;
        
        contextId.replace('/', '_');
        contextId.replace('.','_');
        contextId.replace('\\','_');
        
        return contextId;
    }

    /**
     * Dig through a given dbObject for the nested value
     */
    private Object getNestedValue(DBObject dbObject, String nestedKey)
    {
        String[] keyChain = nestedKey.split("\\.");

        DBObject temp = dbObject;

        for (int i = 0; i < keyChain.length - 1; ++i)
        {
            temp = (DBObject)temp.get(keyChain[i]);
            
            if ( temp == null )
            {
                return null;
            }
        }

        return temp.get(keyChain[keyChain.length - 1]);
    }

}
