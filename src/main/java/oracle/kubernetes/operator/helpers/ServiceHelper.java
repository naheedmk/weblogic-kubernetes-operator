// Copyright 2017, 2018, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at http://oss.oracle.com/licenses/upl..

package oracle.kubernetes.operator.helpers;

import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.*;
import oracle.kubernetes.operator.LabelConstants;
import oracle.kubernetes.operator.ProcessingConstants;
import oracle.kubernetes.operator.domain.model.oracle.kubernetes.weblogic.domain.v1.Domain;
import oracle.kubernetes.operator.domain.model.oracle.kubernetes.weblogic.domain.v1.DomainSpec;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.logging.MessageKeys;
import oracle.kubernetes.operator.wlsconfig.NetworkAccessPoint;
import oracle.kubernetes.operator.work.NextAction;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ServiceHelper {
  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");
  
  private ServiceHelper() {}

  /**
   * Create asynchronous step for internal cluster service
   * @param next Next processing step
   * @return Step for internal service creation
   */
  public static Step createForServerStep(Step next) {
    return new ForServerStep(next);
  }
  
  private static class ForServerStep extends Step {
    public ForServerStep(Step next) {
      super(next);
    }

    @Override
    public NextAction apply(Packet packet) {
      DomainPresenceInfo info = packet.getSPI(DomainPresenceInfo.class);
      String serverName = (String) packet.get(ProcessingConstants.SERVER_NAME);
      Integer port = (Integer) packet.get(ProcessingConstants.PORT);
      Integer nodePort = (Integer) packet.get(ProcessingConstants.NODE_PORT);
      
      Domain dom = info.getDomain();
      V1ObjectMeta meta = dom.getMetadata();
      DomainSpec spec = dom.getSpec();
      String namespace = meta.getNamespace();

      String weblogicDomainUID = spec.getDomainUID();
      String weblogicDomainName = spec.getDomainName();

      String name = CallBuilder.toDNS1123LegalName(weblogicDomainUID + "-" + serverName);

      V1Service service = new V1Service(); 

      V1ObjectMeta metadata = new V1ObjectMeta();
      metadata.setName(name);
      metadata.setNamespace(namespace);
      Map<String, String> labels = new HashMap<>();
      labels.put(LabelConstants.DOMAINUID_LABEL, weblogicDomainUID);
      labels.put(LabelConstants.DOMAINNAME_LABEL, weblogicDomainName);
      metadata.setLabels(labels);
      service.setMetadata(metadata);

      AnnotationHelper.annotateWithDomain(metadata, dom);

      V1ServiceSpec serviceSpec = new V1ServiceSpec();
      serviceSpec.setType(nodePort == null ? "ClusterIP" : "NodePort");
      Map<String, String> selector = new HashMap<>();
      selector.put(LabelConstants.DOMAINUID_LABEL, weblogicDomainUID);
      selector.put(LabelConstants.SERVERNAME_LABEL, serverName);
      serviceSpec.setSelector(selector);
      List<V1ServicePort> ports = new ArrayList<>();
      V1ServicePort servicePort = new V1ServicePort();
      servicePort.setPort(port);
      if (nodePort != null) {
        servicePort.setNodePort(nodePort);
      }
      ports.add(servicePort);
      serviceSpec.setPorts(ports);
      service.setSpec(serviceSpec);

      // Verify if Kubernetes api server has a matching Service
      // Create or replace, if necessary
      ServerKubernetesObjects sko = info.getServers().computeIfAbsent(serverName, k -> new ServerKubernetesObjects());

      if (sko.getService() == null || !validateCurrentService(service, sko.getService())) {
        // There is no Service or Service spec has changed
        // First, verify there is no existing Service
        Step read = CallBuilder.create().readServiceAsync(name, namespace, new ResponseStep<V1Service>(next) {
          @Override
          public NextAction onFailure(Packet packet, ApiException e, int statusCode,
              Map<String, List<String>> responseHeaders) {
            if (statusCode == CallBuilder.NOT_FOUND) {
              return onSuccess(packet, null, statusCode, responseHeaders);
            }
            return super.onFailure(packet, e, statusCode, responseHeaders);
          }

          @Override
          public NextAction onSuccess(Packet packet, V1Service result, int statusCode,
              Map<String, List<String>> responseHeaders) {
            if (result == null) {
              Step create = CallBuilder.create().createServiceAsync(namespace, service, new ResponseStep<V1Service>(next) {
                @Override
                public NextAction onSuccess(Packet packet, V1Service result, int statusCode,
                    Map<String, List<String>> responseHeaders) {
                  
                  LOGGER.info(serverName.equals(spec.getAsName()) ? MessageKeys.ADMIN_SERVICE_CREATED : MessageKeys.MANAGED_SERVICE_CREATED, weblogicDomainUID, serverName);
                  sko.setService(result);
                  return doNext(packet);
                }
              });
              return doNext(create, packet);
            } else if (AnnotationHelper.checkDomainAnnotation(result.getMetadata(), dom) || validateCurrentService(service, result)) {
              // existing Service has correct spec
              LOGGER.info(serverName.equals(spec.getAsName()) ? MessageKeys.ADMIN_SERVICE_EXISTS : MessageKeys.MANAGED_SERVICE_EXISTS, weblogicDomainUID, serverName);
              sko.setService(result);
              return doNext(packet);
            } else {
              // we need to update the Service
              Step replace = new CycleServiceStep(
                  name, namespace, service, 
                  serverName.equals(spec.getAsName()) ? MessageKeys.ADMIN_SERVICE_REPLACED : MessageKeys.MANAGED_SERVICE_REPLACED,
                  weblogicDomainUID, serverName, sko, next);
              return doNext(replace, packet);
            }
          }
        });
        
        return doNext(read, packet);
      }

      return doNext(packet);
    }
  }
  
  private static boolean validateCurrentService(V1Service build, V1Service current) {
    V1ServiceSpec buildSpec = build.getSpec();
    V1ServiceSpec currentSpec = current.getSpec();
    
    String buildType = buildSpec.getType();
    if (buildType == null) {
      buildType = "ClusterIP";
    }
    String currentType = currentSpec.getType();
    if (currentType == null) {
      currentType = "ClusterIP";
    }
    if (!currentType.equals(buildType)) {
      return false;
    }
    
    List<V1ServicePort> buildPorts = buildSpec.getPorts();
    List<V1ServicePort> currentPorts = currentSpec.getPorts();
    
    outer:
    for (V1ServicePort bp : buildPorts) {
      for (V1ServicePort cp : currentPorts) {
        if (cp.getPort().equals(bp.getPort())) {
          if (!"NodePort".equals(buildType) || bp.getNodePort() == null || bp.getNodePort().equals(cp.getNodePort())) {
            continue outer;
          }
        }
      }
      return false;
    }
    
    return true;
  }
  
  private static class CycleServiceStep extends Step  {
    private final String serviceName;
    private final String namespace;
    private final V1Service newService;
    private final String messageKey;
    private final String weblogicDomainUID;
    private final String serverName;
    private final ServerKubernetesObjects sko;
    private final String channelName;
    
    public CycleServiceStep(String serviceName, String namespace, V1Service newService, String messageKey, String weblogicDomainUID, String serverName, ServerKubernetesObjects sko, Step next) {
      this(serviceName, namespace, newService, messageKey, weblogicDomainUID, serverName, sko, null, next);
    }
    
    public CycleServiceStep(String serviceName, String namespace, V1Service newService, String messageKey, String weblogicDomainUID, String serverName, ServerKubernetesObjects sko, String channelName, Step next) {
      super(next);
      this.serviceName = serviceName;
      this.namespace = namespace;
      this.newService = newService;
      this.messageKey = messageKey;
      this.weblogicDomainUID = weblogicDomainUID;
      this.serverName = serverName;
      this.sko = sko;
      this.channelName = channelName;
    }

    @Override
    public NextAction apply(Packet packet) {
      Step delete = CallBuilder.create().deleteServiceAsync(serviceName, namespace, new ResponseStep<V1Status>(next) {
        @Override
        public NextAction onFailure(Packet packet, ApiException e, int statusCode,
            Map<String, List<String>> responseHeaders) {
          if (statusCode == CallBuilder.NOT_FOUND) {
            return onSuccess(packet, null, statusCode, responseHeaders);
          }
          return super.onFailure(packet, e, statusCode, responseHeaders);
        }

        @Override
        public NextAction onSuccess(Packet packet, V1Status result, int statusCode,
            Map<String, List<String>> responseHeaders) {
          Step create = CallBuilder.create().createServiceAsync(namespace, newService, new ResponseStep<V1Service>(next) {
            @Override
            public NextAction onSuccess(Packet packet, V1Service result, int statusCode,
                Map<String, List<String>> responseHeaders) {
              
              LOGGER.info(messageKey, weblogicDomainUID, serverName);
              if (channelName != null) {
                sko.getChannels().put(channelName, result);
              } else {
                sko.setService(result);
              }
              return doNext(packet);
            }
          });
          return doNext(create, packet);
        }
      });
      return doNext(delete, packet);
    }
  }

  /**
   * Create asynchronous step for external channel
   * @param next Next processing step
   * @return Step for external channel creation
   */
  public static Step createForExternalChannelStep(Step next) {
    return new ForExternalChannelStep(next);
  }
  
  private static class ForExternalChannelStep extends Step {
    public ForExternalChannelStep(Step next) {
      super(next);
    }

    @Override
    public NextAction apply(Packet packet) {
      DomainPresenceInfo info = packet.getSPI(DomainPresenceInfo.class);
      String serverName = (String) packet.get(ProcessingConstants.SERVER_NAME);
      NetworkAccessPoint networkAccessPoint = (NetworkAccessPoint) packet.get(ProcessingConstants.NETWORK_ACCESS_POINT);
      
      Domain dom = info.getDomain();
      V1ObjectMeta meta = dom.getMetadata();
      DomainSpec spec = dom.getSpec();
      String namespace = meta.getNamespace();

      String weblogicDomainUID = spec.getDomainUID();
      String weblogicDomainName = spec.getDomainName();

      String name = CallBuilder.toDNS1123LegalName(
          weblogicDomainUID + "-" + serverName + "-extchannel-" + networkAccessPoint.getName());

      V1Service service = new V1Service(); 

      V1ObjectMeta metadata = new V1ObjectMeta();
      metadata.setName(name);
      metadata.setNamespace(namespace);
      Map<String, String> labels = new HashMap<>();
      labels.put(LabelConstants.DOMAINUID_LABEL, weblogicDomainUID);
      labels.put(LabelConstants.DOMAINNAME_LABEL, weblogicDomainName);
      metadata.setLabels(labels);
      service.setMetadata(metadata);

      AnnotationHelper.annotateWithDomain(metadata, dom);

      V1ServiceSpec serviceSpec = new V1ServiceSpec();
      serviceSpec.setType("NodePort");
      Map<String, String> selector = new HashMap<>();
      selector.put(LabelConstants.DOMAINUID_LABEL, weblogicDomainUID);
      selector.put(LabelConstants.SERVERNAME_LABEL, serverName);
      serviceSpec.setSelector(selector);
      List<V1ServicePort> ports = new ArrayList<>();
      V1ServicePort servicePort = new V1ServicePort();
      servicePort.setNodePort(networkAccessPoint.getPublicPort());
      servicePort.setPort(networkAccessPoint.getListenPort());
      ports.add(servicePort);
      serviceSpec.setPorts(ports);
      service.setSpec(serviceSpec);

      // Verify if Kubernetes api server has a matching Service
      // Create or replace, if necessary
      ServerKubernetesObjects sko = info.getServers().computeIfAbsent(serverName, k -> new ServerKubernetesObjects());

      if (sko.getChannels().get(networkAccessPoint.getName()) == null || !validateCurrentService(service, sko.getChannels().get(networkAccessPoint.getName()))) {
        // There is no Service or Service spec has changed
        // First, verify there is no existing Service
        Step read = CallBuilder.create().readServiceAsync(name, namespace, new ResponseStep<V1Service>(next) {
          @Override
          public NextAction onFailure(Packet packet, ApiException e, int statusCode,
              Map<String, List<String>> responseHeaders) {
            if (statusCode == CallBuilder.NOT_FOUND) {
              return onSuccess(packet, null, statusCode, responseHeaders);
            }
            return super.onFailure(packet, e, statusCode, responseHeaders);
          }

          @Override
          public NextAction onSuccess(Packet packet, V1Service result, int statusCode,
              Map<String, List<String>> responseHeaders) {
            if (result == null) {
              Step create = CallBuilder.create().createServiceAsync(namespace, service, new ResponseStep<V1Service>(next) {
                @Override
                public NextAction onSuccess(Packet packet, V1Service result, int statusCode,
                    Map<String, List<String>> responseHeaders) {
                  
                  LOGGER.info(serverName.equals(spec.getAsName()) ? MessageKeys.ADMIN_SERVICE_CREATED : MessageKeys.MANAGED_SERVICE_CREATED, weblogicDomainUID, serverName);
                  sko.getChannels().put(networkAccessPoint.getName(), result);
                  return doNext(packet);
                }
              });
              return doNext(create, packet);
            } else if (AnnotationHelper.checkDomainAnnotation(result.getMetadata(), dom) || validateCurrentService(service, result)) {
              // existing Service has correct spec
              LOGGER.info(serverName.equals(spec.getAsName()) ? MessageKeys.ADMIN_SERVICE_EXISTS : MessageKeys.MANAGED_SERVICE_EXISTS, weblogicDomainUID, serverName);
              sko.getChannels().put(networkAccessPoint.getName(), result);
              return doNext(packet);
            } else {
              // we need to update the Service
              Step replace = new CycleServiceStep(
                  name, namespace, service, 
                  serverName.equals(spec.getAsName()) ? MessageKeys.ADMIN_SERVICE_REPLACED : MessageKeys.MANAGED_SERVICE_REPLACED,
                  weblogicDomainUID, serverName, sko, networkAccessPoint.getName(), next);
              return doNext(replace, packet);
            }
          }
        });
        
        return doNext(read, packet);
      }

      return doNext(packet);
    }
  }
}