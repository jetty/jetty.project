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

package org.eclipse.jetty.gcloud.session;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.cloud.datastore.Blob;
import com.google.cloud.datastore.BlobValue;
import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.DatastoreException;
import com.google.cloud.datastore.DatastoreOptions;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.Key;
import com.google.cloud.datastore.KeyFactory;
import com.google.cloud.datastore.ProjectionEntity;
import com.google.cloud.datastore.Query;
import com.google.cloud.datastore.QueryResults;
import com.google.cloud.datastore.StructuredQuery.CompositeFilter;
import com.google.cloud.datastore.StructuredQuery.PropertyFilter;
import org.eclipse.jetty.server.session.AbstractSessionDataStore;
import org.eclipse.jetty.server.session.SessionContext;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.UnreadableSessionDataException;
import org.eclipse.jetty.server.session.UnwriteableSessionDataException;
import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GCloudSessionDataStore
 */
@ManagedObject
public class GCloudSessionDataStore extends AbstractSessionDataStore
{
    private static final Logger LOG = LoggerFactory.getLogger(GCloudSessionDataStore.class);

    public static final int DEFAULT_MAX_QUERY_RESULTS = 100;
    public static final int DEFAULT_MAX_RETRIES = 5;
    public static final int DEFAULT_BACKOFF_MS = 1000;
    public static final String DEFAULT_NAMESPACE = "";

    protected Datastore _datastore;
    protected KeyFactory _keyFactory;
    protected int _maxResults = DEFAULT_MAX_QUERY_RESULTS;
    protected int _maxRetries = DEFAULT_MAX_RETRIES;
    protected int _backoff = DEFAULT_BACKOFF_MS;
    protected boolean _dsProvided = false;
    protected boolean _indexesPresent = false;
    protected EntityDataModel _model;
    protected boolean _modelProvided;
    private String _namespace = DEFAULT_NAMESPACE;
    private String _host;
    private String _projectId;

    /**
     * EntityDataModel
     *
     * Names of type of Entity and Entity properties for sessions.
     */
    public static class EntityDataModel
    {
        public static final String ID = "id";
        public static final String CONTEXTPATH = "contextPath";
        public static final String VHOST = "vhost";
        public static final String ACCESSED = "accessed";
        public static final String LASTACCESSED = "lastAccessed";
        public static final String CREATETIME = "createTime";
        public static final String COOKIESETTIME = "cookieSetTime";
        public static final String LASTNODE = "lastNode";
        public static final String EXPIRY = "expiry";
        public static final String MAXINACTIVE = "maxInactive";
        public static final String ATTRIBUTES = "attributes";
        public static final String LASTSAVED = "lastSaved";
        public static final String KIND = "GCloudSession";
        protected String _kind = KIND;
        protected String _id = ID;
        protected String _contextPath = CONTEXTPATH;
        protected String _vhost = VHOST;
        protected String _accessed = ACCESSED;
        protected String _lastAccessed = LASTACCESSED;
        protected String _lastNode = LASTNODE;
        protected String _lastSaved = LASTSAVED;
        protected String _createTime = CREATETIME;
        protected String _cookieSetTime = COOKIESETTIME;
        protected String _expiry = EXPIRY;
        protected String _maxInactive = MAXINACTIVE;
        protected String _attributes = ATTRIBUTES;

        private void checkNotNull(String s)
        {
            if (s == null)
                throw new IllegalArgumentException(s);
        }

        /**
         * @return the lastNode
         */
        public String getLastNode()
        {
            return _lastNode;
        }

        /**
         * @param lastNode the lastNode to set
         */
        public void setLastNode(String lastNode)
        {
            _lastNode = lastNode;
        }

        /**
         * @return the kind
         */
        public String getKind()
        {
            return _kind;
        }

        /**
         * @param kind the kind to set
         */
        public void setKind(String kind)
        {
            checkNotNull(kind);
            _kind = kind;
        }

        /**
         * @return the id
         */
        public String getId()
        {
            return _id;
        }

