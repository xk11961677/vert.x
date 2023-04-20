package io.vertx.core.http;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.dns.AddressResolverOptions;
import io.vertx.core.dns.DnsClient;
import io.vertx.core.http.impl.HttpClientInternal;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.spi.naming.NameResolver;
import io.vertx.test.core.VertxTestBase;
import io.vertx.test.fakedns.FakeDNSServer;
import org.apache.directory.server.dns.messages.*;
import org.apache.directory.server.dns.store.DnsAttribute;
import org.junit.Assert;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public class ResolvingHttpClientTest extends VertxTestBase {

  private FakeDNSServer dnsServer;
  private InetSocketAddress dnsServerAddress;
  private BiConsumer<Integer, HttpServerRequest> requestHandler;
  private List<HttpServer> servers;

  @Override
  public void setUp() throws Exception {
    dnsServer = new FakeDNSServer();
    dnsServer.start();
    dnsServerAddress = dnsServer.localAddress();
    super.setUp();
  }

  private void startServers(int numServers) throws Exception {
    if (servers != null) {
      throw new IllegalStateException();
    }
    servers = new ArrayList<>();
    for (int i = 0;i < numServers;i++) {
      int val = i;
      HttpServer server = vertx.createHttpServer().requestHandler(req -> {
        BiConsumer<Integer, HttpServerRequest> handler = requestHandler;
        if (handler != null) {
          handler.accept(val, req);
        } else {
          req.response().setStatusCode(404).end();
        }
      });
      servers.add(server);
      awaitFuture(server.listen(8080 + i, "localhost"));
    }
    dnsServer.store(question -> {
      Set<ResourceRecord> set = new HashSet<>();
      if (question.getDomainName().equals("example.com")) {
        for (int i = 0;i < numServers;i++) {
          ResourceRecordModifier rm = new ResourceRecordModifier();
          rm.setDnsClass(RecordClass.IN);
          rm.setDnsName("dns.vertx.io." + i);
          rm.setDnsTtl(100);
          rm.setDnsType(RecordType.SRV);
          rm.put(DnsAttribute.SERVICE_PRIORITY, String.valueOf(1));
          rm.put(DnsAttribute.SERVICE_WEIGHT, String.valueOf(1));
          rm.put(DnsAttribute.SERVICE_PORT, String.valueOf(8080 + i));
          rm.put(DnsAttribute.DOMAIN_NAME, "localhost");
          set.add(rm.getEntry());
        }
      }
      return set;
    });
  }

  @Override
  protected void tearDown() throws Exception {
    dnsServer.stop();
    super.tearDown();
  }

  @Override
  protected VertxOptions getOptions() {
    return super.getOptions().setAddressResolverOptions(getAddressResolverOptions());
  }

  private AddressResolverOptions getAddressResolverOptions() {
    return new AddressResolverOptions()
      .addServer(dnsServerAddress.getAddress().getHostAddress() + ":" + dnsServerAddress.getPort());
  }

  static class SrvState {
    final String name;
    final List<SocketAddress> addresses;
    int index;
    SrvState(String name, List<SocketAddress> addresses) {
      this.name = name;
      this.addresses = addresses;
    }
  }

  private static class SrvNameResolver implements NameResolver<SrvState> {

    private final DnsClient dnsClient;
    private final ConcurrentMap<String, Future<SrvState>> cache = new ConcurrentHashMap<>();

    SrvNameResolver(Vertx vertx) {
      dnsClient = vertx.createDnsClient(53530, "127.0.0.1");
    }

    @Override
    public Future<SrvState> resolve(String name) {
      return cache.computeIfAbsent(name, name_ -> dnsClient.resolveSRV(name_).map(list -> {
        if (list.size() > 0) {
          return new SrvState(name, list.stream()
            .map(record -> SocketAddress.inetSocketAddress(record.port(), record.target()))
            .collect(Collectors.toList()));
        }
        throw new NoSuchElementException("No names");
      }));
    }

    @Override
    public SocketAddress pickName(SrvState state) {
      int idx = state.index++;
      return state.addresses.get(idx % state.addresses.size());
    }

    @Override
    public boolean removeName(SrvState state, SocketAddress socketAddress) {
      state.addresses.remove(socketAddress);
      return state.addresses.isEmpty();
    }

    @Override
    public void dispose(SrvState state) {
      cache.remove(state.name);
    }
  }

  @Test
  public void testResolve() throws Exception {
    int numServers = 2;
    waitFor(numServers * 2);
    requestHandler = (idx, req) -> req.response().end("server-" + idx);
    startServers(numServers);
    HttpClient client = vertx.createHttpClient();
    ((HttpClientInternal)client).nameResolver(new SrvNameResolver(vertx));
    Set<String> responses = Collections.synchronizedSet(new HashSet<>());
    for (int i = 0;i < numServers * 2;i++) {
      client.request(HttpMethod.GET, 8080, "example.com", "/").compose(req -> req
        .send()
        .andThen(onSuccess(resp -> assertEquals(200, resp.statusCode())))
        .compose(HttpClientResponse::body)
      ).onComplete(onSuccess(v -> {
        responses.add(v.toString());
        complete();
      }));
    }
    await();
    Assert.assertEquals(new HashSet<>(Arrays.asList("server-0", "server-1")), responses);
  }

  @Test
  public void testShutdownServers() throws Exception {
    int numServers = 4;
    requestHandler = (idx, req) -> req.response().end("server-" + idx);
    startServers(numServers);
    SrvNameResolver resolver = new SrvNameResolver(vertx);
    HttpClient client = vertx.createHttpClient();
    ((HttpClientInternal)client).nameResolver(resolver);
    CountDownLatch latch = new CountDownLatch(numServers);
    for (int i = 0;i < numServers;i++) {
      client.request(HttpMethod.GET, 8080, "example.com", "/").compose(req -> req
        .send()
        .andThen(onSuccess(resp -> assertEquals(200, resp.statusCode())))
        .compose(HttpClientResponse::body)
      ).onComplete(onSuccess(v -> {
        latch.countDown();
      }));
    }
    awaitLatch(latch);
    SrvState state = resolver.cache.get("example.com").result();
    for (int i = 0;i < servers.size();i++) {
      int expected = state.addresses.size() - 1;
      awaitFuture(servers.get(i).close());
      assertWaitUntil(() -> state.addresses.size() == expected);
    }
    assertWaitUntil(() -> resolver.cache.get("example.com") == null);
  }
}
