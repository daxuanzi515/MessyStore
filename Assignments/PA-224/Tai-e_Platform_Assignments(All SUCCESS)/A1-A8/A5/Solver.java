/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.pta.ci;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.DefaultCallGraph;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.*;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.AnalysisException;
import pascal.taie.language.type.Type;

import java.util.List;
import java.util.stream.Collectors;

class Solver {

    private static final Logger logger = LogManager.getLogger(Solver.class);

    private final HeapModel heapModel;

    private DefaultCallGraph callGraph;

    private PointerFlowGraph pointerFlowGraph;

    private WorkList workList;

    private StmtProcessor stmtProcessor;

    private ClassHierarchy hierarchy;

    Solver(HeapModel heapModel) {
        this.heapModel = heapModel;
    }

    /**
     * Runs pointer analysis algorithm.
     */
    void solve() {
        initialize();
        analyze();
    }

    /**
     * Initializes pointer analysis.
     */
    private void initialize() {
        workList = new WorkList();
        pointerFlowGraph = new PointerFlowGraph();
        callGraph = new DefaultCallGraph();
        stmtProcessor = new StmtProcessor();
        hierarchy = World.get().getClassHierarchy();
        // initialize main method
        JMethod main = World.get().getMainMethod();
        callGraph.addEntryMethod(main);
        addReachable(main);
    }

    /**
     * Processes new reachable method.
     */
    private void addReachable(JMethod method) {
        // TODO - finish me
        // 使用访问者模式Visitor 可以使你在不改变这些对象本身的情况下，定义作用于这些对象的新操作。
        if(callGraph.addReachableMethod(method)){
            for(Stmt stmt : method.getIR().getStmts())
            {
                //  <T> T accept(StmtVisitor<T> visitor);
                // stmt是被访问者 所以有一个设定接受对应访问者的函数accpet
                stmt.accept(stmtProcessor);
            }
        }

    }

    /**
     * Processes statements in new reachable methods.
     */
    private class StmtProcessor implements StmtVisitor<Void> {
        // TODO - if you choose to implement addReachable()
        //  via visitor pattern, then finish me
        // 定义访问者的行为
        // AssignStmt: New Copy StoreField LoadField StoreArray LoadArray
        // DefinitionStmt - Invoke, AssignStmt
        // visit(…) 方法的返回值被忽略，因此你在实现 visit(…) 方法时只需要返回 null
        @Override
        // x = new T() -> add({x,{oi}}) to WL
        public Void visit(New stmt) {
            //WorkList.addEntry(Pointer pointer, PointsToSet pointsToSet) {
            //        entries.add(new Entry(pointer, pointsToSet));}
            // 加入一个Entry对象集合 add(s,pt(s)) 把{s,pt(s)}->{对象, 流经这个对象的指针}
            // PointerFlowGraph.getVarPtr(Var) -> Var Node 给出对应变量的变量节点
            // HeapModel.geObj(statement) -> abstract object 给出对应语句的抽象对象
            workList.addEntry(pointerFlowGraph.getVarPtr(stmt.getLValue()), new PointsToSet(heapModel.getObj(stmt)));
            return null;
        }
        @Override
        // x = y -> AddEdge(y,x)
        public Void visit(Copy stmt) {
            addPFGEdge(pointerFlowGraph.getVarPtr(stmt.getRValue()), pointerFlowGraph.getVarPtr(stmt.getLValue()));
            return null;
        }
        @Override
        // 静态方法调用
        // static invoke r = T.m(p1,p2,....,)
        // T m(a1,a2,...,){ return mret; }   p1->a1,p2->a2,....; mret->r
        public Void visit(Invoke stmt) {
            JMethod m = stmt.getMethodRef().resolve();
            if (stmt.isStatic()) {
                if (callGraph.addEdge(new Edge<>(CallKind.STATIC, stmt, m))) {
                    addReachable(m);
                    // Parameters. 传参数值
                    for (int i = 0; i < stmt.getInvokeExp().getArgCount(); ++i) {
                        // 把实参的值传给形参 addEdge(y,x) 把y的值传给x
                        addPFGEdge(pointerFlowGraph.getVarPtr(stmt.getInvokeExp().getArg(i)), pointerFlowGraph.getVarPtr(m.getIR().getParam(i)));
                    }
                    // Return. 传返回值
                    Var r = stmt.getResult();
                    if (r != null) {
                        for (Var mret : m.getIR().getReturnVars()) {
                            // 把形参mret的值传给实参r
                            addPFGEdge(pointerFlowGraph.getVarPtr(mret), pointerFlowGraph.getVarPtr(r));
                        }
                    }
                }
            }
            return null;
        }
        @Override
        // load/store 静态字段传值 需要加边PFG
        // load: y = T.f
        public Void visit(StoreField stmt) {
            if (stmt.isStatic()) {
                Var y = stmt.getRValue();
                JField field = stmt.getFieldRef().resolve();
                addPFGEdge(pointerFlowGraph.getVarPtr(y), pointerFlowGraph.getStaticField(field));
            }
            return null;
        }

