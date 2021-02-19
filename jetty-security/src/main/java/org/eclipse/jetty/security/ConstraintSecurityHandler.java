//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;
import javax.servlet.HttpConstraintElement;
import javax.servlet.HttpMethodConstraintElement;
import javax.servlet.ServletSecurityElement;
import javax.servlet.annotation.ServletSecurity.EmptyRoleSemantic;
import javax.servlet.annotation.ServletSecurity.TransportGuarantee;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.security.Constraint;

/**
 * ConstraintSecurityHandler
 * <p>
 * Handler to enforce SecurityConstraints. This implementation is servlet spec
 * 3.1 compliant and pre-computes the constraint combinations for runtime
 * efficiency.
 */
public class ConstraintSecurityHandler extends SecurityHandler implements ConstraintAware
{
    private static final Logger LOG = Log.getLogger(SecurityHandler.class); //use same as SecurityHandler

    private static final String OMISSION_SUFFIX = ".omission";
    private static final String ALL_METHODS = "*";
    private final List<ConstraintMapping> _constraintMappings = new CopyOnWriteArrayList<>();
    private final List<ConstraintMapping> _durableConstraintMappings = new CopyOnWriteArrayList<>();
    private final Set<String> _roles = new CopyOnWriteArraySet<>();

    private final PathMappings<Map<String, RoleInfo>> _constraintRoles = new PathMappings<>();
    private boolean _denyUncoveredMethods = false;

    public static Constraint createConstraint()
    {
        return new Constraint();
    }

