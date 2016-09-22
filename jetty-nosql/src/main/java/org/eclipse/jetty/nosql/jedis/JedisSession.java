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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.eclipse.jetty.nosql.NoSqlSession;
import org.eclipse.jetty.nosql.NoSqlSessionManager;
import org.eclipse.jetty.nosql.mongodb.MongoSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A bridge between the format stored ({@link Map}) and the {@link NoSqlSession}, that the {@link NoSqlSessionManager} uses. For inspiration, the
 * {@link MongoSessionManager} was used
 *
 * @author Friso Vrolijken
 * @since 19 sep. 2016
 */
class JedisSession
{

    enum MapKey
    {
        /**
         * Session id, can be any kind of String, required
         */
        ID,

        /**
         * Version number, long starting with 0, which should be incremented immediately. As Redis doesn't store the four bytes this fails when incrementing the
         * value. Therefore this is stored as a string. Required
         */
        VERSION,

        /**
         * Time of session creation, long in milliseconds since the epoch, required
         */
        CREATED,

        /**
         * Whether or not session is valid, boolean, required
         */
        VALID,

        /**
         * Time at which session was invalidated, long in milliseconds since the epoch, optional
         */
        INVALIDATED,

        /**
         * Last access time of session
         */
        ACCESSED,

        /**
         * Time this session will expire, based on last access time and maxIdle
         */
        EXPIRY,

        /**
         * The max idle time of a session (smallest value across all contexts which has a session with the same id)
         */
        MAX_IDLE,

        /**
         * Key for the actual session attributes, the value will be a Map<String, Object>
         */
        ATTRIBUTES;

        private final byte[] key;

        /**
         * Constructor for JedisSession.MapKey
         */
        private MapKey()
        {
            key = this.name().getBytes(StandardCharsets.ISO_8859_1);
        }

        /**
         * Gets the key for use by Jedis.hm*** operations
         * 
         * @return the byte[] value of the name()
         */
        public byte[] getKey()
        {
            return key;
        }

    }

    /**
     * Standard SLF4J Logger
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(JedisSession.class);

    private static final String ZERO = "0";

    private final Map<byte[], byte[]> persistentValue;
    private final NoSqlSessionManager manager;
    private final NoSqlSession delegate;
    private final boolean newEntry;

    /**
     * Private constructor only used to create invalid sessions, via the factory method
     * 
     * @param clusterId
     *            unique identifier
     */
    private JedisSession(String clusterId)
    {
        super();
        this.persistentValue = new HashMap<>();
        this.manager = null;
        this.delegate = null;
        this.newEntry = false;

        setString(MapKey.ID,clusterId);
        setBoolean(MapKey.VALID,false);
        setLong(MapKey.INVALIDATED,System.currentTimeMillis());
    }

    /**
     * Constructor for JedisSession, to be used when the session was loaded form the datastore
     * 
     * @param manager
     *            the manager that created this object
     * @param fromDataStore
     *            the actual data in the store
     */
    private JedisSession(NoSqlSessionManager manager, Map<byte[], byte[]> fromDataStore)
    {
        super();
        this.manager = manager;
        this.persistentValue = fromDataStore;
        if (getBoolean(MapKey.VALID))
        {
            this.delegate = new NoSqlSession(manager,getLong(MapKey.CREATED),getLong(MapKey.ACCESSED),getString(MapKey.ID),
                    Long.valueOf(getString(MapKey.VERSION)));
            this.delegate.setMaxInactiveInterval(getMaxIdle());
            byte[] attr = this.persistentValue.get(MapKey.ATTRIBUTES.getKey());
            if (attr != null)
            {
                HashMap<String, Object> attributes = bytesToMap(attr);
                for (Entry<String, Object> entry : attributes.entrySet())
                {
                    this.delegate.doPutOrRemove(entry.getKey(),entry.getValue());
                    this.delegate.bindValue(entry.getKey(),entry.getValue());
                }
            }
            this.delegate.didActivate();
        }
        else
        {
            this.delegate = null;
        }
        this.newEntry = false;
    }