        @Override
        // store: T.f = y
        public Void visit(LoadField stmt) {
            if (stmt.isStatic()) {
                Var y = stmt.getLValue();
                JField field = stmt.getFieldRef().resolve();
                addPFGEdge(pointerFlowGraph.getStaticField(field), pointerFlowGraph.getVarPtr(y));
            }
            return null;
        }
    }

    /**
     * Adds an edge "source -> target" to the PFG.
     */
    private void addPFGEdge(Pointer source, Pointer target) {
        // TODO - finish me
        // PointerFlowGraph.addEdge(s,t) 判断s->t是否在PFG里
        // Pointer.getPointsToSet 得到pt(s)的数据流流经s的指针合集
        // add(t,pt(s)) - > WL
        if (pointerFlowGraph.addEdge(source, target)) {
            PointsToSet pts = source.getPointsToSet();
            if (!pts.isEmpty()) {
                workList.addEntry(target, pts);
            }
        }
    }

    /**
     * Processes work-list entries until the work-list is empty.
     */
    private void analyze() {
        // TODO - finish me
        // solve函数的循环部分
        while (!workList.isEmpty()) {
            WorkList.Entry entry = workList.pollEntry();
            PointsToSet pts = entry.pointsToSet(); // pts
            Pointer n = entry.pointer();

            PointsToSet delta = propagate(n, pts);
            if (n instanceof VarPtr varPtr) {
                Var var = varPtr.getVar();
                for (Obj obj : delta) {
                    // x.f = y;
                    for (StoreField storeField : var.getStoreFields()) {
                        Var y = storeField.getRValue();
                        JField field = storeField.getFieldRef().resolve();
                        addPFGEdge(pointerFlowGraph.getVarPtr(y), pointerFlowGraph.getInstanceField(obj, field));
                    }
                    // y = x.f
                    for (LoadField loadField : var.getLoadFields()) {
                        Var y = loadField.getLValue();
                        JField field = loadField.getFieldRef().resolve();
                        addPFGEdge(pointerFlowGraph.getInstanceField(obj, field), pointerFlowGraph.getVarPtr(y));
                    }
                    // x[i] = y;
                    for (StoreArray storeArray : var.getStoreArrays()) {
                        Var y = storeArray.getRValue();
                        addPFGEdge(pointerFlowGraph.getVarPtr(y), pointerFlowGraph.getArrayIndex(obj));
                    }
                    // y = x[i];
                    for (LoadArray loadArray : var.getLoadArrays()) {
                        Var y = loadArray.getLValue();
                        addPFGEdge(pointerFlowGraph.getArrayIndex(obj), pointerFlowGraph.getVarPtr(y));
                    }
                    processCall(var, obj);
                }
            }
        }
    }

