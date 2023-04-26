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

package org.eclipse.jetty.ee10.servlet.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.servlet.HttpConstraintElement;
import jakarta.servlet.HttpMethodConstraintElement;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.ServletSecurity.EmptyRoleSemantic;
import jakarta.servlet.annotation.ServletSecurity.TransportGuarantee;
import org.eclipse.jetty.ee.security.ConstraintAware;
import org.eclipse.jetty.ee.security.ConstraintMapping;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.MatchedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.Constraint.Transport;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ConstraintSecurityHandler
 * <p>
 * Handler to enforce SecurityConstraints. This implementation is servlet spec
 * 3.1 compliant and pre-computes the constraint combinations for runtime
 * efficiency.
 */
public class ConstraintSecurityHandler extends SecurityHandler implements ConstraintAware
{
    private static final Logger LOG = LoggerFactory.getLogger(SecurityHandler.class); //use same as SecurityHandler

    private static final String OMISSION_SUFFIX = ".omission";
    private static final String ALL_METHODS = "*";

    public static final String ANY_KNOWN_ROLE = "*";
    public static final String ANY_ROLE = "**";

    private final List<ConstraintMapping> _constraintMappings = new CopyOnWriteArrayList<>();
    private final List<ConstraintMapping> _durableConstraintMappings = new CopyOnWriteArrayList<>();
    private final Set<String> _roles = new CopyOnWriteArraySet<>();
    private final PathMappings<Map<String, Constraint>> _constraintsByPathAndMethod = new PathMappings<>();
    private boolean _denyUncoveredMethods = false;

