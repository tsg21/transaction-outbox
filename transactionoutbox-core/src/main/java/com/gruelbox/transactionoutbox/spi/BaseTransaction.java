package com.gruelbox.transactionoutbox.spi;

import com.gruelbox.transactionoutbox.Persistor;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Access and manipulation of a currently-active transaction. This is an extremely high-level
 * generalisation; it is advised that you refer to subtypes.
 *
 * @param <CN> The type which the associated {@link Persistor} implementation will use to interact
 *     with the data store.
 */
public interface BaseTransaction<CN> {

  /**
   * @return The object used by the associated {@link Persistor} to interact with the data store.
   */
  CN connection();

  /**
   * @param <T> The context type. Coerced on read.
   * @return A {@link BaseTransactionManager}-specific object representing the context of this
   *     transaction. Intended for use with {@link BaseTransactionManager} implementations that
   *     support explicitly-passed transaction context injection into method arguments.
   */
  default <T> T context() {
    return null;
  }

  /**
   * Will be called to perform work immediately after the current transaction is committed. This
   * should occur in the same thread and will generally not be long-lasting.
   *
   * @param hook The code to run post-commit.
   */
  void addPostCommitHook(Supplier<CompletableFuture<Void>> hook);
}