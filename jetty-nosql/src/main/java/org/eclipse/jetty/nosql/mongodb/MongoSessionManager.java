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

package org.eclipse.jetty.nosql.mongodb;

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
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoException;


/**
 * MongoSessionManager
 *
 * Clustered session manager using MongoDB as the shared DB instance.
 * The document model is an outer object that contains the elements:
 * <ul>
 *  <li>"id"      : session_id </li>
 *  <li>"created" : create_time </li>
 *  <li>"accessed": last_access_time </li>
 *  <li>"maxIdle" : max_idle_time setting as session was created </li>
 *  <li>"expiry"  : time at which session should expire </li>
 *  <li>"valid"   : session_valid </li>
 *  <li>"context" : a nested object containing 1 nested object per context for which the session id is in use
 * </ul>
 * Each of the nested objects inside the "context" element contains:
 * <ul>
 *  <li>unique_context_name : nested object containing name:value pairs of the session attributes for that context</li>
 * </ul>
 * <p>
 * One of the name:value attribute pairs will always be the special attribute "__metadata__". The value 
 * is an object representing a version counter which is incremented every time the attributes change.
 * </p>
 * <p>
 * For example:
 * <code>
 * { "_id"       : ObjectId("52845534a40b66410f228f23"), 
 *    "accessed" :  NumberLong("1384818548903"), 
 *    "maxIdle"  : 1,
 *    "context"  : { "::/contextA" : { "A"            : "A", 
 *                                     "__metadata__" : { "version" : NumberLong(2) } 
 *                                   },
 *                   "::/contextB" : { "B"            : "B", 
 *                                     "__metadata__" : { "version" : NumberLong(1) } 
 *                                   } 
 *                 }, 
 *    "created"  : NumberLong("1384818548903"),
 *    "expiry"   : NumberLong("1384818549903"),
 *    "id"       : "w01ijx2vnalgv1sqrpjwuirprp7", 
 *    "valid"    : true 
 * }
 * </code>
 * </p>
 * <p>
 * In MongoDB, the nesting level is indicated by "." separators for the key name. Thus to
 * interact with a session attribute, the key is composed of:
 * "context".unique_context_name.attribute_name
 *  Eg  "context"."::/contextA"."A"
 *  </p>
 */
@ManagedObject("Mongo Session Manager")
public class MongoSessionManager extends NoSqlSessionManager
{
    private static final Logger LOG = Log.getLogger(MongoSessionManager.class);
  
    private final static Logger __log = Log.getLogger("org.eclipse.jetty.server.session");
   
    /*
     * strings used as keys or parts of keys in mongo
     */
    /**
     * Special attribute for a session that is context-specific
     */
    private final static String __METADATA = "__metadata__";

    
    /**
     * Session id
     */
    public final static String __ID = "id";
    
    /**
     * Time of session creation
     */
    private final static String __CREATED = "created";
    
    /**
     * Whether or not session is valid
     */
    public final static String __VALID = "valid";
    
    /**
     * Time at which session was invalidated
     */
    public final static String __INVALIDATED = "invalidated";
    
    /**
     * Last access time of session
     */
    public final static String __ACCESSED = "accessed";
    
    /**
     * Time this session will expire, based on last access time and maxIdle
     */
    public final static String __EXPIRY = "expiry";
    
    /**
     * The max idle time of a session (smallest value across all contexts which has a session with the same id)
     */
    public final static String __MAX_IDLE = "maxIdle";
    
    /**
     * Name of nested document field containing 1 sub document per context for which the session id is in use
     */
    private final static String __CONTEXT = "context";   
    
    
    /**
     * Special attribute per session per context, incremented each time attributes are modified
     */
    public final static String __VERSION = __METADATA + ".version";

    /**
    * the context id is only set when this class has been started
    */
    private String _contextId = null;

    
    /**
     * Access to MongoDB
     */
    private DBCollection _dbSessions;
    
    
    /**
     * Utility value of 1 for a session version for this context
     */
    private DBObject _version_1;


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
            hosts = new String[]
            { "::" }; // IPv6 equiv of 0.0.0.0

        String contextPath = getContext().getContextPath();
        if (contextPath == null || "".equals(contextPath))
        {
            contextPath = "*";
        }

        _contextId = createContextId(hosts,contextPath);
        _version_1 = new BasicDBObject(getContextAttributeKey(__VERSION),1);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#setSessionIdManager(org.eclipse.jetty.server.SessionIdManager)
     */
    @Override
    public void setSessionIdManager(SessionIdManager metaManager)
    {
        MongoSessionIdManager msim = (MongoSessionIdManager)metaManager;
        _dbSessions=msim.getSessions();
        super.setSessionIdManager(metaManager);
        
    }

