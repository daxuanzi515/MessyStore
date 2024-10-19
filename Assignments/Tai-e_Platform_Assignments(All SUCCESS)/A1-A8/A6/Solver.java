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

package pascal.taie.analysis.pta.cs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.PointerAnalysisResultImpl;
import pascal.taie.analysis.pta.core.cs.CSCallGraph;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.ArrayIndex;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.cs.element.InstanceField;
import pascal.taie.analysis.pta.core.cs.element.MapBasedCSManager;
import pascal.taie.analysis.pta.core.cs.element.Pointer;
import pascal.taie.analysis.pta.core.cs.element.StaticField;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.analysis.pta.pts.PointsToSetFactory;
import pascal.taie.config.AnalysisOptions;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.*;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;

import java.util.stream.Collectors;

class Solver {

    private static final Logger logger = LogManager.getLogger(Solver.class);

    private final AnalysisOptions options;

    private final HeapModel heapModel;

    private final ContextSelector contextSelector;

    private CSManager csManager;

    private CSCallGraph callGraph;

    private PointerFlowGraph pointerFlowGraph;

    private WorkList workList;

    private PointerAnalysisResult result;

    Solver(AnalysisOptions options, HeapModel heapModel,
           ContextSelector contextSelector) {
        this.options = options;
        this.heapModel = heapModel;
        this.contextSelector = contextSelector;
    }

    void solve() {
        initialize();
        analyze();
    }

    private void initialize() {
        csManager = new MapBasedCSManager();
        callGraph = new CSCallGraph(csManager);
        pointerFlowGraph = new PointerFlowGraph();
        workList = new WorkList();
        // process program entry, i.e., main method
        Context defContext = contextSelector.getEmptyContext();
        JMethod main = World.get().getMainMethod();
        CSMethod csMethod = csManager.getCSMethod(defContext, main);
        callGraph.addEntryMethod(csMethod);
        addReachable(csMethod);
    }

    /**
     * Processes new reachable context-sensitive method.
     */
    private void addReachable(CSMethod csMethod) {
        // TODO - finish me
        //在 addReachable() 方法中，你需要为每个可达的 CSMethod 创建一个 StmtProcessor 的实例来处理该方法中的语句。
        if (callGraph.addReachableMethod(csMethod)) {
            StmtProcessor stmtProcessor = new StmtProcessor(csMethod);
            stmtProcessor.launch();
        }
    }

    /**
     * Processes the statements in context-sensitive new reachable methods.
     */
    private class StmtProcessor implements StmtVisitor<Void> {

        private final CSMethod csMethod;

        private final Context context;

        private StmtProcessor(CSMethod csMethod) {
            this.csMethod = csMethod;
            this.context = csMethod.getContext();
        }

        // TODO - if you choose to implement addReachable()
        //  via visitor pattern, then finish me
        // 在上下文敏感指针分析中，访问者模式的具体访问者（内部类 StmtProcessor）的 visit(...) 方法需要能够访问到正在被处理的 CSMethod 和 Context
        // 因此我们为 StmtProcessor 的构造方法添加了一个 CSMethod 参数。 就是说需要访问到遍历过程中的对象CsMethod,不是说遍历完之后再对其遍历（A5）
        public void launch() {
            for (Stmt stmt : csMethod.getMethod().getIR().getStmts()) {
                stmt.accept(this);
            }
        }
        @Override
        public Void visit(New stmt) {
            // x = new T();
            // c:oi in pt(c:x)
            // no edge
            // pt(c:x) 代表和x上下文敏感信息有关且流经x的CSobj对象集合
            Obj obj = heapModel.getObj(stmt);
            // ContextSelector.selectHeapContext(csMethod, obj)
            // 得到csMethod和obj对应的堆上下文heap context
            Context ctx = contextSelector.selectHeapContext(csMethod, obj);
            // CSVar getCSVar(context, var) 得到context,var的上下文敏感变量
            // stmt.getLValue()传入左值
            // PointsToSetFactory -> pt<context, object>  .make(CSObj) 把上述关系加入set
            // CSManager.getCSObj(Context heapContext, Obj obj) 返回heap-context和object对应的上下文敏感对象CSObj
            workList.addEntry(csManager.getCSVar(context, stmt.getLValue()), PointsToSetFactory.make(csManager.getCSObj(ctx, obj)));
            return null;
        }
        @Override
        public Void visit(Copy stmt) {
            // x = y
            // c': oi in pt(c:y) -> c': oi in pt(c:x)
            // edge: c:y->c:x 几乎和CI异曲同工，就是根据上下文敏感信息得到变量/对象，给它们对应的指针传值覆盖
            addPFGEdge(csManager.getCSVar(context, stmt.getRValue()), csManager.getCSVar(context, stmt.getLValue()));
            return null;
        }

