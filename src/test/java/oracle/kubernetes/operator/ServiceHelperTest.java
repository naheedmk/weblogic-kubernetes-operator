// Copyright 2017, 2018, Oracle Corporation and/or its affiliates.  All rights reserved.

package oracle.kubernetes.operator;

import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1Service;
import io.kubernetes.client.models.V1ServiceList;
import io.kubernetes.client.models.V1ServicePort;
import oracle.kubernetes.operator.domain.model.oracle.kubernetes.weblogic.domain.v1.Domain;
import oracle.kubernetes.operator.domain.model.oracle.kubernetes.weblogic.domain.v1.DomainSpec;
import oracle.kubernetes.operator.helpers.CallBuilder;
import oracle.kubernetes.operator.helpers.DomainPresenceInfo;
import oracle.kubernetes.operator.helpers.ServiceHelper;
import oracle.kubernetes.operator.work.Component;
import oracle.kubernetes.operator.work.Engine;
import oracle.kubernetes.operator.work.Fiber;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class ServiceHelperTest {

  // These test will use the "tests" namespace.

  private boolean serviceCreated = false;

  @Before
  public void startClean() throws Exception {
    
    // Delete the service if left around.
    System.out.println("Deleting service pre-test");
    try {
      CallBuilder.create().deleteService("domain-uid-admin", "tests");
    } catch (ApiException e) {
      if (e.getCode() != CallBuilder.NOT_FOUND) {
        throw e;
      }
    }

  }

  @After
  public void tearDown() throws Exception {

    // Delete the service if we created one.
    if (serviceCreated) {
      System.out.println("Deleting service post-test");
      CallBuilder.create().deleteService("domain-uid-admin", "tests");
      serviceCreated = false;
    }
  }

  @Test
  public void createReadListUpdate() throws Exception {
    
    // Domain
    Domain dom = new Domain();
    V1ObjectMeta metadata = new V1ObjectMeta();
    metadata.setResourceVersion("12345");
    metadata.setNamespace("tests");
    dom.setMetadata(metadata);
    
    DomainSpec spec = new DomainSpec();
    spec.setDomainUID("domain-uid");
    spec.setDomainName("base_domain");
    dom.setSpec(spec);

    // Create a new service.
    System.out.println("Creating service");
    
    Step s = ServiceHelper.createForServerStep(null);
    Engine e = new Engine("ServiceHelperTest");
    Packet p = new Packet();
    DomainPresenceInfo info = new DomainPresenceInfo(dom);
    p.getComponents().put(ProcessingConstants.DOMAIN_COMPONENT_NAME, Component.createFor(info));
    p.put(ProcessingConstants.SERVER_NAME, "admin");
    p.put(ProcessingConstants.PORT, Integer.valueOf(7001));

    Fiber f = e.createFiber();
    f.start(s, p, null);
    
    f.get();
    
    // Read the service we just created.
    System.out.println("Reading service");
    V1Service service = CallBuilder.create().readService("domain-uid-admin", "tests");
    checkService(service, false);

    // Get a list of services.
    System.out.println("Listing services");
    V1ServiceList serviceList = CallBuilder.create().listService("tests");
    boolean serviceFound = false;
    for (V1Service item : serviceList.getItems()) {
      if (item.getMetadata().getName().equals("domain-uid-admin")) {
        serviceFound = true;
        break;
      }
    }
    Assert.assertTrue("Expected service domain-uid-admin not found in list", serviceFound);

    // Add a second selector to this service.
    Map<String, String> selector = service.getSpec().getSelector();
    selector.put("domain", "domain-uid");
    service.getSpec().setSelector(selector);
    // TODO: uncomment out when bug calling replace service is fixed.
//        System.out.println("Replacing service");
//        service = serviceHelper.replace("domain-uid-admin", service);
//        checkService(service, true);
  }

  private void checkService(V1Service service, boolean serviceUpdated) {
    Assert.assertTrue("Service name should be domain-uid-admin",
        service.getMetadata().getName().equals("domain-uid-admin"));
    Assert.assertTrue("Service namespace should be tests",
        service.getMetadata().getNamespace().equals("tests"));

    String matchLabel = service.getMetadata().getLabels().get("weblogic.domainUID");
    Assert.assertNotNull("Service label should not be null",
        matchLabel);
    Assert.assertTrue("Service label should be weblogic.domainUID: domain-uid",
        matchLabel.equals("domain-uid"));

    matchLabel = service.getSpec().getSelector().get("weblogic.serverName");
    Assert.assertNotNull("Service selector should not be null",
        matchLabel);
    Assert.assertTrue("Service selector should be weblogic.server: server-admin",
        matchLabel.equals("admin"));

    // A second selector was added when we updated the service.
    if (serviceUpdated) {
      matchLabel = service.getSpec().getSelector().get("weblogic.domainUID");
      Assert.assertNotNull("Service selector should not be null",
          matchLabel);
      Assert.assertTrue("Service selector should be weblogic.domainUID: domain-uid",
          matchLabel.equals("domain-uid"));
    }
    List<V1ServicePort> ports = service.getSpec().getPorts();
    Assert.assertTrue("Service should have one port", ports.size() == 1);
    Assert.assertTrue("Service port should be 7001", ports.get(0).getPort() == 7001);
    Assert.assertTrue("Service node port should be null", ports.get(0).getNodePort() == null);
  }
}