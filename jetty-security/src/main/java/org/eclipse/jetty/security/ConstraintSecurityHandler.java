//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpChannelConfig;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.StringMap;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.security.Constraint;

/* ------------------------------------------------------------ */
/**
 * Handler to enforce SecurityConstraints. This implementation is servlet spec
 * 2.4 compliant and pre-computes the constraint combinations for runtime
 * efficiency.
 *
 */
public class ConstraintSecurityHandler extends SecurityHandler implements ConstraintAware
{
    private static final String ALL_METHODS = "*";
    private final List<ConstraintMapping> _constraintMappings= new CopyOnWriteArrayList<>();
    private final Set<String> _roles = new CopyOnWriteArraySet<>();
    private final PathMap<Map<String, RoleInfo>> _constraintMap = new PathMap<>();
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
     * @see #setRoles(Set)
     * @see #setConstraintMappings(List, Set)
     */
    public void setStrict(boolean strict)
    {
        _strict = strict;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the constraintMappings.
     */
    @Override
    public List<ConstraintMapping> getConstraintMappings()
    {
        return _constraintMappings;
    }

    /* ------------------------------------------------------------ */
    @Override
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
     *            The constraintMappings to set, from which the set of known roles
     *            is determined.
     */
    public void setConstraintMappings(List<ConstraintMapping> constraintMappings)
    {
        setConstraintMappings(constraintMappings,null);
    }

    /**
     * Process the constraints following the combining rules in Servlet 3.0 EA
     * spec section 13.7.1 Note that much of the logic is in the RoleInfo class.
     *
     * @param constraintMappings
     *            The constraintMappings to set as array, from which the set of known roles
     *            is determined.  Needed to retain API compatibility for 7.x
     */
    public void setConstraintMappings( ConstraintMapping[] constraintMappings )
    {
        setConstraintMappings( Arrays.asList(constraintMappings), null);
    }

    /* ------------------------------------------------------------ */
    /**
     * Process the constraints following the combining rules in Servlet 3.0 EA
     * spec section 13.7.1 Note that much of the logic is in the RoleInfo class.
     *
     * @param constraintMappings
     *            The constraintMappings to set.
     * @param roles The known roles (or null to determine them from the mappings)
     */
    @Override
    public void setConstraintMappings(List<ConstraintMapping> constraintMappings, Set<String> roles)
    {
        if (isStarted())
            throw new IllegalStateException("Started");
        _constraintMappings.clear();
        _constraintMappings.addAll(constraintMappings);

        if (roles==null)
        {
            roles = new HashSet<>();
            for (ConstraintMapping cm : constraintMappings)
            {
                String[] cmr = cm.getConstraint().getRoles();
                if (cmr!=null)
                {
                    for (String r : cmr)
                        if (!ALL_METHODS.equals(r))
                            roles.add(r);
                }
            }
        }
        setRoles(roles);
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the known roles.
     * This may be overridden by a subsequent call to {@link #setConstraintMappings(ConstraintMapping[])} or
     * {@link #setConstraintMappings(List, Set)}.
     * @see #setStrict(boolean)
     * @param roles The known roles (or null to determine them from the mappings)
     */
    public void setRoles(Set<String> roles)
    {
        if (isStarted())
            throw new IllegalStateException("Started");

        _roles.clear();
        _roles.addAll(roles);
    }



    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.security.ConstraintAware#addConstraintMapping(org.eclipse.jetty.security.ConstraintMapping)
     */
    @Override
    public void addConstraintMapping(ConstraintMapping mapping)
    {
        _constraintMappings.add(mapping);
        if (mapping.getConstraint()!=null && mapping.getConstraint().getRoles()!=null)
            for (String role :  mapping.getConstraint().getRoles())
                addRole(role);

        if (isStarted())
        {
            processConstraintMapping(mapping);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.security.ConstraintAware#addRole(java.lang.String)
     */
    @Override
    public void addRole(String role)
    {
        boolean modified = _roles.add(role);
        if (isStarted() && modified && isStrict())
        {
            // Add the new role to currently defined any role role infos
            for (Map<String,RoleInfo> map : _constraintMap.values())
            {
                for (RoleInfo info : map.values())
                {
                    if (info.isAnyRole())
                        info.addRole(role);
                }
            }
        }
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
                processConstraintMapping(mapping);
            }
        }
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _constraintMap.clear();
    }

    protected void processConstraintMapping(ConstraintMapping mapping)
    {
        Map<String, RoleInfo> mappings = _constraintMap.get(mapping.getPathSpec());
        if (mappings == null)
        {
            mappings = new StringMap<>();
            _constraintMap.put(mapping.getPathSpec(),mappings);
        }
        RoleInfo allMethodsRoleInfo = mappings.get(ALL_METHODS);
        if (allMethodsRoleInfo != null && allMethodsRoleInfo.isForbidden())
            return;

        String httpMethod = mapping.getMethod();
        if (httpMethod==null)
            httpMethod=ALL_METHODS;
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
            return;

        Constraint constraint = mapping.getConstraint();
        boolean forbidden = constraint.isForbidden();
        roleInfo.setForbidden(forbidden);
        if (forbidden)
        {
            if (httpMethod.equals(ALL_METHODS))
            {
                mappings.clear();
                mappings.put(ALL_METHODS,roleInfo);
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
            if (httpMethod.equals(ALL_METHODS))
            {
                for (Map.Entry<String, RoleInfo> entry : mappings.entrySet())
                {
                    if (!entry.getKey().equals(ALL_METHODS))
                    {
                        RoleInfo specific = entry.getValue();
                        specific.combine(roleInfo);
                    }
                }
            }
        }
    }

    @Override
    protected RoleInfo prepareConstraintInfo(String pathInContext, Request request)
    {
        Map<String, RoleInfo> mappings = _constraintMap.match(pathInContext);

        if (mappings != null)
        {
            String httpMethod = request.getMethod();
            RoleInfo roleInfo = mappings.get(httpMethod);
            if (roleInfo == null)
                roleInfo = mappings.get(ALL_METHODS);
            return roleInfo;
        }

        return null;
    }

    @Override
    protected boolean checkUserDataPermissions(String pathInContext, Request request, Response response, RoleInfo roleInfo) throws IOException
    {
        if (roleInfo == null)
            return true;

        if (roleInfo.isForbidden())
            return false;

        UserDataConstraint dataConstraint = roleInfo.getUserDataConstraint();
        if (dataConstraint == null || dataConstraint == UserDataConstraint.None)
            return true;

        HttpChannelConfig httpConfig = HttpChannel.getCurrentHttpChannel().getHttpChannelConfig();

        if (dataConstraint == UserDataConstraint.Confidential || dataConstraint == UserDataConstraint.Integral)
        {
            if (request.isSecure())
                return true;

            if (httpConfig.getSecurePort() > 0)
            {
                String url = httpConfig.getSecureScheme() + "://" + request.getServerName() + ":" + httpConfig.getSecurePort()
                        + request.getRequestURI();
                if (request.getQueryString() != null)
                    url += "?" + request.getQueryString();

                response.setContentLength(0);
                response.sendRedirect(url);
            }
            else
                response.sendError(HttpStatus.FORBIDDEN_403,"!Secure");

            request.setHandled(true);
            return false;
        }
        else
        {
            throw new IllegalArgumentException("Invalid dataConstraint value: " + dataConstraint);
        }

    }

    @Override
    protected boolean isAuthMandatory(Request baseRequest, Response base_response, Object constraintInfo)
    {
        return constraintInfo != null && ((RoleInfo)constraintInfo).isChecked();
    }

    @Override
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

        for (String role : roleInfo.getRoles())
        {
            if (userIdentity.isUserInRole(role, null))
                return true;
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void dump(Appendable out,String indent) throws IOException
    {
        // TODO these should all be beans
        dumpBeans(out,indent,
                Collections.singleton(getLoginService()),
                Collections.singleton(getIdentityService()),
                Collections.singleton(getAuthenticator()),
                Collections.singleton(_roles),
                _constraintMap.entrySet());
    }
}
