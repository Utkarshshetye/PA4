import soot.*;
import soot.jimple.*;
import java.util.*;

import java.util.*;

import soot.jimple.*;
import soot.util.Chain;
import soot.jimple.toolkits.callgraph.*;
import soot.javaToJimple.DefaultLocalGenerator;

public class InlineCheck {

    private final ObjSensAnalysisTransformer analysis;

    public InlineCheck(ObjSensAnalysisTransformer analysis) {
        this.analysis = analysis;
    }

    public void run() {

        clonePass();

        for (SootClass cls : Scene.v().getApplicationClasses()) {
            if (!analysis.isUserClass(cls))
                continue;

            List<SootMethod> methods = new ArrayList<>(cls.getMethods());

            for (SootMethod method : methods) {
                if (!method.hasActiveBody())
                    continue;

                boolean isInlineChanged = true;

                while (isInlineChanged) {
                    isInlineChanged = false;

                    Iterator<Unit> units = method.getActiveBody().getUnits().snapshotIterator();

                    while (units.hasNext()) {
                        Unit u = units.next();
                        Stmt stmt = (Stmt) u;

                        if (!stmt.containsInvokeExpr())
                            continue;

                        if (!analysis.isDispatchCall(stmt) && !(stmt.getInvokeExpr() instanceof StaticInvokeExpr))
                            continue;

                        SootClass calleeClass = stmt.getInvokeExpr().getMethodRef().getDeclaringClass();

                        if (!analysis.isUserClass(calleeClass))
                            continue;

                        SootMethod targt = null;

                        if (stmt.getInvokeExpr() instanceof StaticInvokeExpr) {
                            targt = stmt.getInvokeExpr().getMethodRef().resolve();
                        } else if (analysis.isGlobalMono(stmt)) {
                            targt = analysis.getGlobalTarget(stmt);
                        } else {

                            SootMethod newClone = stmt.getInvokeExpr().getMethod();

                            // Cloned method, which redirect to specialized
                            if (newClone.getName().contains("n_v")) {
                                targt = newClone;
                            }
                        }

                        if (targt != null && targt.hasActiveBody() && targt != method) {
                            performInliningAtCaller(method.retrieveActiveBody(), stmt, targt);

                            isInlineChanged = true;

                            break;
                        }

                        else if (analysis.isTrulyPoly(stmt)) {
                            System.out.println("Inlining not possible");

                        } else {
                            System.out.println("[UNKNOWN]");
                            System.out.println("  stmt:   " + stmt);
                            System.out.println("  action: skip — no pts-to info");
                        }
                    }
                }
            }
        }
    }

    private void clonePass() {
        Chain<SootClass> sootclasses = Scene.v().getApplicationClasses();

        for (SootClass cls : sootclasses) {
            if (!analysis.isUserClass(cls))
                continue;

            List<SootMethod> methods = new ArrayList<>(cls.getMethods());

            for (SootMethod method : methods) {
                if (!method.hasActiveBody())
                    continue;

                Iterator<Unit> units = method.retrieveActiveBody().getUnits().snapshotIterator();

                while (units.hasNext()) {
                    Unit u = units.next();
                    Stmt stmt = (Stmt) u;

                    if (!analysis.isDispatchCall(stmt))
                        continue;

                    if (analysis.isPerCtxMono(stmt)) {
                        handlePerCtxMono(method, stmt);
                    }
                }
            }
        }
    }

