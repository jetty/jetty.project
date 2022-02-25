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

package org.eclipse.jetty.nosql.mongodb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import com.mongodb.WriteResult;
import org.eclipse.jetty.nosql.NoSqlSessionDataStore;
import org.eclipse.jetty.server.session.SessionContext;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.UnreadableSessionDataException;
import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoSessionDataStore
 *
 * The document model is an outer object that contains the elements:
 * <ul>
 * <li>"id"      : session_id </li>
 * <li>"created" : create_time </li>
 * <li>"accessed": last_access_time </li>
 * <li>"maxIdle" : max_idle_time setting as session was created </li>
 * <li>"expiry"  : time at which session should expire </li>
 * <li>"valid"   : session_valid </li>
 * <li>"context" : a nested object containing 1 nested object per context for which the session id is in use
 * </ul>
 * Each of the nested objects inside the "context" element contains:
 * <ul>
 * <li>unique_context_name : nested object containing name:value pairs of the session attributes for that context</li>
 * <li>unique_context_name: vhost:contextpath, where no vhosts="0_0_0_0", root context = "", contextpath "/" replaced by "_"
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
 * interact with session fields, the key is composed of:
 * <code>"context".unique_context_name.field_name</code>
 * Eg  <code>"context"."0_0_0_0:_testA"."lastSaved"</code>
 */
@ManagedObject
public class MongoSessionDataStore extends NoSqlSessionDataStore
{

    private static final Logger LOG = LoggerFactory.getLogger(MongoSessionDataStore.class);

    /**
     * Special attribute for a session that is context-specific
     */
    public static final String __METADATA = "__metadata__";

    /**
     * Name of nested document field containing 1 sub document per context for which the session id is in use
     */
    public static final String __CONTEXT = "context";

    /**
     * Special attribute per session per context, incremented each time attributes are modified
     */
    public static final String __VERSION = __METADATA + ".version";

    public static final String __LASTSAVED = __METADATA + ".lastSaved";

    public static final String __LASTNODE = __METADATA + ".lastNode";

    /**
     * Last access time of session
     */
    public static final String __ACCESSED = "accessed";

    public static final String __LAST_ACCESSED = "lastAccessed";

    public static final String __ATTRIBUTES = "attributes";

    /**
     * Time this session will expire, based on last access time and maxIdle
     */
    public static final String __EXPIRY = "expiry";

    /**
     * The max idle time of a session (smallest value across all contexts which has a session with the same id)
     */
    public static final String __MAX_IDLE = "maxIdle";

    /**
     * Time of session creation
     */
    public static final String __CREATED = "created";

    /**
     * Whether or not session is valid
     */
    public static final String __VALID = "valid";

    /**
     * Session id
     */
    public static final String __ID = "id";

    /**
     * Utility value of 1 for a session version for this context
     */
    private DBObject _version1;

    /**
     * Access to MongoDB
     */
    private DBCollection _dbSessions;

    public void setDBCollection(DBCollection collection)
    {
        _dbSessions = collection;
    }

    @ManagedAttribute(value = "DBCollection", readonly = true)
    public DBCollection getDBCollection()
    {
        return _dbSessions;
    }

