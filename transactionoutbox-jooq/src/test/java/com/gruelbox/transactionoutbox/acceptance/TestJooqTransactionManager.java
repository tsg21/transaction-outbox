package com.gruelbox.transactionoutbox.acceptance;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.fail;

import com.gruelbox.transactionoutbox.Dialect;
import com.gruelbox.transactionoutbox.Instantiator;
import com.gruelbox.transactionoutbox.JooqTransactionListener;
import com.gruelbox.transactionoutbox.JooqTransactionManager;
import com.gruelbox.transactionoutbox.Persistor;
import com.gruelbox.transactionoutbox.ThrowingRunnable;
import com.gruelbox.transactionoutbox.TransactionManager;
import com.gruelbox.transactionoutbox.TransactionOutbox;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.hamcrest.MatcherAssert;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.ThreadLocalTransactionProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class TestJooqTransactionManager {

  private final ExecutorService unreliablePool =
      new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16));

  private HikariDataSource dataSource;
  private TransactionManager transactionManager;
  private DSLContext dsl;

  @BeforeEach
  void beforeEach() {
    dataSource = pooledDataSource();
    transactionManager = createTransactionManager();
  }

  @AfterEach
  void afterEach() {
    dsl.close();
    dataSource.close();
  }

  private HikariDataSource pooledDataSource() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(
        "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;DEFAULT_LOCK_TIMEOUT=60000;LOB_TIMEOUT=2000;MV_STORE=TRUE");
    config.setUsername("test");
    config.setPassword("test");
    config.addDataSourceProperty("cachePrepStmts", "true");
    return new HikariDataSource(config);
  }

  private TransactionManager createTransactionManager() {
    DataSourceConnectionProvider connectionProvider = new DataSourceConnectionProvider(dataSource);
    DefaultConfiguration configuration = new DefaultConfiguration();
    configuration.setConnectionProvider(connectionProvider);
    configuration.setSQLDialect(SQLDialect.H2);
    configuration.setTransactionProvider(new ThreadLocalTransactionProvider(connectionProvider));
    JooqTransactionListener listener = JooqTransactionManager.createListener(configuration);
    dsl = DSL.using(configuration);
    return JooqTransactionManager.create(dsl, listener);
  }

  @Test
  void testSimpleDirectInvocation() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .persistor(Persistor.forDialect(Dialect.H2).build())
            .listener(entry -> latch.countDown())
            .build();

    clearOutbox(transactionManager);

    transactionManager.inTransaction(
        () -> {
          outbox.schedule(Worker.class).process(1);
          try {
            // Should not be fired until after commit
            Assertions.assertFalse(latch.await(2, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            fail("Interrupted");
          }
        });

    // Should be fired after commit
    Assertions.assertTrue(latch.await(2, TimeUnit.SECONDS));
  }

  @Test
  @Disabled // TODO support this. What's going on?
  void testNestedDirectInvocation() throws InterruptedException {
    CountDownLatch latch1 = new CountDownLatch(1);
    CountDownLatch latch2 = new CountDownLatch(1);
    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .persistor(Persistor.forDialect(Dialect.H2).build())
            .listener(
                entry -> {
                  if (entry.getInvocation().getArgs()[0].equals(1)) {
                    latch1.countDown();
                  } else {
                    latch2.countDown();
                  }
                })
            .build();

    clearOutbox(transactionManager);

    transactionManager.inTransactionThrows(
        tx1 -> {
          outbox.schedule(Worker.class).process(1);

          transactionManager.inTransactionThrows(
              tx2 -> {
                outbox.schedule(Worker.class).process(2);

                // Should not be fired until after commit
                Assertions.assertFalse(latch2.await(2, TimeUnit.SECONDS));
              });

          // Should be fired after commit
          Assertions.assertTrue(latch2.await(2, TimeUnit.SECONDS));

          try {
            // Should not be fired until after commit
            Assertions.assertFalse(latch1.await(2, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            fail("Interrupted");
          }
        });

    // Should be fired after commit
    Assertions.assertTrue(latch1.await(2, TimeUnit.SECONDS));
  }

  @Test
  void testSimpleViaListener() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .persistor(Persistor.forDialect(Dialect.H2).build())
            .listener(entry -> latch.countDown())
            .build();

    clearOutbox(transactionManager);

    dsl.transaction(
        () -> {
          outbox.schedule(Worker.class).process(1);
          try {
            // Should not be fired until after commit
            Assertions.assertFalse(latch.await(2, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            fail("Interrupted");
          }
        });

    // Should be fired after commit
    Assertions.assertTrue(latch.await(2, TimeUnit.SECONDS));
  }

  @Test
  @Disabled // TODO support this. What's going on?
  void testNestedViaListener() throws InterruptedException {
    CountDownLatch latch1 = new CountDownLatch(1);
    CountDownLatch latch2 = new CountDownLatch(1);
    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .persistor(Persistor.forDialect(Dialect.H2).build())
            .listener(
                entry -> {
                  if (entry.getInvocation().getArgs()[0].equals(1)) {
                    latch1.countDown();
                  } else {
                    latch2.countDown();
                  }
                })
            .build();

    clearOutbox(transactionManager);

    dsl.transaction(
        ctx -> {
          outbox.schedule(Worker.class).process(1);

          ctx.dsl()
              .transaction(
                  () -> {
                    outbox.schedule(Worker.class).process(2);
                    try {
                      // Should not be fired until after commit
                      Assertions.assertFalse(latch2.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                      fail("Interrupted");
                    }
                  });

          // Should be fired after commit
          Assertions.assertTrue(latch2.await(2, TimeUnit.SECONDS));

          try {
            // Should not be fired until after commit
            Assertions.assertFalse(latch1.await(2, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            fail("Interrupted");
          }
        });

    // Should be fired after commit
    Assertions.assertTrue(latch1.await(2, TimeUnit.SECONDS));
  }

  @Test
  void retryBehaviour() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger attempts = new AtomicInteger();
    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .persistor(Persistor.forDialect(Dialect.H2).build())
            .instantiator(new FailingInstantiator())
            .executor(unreliablePool)
            .attemptFrequency(Duration.ofSeconds(1))
            .listener(entry -> latch.countDown())
            .build();

    clearOutbox(transactionManager);

    withRunningFlusher(
        outbox,
        () -> {
          transactionManager.inTransaction(() -> outbox.schedule(InterfaceWorker.class).process(3));
          Assertions.assertTrue(latch.await(15, TimeUnit.SECONDS));
        });
  }

  @Test
  void highVolumeUnreliable() throws Exception {
    int count = 10;

    CountDownLatch latch = new CountDownLatch(count * 10);
    ConcurrentHashMap<Integer, Integer> results = new ConcurrentHashMap<>();
    ConcurrentHashMap<Integer, Integer> duplicates = new ConcurrentHashMap<>();

    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .persistor(Persistor.forDialect(Dialect.H2).build())
            .instantiator(new FailingInstantiator())
            .executor(unreliablePool)
            .attemptFrequency(Duration.ofSeconds(1))
            .flushBatchSize(1000)
            .listener(
                entry -> {
                  Integer i = (Integer) entry.getInvocation().getArgs()[0];
                  if (results.putIfAbsent(i, i) != null) {
                    duplicates.put(i, i);
                  }
                  latch.countDown();
                })
            .build();

    withRunningFlusher(
        outbox,
        () -> {
          IntStream.range(0, count)
              .parallel()
              .forEach(
                  i ->
                      dsl.transaction(
                          () -> {
                            for (int j = 0; j < 10; j++) {
                              outbox.schedule(InterfaceWorker.class).process(i * 10 + j);
                            }
                          }));
          Assertions.assertTrue(latch.await(30, TimeUnit.SECONDS));
        });

    MatcherAssert.assertThat(
        "Should never get duplicates running to full completion", duplicates.keySet(), empty());
    MatcherAssert.assertThat(
        "Only got: " + results.keySet(),
        results.keySet(),
        containsInAnyOrder(IntStream.range(0, count * 10).boxed().toArray()));
  }

  private void clearOutbox(TransactionManager transactionManager) {
    TestUtils.runSql(transactionManager, "DELETE FROM TXNO_OUTBOX");
  }

  private void withRunningFlusher(TransactionOutbox outbox, ThrowingRunnable runnable)
      throws Exception {
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    try {
      scheduler.scheduleAtFixedRate(
          () -> {
            if (Thread.interrupted()) {
              return;
            }
            outbox.flush();
          },
          500,
          500,
          TimeUnit.MILLISECONDS);
      runnable.run();
    } finally {
      scheduler.shutdown();
      Assertions.assertTrue(scheduler.awaitTermination(20, TimeUnit.SECONDS));
    }
  }

  interface InterfaceWorker {

    void process(int i);
  }

  @SuppressWarnings("EmptyMethod")
  static class Worker {

    @SuppressWarnings("SameParameterValue")
    void process(int i) {
      // No-op
    }
  }

  private static class FailingInstantiator implements Instantiator {

    private final AtomicInteger attempts;

    FailingInstantiator() {
      this.attempts = new AtomicInteger(0);
    }

    @Override
    public String getName(Class<?> clazz) {
      return clazz.getName();
    }

    @Override
    public Object getInstance(String name) {
      return (InterfaceWorker)
          (i) -> {
            if (attempts.incrementAndGet() < 3) {
              throw new RuntimeException("Temporary failure");
            }
          };
    }
  }
}