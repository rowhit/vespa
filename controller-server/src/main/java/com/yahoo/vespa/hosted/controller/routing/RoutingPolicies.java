// Copyright 2020 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.routing;

import com.yahoo.config.application.api.DeploymentSpec;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.zone.RoutingMethod;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.curator.Lock;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.identifiers.DeploymentId;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.LoadBalancer;
import com.yahoo.vespa.hosted.controller.api.integration.dns.AliasTarget;
import com.yahoo.vespa.hosted.controller.api.integration.dns.Record;
import com.yahoo.vespa.hosted.controller.api.integration.dns.RecordData;
import com.yahoo.vespa.hosted.controller.api.integration.dns.RecordName;
import com.yahoo.vespa.hosted.controller.application.EndpointId;
import com.yahoo.vespa.hosted.controller.dns.NameServiceForwarder;
import com.yahoo.vespa.hosted.controller.dns.NameServiceQueue.Priority;
import com.yahoo.vespa.hosted.controller.persistence.CuratorDb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Updates routing policies and their associated DNS records based on an deployment's load balancers.
 *
 * @author mortent
 * @author mpolden
 */
public class RoutingPolicies {

    private final Controller controller;
    private final CuratorDb db;

    public RoutingPolicies(Controller controller) {
        this.controller = Objects.requireNonNull(controller, "controller must be non-null");
        this.db = controller.curator();
        try (var lock = db.lockRoutingPolicies()) { // Update serialized format
            for (var policy : db.readRoutingPolicies().entrySet()) {
                db.writeRoutingPolicies(policy.getKey(), policy.getValue());
            }
        }
    }

    /** Read all known routing policies for given instance */
    public Map<RoutingPolicyId, RoutingPolicy> get(ApplicationId application) {
        return db.readRoutingPolicies(application);
    }

