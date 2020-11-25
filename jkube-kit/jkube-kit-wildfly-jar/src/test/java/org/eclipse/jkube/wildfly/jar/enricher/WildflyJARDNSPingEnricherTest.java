/**
 * Copyright (c) 2019 Red Hat, Inc.
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

import com.fasterxml.jackson.core.JsonProcessingException;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.KubernetesListBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mockit.Expectations;
import org.eclipse.jkube.kit.enricher.api.JKubeEnricherContext;
import mockit.Mocked;
import org.eclipse.jkube.generator.api.support.AbstractPortsExtractor;
import org.eclipse.jkube.kit.common.JavaProject;
import org.eclipse.jkube.kit.common.Plugin;
import org.eclipse.jkube.kit.common.util.KubernetesHelper;
import org.eclipse.jkube.kit.config.resource.PlatformMode;
import org.eclipse.jkube.kit.config.resource.ProcessorConfig;
import org.eclipse.jkube.kit.enricher.api.model.Configuration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WildflyJARDNSPingEnricherTest {

    @Mocked
    protected JKubeEnricherContext context;

    private static final String SERVICE_NAME = "my-svc";
    private static final String PING_SERVICE_NAME = SERVICE_NAME + "-ping";

    private void setupExpectations(JavaProject project, Map<String, Object> bootableJarconfig, Map<String, Map<String, Object>> jkubeConfig) {
        Plugin plugin
                = Plugin.builder().artifactId(WildflyJARHealthCheckEnricher.BOOTABLE_JAR_ARTIFACT_ID).
                        groupId(WildflyJARHealthCheckEnricher.BOOTABLE_JAR_GROUP_ID).configuration(bootableJarconfig).build();
        List<Plugin> lst = new ArrayList<>();
        lst.add(plugin);
        ProcessorConfig c = new ProcessorConfig(null, null, jkubeConfig);
        new Expectations() {
            {
                project.getPlugins();
                result = lst;
                context.getProject();
                result = project;
                Configuration.ConfigurationBuilder configBuilder = Configuration.builder();
                configBuilder.processorConfig(c);
                context.getConfiguration();
                result = configBuilder.build();
            }
        };
    }
    
    private void setupExpectations(Map<String, Map<String, Object>> jkubeConfig) {
        ProcessorConfig c = new ProcessorConfig(null, null, jkubeConfig);
        new Expectations() {{
            Configuration.ConfigurationBuilder configBuilder = Configuration.builder();
            configBuilder.processorConfig(c);
            context.getConfiguration();
            result = configBuilder.build();
        }};
    }

    private ServiceBuilder getMockServiceBuilder(String serviceName) {
        return new ServiceBuilder()
                .editOrNewMetadata()
                .withName(serviceName)
                .endMetadata()
                .editOrNewSpec()
                .addNewPort()
                .withName("http")
                .withPort(8080)
                .withProtocol("TCP")
                .withTargetPort(new IntOrString(8080))
                .endPort()
                .addToSelector("app", SERVICE_NAME)
                .withType("NodePort")
                .endSpec();
    }

    private DeploymentBuilder getMockDeploymentBuilder() {
        return new DeploymentBuilder().withNewSpec()
                .withNewTemplate()
                .withNewSpec()
                .addToContainers(new ContainerBuilder()
                        .withImage("the-image:latest")
                        .build())
                .endSpec()
                .endTemplate()
                .endSpec();
    }

    @Test
    public void testDNSConfiguration(@Mocked final JavaProject project) {
        tesDNSConfiguration(project, PlatformMode.kubernetes,
                "KUBERNETES_DNS_PING_SERVICE_NAME", SERVICE_NAME, PING_SERVICE_NAME, createDNSCloudConfig(), Collections.emptyMap());
        tesDNSConfiguration(project, PlatformMode.openshift,
                "OPENSHIFT_DNS_PING_SERVICE_NAME", SERVICE_NAME, PING_SERVICE_NAME, createDNSCloudConfig(), Collections.emptyMap());
    }

    private void tesDNSConfiguration(JavaProject project, PlatformMode mode,
            String envVar, String serviceName, String pingServiceName, Map<String, Object> jarConfig, Map<String, Map<String, Object>> config) {
        setupExpectations(project, jarConfig, config);
        WildflyJARDNSPingEnricher enricher = new WildflyJARDNSPingEnricher(context);
        KubernetesListBuilder klb = new KubernetesListBuilder();
        klb.addToItems(getMockDeploymentBuilder().build());
        klb.addToItems(getMockServiceBuilder(serviceName).build());
        enricher.create(mode, klb);
        List<HasMetadata> lst = klb.build().getItems();
        assertEquals(3, lst.size());
        Deployment deployment = (Deployment) lst.get(2);
        EnvVar env = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().get(0);
        assertEquals(envVar, env.getName());
        assertEquals(pingServiceName, env.getValue());
        Service defaultService = (Service) lst.get(0);
        Service pingService = (Service) lst.get(1);
        assertEquals(serviceName, KubernetesHelper.getName(defaultService));
        assertEquals(pingServiceName, KubernetesHelper.getName(pingService));
        assertEquals(defaultService.getSpec().getSelector(), pingService.getSpec().getSelector());
        assertEquals("None", pingService.getSpec().getClusterIP());
        assertEquals(true, pingService.getSpec().getPublishNotReadyAddresses());
        assertEquals("true", KubernetesHelper.getOrCreateAnnotations(pingService).get("service.alpha.kubernetes.io/tolerate-unready-endpoints"));
        assertEquals(1, pingService.getSpec().getPorts().size());
        ServicePort port = pingService.getSpec().getPorts().get(0);
        assertEquals((Integer) 8888, port.getPort());
        assertEquals("ping", port.getName());
    }

    @Test
    public void testNoDNSConfiguration(@Mocked final JavaProject project) {
        Map<String, Object> config = createNoDNSCloudConfig();
        setupExpectations(project, config, Collections.emptyMap());
        testNoGeneration();
    }

    @Test
    public void testNoJarPlugin(@Mocked final JavaProject project) {
        setupExpectations(Collections.emptyMap());
        testNoGeneration();
    }

    private void testNoGeneration() {
        WildflyJARDNSPingEnricher enricher = new WildflyJARDNSPingEnricher(context);
        KubernetesListBuilder klb = new KubernetesListBuilder();
        klb.addToItems(getMockDeploymentBuilder().build());
        klb.addToItems(getMockServiceBuilder(SERVICE_NAME).build());
        enricher.create(PlatformMode.openshift, klb);
        List<HasMetadata> lst = klb.build().getItems();
        assertEquals(2, lst.size());
        Deployment deployment = (Deployment) lst.get(1);
        assertTrue(deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().isEmpty());
    }

    @Test
    public void testDisableGeneration(@Mocked final JavaProject project) {
        final Map<String, Map<String, Object>> config = createFakeConfig("{\"disableServiceGeneration\":\"true\"}");
        setupExpectations(config);
        testNoGeneration();
    }

    @Test
    public void testInvalidServiceName(@Mocked final JavaProject project) {
        final Map<String, Map<String, Object>> config = createFakeConfig("{\"applicationServiceName\":\"foo\"}");
        Map<String, Object> jarConfig = createDNSCloudConfig();
        setupExpectations(project, jarConfig, config);
        testNoGeneration();
    }

    @Test
    public void testCustomServiceNameGeneration(@Mocked final JavaProject project) {
        String name = "zoo";
        tesDNSConfiguration(project, PlatformMode.kubernetes,
                "KUBERNETES_DNS_PING_SERVICE_NAME", SERVICE_NAME, name, createDNSCloudConfig(),
                createFakeConfig("{\"pingServiceName\":\"" + name + "\"}"));
    }

    @Test
    public void testMultipleServices(@Mocked final JavaProject project) {
        String name = SERVICE_NAME + "2";
        String pingServiceName = name + "-ping";
        setupExpectations(project, createDNSCloudConfig(), createFakeConfig("{\"applicationServiceName\":\"" + name + "\"}"));
        WildflyJARDNSPingEnricher enricher = new WildflyJARDNSPingEnricher(context);
        KubernetesListBuilder klb = new KubernetesListBuilder();
        klb.addToItems(getMockDeploymentBuilder().build());
        klb.addToItems(getMockServiceBuilder(SERVICE_NAME).build());
        klb.addToItems(getMockServiceBuilder(name).build());
        enricher.create(PlatformMode.kubernetes, klb);
        List<HasMetadata> lst = klb.build().getItems();
        assertEquals(4, lst.size());
        Deployment deployment = (Deployment) lst.get(3);
        EnvVar env = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().get(0);
        assertEquals(pingServiceName, env.getValue());
        Service defaultService = (Service) lst.get(0);
        Service extraService = (Service) lst.get(1);
        Service pingService = (Service) lst.get(2);
        assertEquals(SERVICE_NAME, KubernetesHelper.getName(defaultService));
        assertEquals(name, KubernetesHelper.getName(extraService));
        assertEquals(pingServiceName, KubernetesHelper.getName(pingService));
    }

    private static Map<String, Object> createDNSCloudConfig() {
        Map<String, Object> config = new HashMap<>();
        Map<String, Object> cloudConfig = new HashMap<>();
        cloudConfig.put("jgroups-ping-protocol", "dns.DNS_PING");
        config.put("cloud", cloudConfig);
        return config;
    }

    private static Map<String, Object> createNoDNSCloudConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("cloud", null);
        return config;
    }
    
    private Map<String, Map<String, Object>> createFakeConfig(String config) {
        try {
            Map<String, Object> dnsPingJarMap = AbstractPortsExtractor.JSON_MAPPER.readValue(config, Map.class);
            Map<String, Map<String, Object>> enricherConfigMap = new HashMap<>();
            enricherConfigMap.put("jkube-dns-ping-wildfly-jar", dnsPingJarMap);

            Map<String, Object> enricherMap = new HashMap<>();
            enricherMap.put("config", enricherConfigMap);

            Map<String, Object> pluginConfigurationMap = new HashMap<>();
            pluginConfigurationMap.put("enricher", enricherMap);

            return enricherConfigMap;
        } catch (JsonProcessingException jsonProcessingException) {
            jsonProcessingException.printStackTrace();
        }
        return null;
    }

}