    /**
     * Constructor for JedisSession, to be used when the manager wants to write the session to the datastore
     * 
     * @param session
     *            as seen by the manager
     * @param manager
     *            the manager that created this object
     * @param newEntry
     *            whether or not this has been persisted before
     */
    JedisSession(NoSqlSession session, NoSqlSessionManager manager, boolean newEntry)
    {
        super();
        this.persistentValue = new HashMap<>();
        this.delegate = session;
        this.manager = manager;
        this.newEntry = newEntry;

        setString(MapKey.ID,session.getClusterId());

        if (newEntry)
        {
            setString(MapKey.VERSION,ZERO);
            setLong(MapKey.CREATED,session.getCreationTime());
            setBoolean(MapKey.VALID,true);
        }
        setLong(MapKey.MAX_IDLE,session.getMaxInactiveInterval()); // seconds
        setLong(MapKey.EXPIRY,calculateExpiry(session)); // milliseconds
        setLong(MapKey.ACCESSED,session.getAccessed());
    }

    /**
     * @param session
     *            to be exipred
     * @return the number of milliseconds until the session expires
     */
    private static long calculateExpiry(NoSqlSession session)
    {
        if (session.getMaxInactiveInterval() > 0)
        {
            return session.getAccessed() + (1000L * session.getMaxInactiveInterval());
        }
        // else
        return 0;
    }

    /**
     * Conversion from the format in the store to the NoSqlSession format
     * 
     * @param jedisSessionManager
     *            session manager
     * @param complete
     *            holds all attributes
     * @return null if the session was null or invalid, the values in the NoSqlSession format otherwise
     */
    static NoSqlSession asNoSqlSession(JedisSessionManager jedisSessionManager, Map<byte[], byte[]> complete)
    {
        if (complete == null)
        {
            return null;
        }
        // else

        JedisSession sess = new JedisSession(jedisSessionManager,complete);
        return sess.delegate;
    }

    /**
     * Helper method to the helper methods {@link #getLong(MapKey)} and {@link #getString(MapKey)}
     * 
     * @param key
     *            a key that must be present in the persisted values
     * @param required
     *            if the value must be present
     * @return the byte[] value stored under the key
     * @throws IllegalArgumentException
     *             if no key was provided
     * @throws IllegalStateException
     *             if the persistentValue is null or the key has no value in the persistentValue when required
     */
    private byte[] getRaw(MapKey key, boolean required)
    {
        byte[] value = persistentValue.get(key.getKey());
        if (required && value == null)
        {
            throw new IllegalStateException(key + " was not present in the persisted value, can't transform");
        }
        return value;
    }

    /**
     * Helper method for transforming byte[] to boolean. The bytes must be stored in the persistentValue or else an {@link IllegalStateException} will be
     * thrown.
     * 
     * @param key
     *            the key by which the required value is known in the persistent value
     * @return true iff the persistentVvalue has a value for the key, that value is a byte[] of length 1 and the value is 1
     */
    private boolean getBoolean(MapKey key)
    {
        byte[] value = persistentValue.get(key.getKey());
        return value != null && value.length == 1 && 1 == value[0];
    }

    private void setBoolean(MapKey key, boolean bool)
    {
        byte[] value = bool?new byte[]
        { 1 }:new byte[]
        { 0 };
        persistentValue.put(key.getKey(),value);
    }

    /**
     * Helper method for transforming byte[] to long. The bytes must be stored in the persistentValue or else an {@link IllegalStateException} will be thrown.
     * 
     * @param key
     *            the key by which the required value is known in the persistent value
     * @return the value of the stored bytes as long
     */
    private long getLong(MapKey key)
    {
        byte[] value = getRaw(key,true);
        if (value.length != Long.BYTES)
        {
            throw new IllegalStateException("An incorrect value was stored for " + key + " got " + value.length + " bytes but expected " + Long.BYTES);
        }
        long result = 0;
        for (int i = 0; i < Long.BYTES; i++)
        {
            result <<= 8;
            result |= (value[i] & 0xFF);
        }
        return result;
    }

    /**
     * Helper method for transforming long to byte[]. The bytes are subsequently stored in the persistentValue. If that is not initialised, an
     * {@link IllegalStateException} will be thrown.
     * 
     * @param key
     *            the key by which the required value is known in the persistent value
     * @param value
     *            the value to convert and set
     */
    private void setLong(MapKey key, long value)
    {
        byte[] result = long2bytes(value);
        persistentValue.put(key.getKey(),result);
    }

    /**
     * Converts a long to a byte[]
     * 
     * @param value
     *            to be converted
     * @return byte[] representing the value
     */
    static byte[] long2bytes(long value)
    {
        byte[] result = new byte[Long.BYTES];
        long tmp = value;
        for (int i = Long.BYTES - 1; i >= 0; i--)
        {
            result[i] = (byte)(tmp & 0xFF);
            tmp >>= 8;
        }
        return result;
    }

