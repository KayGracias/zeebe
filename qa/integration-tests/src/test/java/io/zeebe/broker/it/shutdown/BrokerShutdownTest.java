/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.broker.it.shutdown;

import io.zeebe.broker.Broker;
import io.zeebe.broker.Loggers;
import io.zeebe.broker.it.EmbeddedBrokerRule;
import io.zeebe.broker.system.configuration.NetworkCfg;
import io.zeebe.broker.transport.TransportServiceNames;
import io.zeebe.client.api.events.*;
import io.zeebe.servicecontainer.*;
import io.zeebe.transport.impl.ServerSocketBinding;
import io.zeebe.util.sched.future.CompletableActorFuture;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.assertj.core.util.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BrokerShutdownTest {

  private static final ServiceName<Void> BLOCK_BROKER_SERVICE_NAME =
      ServiceName.newServiceName("blockService", Void.class);

  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void shouldReleaseSockets() {
    // given
    final Broker broker = startBrokerWithBlockingService();
    broker.getBrokerContext().setCloseTimeout(1);

    // when
    broker.close();

    // then
    final NetworkCfg networkCfg = broker.getBrokerContext().getBrokerConfiguration().getNetwork();

    final InetSocketAddress managementSocket =
        networkCfg.getManagement().toSocketAddress().toInetSocketAddress();
    new ServerSocketBinding(managementSocket).doBind();

    final InetSocketAddress replicationSocket =
        networkCfg.getReplication().toSocketAddress().toInetSocketAddress();
    new ServerSocketBinding(replicationSocket).doBind();

    final InetSocketAddress clientSocket =
        networkCfg.getClient().toSocketAddress().toInetSocketAddress();
    new ServerSocketBinding(clientSocket).doBind();
  }

  private Broker startBrokerWithBlockingService() {
    final File brokerBase = Files.newTemporaryFolder();

    final Broker broker;
    try (InputStream configStream =
        EmbeddedBrokerRule.class.getResourceAsStream("/zeebe.default.cfg.toml")) {
      broker = new Broker(configStream, brokerBase.getAbsolutePath(), null);
    } catch (final IOException e) {
      throw new RuntimeException("Unable to open configuration", e);
    }

    final ServiceContainer serviceContainer = broker.getBrokerContext().getServiceContainer();

    try {
      // blocks on shutdown
      serviceContainer
          .createService(BLOCK_BROKER_SERVICE_NAME, new BlockingService())
          .dependency(
              TransportServiceNames.bufferingServerTransport(
                  TransportServiceNames.MANAGEMENT_API_SERVER_NAME))
          .dependency(
              TransportServiceNames.serverTransport(TransportServiceNames.CLIENT_API_SERVER_NAME))
          .dependency(
              TransportServiceNames.serverTransport(
                  TransportServiceNames.REPLICATION_API_SERVER_NAME))
          .install()
          .get(25, TimeUnit.SECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      throw new RuntimeException(
          "System patition not installed into the container withing 25 seconds.");
    }
    return broker;
  }

  private class BlockingService implements Service<Void> {
    @Override
    public void start(ServiceStartContext startContext) {}

    @Override
    public void stop(ServiceStopContext stopContext) {
      final CompletableActorFuture<Void> neverCompletingFuture = new CompletableActorFuture<>();
      stopContext.async(neverCompletingFuture);
    }

    @Override
    public Void get() {
      return null;
    }
  }
}