/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shenyu.k8s.parser;

import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1EndpointAddress;
import io.kubernetes.client.openapi.models.V1EndpointSubset;
import io.kubernetes.client.openapi.models.V1Endpoints;
import io.kubernetes.client.openapi.models.V1HTTPIngressPath;
import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1IngressBackend;
import io.kubernetes.client.openapi.models.V1IngressRule;
import io.kubernetes.client.openapi.models.V1IngressServiceBackend;
import io.kubernetes.client.openapi.models.V1IngressTLS;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1Service;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.shenyu.common.config.ssl.SslCrtAndKeyStream;
import org.apache.shenyu.common.dto.ConditionData;
import org.apache.shenyu.common.dto.MetaData;
import org.apache.shenyu.common.dto.RuleData;
import org.apache.shenyu.common.dto.SelectorData;
import org.apache.shenyu.common.dto.convert.rule.impl.DubboRuleHandle;
import org.apache.shenyu.common.dto.convert.selector.DubboUpstream;
import org.apache.shenyu.common.enums.LoadBalanceEnum;
import org.apache.shenyu.common.enums.MatchModeEnum;
import org.apache.shenyu.common.enums.OperatorEnum;
import org.apache.shenyu.common.enums.ParamTypeEnum;
import org.apache.shenyu.common.enums.PluginEnum;
import org.apache.shenyu.common.enums.RpcTypeEnum;
import org.apache.shenyu.common.enums.SelectorTypeEnum;
import org.apache.shenyu.common.utils.GsonUtils;
import org.apache.shenyu.k8s.common.IngressConfiguration;
import org.apache.shenyu.k8s.common.IngressConstants;
import org.apache.shenyu.k8s.common.ShenyuMemoryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parser of Ingress Dubbo Annotations.
 */
public class DubboIngressParser implements K8sResourceParser<V1Ingress> {

    private static final Logger LOG = LoggerFactory.getLogger(DubboIngressParser.class);

    private final Lister<V1Service> serviceLister;

    private final Lister<V1Endpoints> endpointsLister;

    /**
     * DubboIngressParser Constructor.
     *
     * @param serviceInformer   serviceInformer
     * @param endpointsInformer endpointsInformer
     */
    public DubboIngressParser(final Lister<V1Service> serviceInformer, final Lister<V1Endpoints> endpointsInformer) {
        this.serviceLister = serviceInformer;
        this.endpointsLister = endpointsInformer;
    }

    /**
     * Parse ingress to ShenyuMemoryConfig.
     *
     * @param ingress   ingress resource
     * @param coreV1Api coreV1Api
     * @return ShenyuMemoryConfig
     */
    @Override
    public ShenyuMemoryConfig parse(final V1Ingress ingress, final CoreV1Api coreV1Api) {
        ShenyuMemoryConfig res = new ShenyuMemoryConfig();

        if (Objects.nonNull(ingress.getSpec())) {
            // Parse the dubbo backend
            V1IngressBackend dubboBackend = ingress.getSpec().getDefaultBackend();
            List<V1IngressRule> rules = ingress.getSpec().getRules();
            List<V1IngressTLS> tlsList = ingress.getSpec().getTls();

            String namespace = Objects.requireNonNull(ingress.getMetadata()).getNamespace();
            List<DubboUpstream> dubboUpstreamList = getDefaultDubboRouteConfig(dubboBackend, namespace);

            if (Objects.isNull(rules) || CollectionUtils.isEmpty(rules)) {
                // if rules is null, dubboBackend become global default
                if (Objects.nonNull(dubboBackend) && Objects.nonNull(dubboBackend.getService())) {
                    IngressConfiguration defaultRouteConfig = getDubboRouteConfig(dubboUpstreamList, ingress.getMetadata().getAnnotations());
                    res.setGlobalDefaultBackend(Pair.of(Pair.of(namespace + "/" + ingress.getMetadata().getName(), dubboBackend.getService().getName()),
                            defaultRouteConfig));
                }
            } else {
                // if rules is not null, dubboBackend is default in this ingress
                List<IngressConfiguration> routeList = new ArrayList<>(rules.size());
                for (V1IngressRule ingressRule : rules) {
                    List<IngressConfiguration> routes = parseIngressRule(ingressRule, dubboUpstreamList,
                            Objects.requireNonNull(ingress.getMetadata()).getNamespace(), ingress.getMetadata().getAnnotations());
                    routeList.addAll(routes);
                }
                res.setRouteConfigList(routeList);
            }

            // Parse tls
            if (Objects.nonNull(tlsList) && CollectionUtils.isNotEmpty(tlsList)) {
                List<SslCrtAndKeyStream> sslList = new ArrayList<>();
                for (V1IngressTLS tls : tlsList) {
                    if (Objects.nonNull(tls.getSecretName()) && Objects.nonNull(tls.getHosts()) && CollectionUtils.isNotEmpty(tls.getHosts())) {
                        try {
                            V1Secret secret = coreV1Api.readNamespacedSecret(tls.getSecretName(), namespace, "ture");
                            if (secret.getData() != null) {
                                InputStream keyCertChainInputStream = new ByteArrayInputStream(secret.getData().get("tls.crt"));
                                InputStream keyInputStream = new ByteArrayInputStream(secret.getData().get("tls.key"));
                                tls.getHosts().forEach(host ->
                                        sslList.add(new SslCrtAndKeyStream(host, keyCertChainInputStream, keyInputStream))
                                );
                            }
                        } catch (ApiException e) {
                            LOG.error("parse tls failed ", e);
                        }
                    }
                }
                res.setTlsConfigList(sslList);
            }
        }
        return res;
    }