    /** Read all known routing policies for given deployment */
    public Map<RoutingPolicyId, RoutingPolicy> get(DeploymentId deployment) {
        return db.readRoutingPolicies(deployment.applicationId()).entrySet()
                 .stream()
                 .filter(kv -> kv.getKey().zone().equals(deployment.zoneId()))
                 .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Read routing policy for given zone */
    public ZoneRoutingPolicy get(ZoneId zone) {
        return db.readZoneRoutingPolicy(zone);
    }

    /**
     * Refresh routing policies for application in given zone. This is idempotent and changes will only be performed if
     * load balancers for given application have changed.
     */
    public void refresh(ApplicationId application, DeploymentSpec deploymentSpec, ZoneId zone) {
        var allocation = new LoadBalancerAllocation(application, zone, controller.serviceRegistry().configServer()
                                                                                 .getLoadBalancers(application, zone),
                                                    deploymentSpec);
        var inactiveZones = inactiveZones(application, deploymentSpec);
        try (var lock = db.lockRoutingPolicies()) {
            removeGlobalDnsUnreferencedBy(allocation, lock);
            storePoliciesOf(allocation, lock);
            removePoliciesUnreferencedBy(allocation, lock);
            updateGlobalDnsOf(get(allocation.deployment.applicationId()).values(), inactiveZones, lock);
        }
    }

    /** Set the status of all global endpoints in given zone */
    public void setGlobalRoutingStatus(ZoneId zone, GlobalRouting.Status status) {
        try (var lock = db.lockRoutingPolicies()) {
            db.writeZoneRoutingPolicy(new ZoneRoutingPolicy(zone, GlobalRouting.status(status, GlobalRouting.Agent.operator,
                                                                                       controller.clock().instant())));
            var allPolicies = db.readRoutingPolicies();
            for (var applicationPolicies : allPolicies.values()) {
                updateGlobalDnsOf(applicationPolicies.values(), Set.of(), lock);
            }
        }
    }

    /** Set the status of all global endpoints for given deployment */
    public void setGlobalRoutingStatus(DeploymentId deployment, GlobalRouting.Status status, GlobalRouting.Agent agent) {
        try (var lock = db.lockRoutingPolicies()) {
            var policies = get(deployment.applicationId());
            var newPolicies = new LinkedHashMap<>(policies);
            for (var policy : policies.values()) {
                if (!policy.id().zone().equals(deployment.zoneId())) continue; // Wrong zone
                var newPolicy = policy.with(policy.status().with(GlobalRouting.status(status, agent,
                                                                                      controller.clock().instant())));
                newPolicies.put(policy.id(), newPolicy);
            }
            db.writeRoutingPolicies(deployment.applicationId(), newPolicies);
            updateGlobalDnsOf(newPolicies.values(), Set.of(), lock);
        }
    }

    /** Update global DNS record for given policies */
    private void updateGlobalDnsOf(Collection<RoutingPolicy> routingPolicies, Set<ZoneId> inactiveZones, @SuppressWarnings("unused") Lock lock) {
        // Create DNS record for each routing ID
        var routingTable = routingTableFrom(routingPolicies);
        for (Map.Entry<RoutingId, List<RoutingPolicy>> routeEntry : routingTable.entrySet()) {
            var targets = new LinkedHashSet<AliasTarget>();
            var staleTargets = new LinkedHashSet<AliasTarget>();
            for (var policy : routeEntry.getValue()) {
                if (policy.dnsZone().isEmpty()) continue;
                if (!controller.zoneRegistry().routingMethods(policy.id().zone()).contains(RoutingMethod.exclusive)) continue;
                var target = new AliasTarget(policy.canonicalName(), policy.dnsZone().get(), policy.id().zone());
                var zonePolicy = db.readZoneRoutingPolicy(policy.id().zone());
                // Remove target zone if global routing status is set out at:
                // - zone level (ZoneRoutingPolicy)
                // - deployment level (RoutingPolicy)
                // - application package level (deployment.xml)
                if (isConfiguredOut(policy, zonePolicy, inactiveZones)) {
                    staleTargets.add(target);
                } else {
                    targets.add(target);
                }
            }
            // If all targets are configured out, all targets are set in. We do this because otherwise removing 100% of
            // the ALIAS records would cause the global endpoint to stop resolving entirely (NXDOMAIN).
            if (targets.isEmpty() && !staleTargets.isEmpty()) {
                targets.addAll(staleTargets);
                staleTargets.clear();
            }
            if (!targets.isEmpty()) {
                var endpoints = controller.routing().endpointsOf(routeEntry.getKey().application())
                                          .named(routeEntry.getKey().endpointId())
                                          .not().requiresRotation();
                endpoints.forEach(endpoint -> controller.nameServiceForwarder().createAlias(RecordName.from(endpoint.dnsName()), targets, Priority.normal));
            }
            staleTargets.forEach(t -> controller.nameServiceForwarder().removeRecords(Record.Type.ALIAS,
                                                                                      RecordData.fqdn(t.name().value()),
                                                                                      Priority.normal));
        }
    }

    /** Store routing policies for given load balancers */
    private void storePoliciesOf(LoadBalancerAllocation allocation, @SuppressWarnings("unused") Lock lock) {
        var policies = new LinkedHashMap<>(get(allocation.deployment.applicationId()));
        for (LoadBalancer loadBalancer : allocation.loadBalancers) {
            var policyId = new RoutingPolicyId(loadBalancer.application(), loadBalancer.cluster(), allocation.deployment.zoneId());
            var existingPolicy = policies.get(policyId);
            var newPolicy = new RoutingPolicy(policyId, loadBalancer.hostname(), loadBalancer.dnsZone(),
                                              allocation.endpointIdsOf(loadBalancer),
                                              new Status(isActive(loadBalancer), GlobalRouting.DEFAULT_STATUS));
            // Preserve global routing status for existing policy
            if (existingPolicy != null) {
                newPolicy = newPolicy.with(newPolicy.status().with(existingPolicy.status().globalRouting()));
            }
            updateZoneDnsOf(newPolicy);
            policies.put(newPolicy.id(), newPolicy);
        }
        db.writeRoutingPolicies(allocation.deployment.applicationId(), policies);
    }

    /** Update zone DNS record for given policy */
    private void updateZoneDnsOf(RoutingPolicy policy) {
        var name = RecordName.from(policy.endpointIn(controller.system(), RoutingMethod.exclusive, controller.zoneRegistry())
                                         .dnsName());
        var data = RecordData.fqdn(policy.canonicalName().value());
        nameUpdaterIn(policy.id().zone()).createCname(name, data);
    }

    /** Remove policies and zone DNS records unreferenced by given load balancers */
    private void removePoliciesUnreferencedBy(LoadBalancerAllocation allocation, @SuppressWarnings("unused") Lock lock) {
        var policies = get(allocation.deployment.applicationId());
        var newPolicies = new LinkedHashMap<>(policies);
        var activeIds = allocation.asPolicyIds();
        for (var policy : policies.values()) {
            // Leave active load balancers and irrelevant zones alone
            if (activeIds.contains(policy.id()) ||
                !policy.id().zone().equals(allocation.deployment.zoneId())) continue;

            var dnsName = policy.endpointIn(controller.system(), RoutingMethod.exclusive, controller.zoneRegistry()).dnsName();
            nameUpdaterIn(allocation.deployment.zoneId()).removeRecords(Record.Type.CNAME, RecordName.from(dnsName));
            newPolicies.remove(policy.id());
        }
        db.writeRoutingPolicies(allocation.deployment.applicationId(), newPolicies);
    }

    /** Remove unreferenced global endpoints from DNS */
    private void removeGlobalDnsUnreferencedBy(LoadBalancerAllocation allocation, @SuppressWarnings("unused") Lock lock) {
        var zonePolicies = get(allocation.deployment).values();
        var removalCandidates = new HashSet<>(routingTableFrom(zonePolicies).keySet());
        var activeRoutingIds = routingIdsFrom(allocation);
        removalCandidates.removeAll(activeRoutingIds);
        for (var id : removalCandidates) {
            var endpoints = controller.routing().endpointsOf(id.application())
                                      .not().requiresRotation()
                                      .named(id.endpointId());
            var nameUpdater = nameUpdaterIn(allocation.deployment.zoneId());
            endpoints.forEach(endpoint -> nameUpdater.removeRecords(Record.Type.ALIAS, RecordName.from(endpoint.dnsName())));
        }
    }

    /** Compute routing IDs from given load balancers */
    private static Set<RoutingId> routingIdsFrom(LoadBalancerAllocation allocation) {
        Set<RoutingId> routingIds = new LinkedHashSet<>();
        for (var loadBalancer : allocation.loadBalancers) {
            for (var endpointId : allocation.endpointIdsOf(loadBalancer)) {
                routingIds.add(new RoutingId(loadBalancer.application(), endpointId));
            }
        }
        return Collections.unmodifiableSet(routingIds);
    }

    /** Compute a routing table from given policies */
    private static Map<RoutingId, List<RoutingPolicy>> routingTableFrom(Collection<RoutingPolicy> routingPolicies) {
        var routingTable = new LinkedHashMap<RoutingId, List<RoutingPolicy>>();
        for (var policy : routingPolicies) {
            for (var endpoint : policy.endpoints()) {
                var id = new RoutingId(policy.id().owner(), endpoint);
                routingTable.putIfAbsent(id, new ArrayList<>());
                routingTable.get(id).add(policy);
            }
        }
        return Collections.unmodifiableMap(routingTable);
    }

    /** Returns whether the global routing status of given policy is configured to be {@link GlobalRouting.Status#out} */
    private static boolean isConfiguredOut(RoutingPolicy policy, ZoneRoutingPolicy zonePolicy, Set<ZoneId> inactiveZones) {
        // A deployment is can be configured out at any of the following levels:
        // - zone level (ZoneRoutingPolicy)
        // - deployment level (RoutingPolicy)
        // - application package level (deployment.xml)
        return zonePolicy.globalRouting().status() == GlobalRouting.Status.out ||
               policy.status().globalRouting().status() == GlobalRouting.Status.out ||
               inactiveZones.contains(policy.id().zone());
    }

    private static boolean isActive(LoadBalancer loadBalancer) {
        switch (loadBalancer.state()) {
            case reserved: // Count reserved as active as we want callers (application API) to see the endpoint as early
                           // as possible
            case active: return true;
        }
        return false;
    }

    /** Load balancers allocated to a deployment */
    private static class LoadBalancerAllocation {

        private final DeploymentId deployment;
        private final List<LoadBalancer> loadBalancers;
        private final DeploymentSpec deploymentSpec;

        private LoadBalancerAllocation(ApplicationId application, ZoneId zone, List<LoadBalancer> loadBalancers,
                                       DeploymentSpec deploymentSpec) {
            this.deployment = new DeploymentId(application, zone);
            this.loadBalancers = List.copyOf(loadBalancers);
            this.deploymentSpec = deploymentSpec;
        }

        /** Returns the policy IDs of the load balancers contained in this */
        private Set<RoutingPolicyId> asPolicyIds() {
            return loadBalancers.stream()
                                .map(lb -> new RoutingPolicyId(lb.application(),
                                                               lb.cluster(),
                                                               deployment.zoneId()))
                                .collect(Collectors.toUnmodifiableSet());
        }

        /** Compute all endpoint IDs for given load balancer */
        private Set<EndpointId> endpointIdsOf(LoadBalancer loadBalancer) {
            if (!deployment.zoneId().environment().isProduction()) { // Only production deployments have configurable endpoints
                return Set.of();
            }
            var instanceSpec = deploymentSpec.instance(loadBalancer.application().instance());
            if (instanceSpec.isEmpty()) {
                return Set.of();
            }
            if (instanceSpec.get().globalServiceId().filter(id -> id.equals(loadBalancer.cluster().value())).isPresent()) {
                // Legacy assignment always has the default endpoint Id
                return Set.of(EndpointId.defaultId());
            }
            return instanceSpec.get().endpoints().stream()
                               .filter(endpoint -> endpoint.containerId().equals(loadBalancer.cluster().value()))
                               .filter(endpoint -> endpoint.regions().contains(deployment.zoneId().region()))
                               .map(com.yahoo.config.application.api.Endpoint::endpointId)
                               .map(EndpointId::of)
                               .collect(Collectors.toUnmodifiableSet());
        }

    }

    /** Returns zones where global routing is declared inactive for instance through deploymentSpec */
    private static Set<ZoneId> inactiveZones(ApplicationId instance, DeploymentSpec deploymentSpec) {
        var instanceSpec = deploymentSpec.instance(instance.instance());
        if (instanceSpec.isEmpty()) return Set.of();
        return instanceSpec.get().zones().stream()
                           .filter(zone -> zone.environment().isProduction())
                           .filter(zone -> !zone.active())
                           .map(zone -> ZoneId.from(zone.environment(), zone.region().get()))
                           .collect(Collectors.toUnmodifiableSet());
    }

    /** Returns the name updater to use for given zone */
    private NameUpdater nameUpdaterIn(ZoneId zone) {
        if (controller.zoneRegistry().routingMethods(zone).contains(RoutingMethod.exclusive)) {
            return new NameUpdater(controller.nameServiceForwarder());
        }
        return new DiscardingNameUpdater();
    }

    /** A name updater that passes name service operations to the next handler */
    private static class NameUpdater {

        private final NameServiceForwarder forwarder;

        public NameUpdater(NameServiceForwarder forwarder) {
            this.forwarder = forwarder;
        }

        public void removeRecords(Record.Type type, RecordName name) {
            forwarder.removeRecords(type, name, Priority.normal);
        }

        public void createAlias(RecordName name, Set<AliasTarget> targets) {
            forwarder.createAlias(name, targets, Priority.normal);
        }

        public void createCname(RecordName name, RecordData data) {
            forwarder.createCname(name, data, Priority.normal);
        }

    }

    /** A name updater that does nothing */
    private static class DiscardingNameUpdater extends NameUpdater {

        private DiscardingNameUpdater() {
            super(null);
        }

        @Override
        public void removeRecords(Record.Type type, RecordName name) {}

        @Override
        public void createAlias(RecordName name, Set<AliasTarget> target) {}

        @Override
        public void createCname(RecordName name, RecordData data) {}

    }

}
