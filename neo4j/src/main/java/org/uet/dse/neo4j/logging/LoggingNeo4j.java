package org.uet.dse.neo4j.logging;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.Consumer;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Query;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.TransactionCallback;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.TransactionWork;
import org.neo4j.driver.Value;

public final class LoggingNeo4j {
  private LoggingNeo4j() {}

  public static Driver wrap(Driver driver) {
    return wrapInterface(Driver.class, driver, "driver");
  }

  public static Session wrap(Session session) {
    return wrapInterface(Session.class, session, "session");
  }

  private static Transaction wrap(Transaction transaction) {
    return wrapInterface(Transaction.class, transaction, "transaction");
  }

  private static TransactionContext wrap(TransactionContext transactionContext) {
    return wrapInterface(TransactionContext.class, transactionContext, "transaction");
  }

  private static <T> T wrapInterface(Class<T> type, T delegate, String scope) {
    if (delegate == null || isLoggingProxy(delegate)) {
      return delegate;
    }
    InvocationHandler handler = new LoggingInvocationHandler(delegate, scope);
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }

  private static boolean isLoggingProxy(Object delegate) {
    return Proxy.isProxyClass(delegate.getClass())
        && Proxy.getInvocationHandler(delegate) instanceof LoggingInvocationHandler;
  }

  private static final class LoggingInvocationHandler implements InvocationHandler {
    private final Object delegate;
    private final String scope;

    private LoggingInvocationHandler(Object delegate, String scope) {
      this.delegate = delegate;
      this.scope = scope;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if ("run".equals(method.getName())) {
        logRun(scope + ".run", args);
      }

      Object[] invocationArgs = wrapCallbackArgs(method.getName(), args);
      try {
        Object result = method.invoke(delegate, invocationArgs);
        return wrapReturn(method.getName(), result);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }

    private Object[] wrapCallbackArgs(String methodName, Object[] args) {
      if (!isTransactionCallbackMethod(methodName) || args == null || args.length == 0 || args[0] == null) {
        return args;
      }

      Object[] wrapped = args.clone();
      Object first = args[0];
      if (first instanceof TransactionCallback<?> callback) {
        wrapped[0] = (TransactionCallback<Object>) tx -> callback.execute(wrap(tx));
      } else if (first instanceof TransactionWork<?> work) {
        wrapped[0] = (TransactionWork<Object>) tx -> work.execute(wrap(tx));
      } else if (first instanceof Consumer<?> consumer) {
        @SuppressWarnings("unchecked")
        Consumer<TransactionContext> original = (Consumer<TransactionContext>) consumer;
        wrapped[0] = (Consumer<TransactionContext>) tx -> original.accept(wrap(tx));
      }
      return wrapped;
    }

    private boolean isTransactionCallbackMethod(String methodName) {
      return "executeRead".equals(methodName)
          || "executeWrite".equals(methodName)
          || "executeWriteWithoutResult".equals(methodName)
          || "readTransaction".equals(methodName)
          || "writeTransaction".equals(methodName);
    }

    private Object wrapReturn(String methodName, Object result) {
      if ("session".equals(methodName) && result instanceof Session session) {
        return wrap(session);
      }
      if ("beginTransaction".equals(methodName) && result instanceof Transaction transaction) {
        return wrap(transaction);
      }
      return result;
    }

    private void logRun(String label, Object[] args) {
      if (args == null || args.length == 0 || args[0] == null) return;

      Object queryArg = args[0];
      if (queryArg instanceof Query query) {
        CypherQueryLogger.log(label, query.text(), query.parameters());
        return;
      }

      if (!(queryArg instanceof String cypher)) return;
      if (args.length < 2 || args[1] == null) {
        CypherQueryLogger.log(label, cypher);
        return;
      }

      Object params = args[1];
      if (params instanceof Map<?, ?> map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> typedMap = (Map<String, Object>) map;
        CypherQueryLogger.log(label, cypher, typedMap);
      } else if (params instanceof Value value) {
        CypherQueryLogger.log(label, cypher, value);
      } else if (params instanceof Record record) {
        CypherQueryLogger.log(label, cypher, record.asMap());
      } else if (params instanceof TransactionConfig) {
        CypherQueryLogger.log(label, cypher);
      } else {
        CypherQueryLogger.log(label, cypher, Map.of("parameters", params));
      }
    }
  }
}