    public static Constraint createConstraint(Constraint constraint)
    {
        try
        {
            return (Constraint)constraint.clone();
        }
        catch (CloneNotSupportedException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Create a security constraint
     *
     * @param name the name of the constraint
     * @param authenticate true to authenticate
     * @param roles list of roles
     * @param dataConstraint the data constraint
     * @return the constraint
     */
    public static Constraint createConstraint(String name, boolean authenticate, String[] roles, int dataConstraint)
    {
        Constraint constraint = createConstraint();
        if (name != null)
            constraint.setName(name);
        constraint.setAuthenticate(authenticate);
        constraint.setRoles(roles);
        constraint.setDataConstraint(dataConstraint);
        return constraint;
    }

    /**
     * Create a Constraint
     *
     * @param name the name
     * @param element the http constraint element
     * @return the created constraint
     */
    public static Constraint createConstraint(String name, HttpConstraintElement element)
    {
        return createConstraint(name, element.getRolesAllowed(), element.getEmptyRoleSemantic(), element.getTransportGuarantee());
    }

    /**
     * Create Constraint
     *
     * @param name the name
     * @param rolesAllowed the list of allowed roles
     * @param permitOrDeny the permission semantic
     * @param transport the transport guarantee
     * @return the created constraint
     */
    public static Constraint createConstraint(String name, String[] rolesAllowed, EmptyRoleSemantic permitOrDeny, TransportGuarantee transport)
    {
        Constraint constraint = createConstraint();

        if (rolesAllowed == null || rolesAllowed.length == 0)
        {
            if (permitOrDeny.equals(EmptyRoleSemantic.DENY))
            {
                //Equivalent to <auth-constraint> with no roles
                constraint.setName(name + "-Deny");
                constraint.setAuthenticate(true);
            }
            else
            {
                //Equivalent to no <auth-constraint>
                constraint.setName(name + "-Permit");
                constraint.setAuthenticate(false);
            }
        }
        else
        {
            //Equivalent to <auth-constraint> with list of <security-role-name>s
            constraint.setAuthenticate(true);
            constraint.setRoles(rolesAllowed);
            constraint.setName(name + "-RolesAllowed");
        }

        //Equivalent to //<user-data-constraint><transport-guarantee>CONFIDENTIAL</transport-guarantee></user-data-constraint>
        constraint.setDataConstraint((transport.equals(TransportGuarantee.CONFIDENTIAL) ? Constraint.DC_CONFIDENTIAL : Constraint.DC_NONE));
        return constraint;
    }

    public static List<ConstraintMapping> getConstraintMappingsForPath(String pathSpec, List<ConstraintMapping> constraintMappings)
    {
        if (StringUtil.isBlank(pathSpec) || constraintMappings == null || constraintMappings.size() == 0)
            return Collections.emptyList();

        return constraintMappings.stream().filter(m -> m.containsPathSpec(pathSpec)).collect(Collectors.toList());
    }

    /**
     * Take out of the constraint mappings those that match the
     * given path.
     *
     * @param pathSpec the path spec
     * @param constraintMappings a new list minus the matching constraints
     * @return the list of constraint mappings
     */
    public static List<ConstraintMapping> removeConstraintMappingsForPath(String pathSpec, List<ConstraintMapping> constraintMappings)
    {
        if (StringUtil.isBlank(pathSpec) || constraintMappings == null || constraintMappings.size() == 0)
            return Collections.emptyList();

        //Remove the matching mappings by only copying in non-matching mappings
        return constraintMappings.stream().filter(m -> !m.containsPathSpec(pathSpec)).collect(Collectors.toList());
    }

    /**
     * Generate Constraints and ContraintMappings for the given url pattern and ServletSecurityElement
     *
     * @param name the name
     * @param pathSpec the path spec
     * @param securityElement the servlet security element
     * @return the list of constraint mappings
     */
    public static List<ConstraintMapping> createConstraintsWithMappingsForPath(String name, String pathSpec, ServletSecurityElement securityElement)
    {
        return createConstraintsWithMappingsForPath(name, new ServletPathSpec(pathSpec), securityElement);
    }

    /**
     * Generate Constraints and ContraintMappings for the given url pattern and ServletSecurityElement
     *
     * @param name the name
     * @param pathSpec the path spec
     * @param securityElement the servlet security element
     * @return the list of constraint mappings
     */
    public static List<ConstraintMapping> createConstraintsWithMappingsForPath(String name, PathSpec pathSpec, ServletSecurityElement securityElement)
    {
        List<ConstraintMapping> mappings = new ArrayList<>();

        //Create a constraint that will describe the default case (ie if not overridden by specific HttpMethodConstraints)
        Constraint httpConstraint;
        ConstraintMapping httpConstraintMapping = null;

        if (securityElement.getEmptyRoleSemantic() != EmptyRoleSemantic.PERMIT ||
            securityElement.getRolesAllowed().length != 0 ||
            securityElement.getTransportGuarantee() != TransportGuarantee.NONE)
        {
            httpConstraint = ConstraintSecurityHandler.createConstraint(name, securityElement);

            //Create a mapping for the pathSpec for the default case
            httpConstraintMapping = new ConstraintMapping();
            httpConstraintMapping.setPathSpec(pathSpec);
            httpConstraintMapping.setConstraint(httpConstraint);
            mappings.add(httpConstraintMapping);
        }

        //See Spec 13.4.1.2 p127
        List<String> methodOmissions = new ArrayList<>();

        //make constraint mappings for this url for each of the HttpMethodConstraintElements
        Collection<HttpMethodConstraintElement> methodConstraintElements = securityElement.getHttpMethodConstraints();
        if (methodConstraintElements != null)
        {
            for (HttpMethodConstraintElement methodConstraintElement : methodConstraintElements)
            {
                //Make a Constraint that captures the <auth-constraint> and <user-data-constraint> elements supplied for the HttpMethodConstraintElement
                Constraint methodConstraint = ConstraintSecurityHandler.createConstraint(name, methodConstraintElement);
                ConstraintMapping mapping = new ConstraintMapping();
                mapping.setConstraint(methodConstraint);
                mapping.setPathSpec(pathSpec);
                if (methodConstraintElement.getMethodName() != null)
                {
                    mapping.setMethod(methodConstraintElement.getMethodName());
                    //See spec 13.4.1.2 p127 - add an omission for every method name to the default constraint
                    methodOmissions.add(methodConstraintElement.getMethodName());
                }
                mappings.add(mapping);
            }
        }
        //See spec 13.4.1.2 p127 - add an omission for every method name to the default constraint
        //UNLESS the default constraint contains all default values. In that case, we won't add it. See Servlet Spec 3.1 pg 129
        if (methodOmissions.size() > 0 && httpConstraintMapping != null)
            httpConstraintMapping.setMethodOmissions(methodOmissions.toArray(new String[0]));

        return mappings;
    }

    @Override
    public List<ConstraintMapping> getConstraintMappings()
    {
        return _constraintMappings;
    }

    @Override
    public Set<String> getRoles()
    {
        return _roles;
    }

    /**
     * Process the constraints following the combining rules in Servlet 3.0 EA
     * spec section 13.7.1 Note that much of the logic is in the RoleInfo class.
     *
     * @param constraintMappings The constraintMappings to set, from which the set of known roles
     * is determined.
     */
    public void setConstraintMappings(List<ConstraintMapping> constraintMappings)
    {
        setConstraintMappings(constraintMappings, null);
    }

    /**
     * Process the constraints following the combining rules in Servlet 3.0 EA
     * spec section 13.7.1 Note that much of the logic is in the RoleInfo class.
     *
     * @param constraintMappings The constraintMappings to set as array, from which the set of known roles
     * is determined.  Needed to retain API compatibility for 7.x
     */
    public void setConstraintMappings(ConstraintMapping[] constraintMappings)
    {
        setConstraintMappings(Arrays.asList(constraintMappings), null);
    }

    /**
     * Process the constraints following the combining rules in Servlet 3.0 EA
     * spec section 13.7.1 Note that much of the logic is in the RoleInfo class.
     *
     * @param constraintMappings The constraintMappings to set.
     * @param roles The known roles (or null to determine them from the mappings)
     */
    @Override
    public void setConstraintMappings(List<ConstraintMapping> constraintMappings, Set<String> roles)
    {

        _constraintMappings.clear();
        _constraintMappings.addAll(constraintMappings);
        
        _durableConstraintMappings.clear();
        if (isInDurableState())
        {
            _durableConstraintMappings.addAll(constraintMappings);
        }

        if (roles == null)
        {
            roles = new HashSet<>();
            for (ConstraintMapping cm : constraintMappings)
            {
                String[] cmr = cm.getConstraint().getRoles();
                if (cmr != null)
                {
                    for (String r : cmr)
                    {
                        if (!ALL_METHODS.equals(r))
                            roles.add(r);
                    }
                }
            }
        }
        setRoles(roles);

        if (isStarted())
            _constraintMappings.forEach(this::processConstraintMapping);
    }

    /**
     * Set the known roles.
     * This may be overridden by a subsequent call to {@link #setConstraintMappings(ConstraintMapping[])} or
     * {@link #setConstraintMappings(List, Set)}.
     *
     * @param roles The known roles (or null to determine them from the mappings)
     */
    public void setRoles(Set<String> roles)
    {
        _roles.clear();
        _roles.addAll(roles);
    }

    /**
     * @see org.eclipse.jetty.security.ConstraintAware#addConstraintMapping(org.eclipse.jetty.security.ConstraintMapping)
     */
    @Override
    public void addConstraintMapping(ConstraintMapping mapping)
    {
        _constraintMappings.add(mapping);
        
        if (isInDurableState())
            _durableConstraintMappings.add(mapping);
        
        if (mapping.getConstraint() != null && mapping.getConstraint().getRoles() != null)
        {
            //allow for lazy role naming: if a role is named in a security constraint, try and
            //add it to the list of declared roles (ie as if it was declared with a security-role
            for (String role : mapping.getConstraint().getRoles())
            {
                if ("*".equals(role) || "**".equals(role))
                    continue;
                addRole(role);
            }
        }

        if (isStarted())
            processConstraintMapping(mapping);
    }

    /**
     * @see org.eclipse.jetty.security.ConstraintAware#addRole(java.lang.String)
     */
    @Override
    public void addRole(String role)
    {
        //add to list of declared roles
        boolean modified = _roles.add(role);
        if (isStarted() && modified)
        {
            // Add the new role to currently defined any role role infos
            for (MappedResource<Map<String, RoleInfo>> map : _constraintRoles)
            {
                for (RoleInfo info : map.getResource().values())
                {
                    if (info.isAnyRole())
                        info.addRole(role);
                }
            }
        }
    }

    /**
     * @see org.eclipse.jetty.security.SecurityHandler#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        _constraintRoles.reset();
        _constraintMappings.forEach(this::processConstraintMapping);

        //Servlet Spec 3.1 pg 147 sec 13.8.4.2 log paths for which there are uncovered http methods
        checkPathsWithUncoveredHttpMethods();

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _constraintRoles.reset();
        _constraintMappings.clear();
        _constraintMappings.addAll(_durableConstraintMappings);
    }       

    /**
     * Create and combine the constraint with the existing processed
     * constraints.
     *
     * @param mapping the constraint mapping
     */
    protected void processConstraintMapping(ConstraintMapping mapping)
    {
        Map<String, RoleInfo> mappings = _constraintRoles.get(mapping.toPathSpec());

        if (mappings == null)
        {
            mappings = new HashMap<>();
            _constraintRoles.put(mapping.toPathSpec(), mappings);
        }
        RoleInfo allMethodsRoleInfo = mappings.get(ALL_METHODS);
        if (allMethodsRoleInfo != null && allMethodsRoleInfo.isForbidden())
            return;

        if (mapping.getMethodOmissions() != null && mapping.getMethodOmissions().length > 0)
        {
            processConstraintMappingWithMethodOmissions(mapping, mappings);
            return;
        }

        String httpMethod = mapping.getMethod();
        if (httpMethod == null)
            httpMethod = ALL_METHODS;
        RoleInfo roleInfo = mappings.get(httpMethod);
        if (roleInfo == null)
        {
            roleInfo = new RoleInfo();
            mappings.put(httpMethod, roleInfo);
            if (allMethodsRoleInfo != null)
            {
                roleInfo.combine(allMethodsRoleInfo);
            }
        }
        if (roleInfo.isForbidden())
            return;

        //add in info from the constraint
        configureRoleInfo(roleInfo, mapping);

        if (roleInfo.isForbidden())
        {
            if (httpMethod.equals(ALL_METHODS))
            {
                mappings.clear();
                mappings.put(ALL_METHODS, roleInfo);
            }
        }
    }

    /**
     * Constraints that name method omissions are dealt with differently.
     * We create an entry in the mappings with key "&lt;method&gt;.omission". This entry
     * is only ever combined with other omissions for the same method to produce a
     * consolidated RoleInfo. Then, when we wish to find the relevant constraints for
     * a given Request (in prepareConstraintInfo()), we consult 3 types of entries in
     * the mappings: an entry that names the method of the Request specifically, an
     * entry that names constraints that apply to all methods, entries of the form
     * &lt;method&gt;.omission, where the method of the Request is not named in the omission.
     *
     * @param mapping the constraint mapping
     * @param mappings the mappings of roles
     */
    protected void processConstraintMappingWithMethodOmissions(ConstraintMapping mapping, Map<String, RoleInfo> mappings)
    {
        String[] omissions = mapping.getMethodOmissions();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < omissions.length; i++)
        {
            if (i > 0)
                sb.append(".");
            sb.append(omissions[i]);
        }
        sb.append(OMISSION_SUFFIX);
        RoleInfo ri = new RoleInfo();
        mappings.put(sb.toString(), ri);
        configureRoleInfo(ri, mapping);
    }

    /**
     * Initialize or update the RoleInfo from the constraint
     *
     * @param ri the role info
     * @param mapping the constraint mapping
     */
    protected void configureRoleInfo(RoleInfo ri, ConstraintMapping mapping)
    {
        Constraint constraint = mapping.getConstraint();
        boolean forbidden = constraint.isForbidden();
        ri.setForbidden(forbidden);

        //set up the data constraint (NOTE: must be done after setForbidden, as it nulls out the data constraint
        //which we need in order to do combining of omissions in prepareConstraintInfo
        UserDataConstraint userDataConstraint = UserDataConstraint.get(mapping.getConstraint().getDataConstraint());
        ri.setUserDataConstraint(userDataConstraint);

        //if forbidden, no point setting up roles
        if (!ri.isForbidden())
        {
            //add in the roles
            boolean checked = mapping.getConstraint().getAuthenticate();
            ri.setChecked(checked);

            if (ri.isChecked())
            {
                if (mapping.getConstraint().isAnyRole())
                {
                    // * means matches any defined role
                    for (String role : _roles)
                    {
                        ri.addRole(role);
                    }
                    ri.setAnyRole(true);
                }
                else if (mapping.getConstraint().isAnyAuth())
                {
                    //being authenticated is sufficient, not necessary to check roles
                    ri.setAnyAuth(true);
                }
                else
                {
                    //user must be in one of the named roles
                    String[] newRoles = mapping.getConstraint().getRoles();
                    for (String role : newRoles)
                    {
                        //check role has been defined
                        if (!_roles.contains(role))
                            throw new IllegalArgumentException("Attempt to use undeclared role: " + role + ", known roles: " + _roles);
                        ri.addRole(role);
                    }
                }
            }
        }
    }

    /**
     * Find constraints that apply to the given path.
     * In order to do this, we consult 3 different types of information stored in the mappings for each path - each mapping
     * represents a merged set of user data constraints, roles etc -:
     * <ol>
     * <li>A mapping of an exact method name </li>
     * <li>A mapping with key * that matches every method name</li>
     * <li>Mappings with keys of the form "&lt;method&gt;.&lt;method&gt;.&lt;method&gt;.omission" that indicates it will match every method name EXCEPT those given</li>
     * </ol>
     *
     * @see org.eclipse.jetty.security.SecurityHandler#prepareConstraintInfo(java.lang.String, org.eclipse.jetty.server.Request)
     */
    @Override
    protected RoleInfo prepareConstraintInfo(String pathInContext, Request request)
    {
        MappedResource<Map<String, RoleInfo>> mapped = _constraintRoles.getMatch(pathInContext);
        Map<String, RoleInfo> mappings = mapped == null ? null : mapped.getResource();

        if (mappings != null)
        {
            String httpMethod = request.getMethod();
            RoleInfo roleInfo = mappings.get(httpMethod);
            if (roleInfo == null)
            {
                //No specific http-method names matched
                List<RoleInfo> applicableConstraints = new ArrayList<>();

                //Get info for constraint that matches all methods if it exists
                RoleInfo all = mappings.get(ALL_METHODS);
                if (all != null)
                    applicableConstraints.add(all);

                //Get info for constraints that name method omissions where target method name is not omitted
                //(ie matches because target method is not omitted, hence considered covered by the constraint)
                for (Entry<String, RoleInfo> entry : mappings.entrySet())
                {
                    if (entry.getKey() != null && entry.getKey().endsWith(OMISSION_SUFFIX) && !entry.getKey().contains(httpMethod))
                        applicableConstraints.add(entry.getValue());
                }

                if (applicableConstraints.size() == 0 && isDenyUncoveredHttpMethods())
                {
                    roleInfo = new RoleInfo();
                    roleInfo.setForbidden(true);
                }
                else if (applicableConstraints.size() == 1)
                    roleInfo = applicableConstraints.get(0);
                else
                {
                    roleInfo = new RoleInfo();
                    roleInfo.setUserDataConstraint(UserDataConstraint.None);

                    for (RoleInfo r : applicableConstraints)
                    {
                        roleInfo.combine(r);
                    }
                }
            }

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

        Request baseRequest = Request.getBaseRequest(request);
        HttpConfiguration httpConfig = baseRequest.getHttpChannel().getHttpConfiguration();

        if (dataConstraint == UserDataConstraint.Confidential || dataConstraint == UserDataConstraint.Integral)
        {
            if (request.isSecure())
                return true;

            if (httpConfig.getSecurePort() > 0)
            {
                String scheme = httpConfig.getSecureScheme();
                int port = httpConfig.getSecurePort();

                String url = URIUtil.newURI(scheme, request.getServerName(), port, request.getRequestURI(), request.getQueryString());
                response.setContentLength(0);
                response.sendRedirect(url, true);
            }
            else
                response.sendError(HttpStatus.FORBIDDEN_403, "!Secure");

            request.setHandled(true);
            return false;
        }
        else
        {
            throw new IllegalArgumentException("Invalid dataConstraint value: " + dataConstraint);
        }
    }

    @Override
    protected boolean isAuthMandatory(Request baseRequest, Response baseResponse, Object constraintInfo)
    {
        return constraintInfo != null && ((RoleInfo)constraintInfo).isChecked();
    }

    /**
     * @see org.eclipse.jetty.security.SecurityHandler#checkWebResourcePermissions(java.lang.String, org.eclipse.jetty.server.Request, org.eclipse.jetty.server.Response, java.lang.Object, org.eclipse.jetty.server.UserIdentity)
     */
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

        //handle ** role constraint
        if (roleInfo.isAnyAuth() && request.getUserPrincipal() != null)
        {
            return true;
        }

        //check if user is any of the allowed roles
        boolean isUserInRole = false;
        for (String role : roleInfo.getRoles())
        {
            if (userIdentity.isUserInRole(role, null))
            {
                isUserInRole = true;
                break;
            }
        }

        //handle * role constraint
        if (roleInfo.isAnyRole() && request.getUserPrincipal() != null && isUserInRole)
        {
            return true;
        }

        //normal role check
        return isUserInRole;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
            DumpableCollection.from("roles", _roles),
            DumpableCollection.from("constraints", _constraintRoles));
    }

