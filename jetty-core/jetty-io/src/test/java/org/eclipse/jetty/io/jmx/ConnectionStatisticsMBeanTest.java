//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io.jmx;

import java.util.Collection;
import java.util.concurrent.TimeoutException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.io.EndPoint;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class ConnectionStatisticsMBeanTest
{
    @Test
    public void testGetConnectionStatisticsAsTabularData() throws Exception
    {
        ConnectionStatistics statistics = new ConnectionStatistics();
        statistics.start();

        // Simulate a connection being opened and closed
        EndPoint endPoint = new ByteArrayEndPoint();
        TestConnection connection = new TestConnection(endPoint);
        connection.setBytesIn(1024);
        connection.setBytesOut(2048);
        connection.setMessagesIn(10);
        connection.setMessagesOut(20);

        statistics.onOpened(connection);
        statistics.onClosed(connection);

        // Create the MBean and get TabularData
        ConnectionStatisticsMBean mbean = new ConnectionStatisticsMBean(statistics);
        TabularData tabularData = mbean.getConnectionStatisticsAsTabularData();

        assertThat(tabularData, is(notNullValue()));
        assertThat(tabularData.size(), is(1));

        // Get the row for the test connection class
        Collection<?> values = tabularData.values();
        CompositeData row = (CompositeData)values.iterator().next();

        assertThat(row.get("name"), is(TestConnection.class.getName()));
        assertThat((Long)row.get("connectionsTotal"), is(1L));
        assertThat((Long)row.get("bytesIn"), is(1024L));
        assertThat((Long)row.get("bytesOut"), is(2048L));
        assertThat((Long)row.get("messagesIn"), is(10L));
        assertThat((Long)row.get("messagesOut"), is(20L));
        assertThat((Long)row.get("connectionDurationMax"), is(greaterThanOrEqualTo(0L)));

        statistics.stop();
    }

    @Test
    public void testGetConnectionStatisticsGroups() throws Exception
    {
        ConnectionStatistics statistics = new ConnectionStatistics();
        statistics.start();

        EndPoint endPoint = new ByteArrayEndPoint();
        TestConnection connection = new TestConnection(endPoint);
        connection.setBytesIn(512);
        connection.setBytesOut(1024);

        statistics.onOpened(connection);
        statistics.onClosed(connection);

        ConnectionStatisticsMBean mbean = new ConnectionStatisticsMBean(statistics);
        Collection<String> groups = mbean.getConnectionStatisticsGroups();

        assertThat(groups, is(notNullValue()));
        assertThat(groups.size(), is(1));

        String group = groups.iterator().next();
        assertThat(group.contains(TestConnection.class.getName()), is(true));

        statistics.stop();
    }

    // Simple test connection implementation
    private static class TestConnection implements Connection
    {
        private final EndPoint endPoint;
        private final long createdTime;
        private long bytesIn;
        private long bytesOut;
        private long messagesIn;
        private long messagesOut;

        TestConnection(EndPoint endPoint)
        {
            this.endPoint = endPoint;
            this.createdTime = System.currentTimeMillis();
        }

        void setBytesIn(long bytesIn)
        {
            this.bytesIn = bytesIn;
        }

        void setBytesOut(long bytesOut)
        {
            this.bytesOut = bytesOut;
        }

        void setMessagesIn(long messagesIn)
        {
            this.messagesIn = messagesIn;
        }

        void setMessagesOut(long messagesOut)
        {
            this.messagesOut = messagesOut;
        }

        @Override
        public void addEventListener(java.util.EventListener listener)
        {
        }

        @Override
        public void removeEventListener(java.util.EventListener listener)
        {
        }

        @Override
        public void onOpen()
        {
        }

        @Override
        public void onClose(Throwable cause)
        {
        }

        @Override
        public EndPoint getEndPoint()
        {
            return endPoint;
        }

        @Override
        public void close()
        {
        }

        @Override
        public boolean onIdleExpired(TimeoutException timeoutException)
        {
            return false;
        }

        @Override
        public long getCreatedTimeStamp()
        {
            return createdTime;
        }

        @Override
        public long getBytesIn()
        {
            return bytesIn;
        }

        @Override
        public long getBytesOut()
        {
            return bytesOut;
        }

        @Override
        public long getMessagesIn()
        {
            return messagesIn;
        }

        @Override
        public long getMessagesOut()
        {
            return messagesOut;
        }
    }
}
