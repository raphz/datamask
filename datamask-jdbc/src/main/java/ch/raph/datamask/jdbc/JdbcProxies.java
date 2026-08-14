package ch.raph.datamask.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * The wrapper itself: a JDK proxy per connection, statement and result set that forwards every call
 * and sanitises every {@link java.sql.SQLException} on the way back out.
 *
 * <p>A proxy rather than hand-written delegates because the interfaces involved carry well over
 * three hundred methods between them, all of which would have to be forwarded identically and every
 * one of which would be a place to get it wrong. Forwarding reflectively is what Spring's
 * {@code TransactionAwareDataSourceProxy}, p6spy and datasource-proxy all do, and it is what keeps
 * this wrapper honest when a driver adds a method: it is forwarded without being taught about it.
 *
 * <p>Result sets are proxied too, which is the one place the cost is worth stating plainly: an
 * error can surface during a fetch rather than at execution — a cast failing on a stored value, a
 * statement timeout — and in cursor mode that means it arrives from {@code next()}. Leaving result
 * sets unwrapped would put a hole exactly in the path that reads data.
 */
final class JdbcProxies {

    private JdbcProxies() {}

    static Connection connection(Connection target, JdbcMasking masking) {
        return (Connection) proxy(Connection.class, new ConnectionHandler(target, masking));
    }

    private static Object proxy(Class<?> jdbcInterface, JdbcHandler handler) {
        // Every interface proxied here is in java.sql, so this loader can always see them.
        return Proxy.newProxyInstance(JdbcProxies.class.getClassLoader(), new Class<?>[] {jdbcInterface}, handler);
    }

    /** The most specific JDBC statement interface the target presents. */
    private static Class<?> statementInterface(Statement target) {
        if (target instanceof CallableStatement) {
            return CallableStatement.class;
        }
        return target instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
    }

    /** Forwards everything, sanitises what comes back out, and re-wraps whatever it hands over. */
    private abstract static class JdbcHandler implements InvocationHandler {

        final Object target;
        final JdbcMasking masking;

        JdbcHandler(Object target, JdbcMasking masking) {
            this.target = target;
            this.masking = masking;
        }

        @Override
        public final Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args);
            }

            Object result;
            try {
                before(method, args);
                result = method.invoke(target, args);
            } catch (InvocationTargetException e) {
                // The one line this whole wrapper exists for.
                throw masking.translate(e.getCause());
            }
            return adapt(proxy, method, args, result);
        }

        /**
         * {@code equals} has to compare the proxy, not the connection behind it: two proxies over one
         * pooled connection are two separate handles, and a pool that closed one because it compared
         * equal to the other would be a genuine surprise. {@code toString} is forwarded, because the
         * delegate's is what identifies the connection in a log.
         */
        private Object objectMethod(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> method.invoke(target, args);
            };
        }

        void before(Method method, Object[] args) {}

        Object adapt(Object proxy, Method method, Object[] args, Object result) {
            return result;
        }
    }

    private static final class ConnectionHandler extends JdbcHandler {

        ConnectionHandler(Connection target, JdbcMasking masking) {
            super(target, masking);
        }

        @Override
        Object adapt(Object proxy, Method method, Object[] args, Object result) {
            if (result instanceof Statement statement) {
                // prepareStatement and prepareCall take the SQL first; createStatement has none, and
                // its SQL turns up at execute time instead.
                String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : null;
                return Proxy.newProxyInstance(
                        JdbcProxies.class.getClassLoader(),
                        new Class<?>[] {statementInterface(statement)},
                        new StatementHandler(statement, masking, sql, proxy));
            }
            return result;
        }
    }

    private static final class StatementHandler extends JdbcHandler {

        private final BoundParameters parameters = new BoundParameters();
        private final Object connectionProxy;
        private String sql;

        StatementHandler(Statement target, JdbcMasking masking, String sql, Object connectionProxy) {
            super(target, masking);
            this.sql = sql;
            this.connectionProxy = connectionProxy;
        }

        @Override
        void before(Method method, Object[] args) {
            String name = method.getName();
            boolean executing = name.startsWith("execute") || "addBatch".equals(name);
            if (!executing && !masking.recordsParameters()) {
                return;
            }

            if (isParameterSetter(method, args)) {
                parameters.record(String.valueOf(args[0]), masking.maskParameter(args[1], "jdbc:param/" + args[0]));
            } else if ("clearParameters".equals(name)) {
                parameters.clear();
            } else if (executing) {
                // Statement.execute(String) and friends carry the SQL at call time, not at creation.
                if (args != null && args.length > 0 && args[0] instanceof String statementSql) {
                    sql = statementSql;
                }
                masking.logStatement(sql, parameters);
            }
        }

        /**
         * A parameter setter, as opposed to {@code setFetchSize} or {@code setQueryTimeout}: declared
         * on {@link PreparedStatement} or {@link CallableStatement}, named {@code setX}, and taking a
         * parameter index or a parameter name first.
         */
        private static boolean isParameterSetter(Method method, Object[] args) {
            Class<?> declaring = method.getDeclaringClass();
            if (declaring != PreparedStatement.class && declaring != CallableStatement.class) {
                return false;
            }
            if (!method.getName().startsWith("set") || args == null || args.length < 2) {
                return false;
            }
            Class<?> first = method.getParameterTypes()[0];
            return first == int.class || first == String.class;
        }

        @Override
        Object adapt(Object proxy, Method method, Object[] args, Object result) {
            if (result instanceof ResultSet resultSet) {
                return Proxy.newProxyInstance(
                        JdbcProxies.class.getClassLoader(),
                        new Class<?>[] {ResultSet.class},
                        new ResultSetHandler(resultSet, masking, proxy));
            }
            // Staying inside the wrapper: a caller navigating back to the connection through the
            // statement must not come out holding the unwrapped one.
            if (result instanceof Connection && connectionProxy != null) {
                return connectionProxy;
            }
            return result;
        }
    }

    private static final class ResultSetHandler extends JdbcHandler {

        private final Object statementProxy;

        ResultSetHandler(ResultSet target, JdbcMasking masking, Object statementProxy) {
            super(target, masking);
            this.statementProxy = statementProxy;
        }

        @Override
        Object adapt(Object proxy, Method method, Object[] args, Object result) {
            return result instanceof Statement && statementProxy != null ? statementProxy : result;
        }
    }
}