    // a.foo() or b.foo()
    // obj.foo(x)
    private void handlePerCtxMono(SootMethod m, Stmt stmt) {
        Map<Context, SootMethod> ctxTargets = analysis.getCtxTargets(stmt);

        for (Map.Entry<Context, SootMethod> entry : ctxTargets.entrySet()) {
            Context ctx = entry.getKey();
            SootMethod target = entry.getValue();

            // System.out.println("Context: " + ctx);
            // System.out.println("Callee: " + target.getDeclaringClass().getShortName() +
            // "::" + target.getName());
            // System.out.println(stmt);

            // Creating different versions(clones) for the inlining

            SootClass cls = m.getDeclaringClass();

            String newMethod = m.getName() + "n_v" + target.getDeclaringClass().getShortName() + "_"
                    + stmt.getJavaSourceStartLineNumber();

            boolean isPresent = cls.declaresMethod(newMethod, m.getParameterTypes(), m.getReturnType());

            SootMethod clonedMethod = null;

            if (!isPresent) {

                // Create a new method and add to the Class
                clonedMethod = new SootMethod(newMethod, m.getParameterTypes(), m.getReturnType(), m.getModifiers());

                cls.addMethod(clonedMethod);

                clonedMethod.setActiveBody((Body) m.retrieveActiveBody().clone());

                Iterator<Unit> cloneUnits = clonedMethod.retrieveActiveBody().getUnits().snapshotIterator();

                for (Iterator<Unit> it = cloneUnits; it.hasNext();) {
                    Unit cu = it.next();

                    if (cu.toString().equals(stmt.toString())) {

                        performInliningAtCaller(clonedMethod.getActiveBody(), (Stmt) cu, target);
                        break;
                    }
                }

            } else {

                clonedMethod = cls.getMethod(newMethod, m.getParameterTypes(), m.getReturnType());
            }

            // clonedMethod is done, replacement need to be done now

            Iterator<SootClass> clsIter = Scene.v().getApplicationClasses().iterator();

            while (clsIter.hasNext()) {
                SootClass c = clsIter.next();

                if (!analysis.isUserClass(c))
                    continue;

                List<SootMethod> methods = new ArrayList<>(c.getMethods());

                for (SootMethod method : methods) {
                    if (!method.hasActiveBody())
                        continue;

                    Iterator<Unit> units = method.retrieveActiveBody().getUnits().snapshotIterator();

                    while (units.hasNext()) {
                        Unit u = units.next();

                        if (u instanceof Stmt) {
                            Stmt stm = (Stmt) u;

                            if (!stm.containsInvokeExpr())
                                continue;

                            // if (!analysis.isDispatchCall(stm))
                            // continue;

                            SootMethodRef mRef = stm.getInvokeExpr().getMethodRef();

                            if (mRef.getName().equals(m.getName()) && mRef.resolve().equals(m)) {

                                boolean ctxMatch = false;

                                // analysis.getCtxTargets(stm).keySet().forEach((ctx1) -> {
                                // // System.out.println("Context: " + ctx1);
                                // Context calleeCtx = analysis.getCalleeContext(stmt, ctx1);

                                // if (calleeCtx.equals(ctx)) {
                                // ctxMatch = true;
                                // }
                                // });

                                // 1. Virtual Call mapping
                                Set<Context> prevContexts = analysis.methodContexts.getOrDefault(method,
                                        Collections.singleton(Context.EMPTY));

                                if (!(stm.getInvokeExpr() instanceof StaticInvokeExpr)) {
                                    for (Context prevContext : prevContexts) {
                                        Context calleeCtx = analysis.getCalleeContext(stm, prevContext);

                                        if (calleeCtx != null && calleeCtx.equals(ctx)) {
                                            ctxMatch = true;
                                            break;
                                        }
                                    }
                                }

                                if (stm.getInvokeExpr() instanceof StaticInvokeExpr) {

                                    for (Value arg : stm.getInvokeExpr().getArgs()) {
                                        String argType = arg.getType().toString();

                                        if (argType.equals(target.getDeclaringClass().getName())) {
                                            ctxMatch = true;
                                            break;
                                        }
                                    }

                                }

                                if (ctxMatch) {
                                    stm.getInvokeExpr().setMethodRef(clonedMethod.makeRef());
                                }
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

        Unit afterInlined = Jimple.v().newNopStmt();

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

                        GotoStmt gotoStmt = Jimple.v().newGotoStmt(afterInlined);
                        modifiedUnits.add(gotoStmt);
                    }

                    // fun1()
                    else {
                        Unit nop = Jimple.v().newNopStmt();
                        modifiedUnits.add(nop);
                        cUnit.redirectJumpsToThisTo(nop);

                        GotoStmt gotoStmt = Jimple.v().newGotoStmt(afterInlined);
                        modifiedUnits.add(gotoStmt);
                    }
                }

                else if (cUnit instanceof ReturnVoidStmt) {
                    Unit nop = Jimple.v().newNopStmt();

                    modifiedUnits.add(nop);

                    cUnit.redirectJumpsToThisTo(nop);

                    GotoStmt gotoStmt = Jimple.v().newGotoStmt(afterInlined);
                    modifiedUnits.add(gotoStmt);
                }

                else {
                    // calleeBody.getUnits().remove(cUnit);
                    modifiedUnits.add(cUnit);
                }
            }
        }

        modifiedUnits.add(afterInlined);

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