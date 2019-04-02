//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import org.apache.commons.pool.ObjectPool;
import org.apache.commons.pool.PoolableObjectFactory;
import org.apache.commons.pool.impl.GenericObjectPool;

import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class CompressionPool
{
    private CompressionPool()
    {
    }

    public static ObjectPool<Inflater> limitedInflaterPool(int capacity, boolean nowrap)
    {
        return new GenericObjectPool<>(new InflaterPoolFactory(nowrap), blockingBorrowPoolConfig(capacity));
    }

    public static ObjectPool<Inflater> growingInflaterPool(boolean nowrap)
    {
        return new GenericObjectPool<>(new InflaterPoolFactory(nowrap), defaultPoolConfig());
    }

    public static ObjectPool<Deflater> limitedDeflaterPool(int capacity, int compressionLevel, boolean nowrap)
    {
        return new GenericObjectPool<>(new DeflaterPoolFactory(compressionLevel, nowrap),
                blockingBorrowPoolConfig(capacity));
    }

    public static ObjectPool<Deflater> growingDeflaterPool(int compressionLevel, boolean nowrap)
    {
        return new GenericObjectPool<>(new DeflaterPoolFactory(compressionLevel, nowrap),
                defaultPoolConfig());
    }

    private static GenericObjectPool.Config blockingBorrowPoolConfig(int capacity)
    {
        GenericObjectPool.Config config = new GenericObjectPool.Config();

        config.maxActive = capacity;
        config.whenExhaustedAction = GenericObjectPool.WHEN_EXHAUSTED_BLOCK;
        config.minEvictableIdleTimeMillis = TimeUnit.SECONDS.toMillis(1);
        config.timeBetweenEvictionRunsMillis = TimeUnit.SECONDS.toMillis(5);
        config.testOnBorrow = true;
        config.testOnReturn = false;
        config.testWhileIdle = false;

        return config;
    }

    private static GenericObjectPool.Config defaultPoolConfig()
    {
        GenericObjectPool.Config config = new GenericObjectPool.Config();

        config.whenExhaustedAction = GenericObjectPool.WHEN_EXHAUSTED_GROW;
        config.minEvictableIdleTimeMillis = TimeUnit.SECONDS.toMillis(5);
        config.timeBetweenEvictionRunsMillis = TimeUnit.SECONDS.toMillis(30);
        config.testOnBorrow = true;
        config.testOnReturn = false;
        config.testWhileIdle = false;

        return config;
    }

    private static class DeflaterPoolFactory implements PoolableObjectFactory<Deflater>
    {
        private final int compressionLevel;
        private final boolean nowrap;

        DeflaterPoolFactory(int compressionLevel, boolean nowrap)
        {
            this.compressionLevel = compressionLevel;
            this.nowrap = nowrap;
        }

        @Override
        public Deflater makeObject() throws Exception
        {
            return new Deflater(compressionLevel, nowrap);
        }

        @Override
        public void destroyObject(Deflater obj) throws Exception
        {
            obj.reset();
            obj.end();
        }

        @Override
        public boolean validateObject(Deflater obj)
        {
            return !obj.finished();
        }

        @Override
        public void activateObject(Deflater obj) throws Exception
        {
        }

        @Override
        public void passivateObject(Deflater obj) throws Exception
        {
            obj.reset();
        }
    }


    private static class InflaterPoolFactory implements PoolableObjectFactory<Inflater>
    {
        private final boolean nowrap;

        InflaterPoolFactory(boolean nowrap)
        {
            this.nowrap = nowrap;
        }

        @Override
        public Inflater makeObject() throws Exception
        {
            return new Inflater(nowrap);
        }

        @Override
        public void destroyObject(Inflater obj) throws Exception
        {
            obj.reset();
            obj.end();
        }

        @Override
        public boolean validateObject(Inflater obj)
        {
            return !obj.finished();
        }

        @Override
        public void activateObject(Inflater obj) throws Exception
        {
        }

        @Override
        public void passivateObject(Inflater obj) throws Exception
        {
            obj.reset();
        }
    }


}