        @Override
        public Void visit(StoreField stmt) {
            // x.f = y
            // c': oi in pt(c:x), c'':oj in pt(c:y) -> c'': oj in pt(c':oi.f)
            // edge: c:y->c:x.f
            if (stmt.isStatic()) {
                Var y = stmt.getRValue();
                JField field = stmt.getFieldRef().resolve();
                // field: x.f,  CSManager.getStaticField(JFiled filed) 返回一个对应下x.f的pts
                addPFGEdge(csManager.getCSVar(context, y), csManager.getStaticField(field));
            }
            return null;
        }

        @Override
        public Void visit(LoadField stmt) {
            // y = x.f
            // c': oi in pt(c:x), c'':oj in pt(c':oj.f) -> c'': oj in pt(c:y)
            // edge: c:x.f->c:y
            if (stmt.isStatic()) {
                Var y = stmt.getLValue();
                JField field = stmt.getFieldRef().resolve();
                addPFGEdge(csManager.getStaticField(field), csManager.getCSVar(context, y));
            }
            return null;
        }

        @Override
        public Void visit(Invoke stmt) {
            // invoke 静态函数调用
            // call-site=> l: r = T.m(a1,a2,...,an)
            // definition: T m(p1,p2,...,pn) { .... return m-ret;}
            // 需要给各个参数传值 首先需要选择关于l的上下文对象c-t
            // 上下文敏感下：实参给形参传值 c:a1 -> c-t:m-p1,...,c:an -> c-t:m-pn
            // 上下文敏感下：返回值形参给实参传值 c:r <- c-t: m-ret
            if (stmt.isStatic()) {
                // 获得m方法
                JMethod m = stmt.getMethodRef().resolve();
                // 获取调用点cs上下文ctx
                CSCallSite csCallSite = csManager.getCSCallSite(context, stmt);
                Context ctx = contextSelector.selectContext(csCallSite, m);
                // m' = select(ctx,m) 获得调用点上下文、方法所对应的上下文敏感方法
                // CSMethod getCSMethod(Context context, JMethod method);
                CSMethod csMethod = csManager.getCSMethod(ctx, m);
                if (callGraph.addEdge(new Edge<>(CallKind.STATIC, csCallSite, csMethod))) {
                    // 对于不是静态变量的变动，而是函数的分析，则需要判断其是否可达
                    // 需要再次应用addReachable(csMethod)
                    addReachable(csMethod);
                    // Parameters.
                    // 实参给形参传值 c:a1 -> c-t:m-p1,...,c:an -> c-t:m-pn
                    for (int i = 0; i < stmt.getInvokeExp().getArgCount(); ++i) {
                        addPFGEdge(csManager.getCSVar(context, stmt.getInvokeExp().getArg(i)), csManager.getCSVar(ctx, m.getIR().getParam(i)));
                    }
                    // Return.
                    Var r = stmt.getResult();
                    if (r != null) {
                        for (Var mret : m.getIR().getReturnVars()) { // 可能有多个返回值分支
                            // 返回值形参给实参传值 c:r <- c-t: m-ret
                            addPFGEdge(csManager.getCSVar(ctx, mret), csManager.getCSVar(context, r));
                        }
                    }
                }
            }
            return null;
        }
    }

    /**
     * Adds an edge "source -> target" to the PFG.
     */
    private void addPFGEdge(Pointer source, Pointer target) {
        // TODO - finish me
        // 建边 s->t
        // boolean PointerFlowGraph.addEdge(Pointer source, Pointer target) {
        //    return successors.put(source, target);}
        if (pointerFlowGraph.addEdge(source, target)) {
            //Pointer.PointsToSet getPointsToSet() 返回一个和source相关的points-to 集合
            PointsToSet pts = source.getPointsToSet();// get pt(s)
            if (!pts.isEmpty()) {
                workList.addEntry(target, pts);// add <t, pt(s)> to WL
            }
        }
    }