    /**
     * @see org.eclipse.jetty.security.ConstraintAware#setDenyUncoveredHttpMethods(boolean)
     */
    @Override
    public void setDenyUncoveredHttpMethods(boolean deny)
    {
        _denyUncoveredMethods = deny;
    }

    @Override
    public boolean isDenyUncoveredHttpMethods()
    {
        return _denyUncoveredMethods;
    }

    /**
     * Servlet spec 3.1 pg. 147.
     */
    @Override
    public boolean checkPathsWithUncoveredHttpMethods()
    {
        Set<PathSpec> paths = getPathsWithUncoveredHttpMethods();
        if (paths != null && !paths.isEmpty())
        {
            for (PathSpec p : paths)
            {
                LOG.warn("{} has uncovered http methods for path: {}", ContextHandler.getCurrentContext(), p);
            }
            if (LOG.isDebugEnabled())
                LOG.debug(new Throwable());
            return true;
        }
        return false;
    }

    /**
     * Servlet spec 3.1 pg. 147.
     * The container must check all the combined security constraint
     * information and log any methods that are not protected and the
     * urls at which they are not protected
     *
     * @return list of paths for which there are uncovered methods
     */
    public Set<PathSpec> getPathsWithUncoveredHttpMethods()
    {
        //if automatically denying uncovered methods, there are no uncovered methods
        if (_denyUncoveredMethods)
            return Collections.emptySet();

        Set<PathSpec> uncoveredPathSpecs = new HashSet<>();
        for (MappedResource<Map<String, RoleInfo>> entry : _constraintRoles)
        {
            Map<String, RoleInfo> methodMappings = entry.getResource();

            //Each key is either:
            // : an exact method name
            // : * which means that the constraint applies to every method
            // : a name of the form <method>.<method>.<method>.omission, which means it applies to every method EXCEPT those named
            if (methodMappings.get(ALL_METHODS) != null)
                continue; //can't be any uncovered methods for this url path

            // TODO handle or ignore non standard pathspec
            boolean hasOmissions = omissionsExist(methodMappings);

            for (String method : methodMappings.keySet())
            {
                if (method.endsWith(OMISSION_SUFFIX))
                {
                    Set<String> omittedMethods = getOmittedMethods(method);
                    for (String m : omittedMethods)
                    {
                        if (!methodMappings.containsKey(m))
                            uncoveredPathSpecs.add(entry.getPathSpec());
                    }
                }
                else
                {
                    //an exact method name
                    if (!hasOmissions)
                        //an http-method does not have http-method-omission to cover the other method names
                        uncoveredPathSpecs.add(entry.getPathSpec());
                }
            }
        }
        return uncoveredPathSpecs;
    }