        /**
         * @param id the id to set
         */
        public void setId(String id)
        {
            checkNotNull(id);
            _id = id;
        }

        /**
         * @return the contextPath
         */
        public String getContextPath()
        {
            return _contextPath;
        }

        /**
         * @param contextPath the contextPath to set
         */
        public void setContextPath(String contextPath)
        {
            checkNotNull(contextPath);
            _contextPath = contextPath;
        }

        /**
         * @return the vhost
         */
        public String getVhost()
        {
            return _vhost;
        }

        /**
         * @param vhost the vhost to set
         */
        public void setVhost(String vhost)
        {
            checkNotNull(vhost);
            _vhost = vhost;
        }

        /**
         * @return the accessed
         */
        public String getAccessed()
        {
            return _accessed;
        }

        /**
         * @param accessed the accessed to set
         */
        public void setAccessed(String accessed)
        {
            checkNotNull(accessed);
            _accessed = accessed;
        }

        /**
         * @return the lastAccessed
         */
        public String getLastAccessed()
        {
            return _lastAccessed;
        }

        /**
         * @param lastAccessed the lastAccessed to set
         */
        public void setLastAccessed(String lastAccessed)
        {
            checkNotNull(lastAccessed);
            _lastAccessed = lastAccessed;
        }

        /**
         * @return the createTime
         */
        public String getCreateTime()
        {
            return _createTime;
        }

        /**
         * @param createTime the createTime to set
         */
        public void setCreateTime(String createTime)
        {
            checkNotNull(createTime);
            _createTime = createTime;
        }

        /**
         * @return the cookieSetTime
         */
        public String getCookieSetTime()
        {
            return _cookieSetTime;
        }

        /**
         * @param cookieSetTime the cookieSetTime to set
         */
        public void setCookieSetTime(String cookieSetTime)
        {
            checkNotNull(cookieSetTime);
            _cookieSetTime = cookieSetTime;
        }

        /**
         * @return the expiry
         */
        public String getExpiry()
        {
            return _expiry;
        }

        /**
         * @param expiry the expiry to set
         */
        public void setExpiry(String expiry)
        {
            checkNotNull(expiry);
            _expiry = expiry;
        }

        /**
         * @return the maxInactive
         */
        public String getMaxInactive()
        {
            return _maxInactive;
        }

        /**
         * @param maxInactive the maxInactive to set
         */
        public void setMaxInactive(String maxInactive)
        {
            checkNotNull(maxInactive);
            _maxInactive = maxInactive;
        }

        /**
         * @return the attributes
         */
        public String getAttributes()
        {
            return _attributes;
        }

        /**
         * @param attributes the attributes to set
         */
        public void setAttributes(String attributes)
        {
            checkNotNull(attributes);
            _attributes = attributes;
        }

        /**
         * @return the lastSaved
         */
        public String getLastSaved()
        {
            return _lastSaved;
        }

        /**
         * @param lastSaved the lastSaved to set
         */
        public void setLastSaved(String lastSaved)
        {
            checkNotNull(lastSaved);
            _lastSaved = lastSaved;
        }

        @Override
        public String toString()
        {
            return String.format("%s==%s:%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s", this.getClass().getName(),
                _kind, _accessed, _attributes, _contextPath, _cookieSetTime, _createTime, _expiry, _id, _lastAccessed, _lastNode, _maxInactive, _vhost);
        }
    }

    /**
     * ExpiryInfo
     *
     * Information related to session expiry
     */
    public static class ExpiryInfo
    {
        String _id;
        String _lastNode;
        String _contextPath;
        String _vhost;
        long _expiry;

        /**
         * @param id session id
         * @param lastNode last node id to manage the session
         * @param expiry timestamp of expiry
         * @param contextPath  context path for session
         * @param vhost vhost of context for session
         */
        public ExpiryInfo(String id, String lastNode, long expiry, String contextPath, String vhost)
        {
            _id = id;
            _lastNode = lastNode;
            _expiry = expiry;
            _contextPath = contextPath;
            _vhost = vhost;
        }

