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

package pascal.taie.analysis.pta.plugin.taint;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraph;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.cs.Solver;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.type.Type;
import pascal.taie.util.collection.Pair;
import java.util.*;
import pascal.taie.analysis.pta.core.cs.element.*;
public class TaintAnalysiss {

    private static final Logger logger = LogManager.getLogger(TaintAnalysiss.class);

    private final TaintManager manager;

    private final TaintConfig config;

    private final Solver solver;

    private final CSManager csManager;

    private final Context emptyContext;

    public TaintAnalysiss(Solver solver) {
        manager = new TaintManager();
        this.solver = solver;
        csManager = solver.getCSManager();
        emptyContext = solver.getContextSelector().getEmptyContext();
        config = TaintConfig.readConfig(
                solver.getOptions().getString("taint-config"),
                World.get().getClassHierarchy(),
                World.get().getTypeSystem());
        logger.info(config);
    }

    // TODO - finish me
    public boolean isTaint(Obj obj) {
        return manager.isTaint(obj);
    }

    public Obj handleTaintSource(Invoke callSite, JMethod callee){
        // 得到被调用点返回类型
        // T fun() {...}  -> T
        Type type = callee.getReturnType();
        // makeTaint(method, type) 污点源 - [被调用的方法 ,返回类型]
        Source source = new Source(callee, type);
        if(config.getSources().contains(source)){
            // 若污点源在污点路径里
            // 由callSite type 得到污点对象Obj
            // 返回这个中间传播的污点对象
            return manager.makeTaint(callSite, type);
        }
        return null;
    }
    public Set<Pair<Var, Obj>> handleTaintTransfer(CSCallSite csCallSite, JMethod callee, CSVar base){
        // 返回一个 call-site statement 不含上下文
        Invoke callSite = csCallSite.getCallSite();
        // res = fun(a); 得到调用点左值 fun(?)不行
        Var lVar = callSite.getLValue();
        PointerAnalysisResult ptaResult = solver.getResult();
        Set<Pair<Var, Obj>> result = new HashSet<>();
        TaintTransfer transfer;
        // 污点传播的三种方式
        if(base != null) {
            // Call (base-to-result)  result = base.foo(...)
            // 如果 receiver object（由 base 指向）被污染了，那么该方法调用的返回值也会被污染
            Type resultType = callee.getReturnType();
            // TaintTransfer(JMethod method, int from, int to, Type type) 这里from * to * 可以指代三种不同的污点传播方式
            // method: 引起污点传播的方法
            // from:污点传播的起点变量 (污点源) to:污点传播的终点变量 (传播目标)
            // type:污点对象的类型
            transfer = new TaintTransfer(callee, TaintTransfer.BASE, TaintTransfer.RESULT, resultType);
            if(config.getTransfers().contains(transfer) && lVar != null){
                // 存在 base-to-result 并且左值有值
                Set<CSObj> basePts = ptaResult.getPointsToSet(base);
                // 获取流经base的所有CSObj 即他们全被污染
                basePts.forEach(csObj -> {
                    if(manager.isTaint(csObj.getObject())){
                        // pair <Var, Obj> = {被传播到的变量,污点对象}
                        result.add(new Pair<>(lVar,
                                // getSourceCall 得到taint object的call method 调用/引起方法
                                manager.makeTaint(manager.getSourceCall(csObj.getObject()), resultType)));
                    }
                });
            }
            // Call (arg-to-base)
            // 如果某个特定的参数被污染了，那么 receiver object（由 base 指向）也会被污染。
            // (res =) base.fun(a1,a2,...,an) a1,..,an -> base
            // 得到基点的类型
            Type baseType = base.getType();
            // 调用点参数获得
            List<Var> args = callSite.getInvokeExp().getArgs();
            for (int i = 0; i < args.size(); i++) {
                Var arg = args.get(i);
                // 根据调用点敏感上下文和参数获取 流经该参数的对象CSObj  getCSVar(Context, Var) 由上下文+变量 得到上下文敏感的变量
                Set<CSObj> argPts = ptaResult.getPointsToSet(csManager.getCSVar(csCallSite.getContext(), arg));
                transfer = new TaintTransfer(callee, i, TaintTransfer.BASE, baseType);
                if (config.getTransfers().contains(transfer)) {
                    // 存在 args-to-base
                    argPts.forEach(csObj -> {
                        if(manager.isTaint(csObj.getObject())){
                            result.add(new Pair<>(base.getVar(),
                                    manager.makeTaint(manager.getSourceCall(csObj.getObject()), baseType)));
                        }
                    });
                }
            }
        }
        // Call (arg-to-result)
        // 如果某个特定的参数被污染了，那么该方法调用的返回值也会被污染。
        // 和上述差不太多 把to * 换成to result
        List<Var> args = callSite.getInvokeExp().getArgs();
        Type resultType = callee.getReturnType();
        for (int i = 0; i < args.size(); i++) {
            Var arg = args.get(i);
            Set<CSObj> argPts = ptaResult.getPointsToSet(csManager.getCSVar(csCallSite.getContext(), arg));
            transfer = new TaintTransfer(callee, i, TaintTransfer.RESULT, resultType);
            if (config.getTransfers().contains(transfer)) {
                argPts.forEach(csObj -> {
                    if(manager.isTaint(csObj.getObject())){
                        result.add(new Pair<>(lVar,
                                manager.makeTaint(manager.getSourceCall(csObj.getObject()), resultType)));
                    }
                });
            }
        }
        return result;
    }


    public void onFinish() {
        Set<TaintFlow> taintFlows = collectTaintFlows();
        solver.getResult().storeResult(getClass().getName(), taintFlows);
    }

    private Set<TaintFlow> collectTaintFlows() {
        // 返回一个集合，其中包含污点分析检测到的所有 taint flows
        Set<TaintFlow> taintFlows = new TreeSet<>();
        PointerAnalysisResult result = solver.getResult();
        // TODO - finish me
        // 得到call-graph表示数据
        CallGraph<CSCallSite, CSMethod> callGraph = result.getCSCallGraph();
        callGraph.reachableMethods().forEach(csMethod -> {
            // 得到call-graph 所有可达方法
            // 遍历这些可达方法的调用点
            callGraph.getCallersOf(csMethod).forEach(csCallSite -> {
                // 无上下文的调用点语句 res = fun(a1,a2,...)  T fun(p1,p2,...,){...}
                Invoke callSite = csCallSite.getCallSite();
                // 得到被调用的方法 T
                JMethod callee = csMethod.getMethod();
                // 得到所使用的参数
                // sources {m,u} m:method signature u:type of taint object
                // sink: target transfer object
                // from source to sink: a1->p1,...,an->pn
                List<Var> args = callSite.getInvokeExp().getArgs();
                for(int i = 0;i < args.size();i ++){
                    Var arg = args.get(i);
                    Sink sink = new Sink(callee, i);
                    if(config.getSinks().contains(sink)){
                        // 存在这个sink
                        int index = i;
                        // 获得所有流经args(a1,a2,...,an)的CSObj
                        result.getPointsToSet(arg).forEach(obj -> {
                            if(manager.isTaint(obj)){
                                // 判断是否是污点对象 taint object
                                // TaintFlow(source_call, sink_call, index) 获得从source到sink的实例对象
                                taintFlows.add(new TaintFlow(manager.getSourceCall(obj), callSite, index));
                            }
                        });
                    }
                }
            });
        });
        // You could query pointer analysis results you need via variable result.
        return taintFlows;
    }
}