    /**
     * Check if any http method omissions exist in the list of method
     * to auth info mappings.
     *
     * @param methodMappings the method mappings
     * @return true if omission exist
     */
    protected boolean omissionsExist(Map<String, RoleInfo> methodMappings)
    {
        if (methodMappings == null)
            return false;
        for (String m : methodMappings.keySet())
        {
            if (m.endsWith(OMISSION_SUFFIX))
                return true;
        }
        return false;
    }

    /**
     * Given a string of the form <code>&lt;method&gt;.&lt;method&gt;.omission</code>
     * split out the individual method names.
     *
     * @param omission the method
     * @return the list of strings
     */
    protected Set<String> getOmittedMethods(String omission)
    {
        if (omission == null || !omission.endsWith(OMISSION_SUFFIX))
            return Collections.emptySet();

        String[] strings = omission.split("\\.");
        return new HashSet<>(Arrays.asList(strings).subList(0, strings.length - 1));
    }
    
    /**
     * Constraints can be added to the ConstraintSecurityHandler before the 
     * associated context is started. These constraints should persist across
     * a stop/start. Others can be added after the associated context is starting
     * (eg by a web.xml/web-fragment.xml, annotation or javax.servlet api call) -
     * these should not be persisted across a stop/start as they will be re-added on
     * the restart.
     * 
     * @return true if the context with which this ConstraintSecurityHandler
     * has not yet started, or if there is no context, the server has not yet started.
     */
    private boolean isInDurableState()
    {
        ContextHandler context = ContextHandler.getContextHandler(null);
        Server server = getServer();
        
        return (context == null && server == null) || (context != null && !context.isRunning()) || (context == null && !server.isRunning());
    }
}
