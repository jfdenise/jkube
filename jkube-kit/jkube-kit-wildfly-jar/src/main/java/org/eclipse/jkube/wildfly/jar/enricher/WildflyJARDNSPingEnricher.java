/**
 * Copyright (c) 2020 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at:
 *
 *     https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.jkube.wildfly.jar.enricher;

import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesListBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServiceFluent;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.eclipse.jkube.kit.common.Configs;
import org.eclipse.jkube.kit.enricher.api.JKubeEnricherContext;

import org.eclipse.jkube.kit.common.JavaProject;
import org.eclipse.jkube.kit.common.Plugin;
import org.eclipse.jkube.kit.common.util.JKubeProjectUtil;
import org.eclipse.jkube.kit.common.util.KubernetesHelper;
import org.eclipse.jkube.kit.config.resource.PlatformMode;
import org.eclipse.jkube.kit.enricher.api.BaseEnricher;
import static org.eclipse.jkube.wildfly.jar.enricher.WildflyJARHealthCheckEnricher.BOOTABLE_JAR_ARTIFACT_ID;
import static org.eclipse.jkube.wildfly.jar.enricher.WildflyJARHealthCheckEnricher.BOOTABLE_JAR_GROUP_ID;
import static org.eclipse.jkube.wildfly.jar.enricher.WildflyJARHealthCheckEnricher.CLOUD_ELEMENT;

/**
 * Enriches with an headless service needed by jgroups DNS ping protocol.
 */
public class WildflyJARDNSPingEnricher extends BaseEnricher {

    private static final String OPENSHIFT_ENV_VAR = "OPENSHIFT_DNS_PING_SERVICE_NAME";
    private static final String KUBERNETES_ENV_VAR = "KUBERNETES_DNS_PING_SERVICE_NAME";
    private static final String PROTOCOL_ELEMENT = "jgroups-ping-protocol";
    private static final String DNS_PROTOCOL = "dns.DNS_PING";

    public WildflyJARDNSPingEnricher(JKubeEnricherContext buildContext) {
        super(buildContext, "jkube-dns-ping-wildfly-jar");
    }

    @AllArgsConstructor
    private enum Config implements Configs.Config {

        PINGSERVICENAME("pingServiceName", null),
        DISABLESERVICEGENERATION("disableServiceGeneration", "false"),
        APPLICATIONSERVICENAME("applicationServiceName", null);

        @Getter
        protected String key;
        @Getter
        protected String defaultValue;
    }

    @Override
    public void create(PlatformMode platformMode, KubernetesListBuilder listBuilder) {

        if (!isAvailable()) {
            return;
        }
        final String envName = PlatformMode.kubernetes.equals(platformMode) ? KUBERNETES_ENV_VAR : OPENSHIFT_ENV_VAR;

        // Retrieve selectors and service name if not set.
        String defaultServiceName = Configs.asString(getConfig(Config.APPLICATIONSERVICENAME));
        List<HasMetadata> items = listBuilder.buildItems();
        Map<String, String> labels = null;
        String serviceName = null;
        if (items != null) {
            for (HasMetadata item : items) {
                if (item instanceof Service) {
                    Service service = (Service) item;
                    String name = KubernetesHelper.getName(service);
                    labels = service.getSpec().getSelector();
                    // The first encountered service or, if a service name has been provided
                    // use this one.
                    if (defaultServiceName == null || defaultServiceName.equals(name)) {
                        serviceName = name;
                        break;
                    }
                }
            }
        }
        if (serviceName == null) {
            log.error("No service found can't generate ping service");
            return;
        }

        String pingServiceName = Configs.asString(getConfig(Config.PINGSERVICENAME, serviceName + "-ping"));
        listBuilder.accept(new TypedVisitor<ContainerBuilder>() {
            @Override
            public void visit(ContainerBuilder containerBuilder) {
                containerBuilder.addToEnv(new EnvVarBuilder()
                        .withName(envName)
                        .withValue(pingServiceName)
                        .build());
            }
        });

        ServiceBuilder serviceBuilder = new ServiceBuilder()
                .withNewMetadata()
                .withName(pingServiceName)
                .withAnnotations(getAnnotations())
                .endMetadata();
        ServiceFluent.SpecNested<ServiceBuilder> specBuilder = serviceBuilder.withNewSpec();
        ServicePortBuilder portBuilder = new ServicePortBuilder()
                // The port is required but value is meaningless.
                .withPort(8888)
                .withName("ping");
        specBuilder.withPorts(portBuilder.build());
        specBuilder.withClusterIP("None");
        specBuilder.withPublishNotReadyAddresses(Boolean.TRUE);
        specBuilder.withSelector(labels);
        specBuilder.endSpec();
        Service pingService = serviceBuilder.build();
        log.info("Adding headless service " + pingServiceName + " for service " + serviceName);
        listBuilder.addToServiceItems(pingService);
    }

    private Map<String, String> getAnnotations() {
        Map<String, String> annotations = new HashMap<>();
        annotations.put("service.alpha.kubernetes.io/tolerate-unready-endpoints", "true");
        annotations.put("description", "The JGroups ping port for clustering.");
        return annotations;
    }

    private boolean isAvailable() {
        if (Configs.asBoolean(getConfig(Config.DISABLESERVICEGENERATION))) {
            return false;
        }
        JavaProject project = ((JKubeEnricherContext) getContext()).getProject();
        Plugin plugin = JKubeProjectUtil.getPlugin(project, BOOTABLE_JAR_GROUP_ID, BOOTABLE_JAR_ARTIFACT_ID);
        if (plugin == null) {
            return false;
        }
        Map<String, Object> config = plugin.getConfiguration();
        Object cloud = config.get(CLOUD_ELEMENT);
        if (cloud != null) {
            String protocol = (String) ((Map<String, Object>) cloud).get(PROTOCOL_ELEMENT);
            return protocol != null && protocol.contains(DNS_PROTOCOL);
        }
        return false;
    }
}
