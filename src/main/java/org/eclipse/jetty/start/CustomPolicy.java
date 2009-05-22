package org.eclipse.jetty.start;
//========================================================================
//Copyright (c) 2003-2009 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at 
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses. 
//========================================================================

import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Policy;
import java.security.ProtectionDomain;
import java.util.Set;

/**
 * CustomPolicy is initialized with a set file policy files which it parses for 
 * policy information the same as any other PolicyFile implementation and proxies
 * the system policy implementation if the local ones do not match
 * 
 * TODO wire in a mechanism to parse the policy files, can't believe there is no
 * general way to do this..boggle, as it stands right now this will fail to load
 * when using custom security policies as simply enabling the SecurityManager
 * like we are kills normal jetty startup because it accesses a host of properties
 * that need to be enabled in the jetty.policy file.
 * 
 * Thinking we should pull a default policy file from the start.jar next to the
 * start.config file and also allow for a default one to be specified in 
 * resources/jetty.policy of the distribution.
 */
public class CustomPolicy extends Policy 
{
	private static final Policy _originalPolicy = Policy.getPolicy();
	
	private Set<String> _policies;
		
	public CustomPolicy( Set<String> policies )
	{
		_policies = policies;
	}
	
	public PermissionCollection getPermissions(ProtectionDomain domain) 
	{
		System.out.println ("CustomPolicy:getPermissions:" + domain );
		return _originalPolicy.getPermissions(domain);
	}

	public boolean implies(ProtectionDomain domain, Permission permission) 
	{
		
		System.out.println ("CustomPolicy:implies:" );
		return _originalPolicy.implies(domain, permission);
	}

	public PermissionCollection getPermissions(CodeSource codesource) 
	{
		System.out.println ("CustomPolicy:" + codesource );
		return _originalPolicy.getPermissions(codesource);
	}

	public void refresh() 
	{
		_originalPolicy.refresh();
	}	
	
}
