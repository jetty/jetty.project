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

package org.eclipse.jetty.jndi.java;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Hashtable;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.LinkRef;
import javax.naming.Name;
import javax.naming.NameAlreadyBoundException;
import javax.naming.NameClassPair;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.StringRefAddr;
import javax.naming.spi.ObjectFactory;

import org.eclipse.jetty.jndi.NamingContext;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.Callback;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 *
 */
public class TestJNDI
{
    private static final Logger LOG = LoggerFactory.getLogger(TestJNDI.class);

    static
    {
        // NamingUtil.__log.setDebugEnabled(true);
    }

    public static class MyObjectFactory implements ObjectFactory
    {
        public static String myString = "xxx";

        @Override
        public Object getObjectInstance(Object obj, Name name, Context nameCtx, Hashtable environment) throws Exception
        {
            return myString;
        }
    }

    @Test
    public void testThreadContextClassloaderAndCurrentContext()
        throws Exception
    {
        //create a jetty context, and start it so that its classloader it created
        //and it is the current context
        ClassLoader currentLoader = Thread.currentThread().getContextClassLoader();
        ContextHandler ch1 = new ContextHandler("/ch");
        URLClassLoader chLoader = new URLClassLoader(new URL[0], currentLoader);
        ch1.setClassLoader(chLoader);
        Server server = new Server();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);
        contexts.addHandler(ch1);

