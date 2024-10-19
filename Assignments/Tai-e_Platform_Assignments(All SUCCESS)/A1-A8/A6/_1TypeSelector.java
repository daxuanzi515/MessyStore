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
import pascal.taie.language.classes.JMethod;

/**
 * Implementation of 1-type sensitivity.
 */
public class _1TypeSelector implements ContextSelector {
    @Override
    public Context getEmptyContext() {
        return ListContext.make();
    }
    // InType(y-object) = X (not Y)
    // class X {
    //  void m(){
    //   Y y = new Y();
    //  }
    // }
    @Override
    public Context selectContext(CSCallSite callSite, JMethod callee) {
        // TODO - finish me
        // S(_,_,c':oi) = [t',t'',...,InType(oi)] when c' = [t',,...,t'']
        // c' call site context
        return callSite.getContext();
    }

    @Override
    public Context selectContext(CSCallSite callSite, CSObj recv, JMethod callee) {
        // TODO - finish me
        // type set 但是因为有InType(obj) 所以要用getContainerType()
        // CSObj.getObject() -> Obj temp -> Obj.getContainerType
        return ListContext.make(recv.getObject().getContainerType());
    }

    @Override
    public Context selectHeapContext(CSMethod method, Obj obj) {
        // TODO - finish me
        // 这个无用直接返回空对象
        return ListContext.make();
    }
}