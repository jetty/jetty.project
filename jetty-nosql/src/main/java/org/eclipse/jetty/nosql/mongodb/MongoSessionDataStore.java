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


package org.eclipse.jetty.nosql.mongodb;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import com.mongodb.WriteResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.nosql.NoSqlSessionDataStore;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * MongoSessionDataStore
 *
 *  The document model is an outer object that contains the elements:
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
 *  <li>unique_context_name: vhost:contextpath, where no vhosts="0_0_0_0", root context = "", contextpath "/" replaced by "_"
 * </ul>
 * <p>
 * One of the name:value attribute pairs will always be the special attribute "__metadata__". The value 
 * is an object representing a version counter which is incremented every time the attributes change.
 * </p>
 * <p>
 * For example:
 * <pre>
 * { "_id"       : ObjectId("52845534a40b66410f228f23"), 
 *    "accessed" :  NumberLong("1384818548903"), 
 *    "maxIdle"  : 1,
 *    "context"  : { "0_0_0_0:_testA" : { "A"            : "A", 
 *                                     "__metadata__" : { "version" : NumberLong(2) } 
 *                                   },
 *                   "0_0_0_0:_testB" : { "B"            : "B", 
 *                                     "__metadata__" : { "version" : NumberLong(1) } 
 *                                   } 
 *                 }, 
 *    "created"  : NumberLong("1384818548903"),
 *    "expiry"   : NumberLong("1384818549903"),
 *    "id"       : "w01ijx2vnalgv1sqrpjwuirprp7", 
 *    "valid"    : true 
 * }
 * </pre>
 * <p>
 * In MongoDB, the nesting level is indicated by "." separators for the key name. Thus to
 * interact with a session attribute, the key is composed of:
 * <code>"context".unique_context_name.attribute_name</code>
 *  Eg  <code>"context"."0_0_0_0:_testA"."A"</code>
 *  
 * 
 */
public class MongoSessionDataStore extends NoSqlSessionDataStore
{
    
    private final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    
    /**
     * Special attribute for a session that is context-specific
     */
    private final static String __METADATA = "__metadata__";

    /**
     * Name of nested document field containing 1 sub document per context for which the session id is in use
     */
    private final static String __CONTEXT = "context";   
    
    /**
     * Special attribute per session per context, incremented each time attributes are modified
     */
    public final static String __VERSION = __METADATA + ".version";
    
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
     * Time of session creation
     */
    private final static String __CREATED = "created";
    
    /**
     * Whether or not session is valid
     */
    public final static String __VALID = "valid";
    
    /**
     * Session id
     */
    public final static String __ID = "id";
    
    
    
    /**
     * Utility value of 1 for a session version for this context
     */
    private DBObject _version_1;
    