    @Override
    public SessionData doLoad(String id) throws Exception
    {
        DBObject sessionDocument = _dbSessions.findOne(new BasicDBObject(__ID, id));

        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("id={} loaded={}", id, sessionDocument);

            if (sessionDocument == null)
                return null;

            Boolean valid = (Boolean)sessionDocument.get(__VALID);

            if (LOG.isDebugEnabled())
                LOG.debug("id={} valid={}", id, valid);
            if (valid == null || !valid)
                return null;

            Object version = MongoUtils.getNestedValue(sessionDocument, getContextSubfield(__VERSION));
            Long lastSaved = (Long)MongoUtils.getNestedValue(sessionDocument, getContextSubfield(__LASTSAVED));
            String lastNode = (String)MongoUtils.getNestedValue(sessionDocument, getContextSubfield(__LASTNODE));
            byte[] attributes = (byte[])MongoUtils.getNestedValue(sessionDocument, getContextSubfield(__ATTRIBUTES));

            Long created = (Long)sessionDocument.get(__CREATED);
            Long accessed = (Long)sessionDocument.get(__ACCESSED);
            Long lastAccessed = (Long)sessionDocument.get(__LAST_ACCESSED);
            Long maxInactive = (Long)sessionDocument.get(__MAX_IDLE);
            Long expiry = (Long)sessionDocument.get(__EXPIRY);

            NoSqlSessionData data = null;

            // get the session for the context
            DBObject sessionSubDocumentForContext = (DBObject)MongoUtils.getNestedValue(sessionDocument, getContextField());

            if (LOG.isDebugEnabled())
                LOG.debug("attrs {}", sessionSubDocumentForContext);

            if (sessionSubDocumentForContext != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Session {} present for context {}", id, _context);

                //only load a session if it exists for this context
                data = (NoSqlSessionData)newSessionData(id, created, accessed, (lastAccessed == null ? accessed : lastAccessed), maxInactive);
                data.setVersion(version);
                data.setExpiry(expiry);
                data.setContextPath(_context.getCanonicalContextPath());
                data.setVhost(_context.getVhost());
                data.setLastSaved(lastSaved);
                data.setLastNode(lastNode);

                if (attributes == null)
                {
                    //legacy attribute storage format: the attributes are all fields in the document
                    Map<String, Object> map = new HashMap<>();
                    for (String name : sessionSubDocumentForContext.keySet())
                    {
                        //skip special metadata attribute which is not one of the actual session attributes
                        if (__METADATA.equals(name))
                            continue;
                        String attr = MongoUtils.decodeName(name);
                        Object value = MongoUtils.decodeValue(sessionSubDocumentForContext.get(name));
                        map.put(attr, value);
                    }
                    data.putAllAttributes(map);
                }
                else
                {
                    //attributes have special serialized format
                    try (ByteArrayInputStream bais = new ByteArrayInputStream(attributes);
                         ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(bais);)
                    {
                        SessionData.deserializeAttributes(data, ois);
                    }
                }
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Session  {} not present for context {}", id, _context);
            }

            return data;
        }
        catch (Exception e)
        {
            throw (new UnreadableSessionDataException(id, _context, e));
        }
    }

    @Override
    public boolean delete(String id) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Remove:session {} for context {}", id, _context);

        /*
         * Check if the session exists and if it does remove the context
         * associated with this session
         */
        BasicDBObject mongoKey = new BasicDBObject(__ID, id);

        DBObject sessionDocument = _dbSessions.findOne(new BasicDBObject(__ID, id));

        if (sessionDocument != null)
        {
            DBObject c = (DBObject)MongoUtils.getNestedValue(sessionDocument, __CONTEXT);
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
            unsets.put(getContextField(), 1);
            remove.put("$unset", unsets);
            _dbSessions.update(mongoKey, remove, false, false, WriteConcern.SAFE);
            return true;
        }
        else
        {
            return false;
        }
    }

    @Override
    public boolean doExists(String id) throws Exception
    {
        DBObject fields = new BasicDBObject();
        fields.put(__EXPIRY, 1);
        fields.put(__VALID, 1);
        fields.put(getContextSubfield(__VERSION), 1);

        DBObject sessionDocument = _dbSessions.findOne(new BasicDBObject(__ID, id), fields);

        if (sessionDocument == null)
            return false; //doesn't exist

        Boolean valid = (Boolean)sessionDocument.get(__VALID);
        if (!valid)
            return false; //invalid - nb should not happen

        Long expiry = (Long)sessionDocument.get(__EXPIRY);

        //expired?
        if (expiry.longValue() > 0 && expiry.longValue() < System.currentTimeMillis())
            return false; //it's expired

        //does it exist for this context?
        Object version = MongoUtils.getNestedValue(sessionDocument, getContextSubfield(__VERSION));
        if (version == null)
            return false;

        return true;
    }

    @Override
    public Set<String> doCheckExpired(Set<String> candidates, long time)
    {
        Set<String> expiredSessions = new HashSet<>();

        //firstly ask mongo to verify if these candidate ids have expired - all of
        //these candidates will be for our node
        BasicDBObject query = new BasicDBObject();
        query.append(__ID, new BasicDBObject("$in", candidates));
        query.append(__EXPIRY, new BasicDBObject("$gt", 0).append("$lte", time));
        
        DBCursor verifiedExpiredSessions = null;
        try
        {
            verifiedExpiredSessions = _dbSessions.find(query, new BasicDBObject(__ID, 1));
            for (DBObject session : verifiedExpiredSessions)
            {
                String id = (String)session.get(__ID);
                if (LOG.isDebugEnabled())
                    LOG.debug("{} Mongo confirmed expired session {}", _context, id);
                expiredSessions.add(id);
            }
        }
        finally
        {
            if (verifiedExpiredSessions != null)
                verifiedExpiredSessions.close();
        }
        

        //check through sessions that were candidates, but not found as expired. 
        //they may no longer be persisted, in which case they are treated as expired.
        for (String c:candidates)
        {
            if (!expiredSessions.contains(c))
            {
                try
                {
                    if (!exists(c))
                        expiredSessions.add(c);
                }
                catch (Exception e)
                {
                    LOG.warn("Problem checking potentially expired session {}", c, e);
                }
            }
        }
        return expiredSessions;
    }

    @Override
    public Set<String> doGetExpired(long timeLimit)
    {
        // now ask mongo to find sessions for this context, last managed by any
        // node, that expired before timeLimit
        Set<String> expiredSessions = new HashSet<>();

        BasicDBObject query = new BasicDBObject();
        BasicDBObject gt = new BasicDBObject(__EXPIRY, new BasicDBObject("$gt", 0));
        BasicDBObject lt = new BasicDBObject(__EXPIRY, new BasicDBObject("$lte", timeLimit));
        BasicDBList list = new BasicDBList();
        list.add(gt);
        list.add(lt);
        query.append("$and", list);

        DBCursor oldExpiredSessions = null;
        try
        {
            BasicDBObject bo = new BasicDBObject(__ID, 1);
            bo.append(__EXPIRY, 1);

            oldExpiredSessions = _dbSessions.find(query, bo);
            for (DBObject session : oldExpiredSessions)
            {
                String id = (String)session.get(__ID);
                
                //TODO we should verify if there is a session for my context, not any context
                expiredSessions.add(id);
            }
        }
        finally
        {
            if (oldExpiredSessions != null)
                oldExpiredSessions.close();
        }

        return expiredSessions;
    }

    @Override
    public void doCleanOrphans(long timeLimit)
    {
        //Delete all session documents where the expiry time (which is always the most
        //up-to-date expiry of all contexts sharing that session id) has already past as
        //at the timeLimit.
        BasicDBObject query = new BasicDBObject();
        query.append(__EXPIRY, new BasicDBObject("$gt", 0).append("$lte", timeLimit));
        _dbSessions.remove(query, WriteConcern.SAFE);
    }

    /**
     * @see org.eclipse.jetty.server.session.SessionDataStore#initialize(org.eclipse.jetty.server.session.SessionContext)
     */
    public void initialize(SessionContext context) throws Exception
    {
        if (isStarted())
            throw new IllegalStateException("Context set after SessionDataStore started");
        _context = context;
        ensureIndexes();
    }

    @Override
    public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
    {
        // Form query for upsert
        final BasicDBObject key = new BasicDBObject(__ID, id);

        // Form updates
        BasicDBObject update = new BasicDBObject();
        boolean upsert = false;
        BasicDBObject sets = new BasicDBObject();

        Object version = ((NoSqlSessionData)data).getVersion();

        // New session
        if (lastSaveTime <= 0)
        {
            upsert = true;
            version = 1L;
            sets.put(__CREATED, data.getCreated());
            sets.put(__VALID, true);
            sets.put(getContextSubfield(__VERSION), version);
            sets.put(getContextSubfield(__LASTSAVED), data.getLastSaved());
            sets.put(getContextSubfield(__LASTNODE), data.getLastNode());
            sets.put(__MAX_IDLE, data.getMaxInactiveMs());
            sets.put(__EXPIRY, data.getExpiry());
            ((NoSqlSessionData)data).setVersion(version);
        }
        else
        {
            sets.put(getContextSubfield(__LASTSAVED), data.getLastSaved());
            sets.put(getContextSubfield(__LASTNODE), data.getLastNode());
            version = ((Number)version).longValue() + 1L;
            ((NoSqlSessionData)data).setVersion(version);
            update.put("$inc", _version1);
            //if max idle time and/or expiry is smaller for this context, then choose that for the whole session doc
            BasicDBObject fields = new BasicDBObject();
            fields.append(__MAX_IDLE, true);
            fields.append(__EXPIRY, true);
            DBObject o = _dbSessions.findOne(new BasicDBObject("id", id), fields);
            if (o != null)
            {
                Long tmpLong = (Long)o.get(__MAX_IDLE);
                long currentMaxIdle = (tmpLong == null ? 0 : tmpLong.longValue());
                tmpLong = (Long)o.get(__EXPIRY);
                long currentExpiry = (tmpLong == null ? 0 : tmpLong.longValue());

                if (currentMaxIdle != data.getMaxInactiveMs())
                    sets.put(__MAX_IDLE, data.getMaxInactiveMs());

                if (currentExpiry != data.getExpiry())
                    sets.put(__EXPIRY, data.getExpiry());
            }
            else
                LOG.warn("Session {} not found, can't update", id);
        }

        sets.put(__ACCESSED, data.getAccessed());
        sets.put(__LAST_ACCESSED, data.getLastAccessed());

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos);)
        {
            SessionData.serializeAttributes(data, oos);
            sets.put(getContextSubfield(__ATTRIBUTES), baos.toByteArray());
        }

        // Do the upsert
        if (!sets.isEmpty())
            update.put("$set", sets);

        WriteResult res = _dbSessions.update(key, update, upsert, false, WriteConcern.SAFE);
        if (LOG.isDebugEnabled())
            LOG.debug("Save:db.sessions.update( {}, {},{} )", key, update, res);
    }

    protected void ensureIndexes() throws MongoException
    {
        _version1 = new BasicDBObject(getContextSubfield(__VERSION), 1);
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
        if (LOG.isDebugEnabled())
            LOG.debug("Done ensure Mongodb indexes existing");
        //TODO perhaps index on expiry time?
    }

    private String getContextField()
    {
        return __CONTEXT + "." + getCanonicalContextId();
    }

    private String getCanonicalContextId()
    {
        return canonicalizeVHost(_context.getVhost()) + ":" + _context.getCanonicalContextPath();
    }

    private String canonicalizeVHost(String vhost)
    {
        if (vhost == null)
            return "";

        return StringUtil.replace(vhost, '.', '_');
    }

    private String getContextSubfield(String attr)
    {
        return getContextField() + "." + attr;
    }

    @ManagedAttribute(value = "does store serialize sessions", readonly = true)
    @Override
    public boolean isPassivating()
    {
        return true;
    }

    @Override
    public String toString()
    {
        return String.format("%s[collection=%s]", super.toString(), getDBCollection());
    }
}
