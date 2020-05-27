package com.gruelbox.transactionoutbox.jdbc;

import static com.gruelbox.transactionoutbox.Utils.toBlockingFuture;

import com.gruelbox.transactionoutbox.AlreadyScheduledException;
import com.gruelbox.transactionoutbox.Dialect;
import com.gruelbox.transactionoutbox.Invocation;
import com.gruelbox.transactionoutbox.InvocationSerializer;
import com.gruelbox.transactionoutbox.OptimisticLockException;
import com.gruelbox.transactionoutbox.Persistor;
import com.gruelbox.transactionoutbox.TransactionManager;
import com.gruelbox.transactionoutbox.TransactionOutbox;
import com.gruelbox.transactionoutbox.TransactionOutboxEntry;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

/**
 * The default JDBC-based {@link Persistor} for {@link TransactionOutbox}.
 *
 * <p>Saves requests to a relational database table, by default called {@code TXNO_OUTBOX}. This can
 * optionally be automatically created and upgraded by {@link JdbcPersistor}, although this
 * behaviour can be disabled if you wish.
 *
 * <p>All operations are blocking, despite returning {@link CompletableFuture}s. No attempt is made
 * to farm off the I/O to additional threads, which would be unlikely to work with JDBC {@link
 * java.sql.Connection}s. As a result, all methods should simply be called followed immediately with
 * {@link CompletableFuture#get()} to obtain the results.
 *
 * <p>More significant changes can be achieved by subclassing, which is explicitly supported. If, on
 * the other hand, you want to use a completely non-relational underlying data store or do something
 * equally esoteric, you may prefer to implement {@link Persistor} from the ground up.
 */