    /* ------------------------------------------------------------ */
    @Override
    protected synchronized Object save(NoSqlSession session, Object version, boolean activateAfterSave)
    {
        try
        {
            __log.debug("MongoSessionManager:save session {}", session.getClusterId());
            session.willPassivate();

            // Form query for upsert
            BasicDBObject key = new BasicDBObject(__ID,session.getClusterId());

            // Form updates
            BasicDBObject update = new BasicDBObject();
            boolean upsert = false;
            BasicDBObject sets = new BasicDBObject();
            BasicDBObject unsets = new BasicDBObject();

            
            // handle valid or invalid
            if (session.isValid())
            {
                long expiry = (session.getMaxInactiveInterval() > 0?(session.getAccessed()+(1000*getMaxInactiveInterval())):0);
                __log.debug("MongoSessionManager: calculated expiry {} for session {}", expiry, session.getId());
                
                // handle new or existing
                if (version == null)
                {
                    // New session
                    upsert = true;
                    version = new Long(1);
                    sets.put(__CREATED,session.getCreationTime());
                    sets.put(__VALID,true);
                   
                    sets.put(getContextAttributeKey(__VERSION),version);
                    sets.put(__MAX_IDLE, getMaxInactiveInterval());
                    sets.put(__EXPIRY, expiry);
                }
                else
                {
                    version = new Long(((Number)version).longValue() + 1);
                    update.put("$inc",_version_1); 
                    //if max idle time and/or expiry is smaller for this context, then choose that for the whole session doc
                    BasicDBObject fields = new BasicDBObject();
                    fields.append(__MAX_IDLE, true);
                    fields.append(__EXPIRY, true);
                    DBObject o = _dbSessions.findOne(new BasicDBObject("id",session.getClusterId()), fields);
                    if (o != null)
                    {
                        Integer currentMaxIdle = (Integer)o.get(__MAX_IDLE);
                        Long currentExpiry = (Long)o.get(__EXPIRY);
                        if (currentMaxIdle != null && getMaxInactiveInterval() > 0 && getMaxInactiveInterval() < currentMaxIdle)
                            sets.put(__MAX_IDLE, getMaxInactiveInterval());
                        if (currentExpiry != null && expiry > 0 && expiry < currentExpiry)
                            sets.put(__EXPIRY, currentExpiry);
                    }
                }
                
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
                        sets.put(getContextKey() + "." + encodeName(name),encodeName(value));
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

            _dbSessions.update(key,update,upsert,false);
            __log.debug("MongoSessionManager:save:db.sessions.update( {}, {}, true) ", key, update);

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
        __log.debug("MongoSessionManager:refresh session {}", session.getId());

        // check if our in memory version is the same as what is on the disk
        if (version != null)
        {
            DBObject o = _dbSessions.findOne(new BasicDBObject(__ID,session.getClusterId()),_version_1);

            if (o != null)
            {
                Object saved = getNestedValue(o, getContextAttributeKey(__VERSION));
                
                if (saved != null && saved.equals(version))
                {
                    __log.debug("MongoSessionManager:refresh not needed session {}", session.getId());
                    return version;
                }
                version = saved;
            }
        }

        // If we are here, we have to load the object
        DBObject o = _dbSessions.findOne(new BasicDBObject(__ID,session.getClusterId()));

        // If it doesn't exist, invalidate
        if (o == null)
        {
            __log.debug("MongoSessionManager:refresh:marking session {} invalid, no object", session.getClusterId());
            session.invalidate();
            return null;
        }
        
        // If it has been flagged invalid, invalidate
        Boolean valid = (Boolean)o.get(__VALID);
        if (valid == null || !valid)
        {
            __log.debug("MongoSessionManager:refresh:marking session {} invalid, valid flag {}", session.getClusterId(), valid);
            session.invalidate();
            return null;
        }

        // We need to update the attributes. We will model this as a passivate,
        // followed by bindings and then activation.
        session.willPassivate();
        try
        {     
            DBObject attrs = (DBObject)getNestedValue(o,getContextKey());    
            //if disk version now has no attributes, get rid of them
            if (attrs == null || attrs.keySet().size() == 0)
            {
                session.clearAttributes();
            }
            else
            {
                //iterate over the names of the attributes on the disk version, updating the value
                for (String name : attrs.keySet())
                {
                    //skip special metadata field which is not one of the session attributes
                    if (__METADATA.equals(name))
                        continue;

                    String attr = decodeName(name);
                    Object value = decodeValue(attrs.get(name));

                    //session does not already contain this attribute, so bind it
                    if (session.getAttribute(attr) == null)
                    { 
                        session.doPutOrRemove(attr,value);
                        session.bindValue(attr,value);
                    }
                    else //session already contains this attribute, update its value
                    {
                        session.doPutOrRemove(attr,value);
                    }

                }
                // cleanup, remove values from session, that don't exist in data anymore:
                for (String str : session.getNames())
                {
                    if (!attrs.keySet().contains(str))
                    {
                        session.doPutOrRemove(str,null);
                        session.unbindValue(str,session.getAttribute(str));
                    }
                }
            }

            /*
             * We are refreshing so we should update the last accessed time.
             */
            BasicDBObject key = new BasicDBObject(__ID,session.getClusterId());
            BasicDBObject sets = new BasicDBObject();
            // Form updates
            BasicDBObject update = new BasicDBObject();
            sets.put(__ACCESSED,System.currentTimeMillis());
            // Do the upsert
            if (!sets.isEmpty())
            {
                update.put("$set",sets);
            }            
            
            _dbSessions.update(key,update,false,false);
            
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
        DBObject o = _dbSessions.findOne(new BasicDBObject(__ID,clusterId));
        
        __log.debug("MongoSessionManager:id={} loaded={}", clusterId, o);
        if (o == null)
            return null;
        
        Boolean valid = (Boolean)o.get(__VALID);
        __log.debug("MongoSessionManager:id={} valid={}", clusterId, valid);
        if (valid == null || !valid)
            return null;
        
        try
        {
            Object version = o.get(getContextAttributeKey(__VERSION));
            Long created = (Long)o.get(__CREATED);
            Long accessed = (Long)o.get(__ACCESSED);
          
            NoSqlSession session = null;

            // get the session for the context
            DBObject attrs = (DBObject)getNestedValue(o,getContextKey());

            __log.debug("MongoSessionManager:attrs {}", attrs);
            if (attrs != null)
            {
                __log.debug("MongoSessionManager: session {} present for context {}", clusterId, getContextKey());
                //only load a session if it exists for this context
                session = new NoSqlSession(this,created,accessed,clusterId,version);
                
                for (String name : attrs.keySet())
                {
                    //skip special metadata attribute which is not one of the actual session attributes
                    if ( __METADATA.equals(name) )
                        continue;
                    
                    String attr = decodeName(name);
                    Object value = decodeValue(attrs.get(name));

                    session.doPutOrRemove(attr,value);
                    session.bindValue(attr,value);
                }
                session.didActivate();
            }
            else
                __log.debug("MongoSessionManager: session  {} not present for context {}",clusterId, getContextKey());        

            return session;
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
        return null;
    }

    
    
    /*------------------------------------------------------------ */
    /** 
     * Remove the per-context sub document for this session id.
     * @see org.eclipse.jetty.nosql.NoSqlSessionManager#remove(org.eclipse.jetty.nosql.NoSqlSession)
     */
    @Override
    protected boolean remove(NoSqlSession session)
    {
        __log.debug("MongoSessionManager:remove:session {} for context {}",session.getClusterId(), getContextKey());

        /*
         * Check if the session exists and if it does remove the context
         * associated with this session
         */
        BasicDBObject key = new BasicDBObject(__ID,session.getClusterId());
        
        DBObject o = _dbSessions.findOne(key,_version_1);

        if (o != null)
        {
            BasicDBObject remove = new BasicDBObject();
            BasicDBObject unsets = new BasicDBObject();
            unsets.put(getContextKey(),1);
            remove.put("$unset",unsets);
            _dbSessions.update(key,remove);

            return true;
        }
        else
        {
            return false;
        }
    }

    
    
    /** 
     * @see org.eclipse.jetty.nosql.NoSqlSessionManager#expire(java.lang.String)
     */
    @Override
    protected void expire (String idInCluster)
    {
        __log.debug("MongoSessionManager:expire session {} ", idInCluster);

        //Expire the session for this context
        super.expire(idInCluster);
        
        //If the outer session document has not already been marked invalid, do so.
        DBObject validKey = new BasicDBObject(__VALID, true);       
        DBObject o = _dbSessions.findOne(new BasicDBObject(__ID,idInCluster), validKey);
        
        if (o != null && (Boolean)o.get(__VALID))
        {
            BasicDBObject update = new BasicDBObject();
            BasicDBObject sets = new BasicDBObject();
            sets.put(__VALID,false);
            sets.put(__INVALIDATED, System.currentTimeMillis());
            update.put("$set",sets);
                        
            BasicDBObject key = new BasicDBObject(__ID,idInCluster);
            _dbSessions.update(key,update);
        }       
    }
    
    
    /*------------------------------------------------------------ */
    /** 
     * Change the session id. Note that this will change the session id for all contexts for which the session id is in use.
     * @see org.eclipse.jetty.nosql.NoSqlSessionManager#update(org.eclipse.jetty.nosql.NoSqlSession, java.lang.String, java.lang.String)
     */
    @Override
    protected void update(NoSqlSession session, String newClusterId, String newNodeId) throws Exception
    {
        BasicDBObject key = new BasicDBObject(__ID, session.getClusterId());
        BasicDBObject sets = new BasicDBObject();
        BasicDBObject update = new BasicDBObject(__ID, newClusterId);
        sets.put("$set", update);
        _dbSessions.update(key, sets, false, false);
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
    protected Object encodeName(Object value) throws IOException
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
                o.append(encodeName(entry.getKey().toString()),encodeName(entry.getValue()));
            }

            if (o != null)
                return o;
        }
        
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bout);
        out.reset();
        out.writeUnshared(value);
        out.flush();
        return bout.toByteArray();
    }

    /*------------------------------------------------------------ */
    protected Object decodeValue(final Object valueToDecode) throws IOException, ClassNotFoundException
    {
        if (valueToDecode == null || valueToDecode instanceof Number || valueToDecode instanceof String || valueToDecode instanceof Boolean || valueToDecode instanceof Date)
        {
            return valueToDecode;
        }
        else if (valueToDecode instanceof byte[])
        {
            final byte[] decodeObject = (byte[])valueToDecode;
            final ByteArrayInputStream bais = new ByteArrayInputStream(decodeObject);
            final ClassLoadingObjectInputStream objectInputStream = new ClassLoadingObjectInputStream(bais);
            return objectInputStream.readUnshared();
        }
        else if (valueToDecode instanceof DBObject)
        {
            Map<String, Object> map = new HashMap<String, Object>();
            for (String name : ((DBObject)valueToDecode).keySet())
            {
                String attr = decodeName(name);
                map.put(attr,decodeValue(((DBObject)valueToDecode).get(name)));
            }
            return map;
        }
        else
        {
            throw new IllegalStateException(valueToDecode.getClass().toString());
        }
    }

   
    /*------------------------------------------------------------ */
    private String getContextKey()
    {
    	return __CONTEXT + "." + _contextId;
    }
    
    /*------------------------------------------------------------ */
    /** Get a dot separated key for 
     * @param key
     * @return
     */
    private String getContextAttributeKey(String attr)
    {
    	return getContextKey()+ "." + attr;
    }
    
    @ManagedOperation(value="purge invalid sessions in the session store based on normal criteria", impact="ACTION")
    public void purge()
    {   
        ((MongoSessionIdManager)_sessionIdManager).purge();
    }
    
    
    @ManagedOperation(value="full purge of invalid sessions in the session store", impact="ACTION")
    public void purgeFully()
    {   
        ((MongoSessionIdManager)_sessionIdManager).purgeFully();
    }
    
    @ManagedOperation(value="scavenge sessions known to this manager", impact="ACTION")
    public void scavenge()
    {
        ((MongoSessionIdManager)_sessionIdManager).scavenge();
    }
    
    @ManagedOperation(value="scanvenge all sessions", impact="ACTION")
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
    @ManagedAttribute("total number of known sessions in the store")
    public long getSessionStoreCount()
    {
        return _dbSessions.find().count();      
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

    
     /**
     * ClassLoadingObjectInputStream
     *
     *
     */
    protected class ClassLoadingObjectInputStream extends ObjectInputStream
    {
        public ClassLoadingObjectInputStream(java.io.InputStream in) throws IOException
        {
            super(in);
        }

        public ClassLoadingObjectInputStream () throws IOException
        {
            super();
        }

        @Override
        public Class<?> resolveClass (java.io.ObjectStreamClass cl) throws IOException, ClassNotFoundException
        {
            try
            {
                return Class.forName(cl.getName(), false, Thread.currentThread().getContextClassLoader());
            }
            catch (ClassNotFoundException e)
            {
                return super.resolveClass(cl);
            }
        }
    }


}