    /**
     * Access to MongoDB
     */
    private DBCollection _dbSessions;
    
    
    public void setDBCollection (DBCollection collection)
    {
        _dbSessions = collection;
    }
    
    
    public DBCollection getDBCollection ()
    {
        return _dbSessions;
    }
    
  
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#load(String)
     */
    @Override
    public SessionData load(String id) throws Exception
    {
        final AtomicReference<SessionData> reference = new AtomicReference<SessionData>();
        final AtomicReference<Exception> exception = new AtomicReference<Exception>();
        Runnable r = new Runnable()
        {
            public void run ()
            {
                try
                {
                    DBObject sessionDocument = _dbSessions.findOne(new BasicDBObject(__ID, id));

                    if (LOG.isDebugEnabled())
                        LOG.debug("id={} loaded={}", id, sessionDocument);
                    
                    if (sessionDocument == null)
                        return;

                    Boolean valid = (Boolean)sessionDocument.get(__VALID);   

                    if (LOG.isDebugEnabled())
                        LOG.debug("id={} valid={}", id, valid);
                    if (valid == null || !valid)
                        return;


                    Object version = getNestedValue(sessionDocument, getContextSubfield(__VERSION));

                    Long created = (Long)sessionDocument.get(__CREATED);
                    Long accessed = (Long)sessionDocument.get(__ACCESSED);
                    Long maxInactive = (Long)sessionDocument.get(__MAX_IDLE);
                    Long expiry = (Long)sessionDocument.get(__EXPIRY);

                    NoSqlSessionData data = null;

                    // get the session for the context
                    DBObject sessionSubDocumentForContext = (DBObject)getNestedValue(sessionDocument,getContextField());

                    if (LOG.isDebugEnabled()) LOG.debug("attrs {}", sessionSubDocumentForContext);

                    if (sessionSubDocumentForContext != null)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Session {} present for context {}", id, _context);

                        //only load a session if it exists for this context
                        data = (NoSqlSessionData)newSessionData(id, created, accessed, accessed, maxInactive);
                        data.setVersion(version);
                        data.setExpiry(expiry);
                        data.setContextPath(_context.getCanonicalContextPath());
                        data.setVhost(_context.getVhost());

                        HashMap<String, Object> attributes = new HashMap<>();
                        for (String name : sessionSubDocumentForContext.keySet())
                        {
                            //skip special metadata attribute which is not one of the actual session attributes
                            if ( __METADATA.equals(name) )
                                continue;         
                            String attr = decodeName(name);
                            Object value = decodeValue(sessionSubDocumentForContext.get(name));
                            attributes.put(attr,value);
                        }

                        data.putAllAttributes(attributes);
                    }
                    else
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Session  {} not present for context {}", id, _context);        
                    }

                    reference.set(data);
                }
                catch (Exception e)
                {
                    exception.set(e);
                }
            }
        };
        
        _context.run(r);
        
        if (exception.get() != null)
            throw exception.get();
        
        return reference.get();
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#delete(String)
     */
    @Override
    public boolean delete(String id) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Remove:session {} for context ",id, _context);

        /*
         * Check if the session exists and if it does remove the context
         * associated with this session
         */
        BasicDBObject mongoKey = new BasicDBObject(__ID, id);
        
        //DBObject sessionDocument = _dbSessions.findOne(mongoKey,_version_1);
        DBObject sessionDocument = _dbSessions.findOne(new BasicDBObject(__ID, id));

        if (sessionDocument != null)
        {
            DBObject c = (DBObject)getNestedValue(sessionDocument, __CONTEXT);
            if (c == null)
            {
                //delete whole doc
                _dbSessions.remove(mongoKey, WriteConcern.SAFE);
                return false;
            }

            Set<String> contexts = c.keySet();
            if (contexts.isEmpty())
            {
                //delete whole doc
                _dbSessions.remove(mongoKey, WriteConcern.SAFE);
                return false;
            }

            if (contexts.size() == 1 && contexts.iterator().next().equals(getCanonicalContextId()))
            {
                //delete whole doc
                _dbSessions.remove(new BasicDBObject(__ID, id), WriteConcern.SAFE);
                return true;
            }
            
            //just remove entry for my context
            BasicDBObject remove = new BasicDBObject();
            BasicDBObject unsets = new BasicDBObject();
            unsets.put(getContextField(),1);
            remove.put("$unset",unsets);
            WriteResult result = _dbSessions.update(mongoKey,remove,false,false,WriteConcern.SAFE);            
            return true;
        }
        else
        {
            return false;
        }
        
    }
    
    

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#exists(java.lang.String)
     */
    @Override
    public boolean exists(String id) throws Exception
    {
        DBObject fields = new BasicDBObject();
        fields.put(__EXPIRY, 1);
        fields.put(__VALID, 1);
        
        DBObject sessionDocument = _dbSessions.findOne(new BasicDBObject(__ID, id), fields);
        
        if (sessionDocument == null)
            return false; //doesn't exist

        Boolean valid = (Boolean)sessionDocument.get(__VALID);
        if (!valid)
            return false; //invalid - nb should not happen
        
        Long expiry = (Long)sessionDocument.get(__EXPIRY);
        
        if (expiry.longValue() <= 0)
            return true; //never expires, its good
        return (expiry.longValue() > System.currentTimeMillis()); //expires later
    }


    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#getExpired(Set)
     */
    @Override
    public Set<String> doGetExpired(Set<String> candidates)
    {
        long now = System.currentTimeMillis();
        long upperBound = now;
        Set<String> expiredSessions = new HashSet<>();
        
        //firstly ask mongo to verify if these candidate ids have expired - all of
        //these candidates will be for our node
        BasicDBObject query = new BasicDBObject();     
        query.append(__ID,new BasicDBObject("$in", candidates));
        query.append(__EXPIRY, new BasicDBObject("$gt", 0));
        query.append(__EXPIRY, new BasicDBObject("$lt", upperBound));   

        DBCursor verifiedExpiredSessions = null;
        try  
        {
            verifiedExpiredSessions = _dbSessions.find(query, new BasicDBObject(__ID, 1));
            for ( DBObject session : verifiedExpiredSessions )  
            {
                String id = (String)session.get(__ID);
                if (LOG.isDebugEnabled()) LOG.debug("{} Mongo confirmed expired session {}", _context,id);
                expiredSessions.add(id);
            }            
        }
        finally
        {
            if (verifiedExpiredSessions != null) verifiedExpiredSessions.close();
        }

        //now ask mongo to find sessions last managed by any nodes that expired a while ago 
        //if this is our first expiry check, make sure that we only grab really old sessions
        if (_lastExpiryCheckTime <= 0)
            upperBound = (now - (3*(1000L * _gracePeriodSec)));
        else
            upperBound =  _lastExpiryCheckTime - (1000L * _gracePeriodSec);
        
        query = new BasicDBObject();
        BasicDBObject gt = new BasicDBObject(__EXPIRY, new BasicDBObject("$gt", 0));
        BasicDBObject lt = new BasicDBObject (__EXPIRY, new BasicDBObject("$lt", upperBound));
        BasicDBList list = new BasicDBList();
        list.add(gt);
        list.add(lt);
        query.append("and", list);

        DBCursor oldExpiredSessions = null;
        try
        {
            BasicDBObject bo = new BasicDBObject(__ID, 1);
            bo.append(__EXPIRY, 1);
            
            oldExpiredSessions = _dbSessions.find(query, bo);
            for (DBObject session : oldExpiredSessions)
            {
                String id = (String)session.get(__ID);
                if (LOG.isDebugEnabled()) LOG.debug("{} Mongo found old expired session {}", _context, id+" exp="+session.get(__EXPIRY));
                expiredSessions.add(id);
            }

        }
        finally
        {
            oldExpiredSessions.close();
        }
        
        return expiredSessions;
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doStore(String, SessionData, long) 
     */
    @Override
    public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
    {        
        NoSqlSessionData nsqd = (NoSqlSessionData)data;
        
        // Form query for upsert
        BasicDBObject key = new BasicDBObject(__ID, id);

        // Form updates
        BasicDBObject update = new BasicDBObject();
        boolean upsert = false;
        BasicDBObject sets = new BasicDBObject();
        BasicDBObject unsets = new BasicDBObject();
        
        Object version = ((NoSqlSessionData)data).getVersion();
        
        // New session
        if (lastSaveTime <= 0)
        {
            upsert = true;
            version = new Long(1);
            sets.put(__CREATED,nsqd.getCreated());
            sets.put(__VALID,true);
            sets.put(getContextSubfield(__VERSION),version);
            sets.put(__MAX_IDLE, nsqd.getMaxInactiveMs());
            sets.put(__EXPIRY, nsqd.getExpiry());
            nsqd.setVersion(version);
        }
        else
        {
            version = new Long(((Number)version).longValue() + 1);
            nsqd.setVersion(version);
            update.put("$inc",_version_1); 
            //if max idle time and/or expiry is smaller for this context, then choose that for the whole session doc
            BasicDBObject fields = new BasicDBObject();
            fields.append(__MAX_IDLE, true);
            fields.append(__EXPIRY, true);
            DBObject o = _dbSessions.findOne(new BasicDBObject("id", id), fields);
            if (o != null)
            {
                Long tmpLong = (Long)o.get(__MAX_IDLE);
                long currentMaxIdle = (tmpLong == null? 0:tmpLong.longValue());
                tmpLong = (Long)o.get(__EXPIRY);
                long currentExpiry = (tmpLong == null? 0 : tmpLong.longValue()); 

                if (currentMaxIdle != nsqd.getMaxInactiveMs())
                    sets.put(__MAX_IDLE, nsqd.getMaxInactiveMs());

                if (currentExpiry != nsqd.getExpiry())
                    sets.put(__EXPIRY, nsqd.getExpiry());
            }
            else
                LOG.warn("Session {} not found, can't update", id);
        }

        sets.put(__ACCESSED, nsqd.getAccessed());

        Set<String> names = nsqd.takeDirtyAttributes();

        if (lastSaveTime <= 0)
        {         
            names.addAll(nsqd.getAllAttributeNames()); // note dirty may include removed names
        }


        for (String name : names)
        {
            Object value = data.getAttribute(name);
            if (value == null)
                unsets.put(getContextField() + "." + encodeName(name),1);
            else
                sets.put(getContextField() + "." + encodeName(name),encodeName(value));
        }

        // Do the upsert
        if (!sets.isEmpty())
            update.put("$set",sets);
        if (!unsets.isEmpty())
            update.put("$unset",unsets);
        WriteResult res = _dbSessions.update(key,update,upsert,false,WriteConcern.SAFE);
        if (LOG.isDebugEnabled())
            LOG.debug("Save:db.sessions.update( {}, {},{} )", key, update, res); 
    }


    
    
    @Override
    protected void doStart() throws Exception
    {
        if (_dbSessions == null)
            throw new IllegalStateException("DBCollection not set");
        
        _version_1 = new BasicDBObject(getContextSubfield(__VERSION),1);
        
        ensureIndexes();
        
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
    }
    
    
    protected void ensureIndexes() throws MongoException
    {
        DBObject idKey = BasicDBObjectBuilder.start().add("id", 1).get();        
        _dbSessions.createIndex(idKey,
                              BasicDBObjectBuilder.start()
                              .add("name", "id_1")
                              .add("ns", _dbSessions.getFullName())
                              .add("sparse", false)
                              .add("unique", true)
                              .get());

        DBObject versionKey = BasicDBObjectBuilder.start().add("id", 1).add("version", 1).get();       
        _dbSessions.createIndex(versionKey, BasicDBObjectBuilder.start()
                              .add("name", "id_1_version_1")
                              .add("ns", _dbSessions.getFullName())
                              .add("sparse", false)
                              .add("unique", true)
                              .get());
        //TODO perhaps index on expiry time?
    }

    /*------------------------------------------------------------ */
    private String getContextField ()
    {
        return __CONTEXT + "." + getCanonicalContextId();
    }
    
   
    private String getCanonicalContextId ()
    {
        return canonicalizeVHost(_context.getVhost()) + ":" + _context.getCanonicalContextPath();
    }
    
    private String canonicalizeVHost (String vhost)
    {
        if (vhost == null)
            return "";
        
        return vhost.replace('.', '_');
    }
  
    
    private String getContextSubfield (String attr)
    {
        return getContextField () +"."+ attr;
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
    protected String decodeName(String name)
    {
        return name.replace("%2E",".").replace("%25","%");
    }
    

    /*------------------------------------------------------------ */
    protected String encodeName(String name)
    {
        return name.replace("%","%25").replace(".","%2E");
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
     * @see org.eclipse.jetty.server.session.SessionDataStore#isPassivating()
     */
    @Override
    public boolean isPassivating()
    {
        return true;
    }


    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#toString()
     */
    @Override
    public String toString()
    {
        return String.format("%s[collection=%s]", super.toString(),getDBCollection());
    }

    
}
