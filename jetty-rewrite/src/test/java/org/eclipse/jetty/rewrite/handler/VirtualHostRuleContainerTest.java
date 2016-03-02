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

package org.eclipse.jetty.rewrite.handler;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

public class VirtualHostRuleContainerTest extends AbstractRuleTestCase
{
    private RewriteHandler _handler;
    private RewritePatternRule _rule;
    private RewritePatternRule _fooRule;
    private VirtualHostRuleContainer _fooContainerRule;

    @Before
    public void init() throws Exception
    {
        _handler = new RewriteHandler();
        _handler.setRewriteRequestURI(true);

        _rule = new RewritePatternRule();
        _rule.setPattern("/cheese/*");
        _rule.setReplacement("/rule");

        _fooRule = new RewritePatternRule();
        _fooRule.setPattern("/cheese/bar/*");
        _fooRule.setReplacement("/cheese/fooRule");

        _fooContainerRule = new VirtualHostRuleContainer();
        _fooContainerRule.setVirtualHosts(new String[] {"foo.com"});
        _fooContainerRule.setRules(new Rule[] { _fooRule });

        start(false);
        _request.setURIPathQuery("/cheese/bar");
        
        _handler.setServer(_server);
        _handler.start();
    }

    @Test
    public void testArbitraryHost() throws Exception
    {
        _request.setAuthority("cheese.com",0);
        _handler.setRules(new Rule[] { _rule, _fooContainerRule });
        handleRequest();
        assertEquals("{_rule, _fooContainerRule, Host: cheese.com}: applied _rule", "/rule/bar", _request.getRequestURI());
    }

    @Test
    public void testVirtualHost() throws Exception
    {
        _request.setAuthority("foo.com",0);
        _handler.setRules(new Rule[] { _fooContainerRule });
        handleRequest();
        assertEquals("{_fooContainerRule, Host: foo.com}: applied _fooRule", "/cheese/fooRule", _request.getRequestURI());
    }

    @Test
    public void testCascadingRules() throws Exception
    {
        _request.setAuthority("foo.com",0);
        _request.setURIPathQuery("/cheese/bar");

        _rule.setTerminating(false);
        _fooRule.setTerminating(false);
        _fooContainerRule.setTerminating(false);

        _handler.setRules(new Rule[]{_rule, _fooContainerRule});
        handleRequest();
        assertEquals("{_rule, _fooContainerRule}: applied _rule, didn't match _fooRule", "/rule/bar", _request.getRequestURI());

        _request.setURIPathQuery("/cheese/bar");
        _handler.setRules(new Rule[] { _fooContainerRule, _rule });
        handleRequest();
        assertEquals("{_fooContainerRule, _rule}: applied _fooRule, _rule","/rule/fooRule", _request.getRequestURI());

        _request.setURIPathQuery("/cheese/bar");
        _fooRule.setTerminating(true);
        handleRequest();
        assertEquals("{_fooContainerRule, _rule}: (_fooRule is terminating); applied _fooRule, _rule", "/rule/fooRule", _request.getRequestURI());

        _request.setURIPathQuery("/cheese/bar");
        _fooRule.setTerminating(false);
        _fooContainerRule.setTerminating(true);
        handleRequest();
        assertEquals("{_fooContainerRule, _rule}: (_fooContainerRule is terminating); applied _fooRule, terminated before _rule", "/cheese/fooRule", _request.getRequestURI());
    }

    @Test
    public void testCaseInsensitiveHostname() throws Exception
    {
        _request.setAuthority("Foo.com",0);
        _fooContainerRule.setVirtualHosts(new String[] {"foo.com"} );

        _handler.setRules(new Rule[]{ _fooContainerRule });
        handleRequest();
        assertEquals("Foo.com and foo.com are equivalent", "/cheese/fooRule", _request.getRequestURI());
    }