    /**
     * Propagates pointsToSet to pt(pointer) and its PFG successors,
     * returns the difference set of pointsToSet and pt(pointer).
     */
    private PointsToSet propagate(Pointer pointer, PointsToSet pointsToSet) {
        // TODO - finish me
        // return delta = pts - pt(n)
        // 去掉在pts里和pt(n)里都有的元素，保留pts剩下的元素 然后复制给result
        PointsToSet result = new PointsToSet();
        for (Obj o : pointsToSet.objects().filter(obj -> !pointer.getPointsToSet().getObjects().contains(obj)).collect(Collectors.toSet())) {
            result.addObject(o);
        }
        // pt(n) U= pts
        if (!pointsToSet.isEmpty()) {
            for (Obj obj : result.getObjects()) {
                pointer.getPointsToSet().addObject(obj);
            }
            // edge:n->s in PFG => add{s,pts}to WL
            for (Pointer s : pointerFlowGraph.getSuccsOf(pointer)) {
                workList.addEntry(s, result);
            }
        }
        return result;
    }

    /**
     * Processes instance calls when points-to set of the receiver variable changes.
     *
     * @param var the variable that holds receiver objects
     * @param recv a new discovered object pointed by the variable.
     */
    private void processCall(Var var, Obj recv) {
        // TODO - finish me
        // 对于每一个形如 l:r = x.k(a1,a2,...,)的语句调用 做如下操作
        // m = dispatch(recv, k) , add{m-this,{recv}} to WL
        // l->m不在CG，则加边l->m到CG里
        // AddReachable(m)
        // 对于每个m的形参pi AddEdge(ai,pi) 就是给pi传值
        // 给结果传值 AddEdge(mret, r)
        for (Invoke callSite : var.getInvokes()) {
            JMethod m = resolveCallee(recv, callSite); // Dispatch.
            Pointer mThis = pointerFlowGraph.getVarPtr(m.getIR().getThis());
            workList.addEntry(mThis, new PointsToSet(recv)); // Add <mthis, {oi}> to WL.

            CallKind kind = null;
            if (callSite.isStatic()) {
                kind = CallKind.STATIC;
            } else if (callSite.isVirtual()) {
                kind = CallKind.VIRTUAL;
            } else if (callSite.isDynamic()) {
                kind = CallKind.DYNAMIC;
            } else if (callSite.isInterface()) {
                kind = CallKind.INTERFACE;
            } else if (callSite.isSpecial()) {
                kind = CallKind.SPECIAL;
            }
            // public Edge(CallKind kind, CallSite callSite, Method callee)
            // 建边传参 CallKind,CallSite, Method
            if (callGraph.addEdge(new Edge<>(kind, callSite, m))) {
                addReachable(m);

                // Parameters.
                for (int i = 0; i < callSite.getInvokeExp().getArgCount(); ++i) {
                    addPFGEdge(pointerFlowGraph.getVarPtr(callSite.getInvokeExp().getArg(i)), pointerFlowGraph.getVarPtr(m.getIR().getParam(i)));
                }
                // Return.
                Var r = callSite.getResult();
                if (r != null) {
                    for (Var mret : m.getIR().getReturnVars()) {
                        addPFGEdge(pointerFlowGraph.getVarPtr(mret), pointerFlowGraph.getVarPtr(r));
                    }
                }
            }
        }
    }

    /**
     * Resolves the callee of a call site with the receiver object.
     *
     * @param recv     the receiver object of the method call. If the callSite
     *                 is static, this parameter is ignored (i.e., can be null).
     * @param callSite the call site to be resolved.
     * @return the resolved callee.
     */
    private JMethod resolveCallee(Obj recv, Invoke callSite) {
        Type type = recv != null ? recv.getType() : null;
        return CallGraphs.resolveCallee(type, callSite);
    }

    CIPTAResult getResult() {
        return new CIPTAResult(pointerFlowGraph, callGraph);
    }
}
