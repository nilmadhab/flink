---
title: 'Overview'
weight: 1
type: docs
bookToc: false
aliases: 
  - /concepts/
  - /concepts/concepts.html
---
<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Concepts

The [Hands-on Training]({{< ref "docs/learn-flink/overview" >}}) explains the basic concepts
of stateful and timely stream processing that underlie Flink's APIs, and provides examples of how these mechanisms are used in applications. Stateful stream processing is introduced in the context of [Data Pipelines & ETL]({{< ref "docs/learn-flink/etl" >}}#stateful-transformations)
and is further developed in the section on [Fault Tolerance]({{< ref "docs/learn-flink/fault_tolerance" >}}). 
Timely stream processing is introduced in the section on [Streaming Analytics]({{< ref "docs/learn-flink/streaming_analytics" >}}).

This _Concepts in Depth_ section provides a deeper understanding of how Flink's architecture and runtime implement these concepts.

## Flink's APIs

Flink offers different levels of abstraction for developing streaming/batch applications.

{{< img src="/fig/concepts/levels_of_abstraction.svg" alt="Programming levels of abstraction" width="70%" >}}

  - The lowest level abstraction simply offers **stateful and timely stream processing**. It is
    embedded into the [DataStream API]({{< ref "docs/dev/datastream/overview" >}}) via the [Process
    Function]({{< ref "docs/dev/datastream/operators/process_function" >}}). It allows
    users to freely process events from one or more streams, and provides consistent, fault tolerant
    *state*. In addition, users can register event time and processing time callbacks, allowing
    programs to realize sophisticated computations.

  - In practice, many applications do not need the low-level
    abstractions described above, and can instead program against the **Core APIs**: the
    [DataStream API]({{< ref "docs/dev/datastream/overview" >}})
    (bounded/unbounded streams). These fluent APIs offer the
    common building blocks for data processing, like various forms of
    user-specified transformations, joins, aggregations, windows, state, etc.
    Data types processed in these APIs are represented as classes in the
    respective programming languages.

    The low level *Process Function* integrates with the *DataStream API*,
    making it possible to use the lower-level abstraction on an as-needed basis.

  - The **Table API** is a declarative DSL centered around *tables*, which may
    be dynamically changing tables (when representing streams).  The [Table
    API]({{< ref "docs/dev/table/overview" >}}) follows the
    (extended) relational model: Tables have a schema attached (similar to
    tables in relational databases) and the API offers comparable operations,
    such as select, project, join, group-by, aggregate, etc.  Table API
    programs declaratively define *what logical operation should be done*
    rather than specifying exactly *how the code for the operation looks*.
    Though the Table API is extensible by various types of user-defined
    functions, it is less expressive than the *Core APIs*, and more concise to
    use (less code to write).  In addition, Table API programs also go through
    an optimizer that applies optimization rules before execution.

    One can seamlessly convert between tables and *DataStream*,
    allowing programs to mix the *Table API* with the *DataStream API*.

  - The highest level abstraction offered by Flink is **SQL**. This abstraction
    is similar to the *Table API* both in semantics and expressiveness, but
    represents programs as SQL query expressions.  The [SQL](
    {{< ref "docs/dev/table/overview" >}}#sql) abstraction closely interacts with the
    Table API, and SQL queries can be executed over tables defined in the
    *Table API*.
