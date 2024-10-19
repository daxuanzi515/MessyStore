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

package pascal.taie.analysis.pta.core.cs.selector;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.context.ListContext;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;

/**
 * Implementation of 2-call-site sensitivity.
 */
public class _2CallSelector implements ContextSelector {
    @Override
    public Context getEmptyContext() {
        return ListContext.make();
    }

    @Override
    public Context selectContext(CSCallSite callSite, JMethod callee) {
        // TODO - finish me
        // 2-call-site
        // S(c,l,_) = [l'',l], when c = [l',l'']
        // 受上下文长度限制
        int length = callSite.getContext().getLength();
        if (length == 0 )
        {
            // 上下文没有 则直接返回调用点本身上下文
            return ListContext.make(callSite.getCallSite());
        }
        // 否则返回上下文最后一个元素 e[len-1]
        return ListContext.make(callSite.getContext().getElementAt(length-1), callSite.getCallSite());
    }

    @Override
    public Context selectContext(CSCallSite callSite, CSObj recv, JMethod callee) {
        // TODO - finish me
        int length = callSite.getContext().getLength();
        if (length == 0 )
        {
            // 上下文没有 则直接返回调用点本身上下文
            return ListContext.make(callSite.getCallSite());
        }
        // 否则返回上下文最后一个元素 e[len-1] 后面还要补上一个自己的上下文
        return ListContext.make(callSite.getContext().getElementAt(length-1), callSite.getCallSite());
    }

    @Override
    public Context selectHeapContext(CSMethod method, Obj obj) {
        // TODO - finish me
        Context ctx = method.getContext(); // 返回该方法的上下文
        // k=2 for method contexts, k=1 for heap contexts
        if (method.getContext().getLength() <= 1) { // 判断length和k值
            return ctx;
        }
        return ListContext.make(ctx.getElementAt(ctx.getLength() - 1));
    }
}
