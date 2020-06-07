package com.gruelbox.transactionoutbox.acceptance;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;

import com.gruelbox.transactionoutbox.Instantiator;
import com.gruelbox.transactionoutbox.Utils;
import java.util.PrimitiveIterator;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RandomFailingInstantiator implements Instantiator {

  private static final PrimitiveIterator.OfInt randoms = new Random().ints(0, 19).iterator();

  @Override
  public String getName(Class<?> clazz) {
    return clazz.getName();
  }

  @Override
  @SneakyThrows
  public Object getInstance(String name) {
    Class<?> clazz = Class.forName(name);
    return Utils.createProxy(
        clazz,
        (method, args) -> {
          Utils.logMethodCall("Enter {}.{}({})", clazz, method, args);
          if (randoms.next() == 5) {
            Utils.logMethodCall("Failed {}.{}({})", clazz, method, args);
            if (CompletableFuture.class.isAssignableFrom(method.getReturnType())) {
              return failedFuture(new RuntimeException("Temporary failure"));
            } else {
              throw new RuntimeException("Temporary failure");
            }
          }
          Utils.logMethodCall("Exit {}.{}({})", clazz, method, args);
          return completedFuture(null);
        });
  }
}