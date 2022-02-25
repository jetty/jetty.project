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

package org.eclipse.jetty.rewrite.handler;

import org.eclipse.jetty.http.HttpURI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class VirtualHostRuleContainerTest extends AbstractRuleTestCase
{
    private RewriteHandler _handler;
    private RewritePatternRule _rule;
    private RewritePatternRule _fooRule;
    private VirtualHostRuleContainer _fooContainerRule;

    @BeforeEach
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
        _fooContainerRule.setVirtualHosts(new String[]{"foo.com"});
        _fooContainerRule.setRules(new Rule[]{_fooRule});

        start(false);
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/cheese/bar"));

        _handler.setServer(_server);
        _handler.start();
    }

    @Test
    public void testArbitraryHost() throws Exception
    {
        _request.setHttpURI(HttpURI.build(_request.getRequestURI()).authority("cheese.com", 0));
        _handler.setRules(new Rule[]{_rule, _fooContainerRule});
        handleRequest();
        assertEquals("/rule/bar", _request.getRequestURI(), "{_rule, _fooContainerRule, Host: cheese.com}: applied _rule");
    }

    @Test
    public void testVirtualHost() throws Exception
    {
        _request.setHttpURI(HttpURI.build(_request.getRequestURI()).authority("foo.com", 0));
        _handler.setRules(new Rule[]{_fooContainerRule});
        handleRequest();
        assertEquals("/cheese/fooRule", _request.getRequestURI(), "{_fooContainerRule, Host: foo.com}: applied _fooRule");
    }

    @Test
    public void testCascadingRules() throws Exception
    {
        _request.setHttpURI(HttpURI.build(_request.getRequestURI()).authority("foo.com", 0));
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/cheese/bar"));

        _rule.setTerminating(false);
        _fooRule.setTerminating(false);
        _fooContainerRule.setTerminating(false);

        _handler.setRules(new Rule[]{_rule, _fooContainerRule});
        handleRequest();
        assertEquals("/rule/bar", _request.getRequestURI(), "{_rule, _fooContainerRule}: applied _rule, didn't match _fooRule");

        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/cheese/bar"));
        _handler.setRules(new Rule[]{_fooContainerRule, _rule});
        handleRequest();
        assertEquals("/rule/fooRule", _request.getRequestURI(), "{_fooContainerRule, _rule}: applied _fooRule, _rule");

        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/cheese/bar"));
        _fooRule.setTerminating(true);
        handleRequest();
        assertEquals("/rule/fooRule", _request.getRequestURI(), "{_fooContainerRule, _rule}: (_fooRule is terminating); applied _fooRule, _rule");

        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/cheese/bar"));
        _fooRule.setTerminating(false);
        _fooContainerRule.setTerminating(true);
        handleRequest();
        assertEquals("/cheese/fooRule", _request.getRequestURI(), "{_fooContainerRule, _rule}: (_fooContainerRule is terminating); applied _fooRule, terminated before _rule");
    }

    @Test
    public void testCaseInsensitiveHostname() throws Exception
    {
        _request.setHttpURI(HttpURI.build(_request.getRequestURI()).authority("Foo.com", 0));
        _fooContainerRule.setVirtualHosts(new String[]{"foo.com"});

        _handler.setRules(new Rule[]{_fooContainerRule});
        handleRequest();
        assertEquals("/cheese/fooRule", _request.getRequestURI(), "Foo.com and foo.com are equivalent");
    }

    @Test
    public void testEmptyVirtualHost() throws Exception
    {
        _request.setHttpURI(HttpURI.build(_request.getRequestURI()).authority("cheese.com", 0));

        _handler.setRules(new Rule[]{_fooContainerRule});
        _fooContainerRule.setVirtualHosts(null);
        handleRequest();
        assertEquals("/cheese/fooRule", _request.getRequestURI(), "{_fooContainerRule: virtual hosts array is null, Host: cheese.com}: apply _fooRule");

        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/cheese/bar"));
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/cheese/bar"));
        _fooContainerRule.setVirtualHosts(new String[]{});
        handleRequest();
        assertEquals("/cheese/fooRule", _request.getRequestURI(), "{_fooContainerRule: virtual hosts array is empty, Host: cheese.com}: apply _fooRule");

        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/cheese/bar"));
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/cheese/bar"));
        _fooContainerRule.setVirtualHosts(new String[]{null});
        handleRequest();
        assertEquals("/cheese/fooRule", _request.getRequestURI(), "{_fooContainerRule: virtual host is null, Host: cheese.com}: apply _fooRule");
    }

    @Test
    public void testMultipleVirtualHosts() throws Exception
    {
        _request.setHttpURI(HttpURI.build(_request.getRequestURI()).authority("foo.com", 0));
        _handler.setRules(new Rule[]{_fooContainerRule});

        _fooContainerRule.setVirtualHosts(new String[]{"cheese.com"});
        handleRequest();
        assertEquals("/cheese/bar", _request.getRequestURI(), "{_fooContainerRule: vhosts[cheese.com], Host: foo.com}: no effect");

        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/cheese/bar"));
        _fooContainerRule.addVirtualHost("foo.com");
        handleRequest();
        assertEquals("/cheese/fooRule", _request.getRequestURI(), "{_fooContainerRule: vhosts[cheese.com, foo.com], Host: foo.com}: apply _fooRule");
    }

    @Test
    public void testWildcardVirtualHosts() throws Exception
    {
        checkWildcardHost(true, null, new String[]{"foo.com", ".foo.com", "vhost.foo.com"});
        checkWildcardHost(true, new String[]{null}, new String[]{"foo.com", ".foo.com", "vhost.foo.com"});

        checkWildcardHost(true, new String[]{"foo.com", "*.foo.com"}, new String[]{"foo.com", ".foo.com", "vhost.foo.com"});
        checkWildcardHost(false, new String[]{"foo.com", "*.foo.com"}, new String[]{
            "badfoo.com", ".badfoo.com", "vhost.badfoo.com"
        });

        checkWildcardHost(false, new String[]{"*."}, new String[]{"anything.anything"});

        checkWildcardHost(true, new String[]{"*.foo.com"}, new String[]{"vhost.foo.com", ".foo.com"});
        checkWildcardHost(false, new String[]{"*.foo.com"}, new String[]{"vhost.www.foo.com", "foo.com", "www.vhost.foo.com"});

        checkWildcardHost(true, new String[]{"*.sub.foo.com"}, new String[]{"vhost.sub.foo.com", ".sub.foo.com"});
        checkWildcardHost(false, new String[]{"*.sub.foo.com"}, new String[]{".foo.com", "sub.foo.com", "vhost.foo.com"});

        checkWildcardHost(false, new String[]{"foo.*.com", "foo.com.*"}, new String[]{
            "foo.vhost.com", "foo.com.vhost", "foo.com"
        });
    }

    private void checkWildcardHost(boolean succeed, String[] ruleHosts, String[] requestHosts) throws Exception
    {
        _fooContainerRule.setVirtualHosts(ruleHosts);
        _handler.setRules(new Rule[]{_fooContainerRule});

        for (String host : requestHosts)
        {
            _request.setHttpURI(HttpURI.build(_request.getRequestURI()).authority(host, 0));
            _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/cheese/bar"));
            handleRequest();
            if (succeed)
                assertEquals("/cheese/fooRule", _request.getRequestURI(), "{_fooContainerRule, Host: " + host + "}: should apply _fooRule");
            else
                assertEquals("/cheese/bar", _request.getRequestURI(), "{_fooContainerRule, Host: " + host + "}: should not apply _fooRule");
        }
    }

    private void handleRequest() throws Exception
    {
        _handler.handle("/cheese/bar", _request, _request, _response);
    }
}