        /**
         * @return the id
         */
        public String getId()
        {
            return _id;
        }

        /**
         * @return the lastNode
         */
        public String getLastNode()
        {
            return _lastNode;
        }

        /**
         * @return the expiry time
         */
        public long getExpiry()
        {
            return _expiry;
        }
        
        public String getContextPath()
        {
            return _contextPath;
        }

        public void setContextPath(String contextPath)
        {
            _contextPath = contextPath;
        }

        public String getVhost()
        {
            return _vhost;
        }

        public void setVhost(String vhost)
        {
            _vhost = vhost;
        }
    }

    public void setEntityDataModel(EntityDataModel model)
    {
        updateBean(_model, model);
        _model = model;
        _modelProvided = true;
    }

    public EntityDataModel getEntityDataModel()
    {
        return _model;
    }

    public void setBackoffMs(int ms)
    {
        _backoff = ms;
    }

    public void setNamespace(String namespace)
    {
        _namespace = namespace;
    }

    @ManagedAttribute(value = "gcloud namespace", readonly = true)
    public String getNamespace()
    {
        return _namespace;
    }

    @ManagedAttribute(value = "unit in ms of exponential backoff")
    public int getBackoffMs()
    {
        return _backoff;
    }

    public void setMaxRetries(int retries)
    {
        _maxRetries = retries;
    }

    @ManagedAttribute(value = "max number of retries for failed writes")
    public int getMaxRetries()
    {
        return _maxRetries;
    }

    public void setHost(String host)
    {
        _host = host;
    }

    @ManagedAttribute(value = "gcloud host", readonly = true)
    public String getHost()
    {
        return _host;
    }

    public void setProjectId(String projectId)
    {
        _projectId = projectId;
    }

    @ManagedAttribute(value = "gcloud project Id", readonly = true)
    public String getProjectId()
    {
        return _projectId;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (!_dsProvided)
        {
            boolean blankCustomnamespace = StringUtil.isBlank(getNamespace());
            boolean blankCustomHost = StringUtil.isBlank(getHost());
            boolean blankCustomProjectId = StringUtil.isBlank(getProjectId());
            if (blankCustomnamespace && blankCustomHost && blankCustomProjectId)
                 _datastore = DatastoreOptions.getDefaultInstance().getService();
            else
            {
                DatastoreOptions.Builder builder = DatastoreOptions.newBuilder();
                if (!blankCustomnamespace)
                    builder.setNamespace(getNamespace());
                if (!blankCustomHost)
                    builder.setHost(getHost());
                if (!blankCustomProjectId)
                    builder.setProjectId(getProjectId());
                _datastore = builder.build().getService();
            }
        }

        if (_model == null)
        {
            _model = new EntityDataModel();
            addBean(_model, true);
        }

        _keyFactory = _datastore.newKeyFactory().setKind(_model.getKind());

        _indexesPresent = checkIndexes();
        if (!_indexesPresent)
            LOG.warn("Session indexes not uploaded, falling back to less efficient queries");

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        if (!_dsProvided)
            _datastore = null;
        if (!_modelProvided)
            _model = null;
    }

    public void setDatastore(Datastore datastore)
    {
        _datastore = datastore;
        _dsProvided = true;
    }

    @ManagedAttribute(value = "max number of results to return from gcloud searches")
    public int getMaxResults()
    {
        return _maxResults;
    }

    public void setMaxResults(int maxResults)
    {
        if (_maxResults <= 0)
            _maxResults = DEFAULT_MAX_QUERY_RESULTS;
        else
            _maxResults = maxResults;
    }

    @Override
    public SessionData doLoad(String id) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Loading session {} from DataStore", id);

