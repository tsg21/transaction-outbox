package com.gruelbox.transactionoutbox.acceptance;

import com.gruelbox.transactionoutbox.Instantiator;
import com.gruelbox.transactionoutbox.Utils;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class InsertingInstantiator implements Instantiator {

  private final Function<Object, CompletableFuture<?>> inserter;

  @Override
  public String getName(Class<?> clazz) {
    return clazz.getName();
  }

  @Override
  @SneakyThrows
  public Object getInstance(String name) {
    return Utils.createProxy(
        Class.forName(name),
        (method, args) -> {
          Utils.logMethodCall("Enter {}.{}({})", method.getDeclaringClass(), method, args);
          return inserter.apply(args[2]);
        });
  }
}
