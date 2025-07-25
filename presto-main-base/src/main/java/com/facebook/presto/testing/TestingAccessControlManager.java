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
package com.facebook.presto.testing;

import com.facebook.presto.common.CatalogSchemaName;
import com.facebook.presto.common.QualifiedObjectName;
import com.facebook.presto.common.Subfield;
import com.facebook.presto.common.transaction.TransactionId;
import com.facebook.presto.security.AccessControlManager;
import com.facebook.presto.security.AllowAllSystemAccessControl;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.security.AccessControlContext;
import com.facebook.presto.spi.security.Identity;
import com.facebook.presto.spi.security.ViewExpression;
import com.facebook.presto.transaction.TransactionManager;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import javax.inject.Inject;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.facebook.presto.spi.security.AccessDeniedException.denyAddColumn;
import static com.facebook.presto.spi.security.AccessDeniedException.denyAddConstraint;
import static com.facebook.presto.spi.security.AccessDeniedException.denyCreateSchema;
import static com.facebook.presto.spi.security.AccessDeniedException.denyCreateTable;
import static com.facebook.presto.spi.security.AccessDeniedException.denyCreateView;
import static com.facebook.presto.spi.security.AccessDeniedException.denyCreateViewWithSelect;
import static com.facebook.presto.spi.security.AccessDeniedException.denyDeleteTable;
import static com.facebook.presto.spi.security.AccessDeniedException.denyDropColumn;
import static com.facebook.presto.spi.security.AccessDeniedException.denyDropConstraint;
import static com.facebook.presto.spi.security.AccessDeniedException.denyDropSchema;
import static com.facebook.presto.spi.security.AccessDeniedException.denyDropTable;
import static com.facebook.presto.spi.security.AccessDeniedException.denyDropView;
import static com.facebook.presto.spi.security.AccessDeniedException.denyInsertTable;
import static com.facebook.presto.spi.security.AccessDeniedException.denyRenameColumn;
import static com.facebook.presto.spi.security.AccessDeniedException.denyRenameSchema;
import static com.facebook.presto.spi.security.AccessDeniedException.denyRenameTable;
import static com.facebook.presto.spi.security.AccessDeniedException.denyRenameView;
import static com.facebook.presto.spi.security.AccessDeniedException.denySelectColumns;
import static com.facebook.presto.spi.security.AccessDeniedException.denySetCatalogSessionProperty;
import static com.facebook.presto.spi.security.AccessDeniedException.denySetSystemSessionProperty;
import static com.facebook.presto.spi.security.AccessDeniedException.denySetTableProperties;
import static com.facebook.presto.spi.security.AccessDeniedException.denySetUser;
import static com.facebook.presto.spi.security.AccessDeniedException.denyShowCreateTable;
import static com.facebook.presto.spi.security.AccessDeniedException.denyTruncateTable;
import static com.facebook.presto.spi.security.AccessDeniedException.denyUpdateTableColumns;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.ADD_COLUMN;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.ADD_CONSTRAINT;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.CREATE_SCHEMA;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.CREATE_TABLE;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.CREATE_VIEW;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.CREATE_VIEW_WITH_SELECT_COLUMNS;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.DELETE_TABLE;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.DROP_COLUMN;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.DROP_CONSTRAINT;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.DROP_SCHEMA;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.DROP_TABLE;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.DROP_VIEW;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.INSERT_TABLE;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.RENAME_COLUMN;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.RENAME_SCHEMA;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.RENAME_TABLE;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.RENAME_VIEW;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.SELECT_COLUMN;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.SET_SESSION;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.SET_TABLE_PROPERTIES;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.SET_USER;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.SHOW_CREATE_TABLE;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.TRUNCATE_TABLE;
import static com.facebook.presto.testing.TestingAccessControlManager.TestingPrivilegeType.UPDATE_TABLE;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

