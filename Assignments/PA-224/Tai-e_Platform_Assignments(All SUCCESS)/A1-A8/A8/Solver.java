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
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.cs.element.MapBasedCSManager;
import pascal.taie.analysis.pta.core.cs.element.Pointer;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.plugin.taint.TaintAnalysiss;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.analysis.pta.pts.PointsToSetFactory;
import pascal.taie.config.AnalysisOptions;
import pascal.taie.ir.exp.InvokeInstanceExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Copy;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.StmtVisitor;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;

import java.util.*;

public class Solver {

    private static final Logger logger = LogManager.getLogger(Solver.class);

    private final AnalysisOptions options;

    private final HeapModel heapModel;

    private final ContextSelector contextSelector;

    private CSManager csManager;

    private CSCallGraph callGraph;

    private PointerFlowGraph pointerFlowGraph;

    private WorkList workList;

    private TaintAnalysiss taintAnalysis;

    private PointerAnalysisResult result;

    private Map<CSVar, Set<Invoke>> possibleTaintTransfers;

    Solver(AnalysisOptions options, HeapModel heapModel,
           ContextSelector contextSelector) {
        this.options = options;
        this.heapModel = heapModel;
        this.contextSelector = contextSelector;
        this.possibleTaintTransfers = new HashMap<>();
    }

    public AnalysisOptions getOptions() {
        return options;
    }

    public ContextSelector getContextSelector() {
        return contextSelector;
    }

    public CSManager getCSManager() {
        return csManager;
    }

    void solve() {
        initialize();
        analyze();
        taintAnalysis.onFinish();
    }