    @Test
    public void testEmptyVirtualHost() throws Exception
    {
        _request.setAuthority("cheese.com",0);

        _handler.setRules(new Rule[] { _fooContainerRule });
        _fooContainerRule.setVirtualHosts(null);
        handleRequest();
        assertEquals("{_fooContainerRule: virtual hosts array is null, Host: cheese.com}: apply _fooRule", "/cheese/fooRule", _request.getRequestURI());

        _request.setURIPathQuery("/cheese/bar");
        _request.setURIPathQuery("/cheese/bar");
        _fooContainerRule.setVirtualHosts(new String[] {});
        handleRequest();
        assertEquals("{_fooContainerRule: virtual hosts array is empty, Host: cheese.com}: apply _fooRule", "/cheese/fooRule", _request.getRequestURI());

        _request.setURIPathQuery("/cheese/bar");
        _request.setURIPathQuery("/cheese/bar");
        _fooContainerRule.setVirtualHosts(new String[] {null});
        handleRequest();
        assertEquals("{_fooContainerRule: virtual host is null, Host: cheese.com}: apply _fooRule", "/cheese/fooRule", _request.getRequestURI());

    }

    @Test
    public void testMultipleVirtualHosts() throws Exception
    {
        _request.setAuthority("foo.com",0);
        _handler.setRules(new Rule[] {_fooContainerRule });

        _fooContainerRule.setVirtualHosts(new String[]{ "cheese.com" });
        handleRequest();
        assertEquals("{_fooContainerRule: vhosts[cheese.com], Host: foo.com}: no effect", "/cheese/bar", _request.getRequestURI());

        _request.setURIPathQuery("/cheese/bar");
        _fooContainerRule.addVirtualHost( "foo.com" );
        handleRequest();
        assertEquals("{_fooContainerRule: vhosts[cheese.com, foo.com], Host: foo.com}: apply _fooRule", "/cheese/fooRule", _request.getRequestURI());
    }

    @Test
    public void testWildcardVirtualHosts() throws Exception
    {
        checkWildcardHost(true,null,new String[] {"foo.com", ".foo.com", "vhost.foo.com"});
        checkWildcardHost(true,new String[] {null},new String[] {"foo.com", ".foo.com", "vhost.foo.com"});

        checkWildcardHost(true,new String[] {"foo.com", "*.foo.com"}, new String[] {"foo.com", ".foo.com", "vhost.foo.com"});
        checkWildcardHost(false,new String[] {"foo.com", "*.foo.com"}, new String[] {"badfoo.com", ".badfoo.com", "vhost.badfoo.com"});

        checkWildcardHost(false,new String[] {"*."}, new String[] {"anything.anything"});

        checkWildcardHost(true,new String[] {"*.foo.com"}, new String[] {"vhost.foo.com", ".foo.com"});
        checkWildcardHost(false,new String[] {"*.foo.com"}, new String[] {"vhost.www.foo.com", "foo.com", "www.vhost.foo.com"});

        checkWildcardHost(true,new String[] {"*.sub.foo.com"}, new String[] {"vhost.sub.foo.com", ".sub.foo.com"});
        checkWildcardHost(false,new String[] {"*.sub.foo.com"}, new String[] {".foo.com", "sub.foo.com", "vhost.foo.com"});

        checkWildcardHost(false,new String[] {"foo.*.com","foo.com.*"}, new String[] {"foo.vhost.com", "foo.com.vhost", "foo.com"});
    }

    private void checkWildcardHost(boolean succeed, String[] ruleHosts, String[] requestHosts) throws Exception
    {
        _fooContainerRule.setVirtualHosts(ruleHosts);
        _handler.setRules(new Rule[] { _fooContainerRule });

        for(String host: requestHosts)
        {
            _request.setAuthority(host,0);
            _request.setURIPathQuery("/cheese/bar");
            handleRequest();
            if(succeed)
                assertEquals("{_fooContainerRule, Host: "+host+"}: should apply _fooRule", "/cheese/fooRule", _request.getRequestURI());
            else
                assertEquals("{_fooContainerRule, Host: "+host+"}: should not apply _fooRule", "/cheese/bar", _request.getRequestURI());
        }
    }

    private void handleRequest() throws Exception
    {
        _handler.handle("/cheese/bar", _request, _request, _response);
    }
}
