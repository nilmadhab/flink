/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.operations;

import org.apache.flink.annotation.Internal;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.expressions.SqlFactory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A set operation on two relations. It provides a way to union, intersect or subtract underlying
 * data sets/streams. Both relations must have equal schemas.
 */
@Internal
public class SetQueryOperation implements QueryOperation {

    private final QueryOperation leftOperation;
    private final QueryOperation rightOperation;

    private final SetQueryOperationType type;
    private final boolean all;
    private final ResolvedSchema resolvedSchema;

    public SetQueryOperation(
            QueryOperation leftOperation,
            QueryOperation rightOperation,
            SetQueryOperationType type,
            boolean all,
            ResolvedSchema resolvedSchema) {
        this.leftOperation = leftOperation;
        this.rightOperation = rightOperation;
        this.type = type;
        this.all = all;
        this.resolvedSchema = resolvedSchema;
    }

    /**
     * Represent kind of this set operation.
     *
     * <ul>
     *   <li><b>MINUS</b> returns records from the left relation that do not exist in the right
     *       relation
     *   <li><b>INTERSECT</b> returns records that exist in both relation
     *   <li><b>UNION</b> returns records from both relations as a single relation
     * </ul>
     */
    @Internal
    public enum SetQueryOperationType {
        INTERSECT,
        MINUS,
        UNION
    }

    @Override
    public ResolvedSchema getResolvedSchema() {
        return resolvedSchema;
    }

    @Override
    public String asSummaryString() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("all", all);

        return OperationUtils.formatWithChildren(
                typeToString(), args, getChildren(), Operation::asSummaryString);
    }

    @Override
    public String asSerializableString(SqlFactory sqlFactory) {
        return String.format(
                "SELECT %s FROM (%s\n) %s (%s\n)",
                OperationUtils.formatSelectColumns(resolvedSchema, null),
                OperationUtils.indent(leftOperation.asSerializableString(sqlFactory)),
                asSerializableType(),
                OperationUtils.indent(rightOperation.asSerializableString(sqlFactory)));
    }

    private String asSerializableType() {
        if (all) {
            return type.toString() + " ALL";
        } else {
            return type.toString();
        }
    }

    private String typeToString() {
        switch (type) {
            case INTERSECT:
                return "Intersect";
            case MINUS:
                return "Minus";
            case UNION:
                return "Union";
            default:
                throw new IllegalStateException("Unknown set operation type: " + type);
        }
    }

    @Override
    public <T> T accept(QueryOperationVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public List<QueryOperation> getChildren() {
        return Arrays.asList(leftOperation, rightOperation);
    }

    public SetQueryOperationType getType() {
        return type;
    }

    public boolean isAll() {
        return all;
    }
}