        ch1.setHandler(new Handler.Abstract()
        {
            private Context comp;
            private Object testObj = new Object();

            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                return false;
            }

            @Override
            protected void doStart() throws Exception
            {
                super.doStart();
                try
                {
                    InitialContext initCtx = new InitialContext();
                    Context java = (Context)initCtx.lookup("java:");
                    assertNotNull(java);
                    comp = (Context)initCtx.lookup("java:comp");
                    assertNotNull(comp);
                    Context env = ((Context)comp).createSubcontext("env");
                    assertNotNull(env);
                    env.bind("ch", testObj);
                }
                catch (Exception e)
                {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            protected void doStop() throws Exception
            {
                super.doStop();
                try
                {
                    assertNotNull(comp);
                    assertEquals(testObj, comp.lookup("env/ch"));
                    comp.destroySubcontext("env");
                }
                catch (Exception e)
                {
                    throw new IllegalStateException(e);
                }
            }
        });

        //Create another one
        ContextHandler ch2 = new ContextHandler("/ch2");
        URLClassLoader ch2Loader = new URLClassLoader(new URL[0], currentLoader);
        ch2.setClassLoader(ch2Loader);
        contexts.addHandler(ch2);
        ch2.setHandler(new Handler.Abstract()
        {
            private Context comp;
            private Object testObj = new Object();

            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                return false;
            }

            @Override
            protected void doStart() throws Exception
            {
                super.doStart();
                try
                {
                    InitialContext initCtx = new InitialContext();
                    comp = (Context)initCtx.lookup("java:comp");
                    assertNotNull(comp);

                    //another context's bindings should not be visible
                    Context env = ((Context)comp).createSubcontext("env");
                    try
                    {
                        env.lookup("ch");
                        fail("java:comp/env visible from another context!");
                    }
                    catch (NameNotFoundException e)
                    {
                        //expected
                    }
                }
                catch (Exception e)
                {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            protected void doStop() throws Exception
            {
                super.doStop();
                try
                {
                    assertNotNull(comp);
                    comp.destroySubcontext("env");
                }
                catch (Exception e)
                {
                    throw new IllegalStateException(e);
                }
            }
        });

        try
        {
            //Starting the context makes it current and creates a classloader for it
            ch1.start();
            //make the new context the current one
            ch2.start();
        }
        finally
        {
            ch1.stop();
            ch2.stop();
            Thread.currentThread().setContextClassLoader(currentLoader);
        }
    }

    @Test
    public void testJavaNameParsing() throws Exception
    {
        Thread currentThread = Thread.currentThread();
        ClassLoader currentLoader = currentThread.getContextClassLoader();
        ClassLoader childLoader1 = new URLClassLoader(new URL[0], currentLoader);

        //set the current thread's classloader
        currentThread.setContextClassLoader(childLoader1);

        try
        {
            InitialContext initCtx = new InitialContext();
            Context sub0 = (Context)initCtx.lookup("java:");

            if (LOG.isDebugEnabled())
                LOG.debug("------ Looked up java: --------------");

            Name n = sub0.getNameParser("").parse("/red/green/");

            if (LOG.isDebugEnabled())
                LOG.debug("get(0)=" + n.get(0));
            if (LOG.isDebugEnabled())
                LOG.debug("getPrefix(1)=" + n.getPrefix(1));
            n = n.getSuffix(1);
            if (LOG.isDebugEnabled())
                LOG.debug("getSuffix(1)=" + n);
            if (LOG.isDebugEnabled())
                LOG.debug("get(0)=" + n.get(0));
            if (LOG.isDebugEnabled())
                LOG.debug("getPrefix(1)=" + n.getPrefix(1));
            n = n.getSuffix(1);
            if (LOG.isDebugEnabled())
                LOG.debug("getSuffix(1)=" + n);
            if (LOG.isDebugEnabled())
                LOG.debug("get(0)=" + n.get(0));
            if (LOG.isDebugEnabled())
                LOG.debug("getPrefix(1)=" + n.getPrefix(1));
            n = n.getSuffix(1);
            if (LOG.isDebugEnabled())
                LOG.debug("getSuffix(1)=" + n);

            n = sub0.getNameParser("").parse("pink/purple/");
            if (LOG.isDebugEnabled())
                LOG.debug("get(0)=" + n.get(0));
            if (LOG.isDebugEnabled())
                LOG.debug("getPrefix(1)=" + n.getPrefix(1));
            n = n.getSuffix(1);
            if (LOG.isDebugEnabled())
                LOG.debug("getSuffix(1)=" + n);
            if (LOG.isDebugEnabled())
                LOG.debug("get(0)=" + n.get(0));
            if (LOG.isDebugEnabled())
                LOG.debug("getPrefix(1)=" + n.getPrefix(1));

            NamingContext ncontext = (NamingContext)sub0;

            Name nn = ncontext.toCanonicalName(ncontext.getNameParser("").parse("/yellow/blue/"));
            LOG.debug(nn.toString());
            assertEquals(2, nn.size());

            nn = ncontext.toCanonicalName(ncontext.getNameParser("").parse("/yellow/blue"));
            LOG.debug(nn.toString());
            assertEquals(2, nn.size());

            nn = ncontext.toCanonicalName(ncontext.getNameParser("").parse("/"));
            if (LOG.isDebugEnabled())
                LOG.debug("/ parses as: " + nn + " with size=" + nn.size());
            LOG.debug(nn.toString());
            assertEquals(1, nn.size());

            nn = ncontext.toCanonicalName(ncontext.getNameParser("").parse(""));
            LOG.debug(nn.toString());
            assertEquals(0, nn.size());

            Context fee = ncontext.createSubcontext("fee");
            fee.bind("fi", "88");
            assertEquals("88", initCtx.lookup("java:/fee/fi"));
            assertEquals("88", initCtx.lookup("java:/fee/fi/"));
            assertTrue(initCtx.lookup("java:/fee/") instanceof javax.naming.Context);
        }
        finally
        {
            InitialContext ic = new InitialContext();
            Context java = (Context)ic.lookup("java:");
            java.destroySubcontext("fee");
            currentThread.setContextClassLoader(currentLoader);
        }
    }

    @Test
    public void testIt() throws Exception
    {
        //set up some classloaders
        Thread currentThread = Thread.currentThread();
        ClassLoader currentLoader = currentThread.getContextClassLoader();
        ClassLoader childLoader1 = new URLClassLoader(new URL[0], currentLoader);
        ClassLoader childLoader2 = new URLClassLoader(new URL[0], currentLoader);
        InitialContext initCtx = null;
        try
        {

            //Uncomment to aid with debug
            /*
            javaRootURLContext.getRoot().addListener(new NamingContext.Listener()
            {
                public void unbind(NamingContext ctx, Binding binding)
                {
                    System.err.println("java unbind "+binding+" from "+ctx.getName());
                }
                
                public Binding bind(NamingContext ctx, Binding binding)
                {
                    System.err.println("java bind "+binding+" to "+ctx.getName());
                    return binding;
                }
            });
            
            localContextRoot.getRoot().addListener(new NamingContext.Listener()
            {
                public void unbind(NamingContext ctx, Binding binding)
                {
                    System.err.println("local unbind "+binding+" from "+ctx.getName());
                }
                
                public Binding bind(NamingContext ctx, Binding binding)
                {
                    System.err.println("local bind "+binding+" to "+ctx.getName());
                    return binding;
                }
            });
            */

            //Set up the tccl before doing any jndi operations
            currentThread.setContextClassLoader(childLoader1);
            initCtx = new InitialContext();

            //Test we can lookup the root java: naming tree
            Context sub0 = (Context)initCtx.lookup("java:");
            assertNotNull(sub0);

            //Test that we cannot bind java:comp as it should
            //already be bound 
            try
            {
                Context sub1 = sub0.createSubcontext("comp");
                fail("Comp should already be bound");
            }
            catch (NameAlreadyBoundException e)
            {
                //expected exception
            }

            //check bindings at comp
            Context sub1 = (Context)initCtx.lookup("java:comp");
            assertNotNull(sub1);

            Context sub2 = sub1.createSubcontext("env");
            assertNotNull(sub2);

            initCtx.bind("java:comp/env/rubbish", "abc");
            assertEquals("abc", initCtx.lookup("java:comp/env/rubbish"));

            //check binding LinkRefs
            LinkRef link = new LinkRef("java:comp/env/rubbish");
            initCtx.bind("java:comp/env/poubelle", link);
            assertEquals("abc", initCtx.lookup("java:comp/env/poubelle"));

            //check binding References
            StringRefAddr addr = new StringRefAddr("blah", "myReferenceable");
            Reference ref = new Reference(java.lang.String.class.getName(),
                addr,
                MyObjectFactory.class.getName(),
                null);

            initCtx.bind("java:comp/env/quatsch", ref);
            assertEquals(MyObjectFactory.myString, initCtx.lookup("java:comp/env/quatsch"));

            //test binding something at java:
            Context sub3 = initCtx.createSubcontext("java:zero");
            initCtx.bind("java:zero/one", "ONE");
            assertEquals("ONE", initCtx.lookup("java:zero/one"));

            //change the current thread's classloader to check distinct naming
            currentThread.setContextClassLoader(childLoader2);

            Context otherSub1 = (Context)initCtx.lookup("java:comp");
            assertTrue(!(sub1 == otherSub1));
            try
            {
                initCtx.lookup("java:comp/env/rubbish");
                fail("env should not exist for this classloader");
            }
            catch (NameNotFoundException e)
            {
                //expected
            }

            //put the thread's classloader back
            currentThread.setContextClassLoader(childLoader1);

            //test rebind with existing binding
            initCtx.rebind("java:comp/env/rubbish", "xyz");
            assertEquals("xyz", initCtx.lookup("java:comp/env/rubbish"));

            //test rebind with no existing binding
            initCtx.rebind("java:comp/env/mullheim", "hij");
            assertEquals("hij", initCtx.lookup("java:comp/env/mullheim"));

            //test that the other bindings are already there
            assertEquals("xyz", initCtx.lookup("java:comp/env/poubelle"));

            //test java:/comp/env/stuff
            assertEquals("xyz", initCtx.lookup("java:/comp/env/poubelle/"));

            //test list Names
            NamingEnumeration nenum = initCtx.list("java:comp/env");
            HashMap results = new HashMap();
            while (nenum.hasMore())
            {
                NameClassPair ncp = (NameClassPair)nenum.next();
                results.put(ncp.getName(), ncp.getClassName());
            }

            assertEquals(4, results.size());

            assertEquals("java.lang.String", results.get("rubbish"));
            assertEquals("javax.naming.LinkRef", results.get("poubelle"));
            assertEquals("java.lang.String", results.get("mullheim"));
            assertEquals("javax.naming.Reference", results.get("quatsch"));

            //test list Bindings
            NamingEnumeration benum = initCtx.list("java:comp/env");
            assertEquals(4, results.size());

            //test NameInNamespace
            assertEquals("comp/env", sub2.getNameInNamespace());

            //test close does nothing
            Context closeCtx = (Context)initCtx.lookup("java:comp/env");
            closeCtx.close();

            //test what happens when you close an initial context
            InitialContext closeInit = new InitialContext();
            closeInit.close();

            //check locking the context
            Context ectx = (Context)initCtx.lookup("java:comp");
            //make a deep structure lie ttt/ttt2 for later use
            Context ttt = ectx.createSubcontext("ttt");
            ttt.createSubcontext("ttt2");
            //bind a value
            ectx.bind("crud", "xxx");
            //lock
            ectx.addToEnvironment("org.eclipse.jetty.jndi.lock", "TRUE");
            //check we can't get the lock
            assertFalse(ectx.getEnvironment().containsKey("org.eclipse.jetty.jndi.lock"));
            //check once locked we can still do lookups
            assertEquals("xxx", initCtx.lookup("java:comp/crud"));
            assertNotNull(initCtx.lookup("java:comp/ttt/ttt2"));

            //test trying to bind into java:comp after lock
            InitialContext zzz = null;
            try
            {
                zzz = new InitialContext();

                ((Context)zzz.lookup("java:comp")).bind("crud2", "xxx2");
                fail("Should not be able to write to locked context");
            }
            catch (NamingException e)
            {
                assertThat(e.getMessage(), Matchers.containsString("immutable"));
            }
            finally
            {
                zzz.close();
            }

            //test trying to bind into a deep structure inside java:comp after lock
            try
            {
                zzz = new InitialContext();

                //TODO test deep locking
                //  ((Context)zzz.lookup("java:comp/ttt/ttt2")).bind("zzz2", "zzz2");
                // fail("Should not be able to write to locked context");
                ((Context)zzz.lookup("java:comp")).bind("foo", "bar");
                fail("Should not be able to write to locked context");
            }
            catch (NamingException e)
            {
                assertThat(e.getMessage(), Matchers.containsString("immutable"));
            }
            finally
            {
                zzz.close();
            }
        }
        finally
        {
            //make some effort to clean up
            initCtx.close();
            InitialContext ic = new InitialContext();
            Context java = (Context)ic.lookup("java:");
            java.destroySubcontext("zero");
            java.destroySubcontext("fee");
            currentThread.setContextClassLoader(childLoader1);
            Context comp = (Context)ic.lookup("java:comp");
            comp.addToEnvironment("org.eclipse.jetty.jndi.unlock", "TRUE");
            comp.destroySubcontext("env");
            comp.unbind("crud");
            comp.unbind("crud2");
            currentThread.setContextClassLoader(currentLoader);
        }
    }
}