    private List<DubboUpstream> getDefaultDubboRouteConfig(final V1IngressBackend defaultBackend, final String namespace) {
        List<DubboUpstream> dubboUpstreamList = new ArrayList<>();
        if (Objects.nonNull(defaultBackend) && Objects.nonNull(defaultBackend.getService())) {
            String serviceName = defaultBackend.getService().getName();
            // shenyu routes directly to the container
            V1Endpoints v1Endpoints = endpointsLister.namespace(namespace).get(serviceName);
            List<V1EndpointSubset> subsets = v1Endpoints.getSubsets();
            if (Objects.isNull(subsets) || CollectionUtils.isEmpty(subsets)) {
                LOG.info("Endpoints {} do not have subsets", serviceName);
            } else {
                for (V1EndpointSubset subset : subsets) {
                    List<V1EndpointAddress> addresses = subset.getAddresses();
                    if (Objects.isNull(addresses) || CollectionUtils.isEmpty(addresses)) {
                        continue;
                    }
                    for (V1EndpointAddress address : addresses) {
                        String upstreamIp = address.getIp();
                        String defaultPort = parsePort(defaultBackend.getService());
                        if (Objects.nonNull(defaultPort)) {
                            DubboUpstream upstream = DubboUpstream.builder()
                                    .upstreamUrl(upstreamIp + ":" + defaultPort)
                                    .weight(100)
                                    .protocol("dubbo://")
                                    .warmup(0)
                                    .status(true)
                                    .upstreamHost("")
                                    .build();
                            dubboUpstreamList.add(upstream);
                        }
                    }
                }
            }
        }
        return dubboUpstreamList;
    }

    private String parsePort(final V1IngressServiceBackend service) {
        if (Objects.nonNull(service.getPort())) {
            if (service.getPort().getNumber() != null && service.getPort().getNumber() > 0) {
                return String.valueOf(service.getPort().getNumber());
            } else if (service.getPort().getName() != null && !"".equals(service.getPort().getName().trim())) {
                return service.getPort().getName().trim();
            }
        }
        return null;
    }

