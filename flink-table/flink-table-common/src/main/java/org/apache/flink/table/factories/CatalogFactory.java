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

package org.apache.flink.table.factories;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.table.catalog.Catalog;
import org.apache.flink.table.legacy.factories.TableFactory;

import java.util.Map;

/**
 * A factory to create configured catalog instances based on string-based properties. See also
 * {@link Factory} for more information.
 *
 * <p>Note that this interface supports the {@link TableFactory} stack for compatibility purposes.
 * This is deprecated, however, and new implementations should implement the {@link Factory} stack
 * instead.
 */
@PublicEvolving
public interface CatalogFactory extends Factory {

    /**
     * Creates and configures a {@link Catalog} using the given context.
     *
     * <p>An implementation should perform validation and the discovery of further (nested)
     * factories in this method.
     */
    Catalog createCatalog(Context context);

    /** Context provided when a catalog is created. */
    @PublicEvolving
    interface Context {
        /** Returns the name with which the catalog is created. */
        String getName();

        /**
         * Returns the options with which the catalog is created.
         *
         * <p>An implementation should perform validation of these options.
         */
        Map<String, String> getOptions();

        /** Gives read-only access to the configuration of the current session. */
        ReadableConfig getConfiguration();

        /**
         * Returns the class loader of the current session.
         *
         * <p>The class loader is in particular useful for discovering further (nested) factories.
         */
        ClassLoader getClassLoader();
    }
}
