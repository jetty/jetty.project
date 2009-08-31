// ========================================================================
// Copyright (c) 1999-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.security;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.http.security.Constraint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.StringMap;

/* ------------------------------------------------------------ */
/**
 * Handler to enforce SecurityConstraints. This implementation is servlet spec
 * 2.4 compliant and precomputes the constraint combinations for runtime
 * efficiency.
 * 
 */
public class ConstraintSecurityHandler extends SecurityHandler implements ConstraintAware
{
    private ConstraintMapping[] _constraintMappings;
    private Set<String> _roles;
    private PathMap _constraintMap = new PathMap();
    private boolean _strict = true;

    
    /* ------------------------------------------------------------ */
    /** Get the strict mode.
     * @return true if the security handler is running in strict mode.
     */
    public boolean isStrict()
    {
        return _strict;
    }

    /* ------------------------------------------------------------ */
    /** Set the strict mode of the security handler.
     * <p>
     * When in strict mode (the default), the full servlet specification
     * will be implemented.
     * If not in strict mode, some additional flexibility in configuration
     * is allowed:<ul>
     * <li>All users do not need to have a role defined in the deployment descriptor
     * <li>The * role in a constraint applies to ANY role rather than all roles defined in
     * the deployment descriptor.
     * </ul>
     * 
     * @param strict the strict to set
     */
    public void setStrict(boolean strict)
    {
        _strict = strict;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the contraintMappings.
     */
    public ConstraintMapping[] getConstraintMappings()
    {
        return _constraintMappings;
    }

    /* ------------------------------------------------------------ */
    public Set<String> getRoles()
    {
        return _roles;
    }

    /* ------------------------------------------------------------ */
    /**
     * Process the constraints following the combining rules in Servlet 3.0 EA
     * spec section 13.7.1 Note that much of the logic is in the RoleInfo class.
     * 
     * @param constraintMappings
     *            The contraintMappings to set, from which the set of known roles
     *            is determined.
     */
    public void setConstraintMappings(ConstraintMapping[] constraintMappings)
    {
        setConstraintMappings(constraintMappings,null);
    }
        
    /* ------------------------------------------------------------ */
    /**
     * Process the constraints following the combining rules in Servlet 3.0 EA
     * spec section 13.7.1 Note that much of the logic is in the RoleInfo class.
     * 
     * @param constraintMappings
     *            The contraintMappings to set.
     * @param roles The known roles (or null to determine them from the mappings)
     */
    public void setConstraintMappings(ConstraintMapping[] constraintMappings, Set<String> roles)
    {
        if (isStarted())
            throw new IllegalStateException("Started");
        _constraintMappings = constraintMappings;
        
        if (roles==null)
        {
            roles = new HashSet<String>();
            for (ConstraintMapping cm : constraintMappings)
            {
                String[] cmr = cm.getConstraint().getRoles();
                if (cmr!=null)
                {
                    for (String r : cmr)
                        if (!"*".equals(r))
                            roles.add(r);
                }
            }
        }
        
        this._roles = roles;

    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.security.SecurityHandler#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        _constraintMap.clear();
        if (_constraintMappings!=null)
        {
            for (ConstraintMapping mapping : _constraintMappings)
            {
                Map<String, RoleInfo> mappings = (Map<String, RoleInfo>)_constraintMap.get(mapping.getPathSpec());
                if (mappings == null)
                {
                    mappings = new StringMap();
                    _constraintMap.put(mapping.getPathSpec(),mappings);
                }
                RoleInfo allMethodsRoleInfo = mappings.get(null);
                if (allMethodsRoleInfo != null && allMethodsRoleInfo.isForbidden())
                {
                    continue;
                }
                String httpMethod = mapping.getMethod();
                RoleInfo roleInfo = mappings.get(httpMethod);
                if (roleInfo == null)
                {
                    roleInfo = new RoleInfo();
                    mappings.put(httpMethod,roleInfo);
                    if (allMethodsRoleInfo != null)
                    {
                        roleInfo.combine(allMethodsRoleInfo);
                    }
                }
                if (roleInfo.isForbidden())
                {
                    continue;
                }
                Constraint constraint = mapping.getConstraint();
                boolean forbidden = constraint.isForbidden();
                roleInfo.setForbidden(forbidden);
                if (forbidden)
                {
                    if (httpMethod == null)
                    {
                        mappings.clear();
                        mappings.put(null,roleInfo);
                    }
                }
                else
                {
                    UserDataConstraint userDataConstraint = UserDataConstraint.get(constraint.getDataConstraint());
                    roleInfo.setUserDataConstraint(userDataConstraint);

                    boolean checked = constraint.getAuthenticate();
                    roleInfo.setChecked(checked);
                    if (roleInfo.isChecked())
                    {
                        if (constraint.isAnyRole())
                        {
                            if (_strict)
                            {
                                // * means "all defined roles"
                                for (String role : _roles)
                                    roleInfo.addRole(role);
                            }
                            else
                                // * means any role
                                roleInfo.setAnyRole(true);
                        }
                        else
                        {
                            String[] newRoles = constraint.getRoles();
                            for (String role : newRoles)
                            {
                                if (_strict &&!_roles.contains(role))
                                    throw new IllegalArgumentException("Attempt to use undeclared role: " + role + ", known roles: " + _roles);
                                roleInfo.addRole(role);
                            }
                        }
                    }
                    if (httpMethod == null)
                    {
                        for (Map.Entry<String, RoleInfo> entry : mappings.entrySet())
                        {
                            if (entry.getKey() != null)
                            {
                                RoleInfo specific = entry.getValue();
                                specific.combine(roleInfo);
                            }
                        }
                    }
                }
            }
        }
        super.doStart();
    }

    protected Object prepareConstraintInfo(String pathInContext, Request request)
    {
        Map<String, RoleInfo> mappings = (Map<String, RoleInfo>)_constraintMap.match(pathInContext);

        if (mappings != null)
        {
            String httpMethod = request.getMethod();
            RoleInfo roleInfo = mappings.get(httpMethod);
            if (roleInfo == null)
                roleInfo = mappings.get(null);
            return roleInfo;
        }
       
        return null;
    }

    protected boolean checkUserDataPermissions(String pathInContext, Request request, Response response, Object constraintInfo) throws IOException
    {
        if (constraintInfo == null)
            return true;
        
        RoleInfo roleInfo = (RoleInfo)constraintInfo;
        if (roleInfo.isForbidden())
            return false;
        
        
        UserDataConstraint dataConstraint = roleInfo.getUserDataConstraint();
        if (dataConstraint == null || dataConstraint == UserDataConstraint.None)
        {
            return true;
        }
        HttpConnection connection = HttpConnection.getCurrentConnection();
        Connector connector = connection.getConnector();

        if (dataConstraint == UserDataConstraint.Integral)
        {
            if (connector.isIntegral(request))
                return true;
            if (connector.getConfidentialPort() > 0)
            {
                String url = connector.getIntegralScheme() + "://" + request.getServerName() + ":" + connector.getIntegralPort() + request.getRequestURI();
                if (request.getQueryString() != null)
                    url += "?" + request.getQueryString();
                response.setContentLength(0);
                response.sendRedirect(url);
            }
            else
                response.sendError(Response.SC_FORBIDDEN,"!Integral");

            request.setHandled(true);
            return false;
        }
        else if (dataConstraint == UserDataConstraint.Confidential)
        {
            if (connector.isConfidential(request))
                return true;

            if (connector.getConfidentialPort() > 0)
            {
                String url = connector.getConfidentialScheme() + "://" + request.getServerName() + ":" + connector.getConfidentialPort()
                        + request.getRequestURI();
                if (request.getQueryString() != null)
                    url += "?" + request.getQueryString();

                response.setContentLength(0);
                response.sendRedirect(url);
            }
            else
                response.sendError(Response.SC_FORBIDDEN,"!Confidential");
            
            request.setHandled(true);
            return false;
        }
        else
        {
            throw new IllegalArgumentException("Invalid dataConstraint value: " + dataConstraint);
        }

    }

    protected boolean isAuthMandatory(Request baseRequest, Response base_response, Object constraintInfo)
    {
        if (constraintInfo == null)
        {
            return false;
        }
        return ((RoleInfo)constraintInfo).isChecked();
    }

    protected boolean checkWebResourcePermissions(String pathInContext, Request request, Response response, Object constraintInfo, UserIdentity userIdentity)
            throws IOException
    {
        if (constraintInfo == null)
        {
            return true;
        }
        RoleInfo roleInfo = (RoleInfo)constraintInfo;

        if (!roleInfo.isChecked())
        {
            return true;
        }
        
        if (roleInfo.isAnyRole() && request.getAuthType()!=null)
            return true;
        
        String[] roles = roleInfo.getRoles();
        for (String role : roles)
        {
            if (userIdentity.isUserInRole(role, null))
                return true;
        }
        return false;
    }
    
    /* ------------------------------------------------------------ */
    protected void dump(StringBuilder b,String indent)
    {
        super.dump(b,indent);
        b.append(indent).append(" +=roles=").append(_roles).append('\n');
        
        for (Object path : _constraintMap.keySet())
        {
            Object constraint = _constraintMap.get(path);
            b.append(indent).append(" +=").append(path).append('=').append(constraint).append('\n');
        }
    }
}