    private List<IngressConfiguration> parseIngressRule(final V1IngressRule ingressRule,
                                                                            final List<DubboUpstream> dubboUpstreamList,
                                                                            final String namespace,
                                                                            final Map<String, String> annotations) {
        List<IngressConfiguration> res = new ArrayList<>();

        ConditionData hostCondition = null;
        if (Objects.nonNull(ingressRule.getHost())) {
            hostCondition = new ConditionData();
            hostCondition.setParamType(ParamTypeEnum.DOMAIN.getName());
            hostCondition.setOperator(OperatorEnum.EQ.getAlias());
            hostCondition.setParamValue(ingressRule.getHost());
        }
        if (Objects.nonNull(ingressRule.getHttp())) {
            List<V1HTTPIngressPath> paths = ingressRule.getHttp().getPaths();
            if (Objects.nonNull(paths)) {
                for (V1HTTPIngressPath path : paths) {
                    if (path.getPath() == null) {
                        continue;
                    }

                    OperatorEnum operator;
                    if ("ImplementationSpecific".equals(path.getPathType())) {
                        operator = OperatorEnum.MATCH;
                    } else if ("Prefix".equals(path.getPathType())) {
                        operator = OperatorEnum.STARTS_WITH;
                    } else if ("Exact".equals(path.getPathType())) {
                        operator = OperatorEnum.EQ;
                    } else {
                        LOG.info("Invalid path type, set it with match operator");
                        operator = OperatorEnum.MATCH;
                    }

                    ConditionData pathCondition = new ConditionData();
                    pathCondition.setOperator(operator.getAlias());
                    pathCondition.setParamType(ParamTypeEnum.URI.getName());
                    pathCondition.setParamValue(path.getPath());
                    List<ConditionData> conditionList = new ArrayList<>(2);
                    if (Objects.nonNull(hostCondition)) {
                        conditionList.add(hostCondition);
                    }
                    conditionList.add(pathCondition);

                    SelectorData selectorData = SelectorData.builder()
                            .pluginId(String.valueOf(PluginEnum.DUBBO.getCode()))
                            .pluginName(PluginEnum.DUBBO.getName())
                            .name(path.getPath())
                            .matchMode(MatchModeEnum.AND.getCode())
                            .type(SelectorTypeEnum.CUSTOM_FLOW.getCode())
                            .enabled(true)
                            .logged(false)
                            .continued(true)
                            .conditionList(conditionList).build();
                    List<DubboUpstream> upstreamList = parseUpstream(path.getBackend(), namespace);
                    if (upstreamList.isEmpty()) {
                        upstreamList = dubboUpstreamList;
                    }
                    selectorData.setHandle(GsonUtils.getInstance().toJson(upstreamList));

                    DubboRuleHandle dubboRuleHandle = new DubboRuleHandle();
                    if (Objects.nonNull(annotations)) {
                        dubboRuleHandle.setLoadbalance(annotations.getOrDefault(IngressConstants.LOADBALANCER_ANNOTATION_KEY, LoadBalanceEnum.RANDOM.getName()));
                    }
                    RuleData ruleData = RuleData.builder()
                            .name(path.getPath())
                            .pluginName(PluginEnum.DUBBO.getName())
                            .matchMode(MatchModeEnum.AND.getCode())
                            .conditionDataList(conditionList)
                            .handle(GsonUtils.getInstance().toJson(dubboRuleHandle))
                            .loged(false)
                            .enabled(true).build();

                    List<MetaData> metaDataList = parseMetaData(paths, namespace);
                    for (MetaData metaData : metaDataList) {
                        res.add(new IngressConfiguration(selectorData, ruleData, metaData));
                    }
                }
            }
        }
        return res;
    }

    private List<DubboUpstream> parseUpstream(final V1IngressBackend backend, final String namespace) {
        List<DubboUpstream> upstreamList = new ArrayList<>();
        if (Objects.nonNull(backend) && Objects.nonNull(backend.getService()) && Objects.nonNull(backend.getService().getName())) {
            String serviceName = backend.getService().getName();
            // shenyu routes directly to the container
            V1Service v1Service = serviceLister.namespace(namespace).get(serviceName);
            List<String> clusterIPs = v1Service.getSpec().getClusterIPs();
            Map<String, String> annotations = v1Service.getMetadata().getAnnotations();
            String[] protocols = annotations.get(IngressConstants.UPSTREAMS_PROTOCOL_ANNOTATION_KEY).split(",");
            if (Objects.isNull(clusterIPs) || CollectionUtils.isEmpty(clusterIPs)) {
                LOG.info("Endpoints {} do not have clusterIPs", serviceName);
            } else {
                int i = 0;
                for (String clusterIP : clusterIPs) {
                    String defaultPort = parsePort(backend.getService());
                    if (Objects.nonNull(defaultPort)) {
                        DubboUpstream upstream = DubboUpstream.builder()
                                .upstreamUrl(clusterIP + ":" + defaultPort)
                                .weight(100)
                                .protocol(Objects.isNull(protocols[i++]) ? "dubbo" : protocols[i++])
                                .warmup(0)
                                .status(true)
                                .upstreamHost("")
                                .build();
                        upstreamList.add(upstream);
                    }
                }
            }
        }
        return upstreamList;
    }

