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

package pascal.taie.analysis.dataflow.analysis.constprop;

import pascal.taie.analysis.dataflow.analysis.AbstractDataflowAnalysis;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.exp.*;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.DefinitionStmt;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.type.PrimitiveType;
import pascal.taie.language.type.Type;
import pascal.taie.util.AnalysisException;
// import sth
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConstantPropagation extends
        AbstractDataflowAnalysis<Stmt, CPFact> {

    public static final String ID = "constprop";

    public ConstantPropagation(AnalysisConfig config) {
        super(config);
    }

    @Override
    public boolean isForward() {
        return true;
    }

    @Override
    public CPFact newBoundaryFact(CFG<Stmt> cfg) {
        // TODO - finish me
        // CPF: map = {{key:value},{},...,{}}
        // key: variable, value: NAC/UNDEF/CONSTANT
        // Constant Parameter Flow 是一个字典列表类型map, 用于保存上述键值对关系，初始化为空
        CPFact fact = new CPFact();
        // getIR(): get IR(Intermediate representation) from control flow graph
        // getParams(): get parameter variables from IR (this variable is excluded)
        // 从数据流图中获取中间表示的参数变量对象
        List<Var> vars = cfg.getIR().getParams();
        // 遍历当前对象列表
        for(Var var:vars)
        {
            // 判断是否属于设定类型中的变量
            // BYTE,SHORT,INT,CHAR,BOOLEAN
            if(canHoldInt(var))
                //更新 键值对关系 {参数对象，参数对象的属性(NAC/UNDEF/CONSTANT)}
                fact.update(var, Value.getNAC());
        }
        return fact;
    }

    @Override
    public CPFact newInitialFact() {
        // TODO - finish me
        return new CPFact();
    }

    @Override
    public void meetInto(CPFact fact, CPFact target) {
        // TODO - finish me
        // meet^ fact and target -> lub
        // CPFact 继承自 MapFact , keySet()用于获取其键集合
        for(Var var : fact.keySet())
        {
            // 更新target里的键值对 CPF.get(key=var) -> 得到key对应的value,不存在则返回默认值UNDEF
            target.update(var, meetValue(fact.get(var), target.get(var)));
        }
    }

    /**
     * Meets two Values.
     */
    public Value meetValue(Value v1, Value v2) {
        // TODO - finish m
    /*   常量传播的规则 meet operator
         PreSet: Undefined state: UNDEF, Not constant: NAC, Variable: V, Constant: C
         1.NAC ^ V = NAC
         2.UNDEF ^ V = V
         3.C ^ V = C
         4.C ^ C = C
         5.C1 ^ C2 = NAC     */
        if (v1.isNAC() || v2.isNAC()) {
            return Value.getNAC();
        } // NAC ^ V = NAC
        if (v1.isUndef()) {
            return v2;
        } // UNDEF ^ V = V
        if (v2.isUndef()) {
            return v1;
        } // UNDEF ^ V = V
        if (v1.getConstant() == v2.getConstant()) {
            return Value.makeConstant(v1.getConstant());
        }// C ^ C = C 判断两个值是否相等 相等返回C的constant value
        return Value.getNAC(); // others C1 ^ C2 = NAC
    }

    @Override
    public boolean transferNode(Stmt stmt, CPFact in, CPFact out) {
        // TODO - finish me
        // copy CPF in data into CPF out data
        // use temp CPF
        if (stmt instanceof DefinitionStmt<?,?>) {
            // DefinitionStmt 里声明了 LValue,RValue getLValue()获取左侧Value，getRValue()获取右侧Value
            LValue lv = ((DefinitionStmt<?, ?>) stmt).getLValue();
            RValue rv = ((DefinitionStmt<?, ?>) stmt).getRValue();
            if (lv instanceof Var && canHoldInt((Var)lv)){ // 判断是否是合法的类型
                CPFact tf = in.copy(); // 赋值给临时变量tf
                tf.update((Var)lv, evaluate(rv, in)); // 对进行运算结果判断Value 更新tf:{{key:value}}
                return out.copyFrom(tf); // 把tf的值复制给out
            }
        }
        return out.copyFrom(in);
    }

    /**
     * @return true if the given variable can hold integer value, otherwise false.
     */
    public static boolean canHoldInt(Var var) {
        Type type = var.getType();
        if (type instanceof PrimitiveType) {
            switch ((PrimitiveType) type) {
                case BYTE:
                case SHORT:
                case INT:
                case CHAR:
                case BOOLEAN:
                    return true;
            }
        }
        return false;
    }

    /**
     * Evaluates the {@link Value} of given expression.
     *
     * @param exp the expression to be evaluated
     * @param in  IN fact of the statement
     * @return the resulting {@link Value}
     */
    public static Value evaluate(Exp exp, CPFact in) {
        // TODO - finish me
        // 这个是计算表达式的各种结果,包含各种运算符
        // 判断是否是整型常量
        if (exp instanceof IntLiteral) {
            //返回它的值判断结果 NAC/ UNDEF / Constant value
            // makeConstant() -> 返回常量值
            return Value.makeConstant(((IntLiteral)exp).getValue());
        }
        //判断是否是变量
        if (exp instanceof Var) {
            // map.get(key) = value type
            //获得对应键值对的值属性
            return in.get((Var) exp);
        }
        //初始化为NAC
        Value result = Value.getNAC();
        // 判断是否是二元表达式 A op B
        if (exp instanceof BinaryExp) {
            // CPF.get(var) 获得var作为key, 进而取出对应的Value值, getOperator()取出表达式里的操作符op
            Var op1 = ((BinaryExp) exp).getOperand1(), op2 = ((BinaryExp) exp).getOperand2();
            Value op1_val = in.get(op1), op2_val = in.get(op2);
            BinaryExp.Op op = ((BinaryExp) exp).getOperator();

            if (op1_val.isConstant() && op2_val.isConstant()) {
                //判断是否是算术表达式 + - * /
                if (exp instanceof ArithmeticExp) {
                    if (op == ArithmeticExp.Op.ADD) {
                        result = Value.makeConstant(op1_val.getConstant() + op2_val.getConstant());
                    } else if (op == ArithmeticExp.Op.DIV) {
                        if (op2_val.getConstant() == 0) {
                            // 判断除数为0
                            result = Value.getUndef();
                        } else {
                            result = Value.makeConstant(op1_val.getConstant() / op2_val.getConstant());
                        }
                    } else if (op == ArithmeticExp.Op.MUL) {
                        result = Value.makeConstant(op1_val.getConstant() * op2_val.getConstant());
                    } else if (op == ArithmeticExp.Op.SUB) {
                        result = Value.makeConstant(op1_val.getConstant() - op2_val.getConstant());
                    } else if (op == ArithmeticExp.Op.REM) {
                        if (op2_val.getConstant() == 0) {
                            // 判断除数为0
                            result = Value.getUndef();
                        } else {
                            result = Value.makeConstant(op1_val.getConstant() % op2_val.getConstant());
                        }
                    }
                } else if (exp instanceof BitwiseExp) {
                    // 逻辑运算符
                    if (op == BitwiseExp.Op.AND) {
                        result = Value.makeConstant(op1_val.getConstant() & op2_val.getConstant());
                    } else if (op == BitwiseExp.Op.OR) {
                        result = Value.makeConstant(op1_val.getConstant() | op2_val.getConstant());
                    } else if (op == BitwiseExp.Op.XOR) {
                        result = Value.makeConstant(op1_val.getConstant() ^ op2_val.getConstant());
                    }
                } else if (exp instanceof ConditionExp) {
                    // 条件运算符
                    if (op == ConditionExp.Op.EQ) {
                        result = Value.makeConstant((op1_val.getConstant() == op2_val.getConstant()) ? 1 : 0);
                    } else if (op == ConditionExp.Op.GE) {
                        result = Value.makeConstant((op1_val.getConstant() >= op2_val.getConstant()) ? 1 : 0);
                    } else if (op == ConditionExp.Op.GT) {
                        result = Value.makeConstant((op1_val.getConstant() > op2_val.getConstant()) ? 1 : 0);
                    } else if (op == ConditionExp.Op.LE) {
                        result = Value.makeConstant((op1_val.getConstant() <= op2_val.getConstant()) ? 1 : 0);
                    } else if (op == ConditionExp.Op.LT) {
                        result = Value.makeConstant((op1_val.getConstant() < op2_val.getConstant()) ? 1 : 0);
                    } else if (op == ConditionExp.Op.NE) {
                        result = Value.makeConstant((op1_val.getConstant() != op2_val.getConstant()) ? 1 : 0);
                    }
                } else if (exp instanceof ShiftExp) {
                    // 移位运算符
                    if (op == ShiftExp.Op.SHL) {
                        result = Value.makeConstant(op1_val.getConstant() << op2_val.getConstant());
                    } else if (op == ShiftExp.Op.SHR) {
                        result = Value.makeConstant(op1_val.getConstant() >> op2_val.getConstant());
                    } else if (op == ShiftExp.Op.USHR) {
                        result = Value.makeConstant(op1_val.getConstant() >>> op2_val.getConstant());
                    }
                } else {
                    result = Value.getUndef();
                }
            } else if (op1_val.isNAC() || op2_val.isNAC()) {
                // 有任意一个为NAC
                if (exp instanceof ArithmeticExp && (op == ArithmeticExp.Op.DIV || op == ArithmeticExp.Op.REM)) {
                    if (op2_val.isConstant() && op2_val.getConstant() == 0) {
                        result = Value.getUndef();
                    } else {
                        result = Value.getNAC();
                    }
                } else {
                    result = Value.getNAC();
                }
            } else {
                result = Value.getUndef();
            }
        }
        return result;
    }
}