public class TestingAccessControlManager
        extends AccessControlManager
{
    private final Set<TestingPrivilege> denyPrivileges = new HashSet<>();
    private final Map<RowFilterKey, List<ViewExpression>> rowFilters = new HashMap<>();
    private final Map<ColumnMaskKey, ViewExpression> columnMasks = new HashMap<>();

    @Inject
    public TestingAccessControlManager(TransactionManager transactionManager)
    {
        super(transactionManager);
        setSystemAccessControl(AllowAllSystemAccessControl.NAME, ImmutableMap.of());
    }

    public static TestingPrivilege privilege(String entityName, TestingPrivilegeType type)
    {
        return new TestingPrivilege(Optional.empty(), entityName, type);
    }

    public static TestingPrivilege privilege(String userName, String entityName, TestingPrivilegeType type)
    {
        return new TestingPrivilege(Optional.of(userName), entityName, type);
    }

    public void deny(TestingPrivilege... deniedPrivileges)
    {
        Collections.addAll(this.denyPrivileges, deniedPrivileges);
    }

    public void reset()
    {
        denyPrivileges.clear();
        rowFilters.clear();
        columnMasks.clear();
    }

    public void rowFilter(QualifiedObjectName table, String identity, ViewExpression filter)
    {
        rowFilters.computeIfAbsent(new RowFilterKey(identity, table), key -> new ArrayList<>())
                .add(filter);
    }

    public void columnMask(QualifiedObjectName table, String column, String identity, ViewExpression mask)
    {
        columnMasks.put(new ColumnMaskKey(identity, table, column), mask);
    }

    @Override
    public void checkCanSetUser(Identity identity, AccessControlContext context, Optional<Principal> principal, String userName)
    {
        if (shouldDenyPrivilege(userName, userName, SET_USER)) {
            denySetUser(principal, userName);
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanSetUser(identity, context, principal, userName);
        }
    }

    @Override
    public void checkCanCreateSchema(TransactionId transactionId, Identity identity, AccessControlContext context, CatalogSchemaName schemaName)
    {
        if (shouldDenyPrivilege(identity.getUser(), schemaName.getSchemaName(), CREATE_SCHEMA)) {
            denyCreateSchema(schemaName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanCreateSchema(transactionId, identity, context, schemaName);
        }
    }

    @Override
    public void checkCanDropSchema(TransactionId transactionId, Identity identity, AccessControlContext context, CatalogSchemaName schemaName)
    {
        if (shouldDenyPrivilege(identity.getUser(), schemaName.getSchemaName(), DROP_SCHEMA)) {
            denyDropSchema(schemaName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanDropSchema(transactionId, identity, context, schemaName);
        }
    }

    @Override
    public void checkCanRenameSchema(TransactionId transactionId, Identity identity, AccessControlContext context, CatalogSchemaName schemaName, String newSchemaName)
    {
        if (shouldDenyPrivilege(identity.getUser(), schemaName.getSchemaName(), RENAME_SCHEMA)) {
            denyRenameSchema(schemaName.toString(), newSchemaName);
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanRenameSchema(transactionId, identity, context, schemaName, newSchemaName);
        }
    }

    @Override
    public void checkCanShowCreateTable(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(identity.getUser(), tableName.getObjectName(), SHOW_CREATE_TABLE)) {
            denyShowCreateTable(tableName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanShowCreateTable(transactionId, identity, context, tableName);
        }
    }

    @Override
    public void checkCanCreateTable(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(identity.getUser(), tableName.getObjectName(), CREATE_TABLE)) {
            denyCreateTable(tableName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanCreateTable(transactionId, identity, context, tableName);
        }
    }

    @Override
    public void checkCanDropTable(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(identity.getUser(), tableName.getObjectName(), DROP_TABLE)) {
            denyDropTable(tableName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanDropTable(transactionId, identity, context, tableName);
        }
    }

    @Override
    public void checkCanRenameTable(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName, QualifiedObjectName newTableName)
    {
        if (shouldDenyPrivilege(identity.getUser(), tableName.getObjectName(), RENAME_TABLE)) {
            denyRenameTable(tableName.toString(), newTableName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanRenameTable(transactionId, identity, context, tableName, newTableName);
        }
    }

    @Override
    public void checkCanSetTableProperties(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName, Map<String, Object> properties)
    {
        if (shouldDenyPrivilege(identity.getUser(), tableName.getObjectName(), SET_TABLE_PROPERTIES)) {
            denySetTableProperties(tableName.toString());
        }

        if (denyPrivileges.isEmpty()) {
            super.checkCanSetTableProperties(transactionId, identity, context, tableName, properties);
        }
    }

    @Override
    public void checkCanAddColumns(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(identity.getUser(), tableName.getObjectName(), ADD_COLUMN)) {
            denyAddColumn(tableName.toString());
        }
        super.checkCanAddColumns(transactionId, identity, context, tableName);
    }

    @Override
    public void checkCanDropColumn(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(identity.getUser(), tableName.getObjectName(), DROP_COLUMN)) {
            denyDropColumn(tableName.toString());
        }
        super.checkCanDropColumn(transactionId, identity, context, tableName);
    }

    @Override
    public void checkCanRenameColumn(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(identity.getUser(), tableName.getObjectName(), RENAME_COLUMN)) {
            denyRenameColumn(tableName.toString());
        }
        super.checkCanRenameColumn(transactionId, identity, context, tableName);
    }

    @Override
    public void checkCanInsertIntoTable(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(identity.getUser(), tableName.getObjectName(), INSERT_TABLE)) {
            denyInsertTable(tableName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanInsertIntoTable(transactionId, identity, context, tableName);
        }
    }

    @Override
    public void checkCanDeleteFromTable(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(identity.getUser(), tableName.getObjectName(), DELETE_TABLE)) {
            denyDeleteTable(tableName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanDeleteFromTable(transactionId, identity, context, tableName);
        }
    }

    @Override
    public void checkCanTruncateTable(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(identity.getUser(), tableName.getObjectName(), TRUNCATE_TABLE)) {
            denyTruncateTable(tableName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanDeleteFromTable(transactionId, identity, context, tableName);
        }
    }

    @Override
    public void checkCanUpdateTableColumns(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName, Set<String> updatedColumnNames)
    {
        if (shouldDenyPrivilege(identity.getUser(), tableName.getObjectName(), UPDATE_TABLE)) {
            denyUpdateTableColumns(tableName.toString(), updatedColumnNames);
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanUpdateTableColumns(transactionId, identity, context, tableName, updatedColumnNames);
        }
    }

    @Override
    public void checkCanCreateView(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName viewName)
    {
        if (shouldDenyPrivilege(identity.getUser(), viewName.getObjectName(), CREATE_VIEW)) {
            denyCreateView(viewName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanCreateView(transactionId, identity, context, viewName);
        }
    }

    @Override
    public void checkCanRenameView(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName viewName, QualifiedObjectName newViewName)
    {
        if (shouldDenyPrivilege(identity.getUser(), viewName.getObjectName(), RENAME_VIEW)) {
            denyRenameView(viewName.toString(), newViewName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanRenameView(transactionId, identity, context, viewName, newViewName);
        }
    }

    @Override
    public void checkCanDropView(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName viewName)
    {
        if (shouldDenyPrivilege(identity.getUser(), viewName.getObjectName(), DROP_VIEW)) {
            denyDropView(viewName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanDropView(transactionId, identity, context, viewName);
        }
    }

    @Override
    public void checkCanSetSystemSessionProperty(Identity identity, AccessControlContext context, String propertyName)
    {
        if (shouldDenyPrivilege(identity.getUser(), propertyName, SET_SESSION)) {
            denySetSystemSessionProperty(propertyName);
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanSetSystemSessionProperty(identity, context, propertyName);
        }
    }

    @Override
    public void checkCanCreateViewWithSelectFromColumns(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName, Set<String> columnNames)
    {
        if (shouldDenyPrivilege(identity.getUser(), tableName.getObjectName(), CREATE_VIEW_WITH_SELECT_COLUMNS)) {
            denyCreateViewWithSelect(tableName.toString(), identity);
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanCreateViewWithSelectFromColumns(transactionId, identity, context, tableName, columnNames);
        }
    }

    @Override
    public void checkCanSetCatalogSessionProperty(TransactionId transactionId, Identity identity, AccessControlContext context, String catalogName, String propertyName)
    {
        if (shouldDenyPrivilege(identity.getUser(), catalogName + "." + propertyName, SET_SESSION)) {
            denySetCatalogSessionProperty(catalogName, propertyName);
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanSetCatalogSessionProperty(transactionId, identity, context, catalogName, propertyName);
        }
    }

    @Override
    public void checkCanSelectFromColumns(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName, Set<Subfield> columnOrSubfieldNames)
    {
        Set<String> columns = columnOrSubfieldNames.stream().map(subfield -> subfield.toString()).collect(toImmutableSet());
        if (shouldDenyPrivilege(identity.getUser(), tableName.getObjectName(), SELECT_COLUMN)) {
            denySelectColumns(tableName.toString(), columns);
        }
        for (String column : columns) {
            if (shouldDenyPrivilege(identity.getUser(), column, SELECT_COLUMN)) {
                denySelectColumns(tableName.toString(), columns);
            }
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanSelectFromColumns(transactionId, identity, context, tableName, columnOrSubfieldNames);
        }
    }

    @Override
    public void checkCanDropConstraint(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(identity.getUser(), tableName.getObjectName(), DROP_CONSTRAINT)) {
            denyDropConstraint(tableName.toString());
        }
        super.checkCanDropConstraint(transactionId, identity, context, tableName);
    }

    @Override
    public void checkCanAddConstraints(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(identity.getUser(), tableName.getObjectName(), ADD_CONSTRAINT)) {
            denyAddConstraint(tableName.toString());
        }
        super.checkCanAddConstraints(transactionId, identity, context, tableName);
    }

    @Override
    public List<ViewExpression> getRowFilters(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName)
    {
        return rowFilters.getOrDefault(new RowFilterKey(identity.getUser(), tableName), ImmutableList.of());
    }

    @Override
    public Map<ColumnMetadata, ViewExpression> getColumnMasks(TransactionId transactionId, Identity identity, AccessControlContext context, QualifiedObjectName tableName, List<ColumnMetadata> columns)
    {
        Map<ColumnMetadata, ViewExpression> superResult = super.getColumnMasks(transactionId, identity, context, tableName, columns);
        ImmutableMap.Builder<ColumnMetadata, ViewExpression> columnMaskBuilder = ImmutableMap.builder();
        for (ColumnMetadata column : columns) {
            ColumnMaskKey columnMaskKey = new ColumnMaskKey(identity.getUser(), tableName, column.getName());
            if (columnMasks.containsKey(columnMaskKey)) {
                columnMaskBuilder.put(column, columnMasks.get(columnMaskKey));
            }
            else if (superResult.containsKey(column)) {
                columnMaskBuilder.put(column, superResult.get(column));
            }
        }
        return columnMaskBuilder.buildOrThrow();
    }

    private boolean shouldDenyPrivilege(String userName, String entityName, TestingPrivilegeType type)
    {
        TestingPrivilege testPrivilege = privilege(userName, entityName, type);
        for (TestingPrivilege denyPrivilege : denyPrivileges) {
            if (denyPrivilege.matches(testPrivilege)) {
                return true;
            }
        }
        return false;
    }

    public enum TestingPrivilegeType
    {
        SET_USER,
        CREATE_SCHEMA, DROP_SCHEMA, RENAME_SCHEMA,
        SHOW_CREATE_TABLE, CREATE_TABLE, DROP_TABLE, RENAME_TABLE, INSERT_TABLE, DELETE_TABLE, TRUNCATE_TABLE, UPDATE_TABLE,
        ADD_COLUMN, DROP_COLUMN, RENAME_COLUMN, SELECT_COLUMN,
        ADD_CONSTRAINT, DROP_CONSTRAINT,
        CREATE_VIEW, RENAME_VIEW, DROP_VIEW, CREATE_VIEW_WITH_SELECT_COLUMNS, SET_TABLE_PROPERTIES,
        SET_SESSION
    }

    public static class TestingPrivilege
    {
        private final Optional<String> userName;
        private final String entityName;
        private final TestingPrivilegeType type;

        private TestingPrivilege(Optional<String> userName, String entityName, TestingPrivilegeType type)
        {
            this.userName = requireNonNull(userName, "userName is null");
            this.entityName = requireNonNull(entityName, "entityName is null");
            this.type = requireNonNull(type, "type is null");
        }

        public boolean matches(TestingPrivilege testPrivilege)
        {
            return userName.map(name -> testPrivilege.userName.get().equals(name)).orElse(true) &&
                    entityName.equals(testPrivilege.entityName) &&
                    type == testPrivilege.type;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TestingPrivilege that = (TestingPrivilege) o;
            return Objects.equals(entityName, that.entityName) &&
                    Objects.equals(type, that.type);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(entityName, type);
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("userName", userName)
                    .add("entityName", entityName)
                    .add("type", type)
                    .toString();
        }
    }

    private static class RowFilterKey
    {
        private final String identity;
        private final QualifiedObjectName table;

        public RowFilterKey(String identity, QualifiedObjectName table)
        {
            this.identity = requireNonNull(identity, "identity is null");
            this.table = requireNonNull(table, "table is null");
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            RowFilterKey that = (RowFilterKey) o;
            return identity.equals(that.identity) &&
                    table.equals(that.table);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(identity, table);
        }
    }

    private static class ColumnMaskKey
    {
        private final String identity;
        private final QualifiedObjectName table;
        private final String column;

        public ColumnMaskKey(String identity, QualifiedObjectName table, String column)
        {
            this.identity = identity;
            this.table = table;
            this.column = column;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ColumnMaskKey that = (ColumnMaskKey) o;
            return identity.equals(that.identity) &&
                    table.equals(that.table) &&
                    column.equals(that.column);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(identity, table, column);
        }
    }
}