        try
        {
            Entity entity = _datastore.get(makeKey(id, _context));
            if (entity == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("No session {} in DataStore ", id);
                return null;
            }
            else
            {
                return sessionFromEntity(entity);
            }
        }
        catch (Exception e)
        {
            throw new UnreadableSessionDataException(id, _context, e);
        }
    }

    @Override
    public boolean delete(String id) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Removing session {} from DataStore", id);
        _datastore.delete(makeKey(id, _context));
        return true;
    }

    @Override
    public Set<String> doCheckExpired(Set<String> candidates, long time)
    {
        Set<String> expired = new HashSet<String>();

        try
        {    
            Set<ExpiryInfo> info = null;
            if (_indexesPresent)
                info = queryExpiryByIndex(time);
            else
                info = queryExpiryByEntity(time);

            for (ExpiryInfo item : info)
            {
                if (StringUtil.isBlank(item.getLastNode()) || !(_context.getWorkerName().equals(item.getLastNode())))
                    continue; //we're not its manager so skip it
                
                if (StringUtil.isBlank(item.getContextPath()) || !(_context.getCanonicalContextPath().equals(item.getContextPath())))
                    continue; // session is not for this context
                expired.add(item.getId());
            }   

            //reconcile against ids that the SessionCache thinks are expired
            Set<String> tmp = new HashSet<String>(candidates);
            tmp.removeAll(expired);
            if (!tmp.isEmpty())
            {
                //sessioncache thinks these are expired, but they are either no
                //longer in the db or not expired in the db, or we exceeded the
                //number of records retrieved by the expiry query, so check them
                //individually
                for (String s : tmp)
                {
                    try
                    {
                        Query<Key> q = Query.newKeyQueryBuilder()
                            .setKind(_model.getKind())
                            .setFilter(PropertyFilter.eq(_model.getId(), s))
                            .build();
                        QueryResults<Key> res = _datastore.run(q);
                        if (!res.hasNext())
                            expired.add(s); //not in db, can be expired
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Unable to expire candidate sessions individually", e);
                    }
                }
            }

            return expired;
            
        }
        catch (Exception e)
        {
            LOG.warn("Unable to get expired", e);
            return expired; //return what we got
        } 
    }
    
    @Override
    public Set<String> doGetExpired(long time)
    {
        // Get sessions managed by any node that expired at or before the given
        // time limit
        Set<String> expired = new HashSet<String>();
        try
        {    
            Set<ExpiryInfo> info = null;
            if (_indexesPresent)
                info = queryExpiryByIndex(time);
            else
                info = queryExpiryByEntity(time);

            for (ExpiryInfo item:info)
            {
                expired.add(item.getId());
            }
            return expired;
        }     
        catch (Exception e)
        {
            LOG.warn("Error querying expired sessions", e);
            return expired; //return what we got
        }   
    }

    @Override
    public void doCleanOrphans(long timeLimit)
    {
        // Gcloud datastore does not support DELETE statements with query params.
        // Therefore need to do a query, and then a separate operation to delete keys 
        //returned.
        try
        {    
            Set<ExpiryInfo> info = null;
            if (_indexesPresent)
                info = queryExpiryByIndex(timeLimit);
            else
                info = queryExpiryByEntity(timeLimit);

            //iterate over each of the returned infos,
            //make a key for each, then do the delete
            Set<Key> keys = info.stream().map(i ->
            {
                return makeKey(i.getId(), i.getContextPath(), i.getVhost());
            }).collect(Collectors.toSet());
            
            _datastore.delete(keys.toArray(new Key[keys.size()]));
        }
        catch (Exception e)
        {
            LOG.warn("Error deleting orphaned sessions", e);
        }   
    }

    /**
     * A less efficient query to find sessions whose expiry time has passed:
     * retrieves the whole Entity.
     *
     * @return set of ExpiryInfo representing the id, lastNode and expiry time of
     * sessions that are expired
     * @throws Exception if datastore experiences a problem
     */
    protected Set<ExpiryInfo> queryExpiryByEntity() throws Exception
    {
        return queryExpiryByEntity(System.currentTimeMillis());
    }

    /**
     * A less efficient query to find sessions whose expiry time is before the
     * given timeLimit.
     * 
     * @param timeLimit time since the epoch
     * 
     * @return set of ExpiryInfo representing the id,lastNode and expiry time
     * @throws Exception
     */
    protected Set<ExpiryInfo> queryExpiryByEntity(long timeLimit) throws Exception
    {
        Set<ExpiryInfo> infos = new HashSet<>();
        //get up to maxResult number of sessions that have expired
        Query<Entity> query = Query.newEntityQueryBuilder()
            .setKind(_model.getKind())
            .setFilter(CompositeFilter.and(PropertyFilter.gt(_model.getExpiry(), 0), PropertyFilter.le(_model.getExpiry(), timeLimit)))
            .setLimit(_maxResults)
            .build();

        QueryResults<Entity> results;
        if (LOG.isDebugEnabled())
        {
            long start = System.currentTimeMillis();
            results = _datastore.run(query);
            LOG.debug("Expiry query no index in {}ms", System.currentTimeMillis() - start);
        }
        else
            results = _datastore.run(query);
        
        while (results.hasNext())
        {
            Entity entity = results.next();
            ExpiryInfo info = new ExpiryInfo(entity.getString(_model.getId()),
                                             entity.getString(_model.getLastNode()), 
                                             entity.getLong(_model.getExpiry()),
                                             entity.getString(_model.getContextPath()),
                                             entity.getString(_model.getVhost()));
            
            infos.add(info);
        }
        return infos;
    }
    
    /**
     * An efficient query to find sessions whose expiry time has passed:
     * uses a projection query, which requires indexes to be uploaded.
     *
     * @return id, lastnode and expiry time of sessions that have expired
     * @throws Exception if datastore experiences a problem
     */
    protected Set<ExpiryInfo> queryExpiryByIndex() throws Exception
    {
        return queryExpiryByIndex(System.currentTimeMillis());       
    }

    /**
     * An efficient query to find sessions whose expiry time is before the given timeLimit:
     * uses a projection query, which requires indexes to be uploaded.
     * 
     * @param timeLimit the upper limit of expiry time to check
     * @return  id,lastnode and expiry time of sessions that have expired
     * @throws Exception
     */
    protected Set<ExpiryInfo>  queryExpiryByIndex(long timeLimit) throws Exception
    {
        Set<ExpiryInfo> infos = new HashSet<>();
        Query<ProjectionEntity> query = Query.newProjectionEntityQueryBuilder()
            .setKind(_model.getKind())
            .setProjection(_model.getId(), _model.getLastNode(), _model.getExpiry(), _model.getContextPath(), _model.getVhost())
            .setFilter(CompositeFilter.and(PropertyFilter.gt(_model.getExpiry(), 0), PropertyFilter.le(_model.getExpiry(), timeLimit)))
            .setLimit(_maxResults)
            .build();

        QueryResults<ProjectionEntity> presults;

        if (LOG.isDebugEnabled())
        {
            long start = System.currentTimeMillis();
            presults = _datastore.run(query);
            LOG.debug("Expiry query by index in {}ms", System.currentTimeMillis() - start);
        }
        else
            presults = _datastore.run(query);

        while (presults.hasNext())
        {
            ProjectionEntity pe = presults.next();
            ExpiryInfo info = new ExpiryInfo(pe.getString(_model.getId()),
                                             pe.getString(_model.getLastNode()), 
                                             pe.getLong(_model.getExpiry()),
                                             pe.getString(_model.getContextPath()),
                                             pe.getString(_model.getVhost()));
            infos.add(info);
        }

        return infos; 
    }
   
    @Override
    public boolean doExists(String id) throws Exception
    {
        if (_indexesPresent)
        {
            Query<ProjectionEntity> query = Query.newProjectionEntityQueryBuilder()
                .setKind(_model.getKind())
                .setProjection(_model.getExpiry())
                .setFilter(CompositeFilter.and(PropertyFilter.eq(_model.getId(), id),
                    PropertyFilter.eq(_model.getContextPath(), _context.getCanonicalContextPath()),
                    PropertyFilter.eq(_model.getVhost(), _context.getVhost())))
                //.setFilter(PropertyFilter.eq(_model.getId(), id))
                .build();

            QueryResults<ProjectionEntity> presults;
            if (LOG.isDebugEnabled())
            {
                long start = System.currentTimeMillis();
                presults = _datastore.run(query);
                LOG.debug("Exists query by index in {}ms", System.currentTimeMillis() - start);
            }
            else
                presults = _datastore.run(query);

            if (presults.hasNext())
            {
                ProjectionEntity pe = presults.next();
                return !isExpired(pe.getLong(_model.getExpiry()));
            }
            else
                return false;
        }
        else
        {
            Query<Entity> query = Query.newEntityQueryBuilder()
                .setKind(_model.getKind())
                .setFilter(CompositeFilter.and(PropertyFilter.eq(_model.getId(), id),
                    PropertyFilter.eq(_model.getContextPath(), _context.getCanonicalContextPath()),
                    PropertyFilter.eq(_model.getVhost(), _context.getVhost())))
                //.setFilter(PropertyFilter.eq(_model.getId(), id))
                .build();

            QueryResults<Entity> results;
            if (LOG.isDebugEnabled())
            {
                long start = System.currentTimeMillis();
                results = _datastore.run(query);
                LOG.debug("Exists query no index in {}ms", System.currentTimeMillis() - start);
            }
            else
                results = _datastore.run(query);

            if (results.hasNext())
            {
                Entity entity = results.next();
                return !isExpired(entity.getLong(_model.getExpiry()));
            }
            else
                return false;
        }
    }

    /**
     * Check to see if the given time is in the past.
     *
     * @param timestamp the time to check
     * @return false if the timestamp is 0 or less, true if it is in the past
     */
    protected boolean isExpired(long timestamp)
    {
        if (timestamp <= 0)
            return false;
        else
            return timestamp < System.currentTimeMillis();
    }

    @Override
    public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Writing session {} to DataStore", data.getId());
        Entity entity = entityFromSession(data, makeKey(id, _context));

        //attempt the update with exponential back-off
        int backoff = getBackoffMs();
        int attempts;
        for (attempts = 0; attempts < getMaxRetries(); attempts++)
        {
            try
            {
                _datastore.put(entity);
                return;
            }
            catch (DatastoreException e)
            {
                if (e.isRetryable())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug(String.format("Datastore put retry=%s backoff=%s", attempts, backoff), e);

                    try
                    {
                        Thread.currentThread().sleep(backoff);
                    }
                    catch (InterruptedException ignored)
                    {
                    }
                    backoff *= 2;
                }
                else
                {
                    throw e;
                }
            }
        }

        //retries have been exceeded
        throw new UnwriteableSessionDataException(id, _context, null);
    }

    /**
     * Make a unique key for this session.
     * As the same session id can be used across multiple contexts, to
     * make it unique, the key must be composed of:
     * <ol>
     * <li>the id</li>
     * <li>the context path</li>
     * <li>the virtual hosts</li>
     * </ol>
     *
     * @param id the id
     * @param context the session context
     * @return the key
     */
    protected Key makeKey(String id, SessionContext context)
    {
        return makeKey(id, context.getCanonicalContextPath(), context.getVhost());
    }
    
    protected Key makeKey(String id, String canonicalContextPath, String canonicalVHost)
    {
        String key = canonicalContextPath + "_" + canonicalVHost + "_" + id;
        return _keyFactory.newKey(key);
    }

    /**
     * Check to see if indexes are available, in which case
     * we can do more performant queries.
     *
     * @return <code>true</code> if indexes are available
     */
    protected boolean checkIndexes()
    {
        try
        {
            Query<ProjectionEntity> query = Query.newProjectionEntityQueryBuilder()
                .setKind(_model.getKind())
                .setProjection(_model.getExpiry())
                .setFilter(PropertyFilter.eq(_model.getId(), "-"))
                .build();
            _datastore.run(query);
            return true;
        }
        catch (DatastoreException e)
        {
            //need to assume that the problem is the index doesn't exist, because there
            //is no specific code for that
            if (LOG.isDebugEnabled())
                LOG.debug("Check for indexes", e);

            return false;
        }
    }

    /**
     * Generate a gcloud datastore Entity from SessionData
     *
     * @param session the session data
     * @param key the key
     * @return the entity
     * @throws Exception if there is a deserialization error
     */
    protected Entity entityFromSession(SessionData session, Key key) throws Exception
    {
        if (session == null)
            return null;

        Entity entity = null;

        //serialize the attribute map
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos))
        {
            SessionData.serializeAttributes(session, oos);

            //turn a session into an entity         
            entity = Entity.newBuilder(key)
                .set(_model.getId(), session.getId())
                .set(_model.getContextPath(), session.getContextPath())
                .set(_model.getVhost(), session.getVhost())
                .set(_model.getAccessed(), session.getAccessed())
                .set(_model.getLastAccessed(), session.getLastAccessed())
                .set(_model.getCreateTime(), session.getCreated())
                .set(_model.getCookieSetTime(), session.getCookieSet())
                .set(_model.getLastNode(), session.getLastNode())
                .set(_model.getExpiry(), session.getExpiry())
                .set(_model.getMaxInactive(), session.getMaxInactiveMs())
                .set(_model.getLastSaved(), session.getLastSaved())
                .set(_model.getAttributes(), BlobValue.newBuilder(Blob.copyFrom(baos.toByteArray())).setExcludeFromIndexes(true).build()).build();
            return entity;
        }
    }

    /**
     * Generate SessionData from an Entity retrieved from gcloud datastore.
     *
     * @param entity the entity
     * @return the session data
     * @throws Exception if unable to get the entity
     */
    protected SessionData sessionFromEntity(Entity entity) throws Exception
    {
        if (entity == null)
            return null;

        //turn an Entity into a Session
        final String id = entity.getString(_model.getId());
        final String contextPath = entity.getString(_model.getContextPath());
        final String vhost = entity.getString(_model.getVhost());
        final long accessed = entity.getLong(_model.getAccessed());
        final long lastAccessed = entity.getLong(_model.getLastAccessed());
        final long createTime = entity.getLong(_model.getCreateTime());
        final long cookieSet = entity.getLong(_model.getCookieSetTime());
        final String lastNode = entity.getString(_model.getLastNode());

        long lastSaved = 0;
        //for compatibility with previously saved sessions, lastSaved may not be present
        try
        {
            lastSaved = entity.getLong(_model.getLastSaved());
        }
        catch (DatastoreException e)
        {
            LOG.trace("IGNORED", e);
        }
        long expiry = entity.getLong(_model.getExpiry());
        long maxInactive = entity.getLong(_model.getMaxInactive());
        Blob blob = (Blob)entity.getBlob(_model.getAttributes());

        SessionData session = newSessionData(id, createTime, accessed, lastAccessed, maxInactive);
        session.setLastNode(lastNode);
        session.setContextPath(contextPath);
        session.setVhost(vhost);
        session.setCookieSet(cookieSet);
        session.setLastNode(lastNode);
        session.setLastSaved(lastSaved);
        session.setExpiry(expiry);
        try (ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(blob.asInputStream()))
        {
            SessionData.deserializeAttributes(session, ois);
        }
        catch (Exception e)
        {
            throw new UnreadableSessionDataException(id, _context, e);
        }
        return session;
    }

    @ManagedAttribute(value = "does gcloud serialize session data", readonly = true)
    @Override
    public boolean isPassivating()
    {
        return true;
    }

    @Override
    public String toString()
    {
        return String.format("%s[namespace=%s,backoff=%d,maxRetries=%d,maxResults=%d,indexes=%b]", super.toString(), _namespace, _backoff, _maxRetries, _maxResults, _indexesPresent);
    }
}