@Slf4j
@SuperBuilder
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class JdbcPersistor implements Persistor<Connection, JdbcTransaction<?>> {

  /**
   * Uses the default relational persistor. Shortcut for: <code>
   * JdbcPersistor.builder().dialect(dialect).build();</code>
   *
   * @param dialect The database dialect.
   * @return The persistor.
   */
  public static JdbcPersistor forDialect(Dialect dialect) {
    return JdbcPersistor.builder().dialect(dialect).build();
  }

  /**
   * @param writeLockTimeoutSeconds How many seconds to wait before timing out on obtaining a write
   *     lock. There's no point making this long; it's always better to just back off as quickly as
   *     possible and try another record. Generally these lock timeouts only kick in if {@link
   *     Dialect#isSupportsSkipLock()} is false.
   */
  @SuppressWarnings("JavaDoc")
  @Builder.Default
  @NotNull
  private final int writeLockTimeoutSeconds = 2;

  /** @param dialect The database dialect to use. Required. */
  @SuppressWarnings("JavaDoc")
  @NotNull
  private final Dialect dialect;

  /** @param tableName The database table name. The default is {@code TXNO_OUTBOX}. */
  @SuppressWarnings("JavaDoc")
  @Builder.Default
  @NotNull
  private final String tableName = "TXNO_OUTBOX";

  /**
   * @param migrate Set to false to disable automatic database migrations. This may be preferred if
   *     the default migration behaviour interferes with your existing toolset, and you prefer to
   *     manage the migrations explicitly (e.g. using FlyWay or Liquibase), or your do not give the
   *     application DDL permissions at runtime.
   */
  @SuppressWarnings("JavaDoc")
  @Builder.Default
  @NotNull
  private final boolean migrate = true;

  /**
   * @param serializer The serializer to use for {@link Invocation}s. See {@link
   *     InvocationSerializer} for more information. Defaults to {@link
   *     InvocationSerializer#createDefaultJsonSerializer()} with no whitelisted classes..
   */
  @SuppressWarnings("JavaDoc")
  @Builder.Default
  private final InvocationSerializer serializer =
      InvocationSerializer.createDefaultJsonSerializer();

  @Override
  public CompletableFuture<Void> migrate(
      TransactionManager<Connection, ?, ? extends JdbcTransaction<?>> transactionManager) {
    return toBlockingFuture(
        () -> JdbcMigrationManager.migrate((JdbcTransactionManager) transactionManager));
  }

  @Override
  public CompletableFuture<Void> save(JdbcTransaction<?> tx, TransactionOutboxEntry entry) {
    return toBlockingFuture(() -> saveBlocking(tx, entry));
  }

  @Override
  public CompletableFuture<Void> delete(JdbcTransaction<?> tx, TransactionOutboxEntry entry) {
    return toBlockingFuture(() -> deleteBlocking(tx, entry));
  }

  @Override
  public CompletableFuture<Void> update(JdbcTransaction<?> tx, TransactionOutboxEntry entry) {
    return toBlockingFuture(() -> updateBlocking(tx, entry));
  }

  @Override
  public CompletableFuture<Boolean> lock(JdbcTransaction<?> tx, TransactionOutboxEntry entry) {
    return toBlockingFuture(() -> lockBlocking(tx, entry));
  }

  @Override
  public CompletableFuture<Boolean> whitelist(JdbcTransaction<?> tx, String entryId) {
    return toBlockingFuture(() -> whitelistBlocking(tx, entryId));
  }

  @Override
  public CompletableFuture<List<TransactionOutboxEntry>> selectBatch(
      JdbcTransaction<?> tx, int batchSize, Instant now) {
    return toBlockingFuture(() -> selectBatchBlocking(tx, batchSize, now));
  }

  @Override
  public CompletableFuture<Integer> deleteProcessedAndExpired(
      JdbcTransaction<?> tx, int batchSize, Instant now) {
    return toBlockingFuture(() -> deleteProcessedAndExpiredInner(tx, batchSize, now));
  }

  void saveBlocking(JdbcTransaction<?> tx, TransactionOutboxEntry entry) throws SQLException {
    var insertSql =
        "INSERT INTO "
            + tableName
            + " (id, uniqueRequestId, invocation, nextAttemptTime, attempts, blacklisted, processed, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    var writer = new StringWriter();
    serializer.serializeInvocation(entry.getInvocation(), writer);
    if (entry.getUniqueRequestId() == null) {
      PreparedStatement stmt = tx.prepareBatchStatement(insertSql);
      setupInsert(entry, writer, stmt);
      stmt.addBatch();
      log.debug("Inserted {} in batch", entry.description());
    } else {
      try (PreparedStatement stmt = tx.connection().prepareStatement(insertSql)) {
        setupInsert(entry, writer, stmt);
        stmt.executeUpdate();
        log.debug("Inserted {} immediately", entry.description());
      } catch (SQLIntegrityConstraintViolationException e) {
        throw new AlreadyScheduledException(
            "Request " + entry.description() + " already exists", e);
      } catch (Exception e) {
        if (e.getClass().getName().equals("org.postgresql.util.PSQLException")
            && e.getMessage().contains("constraint")) {
          throw new AlreadyScheduledException(
              "Request " + entry.description() + " already exists", e);
        }
      }
    }
  }

  private void setupInsert(
      TransactionOutboxEntry entry, StringWriter writer, PreparedStatement stmt)
      throws SQLException {
    stmt.setString(1, entry.getId());
    stmt.setString(2, entry.getUniqueRequestId());
    stmt.setString(3, writer.toString());
    stmt.setTimestamp(4, Timestamp.from(entry.getNextAttemptTime()));
    stmt.setInt(5, entry.getAttempts());
    stmt.setBoolean(6, entry.isBlacklisted());
    stmt.setBoolean(7, entry.isProcessed());
    stmt.setInt(8, entry.getVersion());
  }

  void deleteBlocking(JdbcTransaction<?> tx, TransactionOutboxEntry entry) throws Exception {
    try (PreparedStatement stmt =
        // language=MySQL
        tx.connection()
            .prepareStatement("DELETE FROM " + tableName + " WHERE id = ? and version = ?")) {
      stmt.setString(1, entry.getId());
      stmt.setInt(2, entry.getVersion());
      if (stmt.executeUpdate() != 1) {
        throw new OptimisticLockException();
      }
      log.debug("Deleted {}", entry.description());
    }
  }

  void updateBlocking(JdbcTransaction<?> tx, TransactionOutboxEntry entry) throws Exception {
    try (PreparedStatement stmt =
        tx.connection()
            .prepareStatement(
                // language=MySQL
                "UPDATE "
                    + tableName
                    + " "
                    + "SET nextAttemptTime = ?, attempts = ?, blacklisted = ?, processed = ?, version = ? "
                    + "WHERE id = ? and version = ?")) {
      stmt.setTimestamp(1, Timestamp.from(entry.getNextAttemptTime()));
      stmt.setInt(2, entry.getAttempts());
      stmt.setBoolean(3, entry.isBlacklisted());
      stmt.setBoolean(4, entry.isProcessed());
      stmt.setInt(5, entry.getVersion() + 1);
      stmt.setString(6, entry.getId());
      stmt.setInt(7, entry.getVersion());
      if (stmt.executeUpdate() != 1) {
        throw new OptimisticLockException();
      }
      entry.setVersion(entry.getVersion() + 1);
      log.debug("Updated {}", entry.description());
    }
  }

  boolean lockBlocking(JdbcTransaction<?> tx, TransactionOutboxEntry entry) throws Exception {
    try (PreparedStatement stmt =
        tx.connection()
            .prepareStatement(
                dialect.isSupportsSkipLock()
                    // language=MySQL
                    ? "SELECT id FROM "
                        + tableName
                        + " WHERE id = ? AND version = ? FOR UPDATE SKIP LOCKED"
                    // language=MySQL
                    : "SELECT id FROM " + tableName + " WHERE id = ? AND version = ? FOR UPDATE")) {
      stmt.setString(1, entry.getId());
      stmt.setInt(2, entry.getVersion());
      stmt.setQueryTimeout(writeLockTimeoutSeconds);
      return gotRecord(entry, stmt);
    }
  }

  boolean whitelistBlocking(JdbcTransaction<?> tx, String entryId) throws Exception {
    PreparedStatement stmt =
        tx.prepareBatchStatement(
            "UPDATE "
                + tableName
                + " SET attempts = 0, blacklisted = false "
                + "WHERE blacklisted = true AND processed = false AND id = ?");
    stmt.setString(1, entryId);
    stmt.setQueryTimeout(writeLockTimeoutSeconds);
    return stmt.executeUpdate() != 0;
  }

  List<TransactionOutboxEntry> selectBatchBlocking(
      JdbcTransaction<?> tx, int batchSize, Instant now) throws Exception {
    String forUpdate = dialect.isSupportsSkipLock() ? " FOR UPDATE SKIP LOCKED" : "";
    try (PreparedStatement stmt =
        tx.connection()
            .prepareStatement(
                // language=MySQL
                "SELECT id, uniqueRequestId, invocation, nextAttemptTime, attempts, blacklisted, processed, version FROM "
                    + tableName
                    + " WHERE nextAttemptTime < ? AND blacklisted = false AND processed = false LIMIT ?"
                    + forUpdate)) {
      stmt.setTimestamp(1, Timestamp.from(now));
      stmt.setInt(2, batchSize);
      return gatherResults(batchSize, stmt);
    }
  }

  private int deleteProcessedAndExpiredInner(JdbcTransaction<?> tx, int batchSize, Instant now)
      throws Exception {
    try (PreparedStatement stmt =
        tx.connection()
            .prepareStatement(dialect.getDeleteExpired().replace("{{table}}", tableName))) {
      stmt.setTimestamp(1, Timestamp.from(now));
      stmt.setInt(2, batchSize);
      return stmt.executeUpdate();
    }
  }

  private List<TransactionOutboxEntry> gatherResults(int batchSize, PreparedStatement stmt)
      throws SQLException, IOException {
    try (ResultSet rs = stmt.executeQuery()) {
      ArrayList<TransactionOutboxEntry> result = new ArrayList<>(batchSize);
      while (rs.next()) {
        result.add(map(rs));
      }
      log.debug("Found {} results", result.size());
      return result;
    }
  }

  private boolean gotRecord(TransactionOutboxEntry entry, PreparedStatement stmt)
      throws SQLException {
    try {
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next();
      }
    } catch (SQLTimeoutException e) {
      log.debug("Lock attempt timed out on {}", entry.description());
      return false;
    }
  }

  private TransactionOutboxEntry map(ResultSet rs) throws SQLException, IOException {
    try (Reader invocationStream = rs.getCharacterStream("invocation")) {
      TransactionOutboxEntry entry =
          TransactionOutboxEntry.builder()
              .id(rs.getString("id"))
              .uniqueRequestId(rs.getString("uniqueRequestId"))
              .invocation(serializer.deserializeInvocation(invocationStream))
              .nextAttemptTime(rs.getTimestamp("nextAttemptTime").toInstant())
              .attempts(rs.getInt("attempts"))
              .blacklisted(rs.getBoolean("blacklisted"))
              .processed(rs.getBoolean("processed"))
              .version(rs.getInt("version"))
              .build();
      log.debug("Found {}", entry);
      return entry;
    }
  }

  // For testing. Assumed low volume.
  void clear(JdbcTransaction<?> tx) throws SQLException {
    try (Statement stmt = tx.connection().createStatement()) {
      stmt.execute("DELETE FROM " + tableName);
    }
  }
}