    private void initialize() {
        csManager = new MapBasedCSManager();
        callGraph = new CSCallGraph(csManager);
        pointerFlowGraph = new PointerFlowGraph();
        workList = new WorkList();
        taintAnalysis = new TaintAnalysiss(this);
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
        if(!callGraph.contains(csMethod)){
            callGraph.addReachableMethod(csMethod);
            // 这里是用于直接循环遍历csMethod 省去写一个函数
            // 访问者->遍历访问 正在参与遍历的元素
            // 在 addReachable() 方法中，你需要为每个可达的 CSMethod 创建一个 StmtProcessor 的实例来处理该方法中的语句
            // 首先需要把这六个类给填平了... selector/_*.java
            csMethod.getMethod().getIR().getStmts().forEach(stmt -> stmt.accept(new StmtProcessor(csMethod)));
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
        // 几乎和CS差不多 除了INVOKE 处理的地方需要经过 processSingleCall, possibleTaintTransfers transferTaint 之外
        @Override
        public Void visit(New stmt) {
            Pointer ptr = csManager.getCSVar(context, stmt.getLValue());
            Obj obj = heapModel.getObj(stmt);
            Context ctx = contextSelector.selectHeapContext(csMethod, obj);
            PointsToSet pts = PointsToSetFactory.make(csManager.getCSObj(ctx, obj));
            workList.addEntry(ptr, pts);
            return null;
        }

        @Override
        public Void visit(Copy stmt) {
            addPFGEdge(
                    csManager.getCSVar(context, stmt.getRValue()),
                    csManager.getCSVar(context, stmt.getLValue())
            );
            return null;
        }

        @Override
        public Void visit(LoadField stmt) {
            if(stmt.isStatic()){
                addPFGEdge(
                        csManager.getStaticField(stmt.getFieldRef().resolve()),
                        csManager.getCSVar(context, stmt.getLValue())
                );
            }
            return null;
        }

        @Override
        public Void visit(StoreField stmt) {
            if(stmt.isStatic()) {
                addPFGEdge(
                        csManager.getCSVar(context, stmt.getRValue()),
                        csManager.getStaticField(stmt.getFieldRef().resolve())
                );
            }
            return null;
        }

        @Override
        public Void visit(Invoke callSite) {
            if(callSite.isStatic()){
                JMethod callee = resolveCallee(null, callSite);
                CSCallSite csCallSite = csManager.getCSCallSite(context, callSite);
                Context calleeContext = contextSelector.selectContext(csCallSite, callee);
                processSingleCall(csCallSite, csManager.getCSMethod(calleeContext, callee));
                // 因为是静态方法 所以没有base 取null
                transferTaint(csCallSite, callee, null);
            }
            csMethod.getMethod().getIR().getStmts().forEach(stmt -> {
                if(stmt instanceof Invoke invoke){
                    invoke.getInvokeExp().getArgs().forEach(arg -> {
                        // 针对一个调用语句，提取它的实参 根据上下文和实参取出 特定变量
                        CSVar var = csManager.getCSVar(context, arg);
                        // possibleTaintTransfers : Map<CSVar, Set<Invoke>>
                        // getOrDefault(var, new HashSet<>())得到对应var的Set<Invoke> 找不到返回空HashSet<>()
                        Set<Invoke> invokes = possibleTaintTransfers.getOrDefault(var, new HashSet<>());
                        // invokes 实参变量参与的调用语句集合 : o.m(...)/ r = o.m(...)
                        invokes.add(invoke);
                        // 加入Map里
                        possibleTaintTransfers.put(var, invokes);
                    });
                }
            });
            return null;
        }
    }

    private void transferTaint(CSCallSite csCallSite, JMethod callee, CSVar base){
        // 污点传播 返回一个pair <Var, Obj>
        taintAnalysis.handleTaintTransfer(csCallSite, callee, base).forEach(varObjPair -> {
            Var var = varObjPair.first();
            Obj obj = varObjPair.second();
            CSObj csObj = csManager.getCSObj(contextSelector.getEmptyContext(), obj);//忽略上下文敏感
            Pointer ptr = csManager.getCSVar(csCallSite.getContext(), var);
            workList.addEntry(ptr, PointsToSetFactory.make(csObj));
        });
    }

    /**
     * Adds an edge "source -> target" to the PFG.
     */
    private void addPFGEdge(Pointer source, Pointer target) {
        if(!pointerFlowGraph.getSuccsOf(source).contains(target)) {
            pointerFlowGraph.addEdge(source, target);
            PointsToSet pts = source.getPointsToSet();
            if(!pts.isEmpty()){
                workList.addEntry(target, pts);
            }
        }
    }

    /**
     * Processes work-list entries until the work-list is empty.
     */
    private void analyze() {
        while(!workList.isEmpty()){
            WorkList.Entry entry = workList.pollEntry();
            Pointer pointer = entry.pointer();
            PointsToSet pts = entry.pointsToSet();
            PointsToSet delta = propagate(pointer, pts);
            if(pointer instanceof CSVar csVar){
                Var var = csVar.getVar();
                Context ctx = csVar.getContext();
                //这里是每次的ctx
                delta.forEach(obj -> {
                    // StoreField
                    // x.f = y
                    var.getStoreFields().forEach(stmt -> {
                        addPFGEdge(
                                csManager.getCSVar(ctx, stmt.getRValue()),
                                csManager.getInstanceField(obj, stmt.getFieldAccess().getFieldRef().resolve())
                        );
                    });
                    // LoadField
                    // y = x.f
                    var.getLoadFields().forEach(stmt -> {
                        addPFGEdge(
                                csManager.getInstanceField(obj, stmt.getFieldAccess().getFieldRef().resolve()),
                                csManager.getCSVar(ctx, stmt.getLValue())
                        );
                    });
                    // StoreArray
                    // x[i] = y;
                    var.getStoreArrays().forEach(stmt -> {
                        addPFGEdge(
                                csManager.getCSVar(ctx, stmt.getRValue()),
                                csManager.getArrayIndex(obj)
                        );
                    });
                    // LoadArray
                    // y = x[i];
                    var.getLoadArrays().forEach(stmt -> {
                        addPFGEdge(
                                csManager.getArrayIndex(obj),
                                csManager.getCSVar(ctx, stmt.getLValue())
                        );
                    });
                    // 和CS一样
                    processCall(csVar, obj);
                    //这里加上污点传播
                    // TaintTransfer
                    if(taintAnalysis.isTaint(obj.getObject())){
                        possibleTaintTransfers.getOrDefault(csVar, new HashSet<>()).forEach(invoke -> {
                            CSCallSite csCallSite = csManager.getCSCallSite(ctx, invoke);
                            if(invoke.getInvokeExp() instanceof InvokeInstanceExp exp){
                                CSVar recv = csManager.getCSVar(ctx, exp.getBase());
                                result.getPointsToSet(recv).forEach(recvObj -> {
                                    JMethod callee = resolveCallee(recvObj, invoke);
                                    transferTaint(csCallSite, callee, recv);
                                });
                            }else{
                                JMethod callee = resolveCallee(null, invoke);
                                transferTaint(csCallSite, callee, null);
                            }
                        });
                    }
                });
            }
        }
    }

    /**
     * Propagates pointsToSet to pt(pointer) and its PFG successors,
     * returns the difference set of pointsToSet and pt(pointer).
     */
    private PointsToSet propagate(Pointer pointer, PointsToSet pointsToSet) {
        // 计算delta pts-pt(n)
        PointsToSet delta = PointsToSetFactory.make();
        pointsToSet.objects()
                .filter(ptr -> !pointer.getPointsToSet().contains(ptr))
                .forEach(delta::addObject);
        if(!delta.isEmpty()){
            delta.forEach(obj -> pointer.getPointsToSet().addObject(obj));
            pointerFlowGraph.getSuccsOf(pointer).forEach(succ -> workList.addEntry(succ, delta));
        }
        return delta;
    }

    private void processSingleCall(CSCallSite csCallSite, CSMethod callee){
        Invoke callSite = csCallSite.getCallSite();
        // 返回污点对象 taint-object(Obj)
        Obj obj = taintAnalysis.handleTaintSource(callSite, callee.getMethod());
        Var lVar = csCallSite.getCallSite().getLValue();
        if(obj != null && lVar != null){
            CSObj csObj = csManager.getCSObj(contextSelector.getEmptyContext(), obj);
            Pointer ptr = csManager.getCSVar(csCallSite.getContext(), lVar);
            workList.addEntry(ptr, PointsToSetFactory.make(csObj));
        }
        // 和CS一样
        Context callerContext = csCallSite.getContext();
        Context calleeContext = callee.getContext();
        if(!callGraph.getCalleesOf(csCallSite).contains(callee)){
            CallKind kind = null;
            if(callSite.isInterface()) kind = CallKind.INTERFACE;
            else if(callSite.isSpecial()) kind = CallKind.SPECIAL;
            else if(callSite.isStatic()) kind = CallKind.STATIC;
            else if(callSite.isVirtual()) kind = CallKind.VIRTUAL;
            if(kind != null) {
                callGraph.addEdge(new Edge<>(kind, csCallSite, callee));
                addReachable(callee);
                List<Var> args = callee.getMethod().getIR().getParams();
                assert args.size() == callSite.getRValue().getArgs().size();
                for(int i = 0;i < args.size();i ++){
                    addPFGEdge(
                            csManager.getCSVar(callerContext, callSite.getRValue().getArg(i)),
                            csManager.getCSVar(calleeContext, args.get(i))
                    );
                }
                if(callSite.getLValue() != null){
                    callee.getMethod().getIR().getReturnVars().forEach(ret -> {
                        addPFGEdge(
                                csManager.getCSVar(calleeContext, ret),
                                csManager.getCSVar(callerContext, callSite.getLValue())
                        );
                    });
                }
            }
        }
    }

    /**
     * Processes instance calls when points-to set of the receiver variable changes.
     *
     * @param recv    the receiver variable
     * @param recvObj set of new discovered objects pointed by the variable.
     */
    private void processCall(CSVar recv, CSObj recvObj) {
        recv.getVar().getInvokes().forEach(callSite -> {
            CSCallSite csCallSite = csManager.getCSCallSite(recv.getContext(), callSite);
            JMethod callee = resolveCallee(recvObj, callSite);
            Context calleeContext = contextSelector.selectContext(csCallSite, recvObj, callee);
            CSMethod csCallee = csManager.getCSMethod(calleeContext, callee);
            workList.addEntry(
                    csManager.getCSVar(calleeContext, callee.getIR().getThis()),// m-this
                    PointsToSetFactory.make(recvObj)
            );
            processSingleCall(csCallSite, csCallee);
            transferTaint(csCallSite, callee, recv);
        });
    }

    /**
     * Resolves the callee of a call site with the receiver object.
     *
     * @param recv the receiver object of the method call. If the callSite
     *             is static, this parameter is ignored (i.e., can be null).
     * @param callSite the call site to be resolved.
     * @return the resolved callee.
     */
    public JMethod resolveCallee(CSObj recv, Invoke callSite) {
        Type type = recv != null ? recv.getObject().getType() : null;
        return CallGraphs.resolveCallee(type, callSite);
    }

    public PointerAnalysisResult getResult() {
        if (result == null) {
            result = new PointerAnalysisResultImpl(csManager, callGraph);
        }
        return result;
    }
}
// 需要补全selector类