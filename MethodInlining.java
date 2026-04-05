
import java.util.*;

import soot.Body;
import soot.Local;
import soot.Scene;
import soot.SceneTransformer;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.ValueBox;
import soot.jimple.*;
import soot.util.Chain;
import soot.jimple.toolkits.callgraph.*;
import soot.javaToJimple.DefaultLocalGenerator;

public class MethodInlining extends SceneTransformer {
    // Key: ClassName.MethodName, Value: Methods that called from current method
    Map<String, List<SootMethod>> methodMap = new HashMap<>();

    @Override
    protected void internalTransform(String phaseName, java.util.Map<String, String> options) {
        prePass();
        // System.out.println(methodMap);
        inlineMethods();
    }

    public void prePass() {
        CallGraph cg = Scene.v().getCallGraph();

        for (SootClass sc : Scene.v().getApplicationClasses()) {
            for (SootMethod sm : sc.getMethods()) {
                String key = sc.getName() + "." + sm.getName();

                List<SootMethod> totalCallees = new LinkedList<>();

                for (Unit u : sm.retrieveActiveBody().getUnits()) {
                    Iterator<Edge> edges = cg.edgesOutOf(u);

                    while (edges.hasNext()) {
                        SootMethod tgt = edges.next().tgt();

                        if (tgt == null || tgt.isJavaLibraryMethod() || tgt.isConstructor()
                                || tgt.isStaticInitializer())
                            continue;

                        totalCallees.add(tgt);
                    }
                }

                methodMap.put(key, totalCallees);
            }
        }
    }

    private void inlineMethods() {

        Chain<SootClass> libraryClasses = Scene.v().getApplicationClasses();

        for (SootClass sc : libraryClasses) {

            List<SootMethod> methods = sc.getMethods();

            for (SootMethod sm : methods) {
                // ClassA.foo()
                // ClassB.bar()
                String key = sc.getName() + "." + sm.getName();

                List<SootMethod> callees = methodMap.get(key);

                if (!callees.isEmpty() && sm != null && sm.hasActiveBody()) {
                    performInlining(sm, callees);
                    // System.out.println(sm.getSignature());
                    // System.out.println(sm.getActiveBody());
                }
            }
        }
    }

    public void performInlining(SootMethod caller, List<SootMethod> callees) {
        // for (SootMethod callee: callees) {
        // if (canInline()) {
        // // Perform inlining
        // System.out.println(callees);
        // }
        // }

        // Queue<SootMethod> q = new LinkedList<>();
        // q.addAll(callees);

        Body callerBody = caller.retrieveActiveBody();

        for (Iterator<Unit> it = callerBody.getUnits().snapshotIterator(); it.hasNext();) {
            Unit u = it.next();
            if (u instanceof Stmt) {
                Stmt stmt = (Stmt) u;

                if (stmt.containsInvokeExpr()) {
                    CallGraph cg = Scene.v().getCallGraph();
                    Iterator<Edge> edges = cg.edgesOutOf(stmt);

                    if (edges.hasNext()) {
                        SootMethod callee = edges.next().tgt();

                        // Inline only if there is a unique target and it has a body
                        if (callee != null && !edges.hasNext() && callee.hasActiveBody()) {
                            if (callee != caller) {
                                performInliningAtCaller(callerBody, stmt, callee);
                            }
                        }
                    }
                }
            }
        }
    }

    private void performInliningAtCaller(Body callerBody, Stmt stmt, SootMethod callee) {
        Map<Local, Local> localMap = new HashMap<>();

        DefaultLocalGenerator dLocalGen = new DefaultLocalGenerator(callerBody);

        Body calleeBody = (Body) callee.retrieveActiveBody().clone();

        // Storing Local variable to new type variable
        for (Local localVar : calleeBody.getLocals()) {
            Type localVarType = localVar.getType();

            Local newVar = dLocalGen.generateLocal(localVarType);

            localMap.put(localVar, newVar);
        }

        Iterator<Unit> calleeUnits = calleeBody.getUnits().snapshotIterator();

        List<Unit> modifiedUnits = new LinkedList<>();
        InvokeExpr invokeExpr = stmt.getInvokeExpr();
        List<Value> args = invokeExpr.getArgs();
        int paramIdx = 0;

        while (calleeUnits.hasNext()) {
            Unit cUnit = calleeUnits.next();

            if (cUnit instanceof IdentityStmt) {
                IdentityStmt iStmt = (IdentityStmt) cUnit;

                // iStmt.getLeftOp();
                Value leftOp = iStmt.getLeftOp();
                Value rightOp = iStmt.getRightOp();
                Value newName = localMap.get(leftOp);

                if (rightOp instanceof ThisRef) {
                    if (invokeExpr instanceof InstanceInvokeExpr) {
                        InstanceInvokeExpr instanceInvokeExpr = (InstanceInvokeExpr) invokeExpr;
                        Value rcvr = instanceInvokeExpr.getBase();

                        AssignStmt assignStmt = Jimple.v().newAssignStmt(newName, rcvr);

                        modifiedUnits.add(assignStmt);

                        cUnit.redirectJumpsToThisTo(assignStmt);
                    }
                } else if (rightOp instanceof ParameterRef) {
                    Value currParam = args.get(paramIdx);

                    AssignStmt assignStmt = Jimple.v().newAssignStmt(newName, currParam);

                    modifiedUnits.add(assignStmt);

                    cUnit.redirectJumpsToThisTo(assignStmt);

                    paramIdx++;
                }
            }

            else {
                // Use and Def
                for (ValueBox value : cUnit.getUseAndDefBoxes()) {
                    if (localMap.containsKey(value.getValue())) {
                        Value newName = localMap.get(value.getValue());

                        value.setValue(newName);
                    }
                }

                if (cUnit instanceof ReturnStmt) {
                    // Current statement in the caller:

                    ReturnStmt rStmt = (ReturnStmt) cUnit;

                    // a = fun1()
                    if (stmt instanceof DefinitionStmt) {
                        DefinitionStmt unitDef = (DefinitionStmt) stmt;

                        Value retVal = rStmt.getOp();

                        Unit newAssign = Jimple.v().newAssignStmt(unitDef.getLeftOp(), retVal);

                        modifiedUnits.add(newAssign);

                        cUnit.redirectJumpsToThisTo(newAssign);
                    }

                    // fun1()
                    else {
                        Unit nop = Jimple.v().newNopStmt();
                        modifiedUnits.add(nop);
                        cUnit.redirectJumpsToThisTo(nop);
                    }
                }

                else if (cUnit instanceof ReturnVoidStmt) {
                    Unit nop = Jimple.v().newNopStmt();

                    modifiedUnits.add(nop);

                    cUnit.redirectJumpsToThisTo(nop);
                }

                else {
                    calleeBody.getUnits().remove(cUnit);

                    modifiedUnits.add(cUnit);
                }
            }
        }

        if (!modifiedUnits.isEmpty()) {
            Unit firstUnit = modifiedUnits.get(0);
            stmt.redirectJumpsToThisTo(firstUnit);

            Chain<Unit> callerUnits = callerBody.getUnits();

            for (Unit newUnit : modifiedUnits) {
                callerUnits.insertBefore(newUnit, stmt);
            }
        }

        callerBody.getUnits().remove(stmt);
    }
}