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

package org.eclipse.jetty.nosql.jedis;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.nosql.NoSqlSession;
import org.eclipse.jetty.nosql.NoSqlSessionManager;
import org.eclipse.jetty.nosql.jedis.JedisSession.MapKey;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Much like the MongoSessionManager, but for Redis
 *
 * @author friso
 * @since 16 sep. 2016
 */
public class JedisSessionManager extends NoSqlSessionManager
{

    private static final long ONE = 1L;

    private final JedisPool pool;

    /**
     * Constructor for RedisSessionManager
     * 
     * @param pool
     *            pool...
     */
    public JedisSessionManager(JedisPool pool)
    {
        this.pool = pool;
    }

    @Override
    protected NoSqlSession loadSession(String idInCluster)
    {
        try (Jedis jedis = pool.getResource())
        {
            Map<byte[], byte[]> complete = jedis.hgetAll(idInCluster.getBytes(UTF_8));
            return JedisSession.asNoSqlSession(this,complete);
        }
    }

    @Override
    protected Object save(NoSqlSession session, Object version, boolean activateAfterSave)
    {
        Long result = (Long)version;
        session.willPassivate();

        if (session.isValid())
        {
            result = updateSessionValues(session,version == null);
        }
        else
        {
            invalidateSession(session);
        }

        if (activateAfterSave)
        {
            session.didActivate();
        }

        return result;
    }

    /**
     * @param session
     */
    private void invalidateSession(NoSqlSession session)
    {
        try (Jedis jedis = pool.getResource())
        {
            JedisSession sess = JedisSession.invalidationSession(session);
            jedis.hmset(sess.getJedisKey(),sess.getJedisValue());
            jedis.expire(sess.getJedisKey(),calculateRedisExpiry(session));
        }

    }

    /**
     * If the session has no maxInactiveInterval set, expunge the session in 15 minutes
     * 
     * @param session
     *            to be exipred
     * @return the number of seconds before the session should be removed from Redis
     */
    static int calculateRedisExpiry(NoSqlSession session)
    {
        return session.getMaxInactiveInterval() < 1?900:session.getMaxInactiveInterval();
    }

    /**
     * @param session
     * @param newSession
     */
    private Long updateSessionValues(NoSqlSession session, boolean newSession)
    {
        JedisSession sess = new JedisSession(session,this,newSession);
        try (Jedis jedis = pool.getResource())
        {
            if (session.isDirty())
            {
                List<byte[]> persistentAttributes = jedis.hmget(sess.getJedisKey(),MapKey.ATTRIBUTES.getKey());
                sess.updateTheseAttributes(persistentAttributes.iterator().next());
            }

            jedis.hmset(sess.getJedisKey(),sess.getJedisValue());
            jedis.expire(sess.getJedisKey(),calculateRedisExpiry(session));
            return jedis.hincrBy(sess.getJedisKey(),JedisSession.MapKey.VERSION.getKey(),ONE);
        }

    }

    @Override
    protected Object refresh(NoSqlSession session, Object version)
    {
        try (Jedis jedis = pool.getResource())
        {
            // the Mongo implementation has a check on version before fetching the whole thing. As in my use case there
            // will not be a lot of data in the attributes, making two calls is probably a lot more expensive than
            // fetching the whole thing. This assumption might turn out to be false at which point this part might need
            // refactoring, until than we don't optimise prematurely
            Map<byte[], byte[]> complete = jedis.hgetAll(session.getClusterId().getBytes(UTF_8));
            NoSqlSession sess = JedisSession.asNoSqlSession(this,complete);
            if (sess == null)
            {
                session.invalidate();
                return null;
            }
            // else
            if (sess.getVersion().equals(version))
            {
                return version;
            }

            session.willPassivate();
            refreshAttributes(sess,session);
            byte[] sessKey = sess.getClusterId().getBytes(UTF_8);
            byte[] accessed = JedisSession.long2bytes(session.getAccessed());
            jedis.hset(sessKey,MapKey.ACCESSED.getKey(),accessed);
            jedis.expire(sessKey,calculateRedisExpiry(session));
            session.didActivate();
            // should this not increment the version?
            // return jedis.hincrBy(sessKey, MapKey.VERSION.getKey(), ONE);
            return version;
        }
    }

    /**
     * @param persisted
     * @param session
     */
    private static void refreshAttributes(NoSqlSession persisted, NoSqlSession session)
    {
        Map<String, Object> attributeMap = persisted.getAttributeMap();
        if (attributeMap == null || attributeMap.isEmpty())
        {
            session.clearAttributes();
            return;
        }
        // else
        Set<String> persistedKeySet = attributeMap.keySet();

        for (String key : persistedKeySet)
        {
            Object value = attributeMap.get(key);
            boolean bind = session.doGet(key) == null;

            session.doPutOrRemove(key,value); // might invoke a save ???
            if (bind)
            {
                session.bindValue(key,value);
            }
        }

        for (String name : session.getNames())
        {
            if (!persistedKeySet.contains(name))
            {
                session.doPutOrRemove(name,null); // might invoke a save ???
                // the Mongo impl. has unbind str, session.getAttribute(str) but surely that last one will return
                // null after the putOrRemove?
                session.unbindValue(name,null);
            }
        }

    }

    @Override
    protected boolean remove(NoSqlSession session)
    {
        try (Jedis jedis = pool.getResource())
        {
            Long del = jedis.del(session.getClusterId().getBytes(UTF_8));
            return del.longValue() == 1;
        }
    }

    @Override
    protected void update(NoSqlSession session, String newClusterId, String newNodeId) throws Exception
    {
        try (Jedis jedis = pool.getResource())
        {
            jedis.rename(session.getClusterId().getBytes(UTF_8),newClusterId.getBytes(UTF_8));
            jedis.expire(newClusterId.getBytes(UTF_8),calculateRedisExpiry(session));
        }
    }

}
