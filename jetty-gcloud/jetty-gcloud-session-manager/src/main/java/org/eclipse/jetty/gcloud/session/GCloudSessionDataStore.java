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


package org.eclipse.jetty.gcloud.session;

import com.google.gcloud.datastore.Blob;
import com.google.gcloud.datastore.Datastore;
import com.google.gcloud.datastore.DatastoreFactory;
import com.google.gcloud.datastore.Entity;
import com.google.gcloud.datastore.Key;
import com.google.gcloud.datastore.KeyFactory;
import com.google.gcloud.datastore.ProjectionEntity;
import com.google.gcloud.datastore.Query;
import com.google.gcloud.datastore.QueryResults;
import com.google.gcloud.datastore.StructuredQuery;
import com.google.gcloud.datastore.StructuredQuery.CompositeFilter;
import com.google.gcloud.datastore.StructuredQuery.KeyQueryBuilder;
import com.google.gcloud.datastore.StructuredQuery.Projection;
import com.google.gcloud.datastore.StructuredQuery.ProjectionEntityQueryBuilder;
import com.google.gcloud.datastore.StructuredQuery.PropertyFilter;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.server.session.AbstractSessionDataStore;
import org.eclipse.jetty.server.session.SessionContext;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * GCloudSessionDataStore
 *
 *
 */