    @Override
    protected Constraint getConstraint(String pathInContext, Request request)
    {
        MatchedResource<Map<String, Constraint>> resource = _constraintsByPathAndMethod.getMatched(pathInContext);
        if (resource == null)
            return null;

        Map<String, Constraint> mappings = resource.getResource();
        if (mappings == null)
            return null;

        String httpMethod = request.getMethod();
        Constraint constraint = mappings.get(httpMethod);
        if (constraint == null)
        {
            //No specific http-method names matched
            //Get info for constraint that matches all methods if it exists
            constraint = mappings.get(ALL_METHODS);

            //Get info for constraints that name method omissions where target method name is not omitted
            //(ie matches because target method is not omitted, hence considered covered by the constraint)
            for (Map.Entry<String, Constraint> entry : mappings.entrySet())
            {
                if (entry.getKey() != null && entry.getKey().endsWith(OMISSION_SUFFIX) && !entry.getKey().contains(httpMethod))
                    constraint = combineServletConstraints(constraint, entry.getValue());
            }

            if (constraint == null && isDenyUncoveredHttpMethods())
                constraint = Constraint.FORBIDDEN;
        }

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
        Constraint.Builder constraint = new Constraint.Builder();

        if (rolesAllowed == null || rolesAllowed.length == 0)
        {
            if (permitOrDeny.equals(EmptyRoleSemantic.DENY))
            {
                //Equivalent to <auth-constraint> with no roles
                constraint.name(name + "-Deny");
                constraint.authorization(Constraint.Authorization.FORBIDDEN);
            }
            else
            {
                //Equivalent to no <auth-constraint>
                constraint.name(name + "-Permit");
                constraint.authorization(Constraint.Authorization.ALLOWED);
            }
        }
        else
        {
            //Equivalent to <auth-constraint> with list of <security-role-name>s
            constraint.authorization(Constraint.Authorization.SPECIFIC_ROLE);
            constraint.roles(rolesAllowed);
            constraint.name(name + "-RolesAllowed");
        }

        //Equivalent to //<user-data-constraint><transport-guarantee>CONFIDENTIAL</transport-guarantee></user-data-constraint>
        constraint.transport(TransportGuarantee.CONFIDENTIAL.equals(transport) ? Transport.SECURE : Transport.ANY);

        return constraint.build();
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
        if (pathSpec == null || "".equals(pathSpec.trim()) || constraintMappings == null || constraintMappings.size() == 0)
            return Collections.emptyList();

        List<ConstraintMapping> mappings = new ArrayList<>();
        for (ConstraintMapping mapping : constraintMappings)
        {
            //Remove the matching mappings by only copying in non-matching mappings
            if (!pathSpec.equals(mapping.getPathSpec()))
            {
                mappings.add(mapping);
            }
        }
        return mappings;
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
        java.util.Collection<HttpMethodConstraintElement> methodConstraintElements = securityElement.getHttpMethodConstraints();
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
    public Set<String> getKnownRoles()
    {
        return _roles;
    }

    /**
     * Process the constraints following the combining rules in Servlet 3.0 EA
     * spec section 13.7.1 Note that much of the logic is in the Constraint class.
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
     * spec section 13.7.1 Note that much of the logic is in the Constraint class.
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
     * spec section 13.7.1 Note that much of the logic is in the Constraint class.
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
                Set<String> cmr = cm.getConstraint().getRoles();
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
                addKnownRole(role);
            }
        }

        if (isStarted())
            processConstraintMapping(mapping);
    }

    @Override
    public void addKnownRole(String role)
    {
        _roles.add(role);
    }

    @Override
    protected void doStart() throws Exception
    {
        _constraintsByPathAndMethod.reset();
        _constraintMappings.forEach(this::processConstraintMapping);

        //Servlet Spec 3.1 pg 147 sec 13.8.4.2 log paths for which there are uncovered http methods
        checkPathsWithUncoveredHttpMethods();

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _constraintsByPathAndMethod.reset();
        _constraintMappings.clear();
        _constraintMappings.addAll(_durableConstraintMappings);
    }

    /**
     * <p>Combine constrains as per the servlet specification.
     * This is NOT equivalent to {@link Constraint#combine(Constraint, Constraint)}, which implements
     * a more secure combination.</p>
     * @param constraintA A constraint
     * @param constraintB B constraint
     * @return The combination as per the servlet specification.
     */
    protected Constraint combineServletConstraints(Constraint constraintA, Constraint constraintB)
    {
        // This method is not identical to Constraint.combine.
        // Instead, it implements the bizarre section 13.8.1 of Servlet 6.0 specification

        if (constraintA == null)
            return constraintB == null ? Constraint.ALLOWED_ANY_TRANSPORT : constraintB;
        if (constraintB == null)
            return constraintA;

        // Don't blame me for the following code. Blame Servlet specification
        Constraint.Authorization authorizationA = constraintA.getAuthorization();
        if (authorizationA == Constraint.Authorization.INHERIT)
            authorizationA = Constraint.Authorization.ALLOWED;

        Set<String> roles = null;
        Constraint.Authorization authorizationB = constraintB.getAuthorization();
        Constraint.Authorization authorization = authorizationB == null ? authorizationA : switch (authorizationB)
        {
            // Forbidden takes precedence
            case FORBIDDEN -> Constraint.Authorization.FORBIDDEN;

            // A constraint with no authorization takes precedence over any roles constraints, but not FORBIDDEN
            case ALLOWED -> authorizationA == Constraint.Authorization.FORBIDDEN
                ? Constraint.Authorization.FORBIDDEN
                : Constraint.Authorization.ALLOWED;

            // The "**" role, which is any role (known or otherwise), has precedence over everything but FORBIDDEN and NONE
            case ANY_USER -> (authorizationA == Constraint.Authorization.FORBIDDEN || authorizationA == Constraint.Authorization.ALLOWED)
                ? authorizationA
                : Constraint.Authorization.ANY_USER;

            // The "*" role, which is any known role, only has precedence over SPECIFIC roles
            case KNOWN_ROLE -> (authorizationA == Constraint.Authorization.KNOWN_ROLE || authorizationA == Constraint.Authorization.SPECIFIC_ROLE)
                ? Constraint.Authorization.KNOWN_ROLE
                : authorizationA;

            // Specific roles only combine with other specific roles, otherwise one of the above cases apply
            case SPECIFIC_ROLE ->
            {
                if (authorizationA == Constraint.Authorization.SPECIFIC_ROLE)
                    roles = Stream.concat(constraintA.getRoles().stream(), constraintB.getRoles().stream()).collect(Collectors.toSet());
                yield authorizationA;
            }

            case INHERIT -> authorizationA;
        };

        Transport transportA = constraintA.getTransport();
        Transport transportB = constraintB.getTransport();
        Transport transport = Transport.SECURE.equals(transportA) && Transport.SECURE.equals(transportB)
            ? Transport.SECURE : Transport.ANY;

        return Constraint.from(
            constraintA.getName() + "|" + constraintB.getName(),
            transport,
            authorization,
            roles);
    }

    /**
     * Create and combine the constraint with the existing processed
     * constraints.
     *
     * @param mapping the constraint mapping
     */
    protected void processConstraintMapping(ConstraintMapping mapping)
    {
        Map<String, Constraint> mappings = _constraintsByPathAndMethod.get(PathSpec.from(mapping.getPathSpec()));
        if (mappings == null)
        {
            mappings = new HashMap<>();
            _constraintsByPathAndMethod.put(mapping.getPathSpec(), mappings);
        }
        Constraint allMethodsConstraint = mappings.get(ALL_METHODS);
        if (allMethodsConstraint != null && allMethodsConstraint.getAuthorization() == Constraint.Authorization.FORBIDDEN)
            return;

        if (mapping.getMethodOmissions() != null && mapping.getMethodOmissions().length > 0)
        {
            processConstraintMappingWithMethodOmissions(mapping, mappings);
            return;
        }

        String httpMethod = mapping.getMethod();
        if (httpMethod == null)
            httpMethod = ALL_METHODS;
        Constraint constraint = mappings.get(httpMethod);
        if (constraint == null)
            constraint = allMethodsConstraint;
        if (constraint != null && constraint.getAuthorization() == Constraint.Authorization.FORBIDDEN)
            return;

        // add in info from the constraint
        constraint = combineServletConstraints(constraint, mapping.getConstraint());

        if (constraint.getAuthorization() == Constraint.Authorization.FORBIDDEN && httpMethod.equals(ALL_METHODS))
        {
            mappings.clear();
            mappings.put(ALL_METHODS, constraint);
        }
        else
        {
            mappings.put(httpMethod, constraint);
        }
    }

    /**
     * Constraints that name method omissions are dealt with differently.
     * We create an entry in the mappings with key "&lt;method&gt;.omission". This entry
     * is only ever combined with other omissions for the same method to produce a
     * consolidated Constraint. Then, when we wish to find the relevant constraints for
     * a given Request (in prepareConstraintInfo()), we consult 3 types of entries in
     * the mappings: an entry that names the method of the Request specifically, an
     * entry that names constraints that apply to all methods, entries of the form
     * &lt;method&gt;.omission, where the method of the Request is not named in the omission.
     *
     * @param mapping the constraint mapping
     * @param mappings the mappings of roles
     */
    protected void processConstraintMappingWithMethodOmissions(ConstraintMapping mapping, Map<String, Constraint> mappings)
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
        mappings.put(sb.toString(), mapping.getConstraint());
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
                    DumpableCollection.from("roles", _roles),
                    DumpableCollection.from("constraints", _constraintMappings));
    }

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
        Set<String> paths = getPathsWithUncoveredHttpMethods();
        if (paths != null && !paths.isEmpty())
        {
            LOG.warn("{} has uncovered HTTP methods for the following paths: {}",
                ContextHandler.getCurrentContext(), paths);
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
     * @return Set of paths for which there are uncovered methods
     */
    public Set<String> getPathsWithUncoveredHttpMethods()
    {
        //if automatically denying uncovered methods, there are no uncovered methods
        if (_denyUncoveredMethods)
            return Collections.emptySet();

        Set<String> uncoveredPaths = new HashSet<>();

        for (MappedResource<Map<String, Constraint>> resource : _constraintsByPathAndMethod)
        {
            String path = resource.getPathSpec().getDeclaration();
            Map<String, Constraint> methodMappings = resource.getResource();
            //Each key is either:
            // : an exact method name
            // : * which means that the constraint applies to every method
            // : a name of the form <method>.<method>.<method>.omission, which means it applies to every method EXCEPT those named
            if (methodMappings.get(ALL_METHODS) != null)
                continue; //can't be any uncovered methods for this url path

            boolean hasOmissions = omissionsExist(methodMappings);

            for (String method : methodMappings.keySet())
            {
                if (method.endsWith(OMISSION_SUFFIX))
                {
                    Set<String> omittedMethods = getOmittedMethods(method);
                    for (String m : omittedMethods)
                    {
                        if (!methodMappings.containsKey(m))
                            uncoveredPaths.add(path);
                    }
                }
                else
                {
                    //an exact method name
                    if (!hasOmissions)
                        //an http-method does not have http-method-omission to cover the other method names
                        uncoveredPaths.add(path);
                }
            }
        }
        return uncoveredPaths;
    }

    /**
     * Check if any http method omissions exist in the list of method
     * to auth info mappings.
     *
     * @param methodMappings the method mappings
     * @return true if omission exist
     */
    protected boolean omissionsExist(Map<String, Constraint> methodMappings)
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
     * @return the set of strings
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
     * (eg by a web.xml/web-fragment.xml, annotation or jakarta.servlet api call) -
     * these should not be persisted across a stop/start as they will be re-added on
     * the restart.
     *
     * @return true if the context with which this ConstraintSecurityHandler
     * has not yet started, or if there is no context, the server has not yet started.
     */
    private boolean isInDurableState()
    {
        ServletContextHandler contextHandler = ServletContextHandler.getCurrentServletContextHandler();
        Server server = getServer();

        return contextHandler == null && server == null || contextHandler != null && !contextHandler.isRunning() || contextHandler == null && !server.isRunning();
    }
}