    /**
     * Helper method for transforming byte[] to String. The bytes must be stored in the persistentValue or else an {@link IllegalStateException} will be thrown.
     * 
     * @param key
     *            the key by which the required value is known in the persistent value
     * @return the value of the stored bytes as String
     */
    private String getString(MapKey key)
    {
        byte[] value = getRaw(key,true);
        return new String(value,StandardCharsets.UTF_8);
    }

    private void setString(MapKey key, String value)
    {
        persistentValue.put(key.getKey(),value.getBytes(UTF_8));
    }

    /**
     * @return the identifier for the session
     */
    private String getId()
    {
        return getString(MapKey.ID);
    }

    /**
     * @return the number of seconds a session is maximally allowed to be idle
     */
    private int getMaxIdle()
    {
        long maxIdle = getLong(MapKey.MAX_IDLE);
        return (int)maxIdle;
    }

    /**
     * Helper method to transform the attributes which should be stored as a {@link HashMap}. Not using the interface Map here since that is not
     * {@link Serializable} and {@link HashMap} is.
     * 
     * @param input
     *            java serialisation of a HashMap
     * @return see description
     */
    @SuppressWarnings("unchecked")
    private static HashMap<String, Object> bytesToMap(byte[] input)
    {
        if (input == null || input.length == 0)
        {
            return new HashMap<>();
        }
        // else

        try (ByteArrayInputStream bais = new ByteArrayInputStream(input); ObjectInputStream ois = new ObjectInputStream(bais))
        {
            Map<String, Object> result = (Map<String, Object>)ois.readObject();
            if (result instanceof HashMap)
            {
                return (HashMap<String, Object>)result;
            }
            // else
            return new HashMap<>(result);
        }
        catch (IOException e)
        {
            LOGGER.warn("Error while doing getAttributes");
            throw new UncheckedIOException(e);
        }
        catch (ClassNotFoundException e)
        {
            LOGGER.warn("Error while doing getAttributes");
            throw new IllegalStateException(e);
        }
    }

    /**
     * @return the id as bytes, key to the Redis entry
     */
    byte[] getJedisKey()
    {
        return getId().getBytes(UTF_8);
    }

    /**
     * Takes the currentValue, transforms it into a Map, than uses the attributes from the delegate NoSqlSession to update the values. The Map that is the
     * result of that operation is transformed into byte[] and put under the attributes key
     * 
     * @param currentValue
     *            as gotten from the data store
     */
    void updateTheseAttributes(byte[] currentValue)
    {
        persistentValue.put(MapKey.ATTRIBUTES.getKey(),currentValue);
        HashMap<String, Object> intermediate = bytesToMap(currentValue);
        boolean saveAllAttributes = newEntry || manager.isSaveAllAttributes();
        Set<String> names = saveAllAttributes?delegate.getNames():delegate.takeDirty();
        for (String name : names)
        {
            Object value = delegate.getAttribute(name);
            if (value == null)
            {
                intermediate.remove(name);
            }
            else if (!(value instanceof Serializable))
            {
                throw new IllegalArgumentException("All values to be stored must be Serializable " + value + " is not");
            }
            else
            {
                intermediate.put(name,value);
            }
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); ObjectOutputStream oos = new ObjectOutputStream(baos))
        {
            oos.writeObject(intermediate);
            persistentValue.put(MapKey.ATTRIBUTES.getKey(),baos.toByteArray());
        }
        catch (IOException e)
        {
            LOGGER.warn("Error while doing calculateAttributeUpdates");
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @return the value as it can be written to Redis. Note that if a key is not present, Redis will not overwrite the current value (i.e. will not delete it).
     *         Deleting should be a conscious action. This is why the attributes are written as one value. Control over them is exerted in
     *         {@link #updateTheseAttributes(byte[])}
     */
    Map<byte[], byte[]> getJedisValue()
    {
        return persistentValue;
    }

    /**
     * @param session
     *            must have a clusterId
     * @return a session that should only be used to save the invalidation values
     */
    static JedisSession invalidationSession(NoSqlSession session)
    {
        return new JedisSession(session.getClusterId());
    }

    /**
     * @return the delegate NoSqlSession
     */
    public NoSqlSession getNoSqlSession()
    {
        return delegate;
    }

}
