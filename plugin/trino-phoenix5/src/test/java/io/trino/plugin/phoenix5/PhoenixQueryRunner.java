/*
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
package io.trino.plugin.phoenix5;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.airlift.log.Logger;
import io.trino.Session;
import io.trino.metadata.QualifiedObjectName;
import io.trino.plugin.base.util.Closables;
import io.trino.plugin.tpch.TpchPlugin;
import io.trino.testing.DistributedQueryRunner;
import io.trino.testing.QueryRunner;
import io.trino.tpch.TpchTable;
import org.apache.hadoop.hbase.NamespaceExistException;
import org.apache.phoenix.exception.PhoenixIOException;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static io.trino.plugin.tpch.TpchMetadata.TINY_SCHEMA_NAME;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static io.trino.tpch.TpchTable.LINE_ITEM;
import static io.trino.tpch.TpchTable.ORDERS;
import static io.trino.tpch.TpchTable.PART_SUPPLIER;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public final class PhoenixQueryRunner
{
    private static final Logger LOG = Logger.get(PhoenixQueryRunner.class);
    private static final String TPCH_SCHEMA = "tpch";

    private PhoenixQueryRunner() {}

    public static Builder builder(TestingPhoenixServer phoenixServer)
    {
        return new Builder(phoenixServer)
                .addConnectorProperty("phoenix.connection-url", phoenixServer.getJdbcUrl())
                .addConnectorProperty("case-insensitive-name-matching", "true");
    }

    public static class Builder
            extends DistributedQueryRunner.Builder<Builder>
    {
        private final TestingPhoenixServer phoenixServer;
        private final Map<String, String> connectorProperties = new HashMap<>();
        private List<TpchTable<?>> initialTables = ImmutableList.of();

        private Builder(TestingPhoenixServer phoenixServer)
        {
            super(testSessionBuilder()
                    .setCatalog("phoenix")
                    .setSchema(TPCH_SCHEMA)
                    .build());
            this.phoenixServer = requireNonNull(phoenixServer, "phoenixServer is null");
        }

        @CanIgnoreReturnValue
        public Builder addConnectorProperty(String key, String value)
        {
            this.connectorProperties.put(key, value);
            return this;
        }

        @CanIgnoreReturnValue
        public Builder setInitialTables(List<TpchTable<?>> initialTables)
        {
            this.initialTables = ImmutableList.copyOf(initialTables);
            return this;
        }

        @Override
        public DistributedQueryRunner build()
                throws Exception
        {
            DistributedQueryRunner queryRunner = super.build();
            try {
                queryRunner.installPlugin(new TpchPlugin());
                queryRunner.createCatalog("tpch", "tpch");

                queryRunner.installPlugin(new PhoenixPlugin());
                queryRunner.createCatalog("phoenix", "phoenix5", connectorProperties);

                createSchema(phoenixServer, TPCH_SCHEMA);
                copyTpchTables(queryRunner, "tpch", TINY_SCHEMA_NAME, createSession(), initialTables);

                return queryRunner;
            }
            catch (Throwable e) {
                Closables.closeAllSuppress(e, queryRunner);
                throw e;
            }
        }
    }

    private static void createSchema(TestingPhoenixServer phoenixServer, String schema)
            throws SQLException
    {
        Properties properties = new Properties();
        properties.setProperty("phoenix.schema.isNamespaceMappingEnabled", "true");
        try (Connection connection = DriverManager.getConnection(phoenixServer.getJdbcUrl(), properties);
                Statement statement = connection.createStatement()) {
            statement.execute(format("CREATE SCHEMA IF NOT EXISTS %s", schema));
        }
        catch (PhoenixIOException e) {
            if (e.getCause() instanceof NamespaceExistException) {
                // Phoenix may throw this exception even if we specify IF NOT EXISTS option
                LOG.debug("Namespace %s already exists", schema);
                return;
            }
            throw e;
        }
    }

    private static void copyTpchTables(
            QueryRunner queryRunner,
            String sourceCatalog,
            String sourceSchema,
            Session session,
            Iterable<TpchTable<?>> tables)
    {
        LOG.debug("Loading data from %s.%s...", sourceCatalog, sourceSchema);
        for (TpchTable<?> table : tables) {
            copyTable(queryRunner, sourceCatalog, session, sourceSchema, table);
        }
    }

    private static void copyTable(
            QueryRunner queryRunner,
            String catalog,
            Session session,
            String schema,
            TpchTable<?> table)
    {
        QualifiedObjectName source = new QualifiedObjectName(catalog, schema, table.getTableName());
        String target = table.getTableName();
        String tableProperties = "";
        if (LINE_ITEM.getTableName().equals(target)) {
            tableProperties = "WITH (ROWKEYS = 'ORDERKEY,LINENUMBER', SALT_BUCKETS=10)";
        }
        else if (ORDERS.getTableName().equals(target)) {
            tableProperties = "WITH (SALT_BUCKETS=10)";
        }
        else if (PART_SUPPLIER.getTableName().equals(target)) {
            tableProperties = "WITH (ROWKEYS = 'PARTKEY,SUPPKEY')";
        }
        @Language("SQL")
        String sql = format("CREATE TABLE IF NOT EXISTS %s %s AS SELECT * FROM %s", target, tableProperties, source);

        LOG.debug("Running import for %s %s", target, sql);
        long rows = queryRunner.execute(session, sql).getUpdateCount().getAsLong();
        LOG.debug("%s rows loaded into %s", rows, target);
    }

    private static Session createSession()
    {
        return testSessionBuilder()
                .setCatalog("phoenix")
                .setSchema(TPCH_SCHEMA)
                .build();
    }

    public static void main(String[] args)
            throws Exception
    {
        QueryRunner queryRunner = PhoenixQueryRunner.builder(TestingPhoenixServer.getInstance().get())
                .addCoordinatorProperty("http-server.http.port", "8080")
                .build();

        Logger log = Logger.get(PhoenixQueryRunner.class);
        log.info("======== SERVER STARTED ========");
        log.info("\n====\n%s\n====", queryRunner.getCoordinator().getBaseUrl());
    }
}