    /**
     * Processes work-list entries until the work-list is empty.
     */
    private void analyze() {
        // TODO - finish me
        // 和CI结构一致
        while (!workList.isEmpty()) {
            WorkList.Entry entry = workList.pollEntry();
            PointsToSet pts = entry.pointsToSet();
            Pointer n = entry.pointer();
            PointsToSet delta = propagate(n, pts);
            if (n instanceof CSVar csVar) {
                for (CSObj csObj : delta) {
                    // x.f = y
                    for (StoreField storeField : csVar.getVar().getStoreFields()) {
                        addPFGEdge(csManager.getCSVar(csVar.getContext(), storeField.getRValue()), csManager.getInstanceField(csObj, storeField.getFieldRef().resolve()));
                    }
                    // y = x.f
                    for (LoadField loadField : csVar.getVar().getLoadFields()) {
                        addPFGEdge(csManager.getInstanceField(csObj, loadField.getFieldRef().resolve()), csManager.getCSVar(csVar.getContext(), loadField.getLValue()));
                    }
                    // x[i] = y;
                    for (StoreArray storeArray : csVar.getVar().getStoreArrays()) {
                        Var y = storeArray.getRValue();
                        addPFGEdge(csManager.getCSVar(csVar.getContext(), y), csManager.getArrayIndex(csObj));
                    }
                    // y = x[i];
                    for (LoadArray loadArray : csVar.getVar().getLoadArrays()) {
                        Var y = loadArray.getLValue();
                        addPFGEdge(csManager.getArrayIndex(csObj), csManager.getCSVar(csVar.getContext(), y));
                    }
                    processCall(csVar, csObj);
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
        // 初始化 PointToSet NULL
        PointsToSet result = PointsToSetFactory.make();
        // pts - pt(n)
        for (CSObj o : pointsToSet.objects().filter(obj -> !pointer.getPointsToSet().getObjects().contains(obj)).collect(Collectors.toSet())) {
            result.addObject(o);
        }

        if (!result.isEmpty()) {
            for (CSObj obj : result.getObjects()) {
                pointer.getPointsToSet().addObject(obj);
            }
            for (Pointer s : pointerFlowGraph.getSuccsOf(pointer)) {
                workList.addEntry(s, result);
            }
        }
        return result;
    }

    /**
     * Processes instance calls when points-to set of the receiver variable changes.
     *
     * @param recv    the receiver variable
     * @param recvObj set of new discovered objects pointed by the variable.
     */
    private void processCall(CSVar recv, CSObj recvObj) {
        // TODO - finish me
        for (Invoke callSite : recv.getVar().getInvokes()) {
            JMethod m = resolveCallee(recvObj, callSite);
            CSCallSite csCallSite = csManager.getCSCallSite(recv.getContext(), callSite);
            Context ctx = contextSelector.selectContext(csCallSite, recvObj, m);
            CSMethod csMethod = csManager.getCSMethod(ctx, m); // ct:m
            Pointer mThis = csManager.getCSVar(ctx, m.getIR().getThis()); // ct:mthis
            workList.addEntry(mThis, PointsToSetFactory.make(recvObj));

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

            if (callGraph.addEdge(new Edge<>(kind, csCallSite, csMethod))) {
                addReachable(csMethod);
                // Parameters.
                // 实参给形参传值 c:a1 -> c-t:m-p1,...,c:an -> c-t:m-pn
                for (int i = 0; i < callSite.getInvokeExp().getArgCount(); ++i) {
                    addPFGEdge(csManager.getCSVar(recv.getContext(), callSite.getInvokeExp().getArg(i)), csManager.getCSVar(ctx, m.getIR().getParam(i)));
                }
                // Return.
                // 返回值形参给实参传值 c:r <- c-t: m-ret
                Var r = callSite.getResult();
                if (r != null) {
                    for (Var mret : m.getIR().getReturnVars()) {
                        addPFGEdge(csManager.getCSVar(ctx, mret), csManager.getCSVar(recv.getContext(), r));
                    }
                }
            }
        }
    }

    /**
     * Resolves the callee of a call site with the receiver object.
     *
     * @param recv the receiver object of the method call. If the callSite
     *             is static, this parameter is ignored (i.e., can be null).
     * @param callSite the call site to be resolved.
     * @return the resolved callee.
     */
    private JMethod resolveCallee(CSObj recv, Invoke callSite) {
        Type type = recv != null ? recv.getObject().getType() : null;
        return CallGraphs.resolveCallee(type, callSite);
    }

    PointerAnalysisResult getResult() {
        if (result == null) {
            result = new PointerAnalysisResultImpl(csManager, callGraph);
        }
        return result;
    }
}
