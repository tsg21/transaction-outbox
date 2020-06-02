package com.gruelbox.transactionoutbox;

import com.gruelbox.transactionoutbox.spi.Transaction;

@FunctionalInterface
public interface ThrowingTransactionalWork<E extends Exception, TX extends Transaction<?, ?>> {

  void doWork(TX transaction) throws E;
}