    private List<MetaData> parseMetaData(final List<V1HTTPIngressPath> paths, final String namespace) {
        List<MetaData> metaData = new ArrayList<>();
        for (V1HTTPIngressPath path : paths) {
            if (path.getPath() == null) {
                continue;
            }
            String serviceName = path.getBackend().getService().getName();
            V1Service v1Service = serviceLister.namespace(namespace).get(serviceName);
            Map<String, String> annotations = v1Service.getMetadata().getAnnotations();
            metaData.add(MetaData.builder()
                    .appName(annotations.getOrDefault(IngressConstants.PLUGIN_DUBBO_APP_NAME, "dubbo"))
                    .path(annotations.getOrDefault(IngressConstants.PLUGIN_DUBBO_PATH, "/dubbo/findById"))
                    .rpcType(annotations.getOrDefault(IngressConstants.PLUGIN_DUBBO_RPC_TYPE, RpcTypeEnum.DUBBO.getName()))
                    .serviceName(annotations.getOrDefault(IngressConstants.PLUGIN_DUBBO_SERVICE_NAME, "org.apache.shenyu.examples.apache.dubbo.service.impl.DubboTestServiceImpl"))
                    .methodName(annotations.getOrDefault(IngressConstants.PLUGIN_DUBBO_METHOD_NAME, "findById"))
                    .enabled(true)
                    .build());
        }
        return metaData;
    }

    private IngressConfiguration getDubboRouteConfig(final List<DubboUpstream> dubboUpstreamList, final Map<String, String> annotations) {
        final ConditionData conditionData = new ConditionData();
        conditionData.setParamName("dubbo");
        conditionData.setParamType(ParamTypeEnum.URI.getName());
        conditionData.setOperator(OperatorEnum.PATH_PATTERN.getAlias());
        conditionData.setParamValue("/**");

        final SelectorData selectorData = SelectorData.builder()
                .name("dubbo-selector")
                .sort(Integer.MAX_VALUE)
                .conditionList(Collections.singletonList(conditionData))
                .handle(GsonUtils.getInstance().toJson(dubboUpstreamList))
                .enabled(true)
                .id(IngressConstants.ID)
                .pluginName(PluginEnum.DUBBO.getName())
                .pluginId(String.valueOf(PluginEnum.DUBBO.getCode()))
                .logged(false)
                .continued(true)
                .matchMode(MatchModeEnum.AND.getCode())
                .type(SelectorTypeEnum.FULL_FLOW.getCode()).build();

        final RuleData ruleData = RuleData.builder()
                .selectorId(IngressConstants.ID)
                .pluginName(PluginEnum.DUBBO.getName())
                .name("dubbo-rule")
                .matchMode(MatchModeEnum.AND.getCode())
                .conditionDataList(Collections.singletonList(conditionData))
                .loged(false)
                .enabled(true)
                .sort(Integer.MAX_VALUE).build();

        MetaData metaData = new MetaData();
        if (Objects.nonNull(annotations)) {
            metaData.setAppName(annotations.getOrDefault(IngressConstants.PLUGIN_DUBBO_APP_NAME, "dubbo"));
            metaData.setMethodName(annotations.getOrDefault(IngressConstants.PLUGIN_DUBBO_METHOD_NAME, "methodName"));
            metaData.setPath(annotations.getOrDefault(IngressConstants.PLUGIN_DUBBO_PATH, "/dubbo/findAll"));
            metaData.setRpcType(annotations.getOrDefault(IngressConstants.PLUGIN_DUBBO_RPC_TYPE, RpcTypeEnum.DUBBO.getName()));
            metaData.setServiceName(annotations.getOrDefault(IngressConstants.PLUGIN_DUBBO_SERVICE_NAME, "dubboService"));
            metaData.setContextPath(annotations.getOrDefault(IngressConstants.PLUGIN_DUBBO_CONTEXT_PATH, "contextPath"));
            metaData.setRpcExt(annotations.getOrDefault(IngressConstants.PLUGIN_DUBBO_RPC_EXT, "rpcExt"));
            metaData.setServiceName(annotations.getOrDefault(IngressConstants.PLUGIN_DUBBO_SERVICE_NAME, "serviceName"));
            metaData.setParameterTypes(annotations.getOrDefault(IngressConstants.PLUGIN_DUBBO_PARAMENT_TYPE, ""));
            metaData.setEnabled(Boolean.parseBoolean(annotations.getOrDefault(IngressConstants.PLUGIN_DUBBO_ENABLED, "true")));
        }
        return new IngressConfiguration(selectorData, ruleData, metaData);
    }
}