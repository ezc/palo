// Copyright (c) 2017, Baidu.com, Inc. All Rights Reserved

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.baidu.palo.planner;

import com.baidu.palo.analysis.Analyzer;
import com.baidu.palo.analysis.Expr;
import com.baidu.palo.analysis.ExprSubstitutionMap;
import com.baidu.palo.analysis.SlotDescriptor;
import com.baidu.palo.analysis.SlotRef;
import com.baidu.palo.analysis.TupleDescriptor;
import com.baidu.palo.catalog.Column;
import com.baidu.palo.catalog.MysqlTable;
import com.baidu.palo.common.InternalException;
import com.baidu.palo.thrift.TMySQLScanNode;
import com.baidu.palo.thrift.TPlanNode;
import com.baidu.palo.thrift.TPlanNodeType;
import com.baidu.palo.thrift.TScanRangeLocations;
import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.collect.Lists;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Full scan of an MySQL table.
 */
public class MysqlScanNode extends ScanNode {
    private static final Logger LOG = LogManager.getLogger(MysqlScanNode.class);

    private final List<String> columns = new ArrayList<String>();
    private final List<String> filters = new ArrayList<String>();
    private       String     tabName;

    /**
     * Constructs node to scan given data files of table 'tbl'.
     */
    public MysqlScanNode(PlanNodeId id, TupleDescriptor desc, MysqlTable tbl) {
        super(id, desc, "SCAN MYSQL");
        // tabName = ((BaseTableRef)desc.getRef()).mysqlTableRefToSql();
        tabName = "`" + tbl.getMysqlTableName() + "`";
    }

    @Override
    protected String debugString() {
        ToStringHelper helper = Objects.toStringHelper(this);
        return helper.addValue(super.debugString()).toString();
    }

    @Override
    public void finalize(Analyzer analyzer) throws InternalException {
        // Convert predicates to MySQL columns and filters.
        createMySQLColumns(analyzer);
        createMySQLFilters(analyzer);
    }

    private void createMySQLColumns(Analyzer analyzer) {
        for (SlotDescriptor slot : desc.getSlots()) {
            if (!slot.isMaterialized()) {
                continue;
            }
            Column col = slot.getColumn();
            columns.add("`" + col.getName() + "`");
        }
        // this happend when count(*)
        if (0 == columns.size()) {
            columns.add("*");
        }
    }

    // We convert predicates of the form <slotref> op <constant> to MySQL filters
    private void createMySQLFilters(Analyzer analyzer) {
        if (conjuncts.isEmpty()) {
            return;

        }
        List<SlotRef> slotRefs = Lists.newArrayList();
        Expr.collectList(conjuncts, SlotRef.class, slotRefs);
        ExprSubstitutionMap sMap = new ExprSubstitutionMap();
        for (SlotRef slotRef : slotRefs) {
            SlotRef tmpRef = (SlotRef) slotRef.clone();
            tmpRef.setTblName(null);

            sMap.put(slotRef, tmpRef);
        }
        ArrayList<Expr> mysqlConjuncts = Expr.cloneList(conjuncts, sMap);
        for (Expr p : mysqlConjuncts) {
            filters.add(p.toMySql());
        }
    }

    @Override
    protected void toThrift(TPlanNode msg) {
        msg.node_type = TPlanNodeType.MYSQL_SCAN_NODE;
        msg.mysql_scan_node = new TMySQLScanNode(desc.getId().asInt(), tabName, columns, filters);
    }

    /**
     * We query MySQL Meta to get request's data localtion
     * extra result info will pass to backend ScanNode
     */
    @Override
    public List<TScanRangeLocations> getScanRangeLocations(long maxScanRangeLength) {
        return null;
    }

    @Override
    public int getNumInstances() {
        return 1;
    }

}