public class GCloudSessionDataStore extends AbstractSessionDataStore
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");

    public  static final String ID = "id";
    public  static final String CONTEXTPATH = "contextPath";
    public  static final String VHOST = "vhost";
    public  static final String ACCESSED = "accessed";
    public  static final String LASTACCESSED = "lastAccessed";
    public  static final String CREATETIME = "createTime";
    public  static final  String COOKIESETTIME = "cookieSetTime";
    public  static final String LASTNODE = "lastNode";
    public  static final String EXPIRY = "expiry";
    public  static final  String MAXINACTIVE = "maxInactive";
    public  static final  String ATTRIBUTES = "attributes";

    public static final String KIND = "GCloudSession";
    public static final int DEFAULT_MAX_QUERY_RESULTS = 100;

    private GCloudConfiguration _config;
    private Datastore _datastore;
    private KeyFactory _keyFactory;
    private int _maxResults = DEFAULT_MAX_QUERY_RESULTS;
    
    
    
    
    

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        
        if (_config == null)
            throw new IllegalStateException("No DataStore configuration");
        
        _datastore = DatastoreFactory.instance().get(_config.getDatastoreOptions());
        _keyFactory = _datastore.newKeyFactory().kind(KIND);
        
        super.doStart();
    }

    /** 
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
    }
    
    
    public void setGCloudConfiguration (GCloudConfiguration cfg)
    {
        _config = cfg;
    }
    
    public GCloudConfiguration getGCloudConfiguration ()
    {
        return _config;
    }

    
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
    
    
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#load(java.lang.String)
     */
    @Override
    public SessionData load(String id) throws Exception
    {
        if (LOG.isDebugEnabled()) LOG.debug("Loading session {} from DataStore", id);

        Entity entity = _datastore.get(makeKey(id, _context));
        if (entity == null)
        {
            if (LOG.isDebugEnabled()) LOG.debug("No session {} in DataStore ", id);
            return null;
        }
        else
        {
            SessionData data = sessionFromEntity(entity);
            return data;
        }
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#delete(java.lang.String)
     */
    @Override
    public boolean delete(String id) throws Exception
    {
        if (LOG.isDebugEnabled()) LOG.debug("Removing session {} from DataStore", id);
        _datastore.delete(makeKey(id, _context));
        return true;
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#getExpired(Set)
     */
    @Override
    public Set<String> doGetExpired(Set<String> candidates)
    {
        long now = System.currentTimeMillis();
        Set<String> expired = new HashSet<String>();

        try
        {        
            //get up to maxResult number of sessions that have expired
            ProjectionEntityQueryBuilder pbuilder = Query.projectionEntityQueryBuilder();
            pbuilder.addProjection(Projection.property(ID), Projection.property(LASTNODE), Projection.property(EXPIRY));
            pbuilder.filter(CompositeFilter.and(PropertyFilter.gt(EXPIRY, 0), PropertyFilter.le(EXPIRY, now)));
            pbuilder.limit(_maxResults);
            pbuilder.kind(KIND);
            StructuredQuery<ProjectionEntity> pquery = pbuilder.build();
            QueryResults<ProjectionEntity> presults = _datastore.run(pquery);

            while (presults.hasNext())
            {
                ProjectionEntity pe = presults.next();
                String id = pe.getString(ID);
                String lastNode = pe.getString(LASTNODE);
                long expiry = pe.getLong(EXPIRY);

                if (StringUtil.isBlank(lastNode))
                    expired.add(id); //nobody managing it
                else
                {
                    if (_context.getWorkerName().equals(lastNode))
                        expired.add(id); //we're managing it, we can expire it
                    else
                    {
                        if (_lastExpiryCheckTime <= 0)
                        {
                            //our first check, just look for sessions that we managed by another node that
                            //expired at least 3 graceperiods ago
                            if (expiry < (now - (1000L * (3 * _gracePeriodSec))))
                                expired.add(id);
                        }
                        else
                        {
                            //another node was last managing it, only expire it if it expired a graceperiod ago
                            if (expiry < (now - (1000L * _gracePeriodSec)))
                                expired.add(id);
                        }
                    }
                }
            }

            //reconcile against ids that the SessionDataStore thinks are expired
            Set<String> tmp = new HashSet<String>(candidates);
            tmp.removeAll(expired);       
            if (!tmp.isEmpty())
            {
                //sessionstore thinks these are expired, but they are either no
                //longer in the db or not expired in the db, or we exceeded the
                //number of records retrieved by the expiry query, so check them
                //individually
                for (String s:tmp)
                {
                    try
                    {
                        KeyQueryBuilder kbuilder = Query.keyQueryBuilder();
                        kbuilder.filter(PropertyFilter.eq(ID, s));
                        kbuilder.kind(KIND);
                        StructuredQuery<Key> kq = kbuilder.build();
                        QueryResults<Key> kresults = _datastore.run(kq);
                        if (!kresults.hasNext())
                            expired.add(s); //not in db, can be expired
                    }
                    catch (Exception e)
                    {
                        LOG.warn(e);
                    }
                }
            }

            return expired;
        }
        catch (Exception e)
        {
            LOG.warn(e);
            return expired; //return what we got
        }

    }

 
    
    
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#exists(java.lang.String)
     */
    @Override
    public boolean exists(String id) throws Exception
    {     
        ProjectionEntityQueryBuilder pbuilder = Query.projectionEntityQueryBuilder();
        pbuilder.addProjection(Projection.property(EXPIRY));
        pbuilder.filter(PropertyFilter.eq(ID, id));
        pbuilder.kind(KIND);
        StructuredQuery<ProjectionEntity> pquery = pbuilder.build();
        QueryResults<ProjectionEntity> presults = _datastore.run(pquery);

        if (presults.hasNext())
        {
            ProjectionEntity pe = presults.next();
            long expiry = pe.getLong(EXPIRY);
            if (expiry <= 0)
                return true; //never expires
            else
                return (expiry > System.currentTimeMillis()); //not expired yet
        }
        else
            return false;
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doStore(java.lang.String, org.eclipse.jetty.server.session.SessionData, long)
     */
    @Override
    public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
    {
        if (LOG.isDebugEnabled()) LOG.debug("Writing session {} to DataStore", data.getId());
    
        Entity entity = entityFromSession(data, makeKey(id, _context));
        _datastore.put(entity);
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
     *
     * @param id the id
     * @param context the session context
     * @return the key
     */
    private Key makeKey (String id, SessionContext context)
    {
        String key = context.getCanonicalContextPath()+"_"+context.getVhost()+"_"+id;
        return _keyFactory.newKey(key);
    }
    
    
    /**
     * Generate a gcloud datastore Entity from SessionData
     * @param session the session data
     * @param key the key
     * @return the entity
     * @throws Exception
     */
    private Entity entityFromSession (SessionData session, Key key) throws Exception
    {
        if (session == null)
            return null;
        
        Entity entity = null;
        
        //serialize the attribute map
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(session.getAllAttributes());
        oos.flush();
        
        try
        {
        //turn a session into an entity
        entity = Entity.builder(key)
                .set(ID, session.getId())
                .set(CONTEXTPATH, session.getContextPath())
                .set(VHOST, session.getVhost())
                .set(ACCESSED, session.getAccessed())
                .set(LASTACCESSED, session.getLastAccessed())
                .set(CREATETIME, session.getCreated())
                .set(COOKIESETTIME, session.getCookieSet())
                .set(LASTNODE,session.getLastNode())
                .set(EXPIRY, session.getExpiry())
                .set(MAXINACTIVE, session.getMaxInactiveMs())
                .set(ATTRIBUTES, Blob.copyFrom(baos.toByteArray())).build();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            throw e;
        }
                 
        return entity;
    }
    
    /**
     * Generate SessionData from an Entity retrieved from gcloud datastore.
     * @param entity the entity
     * @return the session data
     * @throws Exception if unable to get the entity
     */
    private SessionData sessionFromEntity (Entity entity) throws Exception
    {
        if (entity == null)
            return null;

        final AtomicReference<SessionData> reference = new AtomicReference<SessionData>();
        final AtomicReference<Exception> exception = new AtomicReference<Exception>();
        Runnable load = new Runnable()
        {
            public void run ()
            {
                try
                {
                    //turn an Entity into a Session
                    String id = entity.getString(ID);
                    String contextPath = entity.getString(CONTEXTPATH);
                    String vhost = entity.getString(VHOST);
                    long accessed = entity.getLong(ACCESSED);
                    long lastAccessed = entity.getLong(LASTACCESSED);
                    long createTime = entity.getLong(CREATETIME);
                    long cookieSet = entity.getLong(COOKIESETTIME);
                    String lastNode = entity.getString(LASTNODE);
                    long expiry = entity.getLong(EXPIRY);
                    long maxInactive = entity.getLong(MAXINACTIVE);
                    Blob blob = (Blob) entity.getBlob(ATTRIBUTES);

                    SessionData session = newSessionData (id, createTime, accessed, lastAccessed, maxInactive);
                    session.setLastNode(lastNode);
                    session.setContextPath(contextPath);
                    session.setVhost(vhost);
                    session.setCookieSet(cookieSet);
                    session.setLastNode(lastNode);
                    session.setExpiry(expiry);
                    try (ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(blob.asInputStream()))
                    {
                        Object o = ois.readObject();
                        session.putAllAttributes((Map<String,Object>)o);
                    }
                    reference.set(session);
                }
                catch (Exception e)
                {
                    exception.set(e);
                }
            }
        };
        
        //ensure this runs in the context classloader
       _context.run(load);
    
        if (exception.get() != null)
            throw exception.get();
        
        return reference.get();
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#isPassivating()
     */
    @Override
    public boolean isPassivating()
    {
       return true;
    }
    
    
}
