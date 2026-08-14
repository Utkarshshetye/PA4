import soot.*;
import soot.jimple.*;
import soot.util.Chain;

import java.util.*;

class LineUtil {
    static int lineOf(Stmt s) {
        if (s == null) return -1;
        return s.getJavaSourceStartLineNumber();
    }

    static String objLabel(Stmt s) {
        if (s == null) return "entry";
        int line = lineOf(s);
        return line < 0 ? "entry" : "O" + line;
    }

     static String methodLabel(SootMethod m) {
        if (m == null) return "[?:?]";
        return "[" + m.getDeclaringClass().getShortName()
             + ":" + m.getName() + "]";
    }
}


class Context {
    public final SootClass type;
    public final Stmt      allocSite;
    public static final Context EMPTY = new Context(null, null);

    public Context(SootClass type, Stmt allocSite) {
        this.type = type; this.allocSite = allocSite;
    }
    @Override public boolean equals(Object o) {
        if (!(o instanceof Context)) return false;
        Context c = (Context) o;
        return Objects.equals(type, c.type) && Objects.equals(allocSite, c.allocSite);
    }
    @Override public int hashCode() { return Objects.hash(type, allocSite); }

        @Override public String toString() {
        if (type == null && allocSite == null) return "[EMPTY]";
        String typePart  = (type == null) ? "T:?" : "T:" + type.getShortName();
        String allocPart = LineUtil.objLabel(allocSite);  // "entry" when no site
        return "[" + typePart + ", " + allocPart + "]";
    }
}


class HeapContext {
    public final Stmt      allocSite;
    public final Stmt      callerAllocSite;
    public final SootClass allocType;
    public static final HeapContext EMPTY = new HeapContext(null, null, null);

    public HeapContext(Stmt allocSite, Stmt callerAllocSite, SootClass allocType) {
        this.allocSite       = allocSite;
        this.callerAllocSite = callerAllocSite;
        this.allocType       = allocType;
    }
    @Override public boolean equals(Object o) {
        if (!(o instanceof HeapContext)) return false;
        HeapContext h = (HeapContext) o;
        return Objects.equals(allocSite, h.allocSite)
            && Objects.equals(callerAllocSite, h.callerAllocSite);
    }
    @Override public int hashCode() { return Objects.hash(allocSite, callerAllocSite); }

    // 1-Obj = O<line>   1-Type = T:ClassName
        @Override public String toString() {
        if (allocSite == null && callerAllocSite == null && allocType == null)
            return "HC[EMPTY]";
        String a1       = LineUtil.objLabel(allocSite);
        String a2       = LineUtil.objLabel(callerAllocSite);  // "entry" when no caller
        String typePart = (allocType == null) ? "T:?" : "T:" + allocType.getShortName();
        return "HC[" + a1 + ", " + a2 + " | " + typePart + "]";
    }
}

class WorklistEntry {
    public final SootMethod method;
    public final Context    ctx;
    public WorklistEntry(SootMethod m, Context c) { method = m; ctx = c; }
}


public class ObjSensAnalysisTransformer extends SceneTransformer {

 
    public Map<Stmt, Map<Context, SootMethod>> resolvedCalls = new HashMap<>();
    public Map<Stmt, Map<Context, Map<HeapContext, SootMethod>>> perHCResolved = new HashMap<>();
    public final Set<Stmt> globalMonoStmts  = new LinkedHashSet<>();
    public final Set<Stmt> perCtxMonoStmts  = new LinkedHashSet<>();
    public final Set<Stmt> trulyPolyStmts   = new LinkedHashSet<>();
    public final Set<Stmt> unknownStmts     = new LinkedHashSet<>();
    

    private boolean analyzeLibraries = false;  
    private boolean useCHAForUnknown = false;
    private boolean useInline = true;  

    private long analysisStartTime;
    private long analysisEndTime;

    private int analyzeMethodCalls  = 0;
    private int handleNewExprCalls  = 0;
    private int worklistSkips       = 0;
    private int calleeRequeues      = 0;
    private int callerRequeues      = 0;

    public int totalVirtualCalls  = 0;
    public int resolvedCount      = 0;
    public int heapObjectsCreated = 0;

    private static final int MAX_CONTEXTS_PER_METHOD = 1200;   // tune: 800-1500
    private static final int MAX_TOTAL_CONTEXTS     = 30000;   // global hard cap

    private final Set<SootMethod> cappedMethods = new HashSet<>();
    private final Set<String> pendingInWorklist = new HashSet<>();   // prevent duplicate enqueue

    private final Map<String, Integer> perContextFactHash = new HashMap<>();

    // Analysis state
    private final Map<String, Set<HeapContext>> localPT  = new HashMap<>();
    private final Map<String, Set<HeapContext>> staticPT = new HashMap<>();
    private final Map<String, Set<HeapContext>> fieldPT  = new HashMap<>();
    private final Map<String, Set<HeapContext>> returnPT = new HashMap<>();

    private final Map<Stmt, SootClass>  stmtToClass  = new HashMap<>();
    private final Map<Stmt, SootMethod> stmtToMethod = new HashMap<>();
    public final Map<SootMethod, Set<Context>> methodContexts = new HashMap<>();

    private final Queue<WorklistEntry> worklist      = new LinkedList<>();
    private final Map<String, Integer> lastFactCount = new HashMap<>();
    private final Map<String, Integer> methodAnalyzeCounts = new LinkedHashMap<>();

    private final Set<HeapContext> uniqueHCs = new HashSet<>();


    // ===================================================================
    // Public API
    // ===================================================================
    public boolean isGlobalMono(Stmt stmt) {
        if (!isDispatchCall(stmt)) return false;
        return collectAllTargets(stmt).size() == 1;
    }

    
    public boolean isPerCtxMono(Stmt stmt) {
        if (!isDispatchCall(stmt)) return false;
        if (isGlobalMono(stmt))    return false;
        // every context must see exactly 1 target, none poly
        return !getCtxTargets(stmt).isEmpty()
            && getCtxPolyTargets(stmt).isEmpty();
    }

    public boolean isTrulyPoly(Stmt stmt) {
        if (!isDispatchCall(stmt)) return false;
        return !getCtxPolyTargets(stmt).isEmpty();
    }

   
    public SootMethod getGlobalTarget(Stmt stmt) {
        if (!isGlobalMono(stmt)) return null;
        return collectAllTargets(stmt).iterator().next();
    }

   
    public Map<Context, SootMethod> getCtxTargets(Stmt stmt) {
        Map<Context, SootMethod> result = new LinkedHashMap<>();
        if (!isDispatchCall(stmt)) return result;

        SootMethod   enclosing = getEnclosingMethod(stmt);
        if (enclosing == null)  return result;

        String       subSig    = stmt.getInvokeExpr().getMethodRef()
                                    .getSubSignature().toString();
        Set<Context> ctxs      = methodContexts.getOrDefault(
                                    enclosing,
                                    Collections.singleton(Context.EMPTY));

        for (Context ctx : ctxs) {
            Set<HeapContext> rcvHCs =
                getLocalHCs(enclosing, ctx, getReceiver(stmt));
            if (rcvHCs == null || rcvHCs.isEmpty()) continue;

            Set<SootMethod> targets = new LinkedHashSet<>();
            for (HeapContext hc : rcvHCs) {
                if (hc.allocType == null) continue;
                SootMethod t = resolveVirtual(hc.allocType, subSig);
                if (t != null) targets.add(t);
            }
            if (targets.size() == 1)
                result.put(ctx, targets.iterator().next());
        }
        return result;
    }

    public Map<Context, Set<SootMethod>> getCtxPolyTargets(Stmt stmt) {
        Map<Context, Set<SootMethod>> result = new LinkedHashMap<>();
        if (!isDispatchCall(stmt)) return result;

        SootMethod   enclosing = getEnclosingMethod(stmt);
        if (enclosing == null)  return result;

        String       subSig    = stmt.getInvokeExpr().getMethodRef()
                                    .getSubSignature().toString();
        Set<Context> ctxs      = methodContexts.getOrDefault(
                                    enclosing,
                                    Collections.singleton(Context.EMPTY));

        for (Context ctx : ctxs) {
            Set<HeapContext> rcvHCs =
                getLocalHCs(enclosing, ctx, getReceiver(stmt));
            if (rcvHCs == null || rcvHCs.isEmpty()) continue;

            Set<SootMethod> targets = new LinkedHashSet<>();
            for (HeapContext hc : rcvHCs) {
                if (hc.allocType == null) continue;
                SootMethod t = resolveVirtual(hc.allocType, subSig);
                if (t != null) targets.add(t);
            }
            if (targets.size() > 1)
                result.put(ctx, targets);
        }
        return result;
    }

 
    public Set<SootMethod> getAllTargetsForStmt(Stmt stmt) {
        return collectAllTargets(stmt);
    }

    
    private Set<SootMethod> collectAllTargets(Stmt stmt) {
        Set<SootMethod> targets   = new LinkedHashSet<>();
        SootMethod      enclosing = getEnclosingMethod(stmt);
        if (enclosing == null) return targets;

        String       subSig = stmt.getInvokeExpr().getMethodRef()
                                .getSubSignature().toString();
        Set<Context> ctxs   = methodContexts.getOrDefault(
                                enclosing,
                                Collections.singleton(Context.EMPTY));

        for (Context ctx : ctxs) {
            Set<HeapContext> rcvHCs =
                getLocalHCs(enclosing, ctx, getReceiver(stmt));
            if (rcvHCs == null) continue;
            for (HeapContext hc : rcvHCs) {
                if (hc.allocType == null) continue;
                SootMethod t = resolveVirtual(hc.allocType, subSig);
                if (t != null) targets.add(t);
            }
        }
        return targets;
    }

    private SootMethod getEnclosingMethod(Stmt stmt) {
        return stmtToMethod.get(stmt);
    }

  
    public Map<Context, SootMethod> getContextTargetPairs(Stmt stmt) {
        return resolvedCalls.getOrDefault(stmt, Collections.emptyMap());
    }

    public Context getCalleeContext(Stmt stmt, Context callerCtx) {
        SootMethod enclosing = getEnclosingMethod(stmt);
        if (enclosing == null)
            return null;

        Set<HeapContext> rcvHCs = getLocalHCs(enclosing, callerCtx, getReceiver(stmt));
        if (rcvHCs == null || rcvHCs.isEmpty()) {
            if (isGlobalMono(stmt))
                return Context.EMPTY;
            return null;
        }

        HeapContext first = rcvHCs.iterator().next();

        return merge(stmt, first, callerCtx);
    }   

//     public void printDevirtualizationPlan() {
//     System.out.println("\n======================================");
//     System.out.println("  Devirtualization Plan");
//     System.out.println("======================================");

//     int mono = 0, perCtx = 0, poly = 0, unkn = 0;

//     for (SootClass cls : Scene.v().getApplicationClasses()) {
//         if (!isUserClass(cls)) continue;
//         for (SootMethod method : new ArrayList<>(cls.getMethods())) {
//             if (!ensureBody(method)) continue;
//             if (!method.hasActiveBody()) continue;
//             for (Unit u : method.getActiveBody().getUnits()) {
//                 Stmt stmt = (Stmt) u;
//                 if (!isDispatchCall(stmt)) continue;
//                 SootClass calleeClass =
//                     stmt.getInvokeExpr().getMethodRef().getDeclaringClass();
//                 if (!isUserClass(calleeClass)) continue;

//                 String kind = stmt.getInvokeExpr() instanceof InterfaceInvokeExpr
//                             ? "interface" : "virtual";
//                 String subSig = stmt.getInvokeExpr().getMethodRef()
//                                     .getSubSignature().toString();

//                 // collect global targets across ALL contexts and ALL rcvHCs
//                 Set<SootMethod> globalTargets = new HashSet<>();
//                 boolean hasPTInfo = false;

//                 Set<Context> ctxs = methodContexts.getOrDefault(
//                     method, Collections.singleton(Context.EMPTY));

//                 for (Context ctx : ctxs) {
//                     Set<HeapContext> rcvHCs = getLocalHCs(method, ctx, getReceiver(stmt));
//                     if (rcvHCs == null || rcvHCs.isEmpty()) continue;
//                     hasPTInfo = true;
//                     for (HeapContext hc : rcvHCs) {
//                         if (hc.allocType == null) continue;
//                         SootMethod t = resolveVirtual(hc.allocType, subSig);
//                         if (t != null) globalTargets.add(t);
//                     }
//                 }

//                 if (!hasPTInfo) {
//                     // ── UNKNOWN ──────────────────────────────────────
//                     unkn++;
//                     // System.out.println("\n[UNKN] " + kind
//                     //     + " call in " + LineUtil.methodLabel(method));
//                     // System.out.println("  stmt:   " + stmt);
//                     // System.out.println("  action: SKIP — no pts-to info ");

//                 } else if (globalTargets.size() == 1) {
//                     // ── GLOBAL MONO ──────────────────────────────────
//                     mono++;
//                     // System.out.println("\n[MONO] " + kind
//                     //     + " call in " + LineUtil.methodLabel(method));
//                     // System.out.println("  stmt:   " + stmt);
//                     // System.out.println("  target: "
//                     //     + LineUtil.methodLabel(globalTargets.iterator().next()));
//                     // System.out.println("  action: DEVIRTUALIZE unconditionally ");
//                     // System.out.println("  per context:");
//                     // getContextTargetPairs(stmt).forEach((ctx, tgt) ->
//                     //     System.out.println("    ctx=" + ctx
//                     //         + " -> " + tgt.getSignature())
//                     // );

//                 } else {
                    
//                     Map<Context, Map<HeapContext, SootMethod>> hcMap =
//                         perHCResolved.getOrDefault(stmt, Collections.emptyMap());

                    
//                     // boolean allPerHCMono = !hcMap.isEmpty();
//                     // for (Map<HeapContext, SootMethod> inner : hcMap.values()) {
//                     //     // each rcvHC maps to exactly 1 target by construction
//                     //     // but check ctx-level: does this ctx see >1 rcvHC targets?
//                     // }

            
//                     boolean anyCtxMono = resolvedCalls.containsKey(stmt);

//                     if (anyCtxMono) {
//                         // ── PER-CONTEXT MONO ─────────────────────────
//                         perCtx++;
//                         // System.out.println("\n[PERCTX] " + kind
//                         //     + " call in " + LineUtil.methodLabel(method));
//                         // System.out.println("  stmt:    " + stmt);
//                         // System.out.println("  action:  DEVIRTUALIZE per context ");
//                         // System.out.println("  global targets: " + globalTargets.size());
//                         // globalTargets.forEach(t ->
//                         //     System.out.println("    - " + LineUtil.methodLabel(t))
//                         // );
//                         // System.out.println("  per (ctx → rcvHC → target):");
//                         // hcMap.forEach((ctx, inner) ->
//                         //     inner.forEach((hc, tgt) ->
//                         //         System.out.println(
//                         //             "    ctx=" + ctx
//                         //             + "  hc=" + hc
//                         //             + "  -> " + tgt.getSignature())
//                         //     )
//                         // );

//                     } else {
//                         // ── TRULY POLY ───────────────────────────────
//                         poly++;
//                         // System.out.println("\n[POLY] " + kind
//                         //     + " call in " + LineUtil.methodLabel(method));
//                         // System.out.println("  stmt:    " + stmt);
//                         // System.out.println("  action:  CANNOT devirtualize ");
//                         // System.out.println("  targets: " + globalTargets.size());
//                         // globalTargets.forEach(t ->
//                         //     System.out.println("    - " + LineUtil.methodLabel(t))
//                         // );
//                         // System.out.println("  per (ctx → rcvHC → target):");
//                         // hcMap.forEach((ctx, inner) ->
//                         //     inner.forEach((hc, tgt) ->
//                         //         System.out.println(
//                         //             "    ctx=" + ctx
//                         //             + "  hc=" + hc
//                         //             + "  -> " + tgt.getSignature())
//                         //     )
//                         // );
//                     }
//                 }
//             }
//         }
//     }

//     System.out.println("\n=====================================");
//     System.out.println("SUMMARY:");
//     System.out.println("  Global mono  (devirt unconditionally): " + mono);
//     System.out.println("  Per-ctx mono (devirt per context):     " + perCtx);
//     System.out.println("  Truly poly   (cannot devirt):          " + poly);
//     System.out.println("  Unknown      (no pts-to):              " + unkn);
//     System.out.println("  Total:                                 "
//         + (mono + perCtx + poly + unkn));
//     System.out.println("======================================\n");
// }

    public void printDevirtualizationPlan() {
        // System.out.println("\n======================================");
        // System.out.println("  Devirtualization Plan");
        // System.out.println("======================================");

        System.out.println("\n=====================================");
        System.out.println("  SUMMARY:");
        System.out.println("  Global mono  (devirt unconditionally): " + globalMonoStmts.size());
        System.out.println("  Per-ctx mono (devirt per context):     " + perCtxMonoStmts.size());
        System.out.println("  Truly poly   (cannot devirt):          " + trulyPolyStmts.size());
        System.out.println("  Unknown      (no pts-to/unresolved):   " + unknownStmts.size());
        System.out.println("  Total calls   : " + totalVirtualCalls);
        System.out.println("  Resolution rate: " + (totalVirtualCalls > 0 ? 
            String.format("%.1f%%", 100.0 * resolvedCount / totalVirtualCalls) : "0%"));
        System.out.println("======================================\n");
    }

    private SootMethod resolveByCHA(Stmt stmt) {
        if (!isDispatchCall(stmt)) return null;

        InvokeExpr ie = stmt.getInvokeExpr();
        SootClass declaredClass = ie.getMethodRef().getDeclaringClass();
        String subSig = ie.getMethodRef().getSubSignature().toString();

        // Use Soot's FastHierarchy (most efficient)
        soot.FastHierarchy fh = Scene.v().getOrMakeFastHierarchy();

        // Check if the declared type has any possible subtypes in the scene
        boolean hasSubtypes = false;

        // For classes: check subclasses
        if (!declaredClass.isInterface()) {
            Collection<SootClass> subclasses = fh.getSubclassesOf(declaredClass);
            hasSubtypes = (subclasses != null && !subclasses.isEmpty());
            // For interfaces: check all implementers
            Set<SootClass> implementers = fh.getAllImplementersOfInterface(declaredClass);
            hasSubtypes = (implementers != null && !implementers.isEmpty());
        }

        if (hasSubtypes) {
            return null;   // polymorphic → cannot safely devirtualize
        }

        // No subtypes → monomorphic, resolve to the declared method
        try {
            SootMethod target = declaredClass.getMethod(subSig);
            if (!target.isAbstract()) {
                return target;
            }
        } catch (RuntimeException e) {
            // method not found in declared class
        }

        // Fallback: try normal virtual resolution on declared type
        return resolveVirtual(declaredClass, subSig);
    }

    // public boolean isUserClass(SootClass cls) {
    //     if (cls.isPhantomClass()) return false;
    //     if (analyzeLibraries) return true;
    //     String n = cls.getName();
    //     return cls.isApplicationClass() &&
    //            !n.startsWith("java.") && !n.startsWith("javax.") &&
    //            !n.startsWith("sun.") && !n.startsWith("com.sun.") &&
    //            !n.startsWith("jdk.") && !n.startsWith("org.xml.") &&
    //            !n.startsWith("org.w3c.");
    // }

    public boolean isUserClass(SootClass cls) {
        if (cls.isPhantomClass()) return false;

        String name = cls.getName();

        // Exclude known problematic internal packages
    //    if (name.startsWith("sun.util.cldr") ||
    //         name.startsWith("sun.util.locale.provider") ||
    //         name.startsWith("com.sun.imageio.plugins.jpeg") ||
    //         name.contains("CLDRLocaleProviderAdapter") ||
    //         name.contains("LocaleDataMetaInfo")) {
    //         return false;
    //     }

              if (name.startsWith("sun")){
                return false;
             }

        if (analyzeLibraries) return true;

        // Normal user code only
        return cls.isApplicationClass() &&
                   !name.startsWith("java.") && !name.startsWith("javax.") &&
                   !name.startsWith("sun.") && !name.startsWith("com.sun.") &&
                   !name.startsWith("jdk.") && !name.startsWith("org.xml.") &&
                   !name.startsWith("org.w3c.");
        }

    // public boolean isLibraryClass(SootClass cls) {
    //     if (cls.isPhantomClass()) return false; // phantom = can't resolve at all
    //     String n = cls.getName();
    //     return n.startsWith("java.")   || n.startsWith("javax.")
    //         || n.startsWith("sun.")    || n.startsWith("com.sun.")
    //         || n.startsWith("jdk.")    || n.startsWith("org.xml.")
    //         || n.startsWith("org.w3c.");    
    // }

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        // System.out.println("[Analysis] Starting 1Type1Obj + HeapCloning...");
        System.out.println("[Analysis] Starting 1Type1Obj + HeapCloning");
        analyzeLibraries = Main.analyzeLibraries || Main1.analyzeLibraries;
        useCHAForUnknown = Main.useCHAForUnknown || Main1.useCHAForUnknown;
        useInline        = Main.useInline && Main1.useInline;
        if(analyzeLibraries){
            System.out.println("Libraries will be analyzed");
        }
        else{
             System.out.println("Common libraries will not be analyzed");

        }
        if(useCHAForUnknown){
            System.out.println("No-pts-to will be resolved using CHA");
        }

        if(useInline){
             System.out.println("Inlining enabled");
        }
        else{
            System.out.println("Inlining disabled");
        }

        analyze();
        System.out.println("[Analysis] Done.");

        if(useInline){
            InlineCheck checker = new InlineCheck(this);
            checker.run();
        }
    }

    // Per-(method, context) fact tracking - prevents useless re-analysis
    private boolean hasNewFactsFor(SootMethod m, Context ctx) {
        String prefix = m.getSignature() + "|" + ctx + "|";
        int relevant = 0;

        for (String key : localPT.keySet()) {
            if (key.startsWith(prefix)) {
                relevant += localPT.get(key).size();
            }
        }

        String retKey = makeReturnKey(m, ctx);
        Set<HeapContext> rets = returnPT.get(retKey);
        if (rets != null) relevant += rets.size();

        int currentHash = relevant;
        String vkey = visitKey(m, ctx);
        Integer last = perContextFactHash.get(vkey);

        if (last != null && last == currentHash) {
            return false;
        }
        perContextFactHash.put(vkey, currentHash);
        return true;
    }

    // Safe enqueue with strong caps
    private void safeEnqueue(SootMethod m, Context c) {
        if (m == null || c == null) return;

        // Global total contexts cap
        long totalCtx = methodContexts.values().stream().mapToLong(Set::size).sum();
        if (totalCtx > MAX_TOTAL_CONTEXTS) {
            if (cappedMethods.add(m)) {
                System.out.println("[GLOBAL-CAP] Total contexts exceeded " + MAX_TOTAL_CONTEXTS 
                    + " → capping " + LineUtil.methodLabel(m));
            }
            return;
        }

        Set<Context> ctxs = methodContexts.computeIfAbsent(m, k -> new HashSet<>());

        if (!ctxs.contains(c)) {
            if (ctxs.size() >= MAX_CONTEXTS_PER_METHOD) {
                if (cappedMethods.add(m)) {
                    System.out.println("[METHOD-CAP] " + ctxs.size() 
                        + " contexts reached for " + LineUtil.methodLabel(m));
                }
                return;
            }
            ctxs.add(c);
        }

        String pk = visitKey(m, c);
        if (pendingInWorklist.contains(pk)) return;

        if (hasNewFactsFor(m, c)) {
            pendingInWorklist.add(pk);
            worklist.add(new WorklistEntry(m, c));
        }
    }
    
    // ----------------------------------------------------------
    // Main driver
    // ----------------------------------------------------------
    // private boolean ensureBody(SootMethod m) {
    //     if (m == null) return false;
    //     if (m.hasActiveBody()) return true;

    //     try {
    //         m.retrieveActiveBody();   // This is the key call
    //         return true;
    //     } catch (RuntimeException e) {
    //         // Abstract, native, phantom, or no body available
    //         return false;
    //     }
    // }

    private boolean ensureBody(SootMethod m) {
        if (m == null) return false;

        SootClass declClass = m.getDeclaringClass();
        String className = declClass.getName();

        // 1. Skip known problematic / internal packages silently
        if (className.startsWith("sun")) {
            return false;
        }

        // 2. Skip default methods in interfaces silently
        if (declClass.isInterface() && m.isAbstract() && !m.isStatic()) {
            return false;
        }

        // 3. Skip native methods silently
        if (m.isNative()) {
            return false;
        }

        if (m.hasActiveBody()) {
            return true;
        }

        try {
            m.retrieveActiveBody();
            return true;
        } catch (RuntimeException e) {
            // Only print warning for non-internal classes to reduce spam
            if (!className.startsWith("com.sun.") && 
                !className.startsWith("sun.")){
                
                // System.out.println("[WARN] Failed to retrieve body for " + m.getSignature());
            }
            return false;
        }
    }

    private void debugNoPtsToReasons() {
        System.out.println("\n=== DEBUG: Reasons for NO PTS-TO (Unknown calls) ===");
        int totalUnknown = 0;
        int noHCAtAll = 0;           // rcvHCs == null || empty
        int receiverNeverAssigned = 0;
        int onlyFromParamsOrThis = 0;
        int fromFields = 0;
        int fromReturns = 0;
        int fromLibrary = 0;
        int declaredTypeOnly = 0;

        for (SootClass cls : Scene.v().getApplicationClasses()) {
            if (cls.isPhantomClass()) continue;
            for (SootMethod method : new ArrayList<>(cls.getMethods())) {
                if (!method.hasActiveBody()) continue;

                Set<Context> ctxs = methodContexts.getOrDefault(method, Collections.singleton(Context.EMPTY));

                for (Unit u : method.getActiveBody().getUnits()) {
                    Stmt stmt = (Stmt) u;
                    if (!isDispatchCall(stmt)) continue;

                    SootClass calleeClass = stmt.getInvokeExpr().getMethodRef().getDeclaringClass();
                    if (!isUserClass(calleeClass)) continue;   // or your filter

                    Local receiver = getReceiver(stmt);
                    String kind = (stmt.getInvokeExpr() instanceof InterfaceInvokeExpr) ? "interface" : "virtual";

                    boolean hasAnyPT = false;

                    for (Context ctx : ctxs) {
                        Set<HeapContext> hcs = getLocalHCs(method, ctx, receiver);
                        if (hcs != null && !hcs.isEmpty()) {
                            hasAnyPT = true;
                            break;
                        }
                    }

                    if (!hasAnyPT) {
                        totalUnknown++;

                        // --- Collect reasons ---
                        if (totalUnknown <= 20) {   // Print details for first 20 only (to avoid spam)
                            System.out.println("\n[UNKNOWN #" + totalUnknown + "] " + kind + " call");
                            System.out.println("   Method: " + LineUtil.methodLabel(method));
                            System.out.println("   Stmt  : " + stmt + "   (line ~" + LineUtil.lineOf(stmt) + ")");
                            System.out.println("   Receiver local: " + receiver.getName() + " : " + receiver.getType());
                        }

                        // Check if this local was ever assigned anything in this method+ctx
                        boolean everAssigned = false;
                        // You can improve this later by tracking defs, but for now we use a simple heuristic

                        noHCAtAll++;

                        // Quick heuristic: check if receiver is 'this' or a parameter
                        if (receiver.getName().equals("this") || 
                            receiver.getName().startsWith("r") && receiver.getName().length() <= 3) {  // rough param guess
                            onlyFromParamsOrThis++;
                        }

                        // More precise later: check if it comes from field load or return
                    }
                }
            }
        }

        System.out.println("\n=== NO PTS-TO SUMMARY ===");
        System.out.println("Total Unknown calls          : " + totalUnknown);
        System.out.println("Heap objects created so far  : " + heapObjectsCreated);
        System.out.println("Unique HCs                   : " + uniqueHCs.size());
        System.out.println("Methods with contexts        : " + methodContexts.size());
        System.out.println("Calls with NO HC at all      : " + noHCAtAll);
        // You can add more counters as you refine
    }
    public void analyze() {
        analysisStartTime = System.currentTimeMillis();   // ← Start timing
        System.out.println("[Analysis] Building maps...");
        buildStmtMap();

        // ==================== LIGHTER + SAFER SEEDING ====================
        System.out.println("[Seeding] Creating HeapContext for ALL new expressions...");

        for (SootClass cls : new ArrayList<>(Scene.v().getApplicationClasses())) {
            if (!isUserClass(cls)) continue;

            for (SootMethod m : new ArrayList<>(cls.getMethods())) {
                if (!ensureBody(m)) continue;

                boolean methodAdded = false;

                for (Unit u : m.getActiveBody().getUnits()) {
                    Stmt s = (Stmt) u;
                    if (!isNewExpr(s)) continue;

                    AssignStmt a = (AssignStmt) s;
                    if (!(a.getLeftOp() instanceof Local)) continue;

                    NewExpr ne = (NewExpr) a.getRightOp();
                    SootClass type = ne.getBaseType().getSootClass();
                    if (type.isPhantomClass()) continue;

                    HeapContext hc = record(s, Context.EMPTY, type);
                    if (uniqueHCs.add(hc)) {
                        heapObjectsCreated++;
                    }

                    addLocalHC(m, Context.EMPTY, (Local) a.getLeftOp(), hc);

                    // Add method to worklist ONLY ONCE per method
                    if (!methodAdded) {
                        safeEnqueue(m, Context.EMPTY);
                        methodAdded = true;
                    }
                }
            }
        }

        SootMethod main = Scene.v().getMainMethod();
        if(main != null) safeEnqueue(main, Context.EMPTY);

        System.out.println("[Seeding] Done.");
        System.out.println("   Heap objects created : " + heapObjectsCreated);
        System.out.println("   Methods added to worklist : " + worklist.size());

        // for (Unit u : main.getActiveBody().getUnits()) {
        //     Stmt s = (Stmt) u;
        //     if (!isNewExpr(s)) continue;
        //     AssignStmt a    = (AssignStmt) s;
        //     if (!(a.getLeftOp() instanceof Local)) continue;
        //     NewExpr    ne   = (NewExpr) a.getRightOp();
        //     SootClass  type = ne.getBaseType().getSootClass();
        //     if (!isUserClass(type)) continue;

        //     // create HC for this allocation
        //     HeapContext hc = record(s, Context.EMPTY, type);
        //     uniqueHCs.add(hc);
        //     heapObjectsCreated++;
        //     addLocalHC(main, Context.EMPTY, (Local) a.getLeftOp(), hc);
        // }

        // while (!worklist.isEmpty()) {
        //     WorklistEntry e      = worklist.poll();
        //     SootMethod    method = e.method;
        //     Context       ctx    = e.ctx;

        //     String  key     = visitKey(method, ctx);
        //     int     factNow = localPT.size() + fieldPT.size() + returnPT.size();
        //     Integer last    = lastFactCount.get(key);

        //     if (last != null && last == factNow) {
        //         worklistSkips++;
        //         continue;
        //     }
        //     lastFactCount.put(key, factNow);

        //     methodContexts.computeIfAbsent(method, k -> new HashSet<>()).add(ctx);
        //     String countKey = method.getName() + "|" + ctx;
        //     methodAnalyzeCounts.merge(countKey, 1, Integer::sum);
        //     analyzeMethodCalls++;
        
        //     analyzeMethod(method, ctx);
        // }

        // int methodsWithBody = 0;
        // int totalDispatch = 0;
        // for (SootClass cls : Scene.v().getApplicationClasses()) {
        //     if (cls.isPhantomClass()) continue;
        //     for (SootMethod m : new ArrayList<>(cls.getMethods())) {
        //         if (m.hasActiveBody()) {
        //             methodsWithBody++;
        //             for (Unit u : m.getActiveBody().getUnits()) {
        //                 if (((Stmt)u).containsInvokeExpr() && isDispatchCall((Stmt)u))
        //                     totalDispatch++;
        //             }
        //         }
        //     }
        // }

        // ======================== WORKLIST PROCESSING ========================
            System.out.println("[Worklist] Starting main analysis loop with " 
                            + worklist.size() + " entries...");

            int processed = 0;
            long startTime = System.currentTimeMillis();
            long lastReportTime = startTime;

            while (!worklist.isEmpty()) {
                WorklistEntry e = worklist.poll();
                String pk = visitKey(e.method, e.ctx);
                pendingInWorklist.remove(pk);

                if (cappedMethods.contains(e.method)) continue;

                methodContexts.computeIfAbsent(e.method, k -> new HashSet<>()).add(e.ctx);

                analyzeMethod(e.method, e.ctx);

                processed++;

                long now = System.currentTimeMillis();
                if ((now - lastReportTime) >= 30000 || processed % 2000 == 0) {
                    long totalCtx = methodContexts.values().stream().mapToLong(Set::size).sum();
                    double elapsed = (now - startTime) / 1000.0;
                    System.out.printf("[Progress] Processed:%6d | Worklist:%5d | TotalCtx:%6d | Heap:%d | Capped:%d | Time:%.1fs%n",
                            processed, worklist.size(), totalCtx, heapObjectsCreated, cappedMethods.size(), elapsed);
                    lastReportTime = now;
                }

                if (worklist.size() > 100000) {
                    System.out.println("[WARNING] Worklist too large - forcing early termination.");
                    break;
                }
            }

            // ======================== POST-WORKLIST DEBUG PRINTS ========================
            System.out.println("\n=== Post-Analysis Debug ===");

            int methodsWithBody = 0;
            int totalDispatch = 0;

            for (SootClass cls : new ArrayList<>(Scene.v().getApplicationClasses())) {
                if (cls.isPhantomClass()) continue;

                for (SootMethod m : new ArrayList<>(cls.getMethods())) {
                    if (m.hasActiveBody()) {
                        methodsWithBody++;

                        for (Unit u : m.getActiveBody().getUnits()) {
                            Stmt s = (Stmt) u;
                            if (s.containsInvokeExpr() && isDispatchCall(s)) {
                                totalDispatch++;
                            }
                        }
                    }
                }
            }

            System.out.println("Methods with body after analysis : " + methodsWithBody);
            System.out.println("Dispatch calls discovered        : " + totalDispatch);

            // ======================== FINAL SUMMARY ========================
            long totalTimeMs = System.currentTimeMillis() - startTime;
            double totalSec = totalTimeMs / 1000.0;

            System.out.println("\n=== Analysis Finished ===");
            System.out.println("Total time taken          : " + totalTimeMs + " ms (" 
                            + String.format("%.2f", totalSec) + " seconds)");
            System.out.println("Methods processed         : " + processed);
            System.out.println("Methods with contexts     : " + methodContexts.size());
            System.out.println("Heap objects created      : " + heapObjectsCreated);

                
                
        // System.out.println(
        //     "\n[DBG] === Dispatch calls + 1Type1Obj context breakdown ==="
        // );
        for (SootClass cls : Scene.v().getApplicationClasses()) {
            if (!isUserClass(cls)) continue;
            for (SootMethod m : new ArrayList<>(cls.getMethods())) {
                if (!m.hasActiveBody()) continue;
                for (Unit u : m.getActiveBody().getUnits()) {
                    Stmt s = (Stmt) u;
                    if (!isDispatchCall(s)) continue;
                    SootClass calleeClass =
                        s.getInvokeExpr().getMethodRef().getDeclaringClass();
                    if (!isUserClass(calleeClass)) continue;

                    Local  receiver = getReceiver(s);
                    String kind     = s.getInvokeExpr() instanceof InterfaceInvokeExpr
                                    ? "interface" : "virtual";
                    String subSig   = s.getInvokeExpr().getMethodRef()
                                       .getSubSignature().toString();

                    // System.out.println("\n  [" + kind + "] " + s);
                    // System.out.println("    in method: " + LineUtil.methodLabel(m));
                    // System.out.println("    receiver:  " + receiver.getName()
                                    // + " in " + LineUtil.methodLabel(m));

                    Set<Context> ctxs = methodContexts.getOrDefault(
                        m, Collections.singleton(Context.EMPTY));

                    for (Context callerCtx : ctxs) {
                        // System.out.println("    callerCtx: " + callerCtx);
                        Set<HeapContext> hcs = getLocalHCs(m, callerCtx, receiver);

                        if (hcs == null || hcs.isEmpty()) {
                            // System.out.println("      -> NO pts-to info");
                            continue;
                        }

                        for (HeapContext hc : hcs) {
                            // System.out.println("      receiver HC: " + hc);

                            // 1-Type and 1-Obj displayed with new abstraction
                            int l1 = LineUtil.lineOf(hc.allocSite);
                            int l2 = LineUtil.lineOf(hc.callerAllocSite);
                            // System.out.println(
                            //     "        1-Type (allocType)      = "
                            //     + (hc.allocType == null ? "null"
                            //        : "T:" + hc.allocType.getShortName())
                            //     + "  ← used for resolveVirtual()"
                            // );
                            // System.out.println(
                            //     "        1-Obj  allocSite (ℓ1)   = "
                            //     + (l1 < 0 ? "entry" : "O" + l1)
                            //     + "  ← OBJ part of calleeCtx"
                            // );
                            // System.out.println(
                            //     "        callerAllocSite (ℓ2)    = "
                            //     + (l2 < 0 ? "entry" : "O" + l2)
                            //     + "  ← T(ℓ2) = TYPE part of calleeCtx"
                            // );

                            Context calleeCtx = merge(s, hc, callerCtx);
                            // System.out.println("        calleeCtx (merge result):");
                            // System.out.println(
                            //     "          1-TYPE = T(ℓ2) = "
                            //     + (calleeCtx.type == null
                            //        ? "null (allocated in main/static)"
                            //        : "T:" + calleeCtx.type.getShortName())
                            // );
                            int cl = LineUtil.lineOf(calleeCtx.allocSite);
                            // System.out.println(
                            //     "          1-OBJ  = ℓ1    = "
                            //     + (cl < 0 ? "O?" : "O" + cl)
                            // );

                            if (hc.allocType != null) {
                                SootMethod target = resolveVirtual(hc.allocType, subSig);
                            // System.out.println("        resolved target: "
                            // + (target == null ? "NOT FOUND" : target.getSignature()));


                            }
                        }
                    }
                }
            }
        }

        // System.out.println("\n[DBG] fieldPT contents:");
        // for (Map.Entry<String, Set<HeapContext>> e : fieldPT.entrySet())
        //     System.out.println("  " + e.getKey() + " -> " + e.getValue());

        debugNoPtsToReasons();

        computeResolvedCalls();

        System.out.println("\n=== Analysis Statistics ===");
        System.out.println("Total virtual calls:  " + totalVirtualCalls);
        System.out.println("Resolved (1T1O+HC):   " + resolvedCount);
        System.out.println("Unresolved:           " + (totalVirtualCalls - resolvedCount));
        System.out.println("Resolution rate     : " + (totalVirtualCalls > 0 ? 
            String.format("%.1f%%", 100.0 * resolvedCount / totalVirtualCalls) : "0%"));
        System.out.println("Heap objects        : " + heapObjectsCreated);
        System.out.println("Methods capped      : " + cappedMethods.size());
        printDevirtualizationPlan();
    }


    private void buildStmtMap() {
        for (SootClass cls : Scene.v().getApplicationClasses()) {
            if (!isUserClass(cls)) continue;
            for (SootMethod m : new ArrayList<>(cls.getMethods())) {
                if (!ensureBody(m)) continue;
                if (!m.hasActiveBody()) continue;
                for (Unit u : m.getActiveBody().getUnits()) {
                    stmtToClass.put((Stmt) u, cls);
                    stmtToMethod.put((Stmt) u, m);   // O(1) enclosing method lookup
                }
            }
        }
    }

    private void analyzeMethod(SootMethod method, Context ctx) {
        if (cappedMethods.contains(method)) return;
        if (!ensureBody(method)) return;
        if (!method.hasActiveBody()) return;
        for (Unit unit : method.getActiveBody().getUnits()) {
            Stmt s = (Stmt) unit;
            if      (isNewExpr(s))      handleNewExpr(s,      method, ctx);
            else if (isFieldStore(s))   handleFieldStore(s,   method, ctx);
            else if (isFieldLoad(s))    handleFieldLoad(s,    method, ctx);
            else if (isDispatchCall(s)) handleDispatchCall(s, method, ctx);
            else if (isSpecialCall(s))  handleSpecialCall(s,  method, ctx);
            else if (isStaticCall(s))   handleStaticCall(s,   method, ctx);
            else if (isCast(s))         handleCast(s,         method, ctx);
            else if (isCopy(s))         handleCopy(s,         method, ctx);
            else if (isReturn(s))       handleReturn(s,       method, ctx);
            else if (isStaticFieldStore(s))  handleStaticFieldStore(s, method, ctx); 
            else if (isStaticFieldLoad(s))   handleStaticFieldLoad(s, method, ctx);
        }
    }

    // ----------------------------------------------------------
    // x = new Foo()
    // ----------------------------------------------------------
    private void handleNewExpr(Stmt stmt, SootMethod method, Context ctx) {
        AssignStmt assign  = (AssignStmt) stmt;
        NewExpr    newExpr = (NewExpr)    assign.getRightOp();
        if (!(assign.getLeftOp() instanceof Local)) return;
        Local      lhs     = (Local)      assign.getLeftOp();
        SootClass  type    = newExpr.getBaseType().getSootClass();

        HeapContext hc = record(stmt, ctx, type);
        handleNewExprCalls++;

        if (uniqueHCs.add(hc)) {
            heapObjectsCreated++;
        }
        addLocalHC(method, ctx, lhs, hc);
    }

    // ----------------------------------------------------------
    // y.field = x
    // ----------------------------------------------------------
    private void handleFieldStore(Stmt stmt, SootMethod method, Context ctx) {
        AssignStmt       assign   = (AssignStmt)       stmt;
        InstanceFieldRef fieldRef = (InstanceFieldRef) assign.getLeftOp();
        Local            base     = (Local)            fieldRef.getBase();
        SootField        f        = fieldRef.getField();
        if(f == null) return;

        Value rhs = assign.getRightOp();
        if (!(rhs instanceof Local)) return;

        Set<HeapContext> baseHCs = getLocalHCs(method, ctx, base);
        Set<HeapContext> rhsHCs  = getLocalHCs(method, ctx, (Local) rhs);
        if (baseHCs == null || rhsHCs == null) return;

        for (HeapContext bHC : baseHCs) {
            for (HeapContext rHC : rhsHCs) {
                String           key        = makeFieldKey(f, bHC);
                Set<HeapContext> existing   = fieldPT.get(key);
                int              sizeBefore = sizeOf(existing);

                fieldPT.computeIfAbsent(key, k -> new HashSet<>()).add(rHC);

                int sizeAfter = sizeOf(fieldPT.get(key));
                if (sizeAfter > sizeBefore) {
                    requeueFieldReaders(f, bHC);
                }
            }
        }
    }

    private void requeueFieldReaders(SootField f, HeapContext ownerHC) {
        for (SootClass cls : Scene.v().getApplicationClasses()) {
            if (!isUserClass(cls)) continue;
            for (SootMethod m : new ArrayList<>(cls.getMethods())) {
                if (!m.hasActiveBody()) continue;
                for (Unit u : m.getActiveBody().getUnits()) {
                    Stmt s = (Stmt) u;
                    if (!isFieldLoad(s)) continue;
                    InstanceFieldRef fr = (InstanceFieldRef)
                        ((AssignStmt) s).getRightOp();
                    SootField field = fr.getField();
                    if (field == null || !field.equals(f)) continue;
                    // if (!fr.getField().equals(f)) continue;
                    Set<Context> ctxs = methodContexts.getOrDefault(
                        m, Collections.emptySet());
                    for (Context c : ctxs)
                        safeEnqueue(m, c);
                }
            }
        }
    }

    // ----------------------------------------------------------
    // x = y.field
    // ----------------------------------------------------------
    private void handleFieldLoad(Stmt stmt, SootMethod method, Context ctx) {
        AssignStmt       assign   = (AssignStmt)       stmt;
        InstanceFieldRef fieldRef = (InstanceFieldRef) assign.getRightOp();
        Local            base     = (Local)            fieldRef.getBase();
        if (!(assign.getLeftOp() instanceof Local)) return;
        Local            lhs      = (Local)            assign.getLeftOp();
        SootField        f        = fieldRef.getField();
        if(f == null) return;
        Set<HeapContext> baseHCs  = getLocalHCs(method, ctx, base);
        if (baseHCs == null) return;
        for (HeapContext bHC : new ArrayList<>(baseHCs)) {
            Set<HeapContext> vals = fieldPT.get(makeFieldKey(f, bHC));
            if (vals == null) continue;
            for (HeapContext fhc : vals) addLocalHC(method, ctx, lhs, fhc);
        }
    }

    // ----------------------------------------------------------
    // virtualinvoke OR invokeinterface
    // ----------------------------------------------------------
    // private void handleDispatchCall(Stmt stmt, SootMethod method, Context ctx) {
    //     SootClass calleeClass =
    //         stmt.getInvokeExpr().getMethodRef().getDeclaringClass();
    //     if (!isUserClass(calleeClass)) return;

    //     Local            receiver = getReceiver(stmt);
    //     Set<HeapContext> rcvHCs   = getLocalHCs(method, ctx, receiver);

    //     if (rcvHCs == null || rcvHCs.isEmpty()) {
    //     return;
    // }

    //     String subSig = stmt.getInvokeExpr().getMethodRef()
    //                         .getSubSignature().toString();

    //     for (HeapContext rcvHC : rcvHCs) {
    //         if (rcvHC.allocType == null) continue;
    //         SootMethod target = resolveVirtual(rcvHC.allocType, subSig);
    //         if (target == null || !target.hasActiveBody()) continue;

    //         Context calleeCtx = merge(stmt, rcvHC, ctx);

    //         boolean calleeGotNewFacts = false;
    //         for (Unit u : target.getActiveBody().getUnits()) {
    //             if (!(u instanceof IdentityStmt)) continue;
    //             IdentityStmt id = (IdentityStmt) u;
    //             if (!(id.getRightOp() instanceof ThisRef)) continue;
    //             Local thisLocal = (Local) id.getLeftOp();
    //             int before = sizeOf(getLocalHCs(target, calleeCtx, thisLocal));
    //             addLocalHC(target, calleeCtx, thisLocal, rcvHC);
    //             int after  = sizeOf(getLocalHCs(target, calleeCtx, thisLocal));
    //             if (after > before) calleeGotNewFacts = true;
    //             break;
    //         }

    //         calleeGotNewFacts |= propagateArgsWithTracking(
    //             stmt.getInvokeExpr().getArgs(),
    //             target, calleeCtx, method, ctx
    //         );

    //         boolean callerGotNewFacts = false;
    //         if (stmt instanceof AssignStmt && ((AssignStmt) stmt).getLeftOp() instanceof Local) {
    //             Local            lhs    = (Local) ((AssignStmt) stmt).getLeftOp();
    //             Set<HeapContext> retHCs = returnPT.get(makeReturnKey(target, calleeCtx));
    //             if (retHCs != null && !retHCs.isEmpty()) {
    //                 int before = sizeOf(getLocalHCs(method, ctx, lhs));
    //                 for (HeapContext r : retHCs) addLocalHC(method, ctx, lhs, r);
    //                 int after  = sizeOf(getLocalHCs(method, ctx, lhs));
    //                 if (after > before) callerGotNewFacts = true;
    //             }
    //         }

    //         String calleeKey = visitKey(target, calleeCtx);
    //         if (calleeGotNewFacts || !lastFactCount.containsKey(calleeKey)) {
    //             worklist.add(new WorklistEntry(target, calleeCtx));
    //             calleeRequeues++;
    //         }
    //         if (callerGotNewFacts) {
    //             worklist.add(new WorklistEntry(method, ctx));
    //             callerRequeues++;
    //         }
    //     }
    // }

    private void handleDispatchCall(Stmt stmt, SootMethod method, Context ctx) {
    SootClass calleeClass = stmt.getInvokeExpr().getMethodRef().getDeclaringClass();
    if (!isUserClass(calleeClass)) return;

    Local receiver = getReceiver(stmt);

    // Get contexts safely
    Set<Context> callerCtxs = methodContexts.getOrDefault(
            method, Collections.singleton(Context.EMPTY));

    // Defensive copy to prevent ConcurrentModificationException
    List<Context> ctxList = new ArrayList<>(callerCtxs);

    for (Context callerCtx : ctxList) {
        Set<HeapContext> rcvHCs = getLocalHCs(method, callerCtx, receiver);
        if (rcvHCs == null || rcvHCs.isEmpty()) {
            continue;
        }

        // Defensive copy of rcvHCs as well
        List<HeapContext> hcList = new ArrayList<>(rcvHCs);

        String subSig = stmt.getInvokeExpr().getMethodRef()
                            .getSubSignature().toString();

        for (HeapContext rcvHC : hcList) {
            if (rcvHC.allocType == null) continue;

            SootMethod target = resolveVirtual(rcvHC.allocType, subSig);
            if (target == null || !target.hasActiveBody()) continue;

            Context calleeCtx = merge(stmt, rcvHC, callerCtx);

            boolean calleeGotNewFacts = false;

            // Pass 'this' reference
            for (Unit u : target.getActiveBody().getUnits()) {
                if (!(u instanceof IdentityStmt)) continue;
                IdentityStmt id = (IdentityStmt) u;
                if (!(id.getRightOp() instanceof ThisRef)) continue;

                Local thisLocal = (Local) id.getLeftOp();
                int before = sizeOf(getLocalHCs(target, calleeCtx, thisLocal));
                addLocalHC(target, calleeCtx, thisLocal, rcvHC);
                int after = sizeOf(getLocalHCs(target, calleeCtx, thisLocal));
                if (after > before) calleeGotNewFacts = true;
                break;
            }

            // Propagate arguments
            calleeGotNewFacts |= propagateArgsWithTracking(
                stmt.getInvokeExpr().getArgs(),
                target, calleeCtx, method, callerCtx   // note: use callerCtx here
            );

            // Handle return value
            boolean callerGotNewFacts = false;
            if (stmt instanceof AssignStmt && ((AssignStmt) stmt).getLeftOp() instanceof Local) {
                Local lhs = (Local) ((AssignStmt) stmt).getLeftOp();
                Set<HeapContext> retHCs = returnPT.get(makeReturnKey(target, calleeCtx));
                if (retHCs != null && !retHCs.isEmpty()) {
                    int before = sizeOf(getLocalHCs(method, callerCtx, lhs));
                    for (HeapContext r : new ArrayList<>(retHCs)) {   // safe
                        addLocalHC(method, callerCtx, lhs, r);
                    }
                    int after = sizeOf(getLocalHCs(method, callerCtx, lhs));
                    if (after > before) callerGotNewFacts = true;
                }
            }

            // Requeue if needed
            String calleeKey = visitKey(target, calleeCtx);
            if (calleeGotNewFacts || !lastFactCount.containsKey(calleeKey)) {
                safeEnqueue(target, calleeCtx);
                calleeRequeues++;
            }

            if (callerGotNewFacts) {
                safeEnqueue(method, callerCtx);
                callerRequeues++;
            }
        }
    }
}

    // ----------------------------------------------------------
    // specialinvoke (constructors, super calls)
    // ----------------------------------------------------------
    private void handleSpecialCall(Stmt stmt, SootMethod method, Context ctx) {
        SootClass calleeClass =
            stmt.getInvokeExpr().getMethodRef().getDeclaringClass();
        if (!isUserClass(calleeClass)) return;

        SpecialInvokeExpr sie    = (SpecialInvokeExpr) stmt.getInvokeExpr();
        SootMethod        target = sie.getMethod();
        if (!target.hasActiveBody()) return;

        Local            rcvLocal = (Local) sie.getBase();
        Set<HeapContext> rcvHCs   = getLocalHCs(method, ctx, rcvLocal);

        if (rcvHCs == null || rcvHCs.isEmpty()) {
            boolean newFacts = propagateArgsWithTracking(
                sie.getArgs(), target, ctx, method, ctx
            );
            String calleeKey = visitKey(target, ctx);
            if (newFacts || !lastFactCount.containsKey(calleeKey)) {
                safeEnqueue(target, ctx);
                calleeRequeues++;
            }
            return;
        }

        for (HeapContext rcvHC : rcvHCs) {
            Context calleeCtx = merge(stmt, rcvHC, ctx);

            boolean calleeGotNewFacts = false;
            for (Unit u : target.getActiveBody().getUnits()) {
                if (!(u instanceof IdentityStmt)) continue;
                IdentityStmt id = (IdentityStmt) u;
                if (!(id.getRightOp() instanceof ThisRef)) continue;
                Local thisLocal = (Local) id.getLeftOp();
                int before = sizeOf(getLocalHCs(target, calleeCtx, thisLocal));
                addLocalHC(target, calleeCtx, thisLocal, rcvHC);
                int after  = sizeOf(getLocalHCs(target, calleeCtx, thisLocal));                
                if (after > before) {
                    calleeGotNewFacts = true;
                    // if(target.isConstructor()) 
                    //     worklist.add(new WorklistEntry(target, calleeCtx));
                }
                break;
            }

            calleeGotNewFacts |= propagateArgsWithTracking(
                sie.getArgs(), target, calleeCtx, method, ctx
            );

            boolean callerGotNewFacts = false;
            if (stmt instanceof AssignStmt && ((AssignStmt) stmt).getLeftOp() instanceof Local) {
                Local            lhs    = (Local) ((AssignStmt) stmt).getLeftOp();
                Set<HeapContext> retHCs = returnPT.get(makeReturnKey(target, calleeCtx));
                if (retHCs != null && !retHCs.isEmpty()) {
                    int before = sizeOf(getLocalHCs(method, ctx, lhs));
                    for (HeapContext r : retHCs) addLocalHC(method, ctx, lhs, r);
                    int after  = sizeOf(getLocalHCs(method, ctx, lhs));
                    if (after > before) callerGotNewFacts = true;
                }
            }

            String calleeKey = visitKey(target, calleeCtx);
            if (calleeGotNewFacts || !lastFactCount.containsKey(calleeKey)) {
                safeEnqueue(target, calleeCtx);
                calleeRequeues++;
            }
            if (callerGotNewFacts) {
                safeEnqueue(method, ctx);
                callerRequeues++;
            }
        }
    }

    // ----------------------------------------------------------
    // staticinvoke
    // ----------------------------------------------------------
  
    private void handleStaticCall(Stmt stmt, SootMethod method, Context ctx) {
        SootClass calleeClass =
            stmt.getInvokeExpr().getMethodRef().getDeclaringClass();
        if (!isUserClass(calleeClass)) return;

        StaticInvokeExpr sie    = (StaticInvokeExpr) stmt.getInvokeExpr();
        SootMethod       target = sie.getMethod();
        if (!target.hasActiveBody()) return;

        // find first object-typed arg HC set
        Set<HeapContext> firstArgHCs = null;
        for (Value arg : sie.getArgs()) {
            if (!(arg instanceof Local)) continue;
            if (((Local) arg).getType() instanceof soot.PrimType) continue;
            firstArgHCs = getLocalHCs(method, ctx, (Local) arg);
            if (firstArgHCs != null && !firstArgHCs.isEmpty()) break;
        }

        // collect contexts to analyse callee under
        Set<Context> calleeCtxSet = new HashSet<>();

        if (firstArgHCs == null || firstArgHCs.isEmpty()) {
            // no object arg — use caller ctx directly
            calleeCtxSet.add(ctx);
        } else {
            // one calleeCtx per distinct HC — separates different callers
            for (HeapContext argHC : firstArgHCs) {
                calleeCtxSet.add(merge(stmt, argHC, ctx));
            }
        }

        for (Context calleeCtx : calleeCtxSet) {
            boolean calleeGotNewFacts = propagateArgsWithTracking(
                sie.getArgs(), target, calleeCtx, method, ctx
            );

            boolean callerGotNewFacts = false;
            if (stmt instanceof AssignStmt && ((AssignStmt) stmt).getLeftOp() instanceof Local) {
                Local            lhs    = (Local) ((AssignStmt) stmt).getLeftOp();
                Set<HeapContext> retHCs = returnPT.get(makeReturnKey(target, calleeCtx));
                if (retHCs != null && !retHCs.isEmpty()) {
                    int before = sizeOf(getLocalHCs(method, ctx, lhs));
                    for (HeapContext r : retHCs) addLocalHC(method, ctx, lhs, r);
                    int after  = sizeOf(getLocalHCs(method, ctx, lhs));
                    if (after > before) callerGotNewFacts = true;
                }
            }

            String calleeKey = visitKey(target, calleeCtx);
            if (calleeGotNewFacts || !lastFactCount.containsKey(calleeKey)) {
                safeEnqueue(target, calleeCtx);
                calleeRequeues++;
            }
            if (callerGotNewFacts) {
                safeEnqueue(method, ctx);
                callerRequeues++;
            }
        }
    }

    // ----------------------------------------------------------
    // x = (Cast) y
    // ----------------------------------------------------------
    private void handleCast(Stmt stmt, SootMethod method, Context ctx) {
        AssignStmt assign = (AssignStmt) stmt;
        CastExpr   cast   = (CastExpr)   assign.getRightOp();
        if (!(cast.getOp() instanceof Local)) return;
        if (!(assign.getLeftOp()  instanceof Local)) return; 
        Set<HeapContext> hcs = getLocalHCs(method, ctx, (Local) cast.getOp());
        if (hcs == null) return;
        for (HeapContext hc : hcs)
            addLocalHC(method, ctx, (Local) assign.getLeftOp(), hc);
    }

    // ----------------------------------------------------------
    // x = y (copy)
    // ----------------------------------------------------------
    private void handleCopy(Stmt stmt, SootMethod method, Context ctx) {
        AssignStmt assign = (AssignStmt) stmt;
        if (!(assign.getRightOp() instanceof Local)) return;
        if (!(assign.getLeftOp()  instanceof Local)) return; 
        Set<HeapContext> hcs = getLocalHCs(method, ctx, (Local) assign.getRightOp());
        if (hcs == null) return;
        for (HeapContext hc : hcs)
            addLocalHC(method, ctx, (Local) assign.getLeftOp(), hc);
    }

    // ----------------------------------------------------------
    //  return x
    // ----------------------------------------------------------
    private void handleReturn(Stmt stmt, SootMethod method, Context ctx) {
        ReturnStmt ret = (ReturnStmt) stmt;
        if (!(ret.getOp() instanceof Local)) return;

        Set<HeapContext> hcs = getLocalHCs(method, ctx, (Local) ret.getOp());
        if (hcs == null) return;

        String key        = makeReturnKey(method, ctx);
        int    sizeBefore = sizeOf(returnPT.get(key));

        for (HeapContext hc : hcs)
            returnPT.computeIfAbsent(key, k -> new HashSet<>()).add(hc);

        int sizeAfter = sizeOf(returnPT.get(key));
        if (sizeAfter > sizeBefore) {
            requeueCallers(method, ctx);
        }
    }

    private void handleStaticFieldStore(Stmt stmt, SootMethod method, Context ctx) {
    StaticFieldRef sfr = (StaticFieldRef) ((AssignStmt) stmt).getLeftOp();
    Local          rhs = (Local)          ((AssignStmt) stmt).getRightOp();
    Set<HeapContext> hcs = getLocalHCs(method, ctx, rhs);
    if (hcs == null) return;
    if(sfr.getField() == null) return;
    String key = sfr.getField().getSignature();
    for (HeapContext hc : hcs)
        staticPT.computeIfAbsent(key, k -> new HashSet<>()).add(hc);
}

private void handleStaticFieldLoad(Stmt stmt, SootMethod method, Context ctx) {
    StaticFieldRef sfr = (StaticFieldRef) ((AssignStmt) stmt).getRightOp();
    Local          lhs = (Local)          ((AssignStmt) stmt).getLeftOp();
    if(sfr.getField() == null) return;
    String key = sfr.getField().getSignature();
    Set<HeapContext> hcs = staticPT.get(key);
    if (hcs == null) return;
    for (HeapContext hc : hcs)
        addLocalHC(method, ctx, lhs, hc);
}

    // private void requeueCallers(SootMethod callee, Context calleeCtx) {
    //     for (SootClass cls : Scene.v().getApplicationClasses()) {
    //         if (!isUserClass(cls)) continue;
    //         for (SootMethod m : cls.getMethods()) {
    //             if (!m.hasActiveBody()) continue;
    //             for (Unit u : m.getActiveBody().getUnits()) {
    //                 Stmt s = (Stmt) u;
    //                 if (!(s instanceof AssignStmt)) continue;
    //                 if (!s.containsInvokeExpr()) continue;
    //                 SootMethod target = null;
    //                 try {
    //                     target = s.getInvokeExpr().getMethod();
    //                 } catch (Exception e) { continue; }
    //                 if (!callee.equals(target)) continue;
    //                 Set<Context> ctxs = methodContexts.getOrDefault(
    //                     m, Collections.emptySet());
    //                 for (Context c : ctxs)
    //                     worklist.add(new WorklistEntry(m, c));
    //             }
    //         }
    //     }
    // }
    private void requeueCallers(SootMethod callee, Context calleeCtx) {
        for (SootClass cls : Scene.v().getApplicationClasses()) {
            if (!isUserClass(cls)) continue;
            for (SootMethod m : new ArrayList<>(cls.getMethods())) {
                if (!m.hasActiveBody()) continue;
                for (Unit u : m.getActiveBody().getUnits()) {
                    Stmt s = (Stmt) u;
                    if (!s.containsInvokeExpr()) continue;

                    // FIX: check ALL invoke types, not just AssignStmt
                    SootMethod target = null;
                    try {
                        target = s.getInvokeExpr().getMethod();
                    } catch (Exception e) { continue; }
                    if (!callee.equals(target)) continue;

                    // Set<Context> ctxs = methodContexts.getOrDefault(
                    //     m, Collections.singleton(Context.EMPTY));
                    Set<Context> ctxs = methodContexts.getOrDefault(
                        m, Collections.emptySet());
                    for (Context c : ctxs)
                        safeEnqueue(m, c);
                }
            }
        }
    }


    private HeapContext record(Stmt allocSite, Context callerCtx, SootClass allocType) {
        Stmt callerAlloc = (callerCtx == null || callerCtx == Context.EMPTY)
            ? null : callerCtx.allocSite;
        return new HeapContext(allocSite, callerAlloc, allocType);
    }

    private Context merge(Stmt callSite, HeapContext rcvHC, Context callerCtx) {
        SootClass type = (rcvHC.callerAllocSite != null)
            ? T(rcvHC.callerAllocSite)  
            : T(rcvHC.allocSite);       
        return new Context(type, rcvHC.allocSite);
    }

   
    private SootClass T(Stmt allocSite) {
        return allocSite == null ? null : stmtToClass.get(allocSite);
    }

    // private void computeResolvedCalls() {
    //     resolvedCalls.clear();
    //     perHCResolved.clear();
    //     resolvedCount = totalVirtualCalls = 0;
    //     // Set<Integer> counted = new HashSet<>();
    //     Set<Stmt> counted = Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    //     for (SootClass cls : Scene.v().getApplicationClasses()) {
    //         if (!isUserClass(cls)) continue;
    //         for (SootMethod method : new ArrayList<>(cls.getMethods())) {
    //             if (!method.hasActiveBody()) continue;

    //             Set<Context> contexts = methodContexts.getOrDefault(
    //                 method, Collections.singleton(Context.EMPTY));

    //             for (Unit u : method.getActiveBody().getUnits()) {
    //                 Stmt stmt = (Stmt) u;
    //                 if (!isDispatchCall(stmt)) continue;
    //                 SootClass calleeClass =
    //                     stmt.getInvokeExpr().getMethodRef().getDeclaringClass();
    //                 if (!isUserClass(calleeClass)) continue;

    //                 // if (counted.add(System.identityHashCode(stmt)))
    //                 //     totalVirtualCalls++;
    //                 if (counted.add(stmt))
    //                     totalVirtualCalls++;

    //                 Local  receiver = getReceiver(stmt);
    //                 String subSig   = stmt.getInvokeExpr().getMethodRef()
    //                                     .getSubSignature().toString();

    //                 // collect all targets globally
    //                 Set<SootMethod> globalTargets = new HashSet<>();

    //                 for (Context ctx : contexts) {
    //                     Set<HeapContext> rcvHCs = getLocalHCs(method, ctx, receiver);
    //                     if (rcvHCs == null || rcvHCs.isEmpty()) continue;

    //                     Set<SootMethod> ctxTargets    = new HashSet<>();
    //                     boolean         incomplete     = false;

    //                     for (HeapContext rcvHC : rcvHCs) {
    //                         if (rcvHC.allocType == null) { incomplete = true; continue; }
    //                         SootMethod t = resolveVirtual(rcvHC.allocType, subSig);
    //                         if (t == null) { incomplete = true; continue; }

    //                         ctxTargets.add(t);
    //                         globalTargets.add(t);

    //                         // store per (stmt, ctx, rcvHC) — finest granularity
    //                         perHCResolved
    //                             .computeIfAbsent(stmt,  k -> new HashMap<>())
    //                             .computeIfAbsent(ctx,   k -> new HashMap<>())
    //                             .put(rcvHC, t);
    //                     }

    //                     // store in resolvedCalls only if ctx-level mono
    //                     if (!incomplete && ctxTargets.size() == 1) {
    //                         resolvedCalls
    //                             .computeIfAbsent(stmt, k -> new HashMap<>())
    //                             .put(ctx, ctxTargets.iterator().next());
    //                     }
    //                 }
    //                 if (resolvedCalls.containsKey(stmt)) resolvedCount++;
    //             }
    //         }
    //     }
    // }

    private void computeResolvedCalls() {
        resolvedCalls.clear();
        perHCResolved.clear();
        globalMonoStmts.clear();
        perCtxMonoStmts.clear();
        trulyPolyStmts.clear();
        unknownStmts.clear();
        resolvedCount = totalVirtualCalls = 0;

        Set<Stmt> counted = Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        for (SootClass cls : Scene.v().getApplicationClasses()) {
            if (!isUserClass(cls)) continue;
            for (SootMethod method : new ArrayList<>(cls.getMethods())) {
                if (!method.hasActiveBody()) continue;

                Set<Context> contexts = methodContexts.getOrDefault(
                    method, Collections.singleton(Context.EMPTY));

                for (Unit u : method.getActiveBody().getUnits()) {
                    Stmt stmt = (Stmt) u;
                    if (!isDispatchCall(stmt)) continue;
                    SootClass calleeClass =
                        stmt.getInvokeExpr().getMethodRef().getDeclaringClass();
                    if (!isUserClass(calleeClass)) continue;

                    if (!counted.add(stmt)) continue;   // deduplicate by identity
                    totalVirtualCalls++;

                    Local  receiver = getReceiver(stmt);
                    String subSig   = stmt.getInvokeExpr().getMethodRef()
                                        .getSubSignature().toString();

                    Set<SootMethod> globalTargets = new LinkedHashSet<>();
                    boolean hasPTInfo = false;
                    boolean anyCtxPoly = false;

                    for (Context ctx : contexts) {
                        Set<HeapContext> rcvHCs = getLocalHCs(method, ctx, receiver);
                        if (rcvHCs == null || rcvHCs.isEmpty()) continue;
                        hasPTInfo = true;

                        Set<SootMethod> ctxTargets = new LinkedHashSet<>();
                        boolean incomplete = false;

                        for (HeapContext rcvHC : rcvHCs) {
                            if (rcvHC.allocType == null) { incomplete = true; continue; }
                            SootMethod t = resolveVirtual(rcvHC.allocType, subSig);
                            if (t == null) { incomplete = true; continue; }
                            ctxTargets.add(t);
                            globalTargets.add(t);

                            perHCResolved
                                .computeIfAbsent(stmt, k -> new HashMap<>())
                                .computeIfAbsent(ctx,  k -> new HashMap<>())
                                .put(rcvHC, t);
                        }

                        if (!incomplete && ctxTargets.size() == 1) {
                            resolvedCalls
                                .computeIfAbsent(stmt, k -> new HashMap<>())
                                .put(ctx, ctxTargets.iterator().next());
                        } else if (ctxTargets.size() > 1) {
                            anyCtxPoly = true;
                        }
                    }

                    // Classify into exactly one bucket
                    if (!hasPTInfo) {
                        // No points-to info from our analysis
                        if (useCHAForUnknown) {
                            SootMethod chaTarget = resolveByCHA(stmt);
                            if (chaTarget != null) {
                                // CHA says monomorphic
                                globalMonoStmts.add(stmt);
                                resolvedCalls
                                    .computeIfAbsent(stmt, k -> new HashMap<>())
                                    .put(Context.EMPTY, chaTarget);
                                resolvedCount++;
                            } else {
                                // CHA says possibly polymorphic
                                trulyPolyStmts.add(stmt);
                            }
                        } else {
                            unknownStmts.add(stmt);
                        }
                    } 
                    else if (globalTargets.size() == 1) {
                        globalMonoStmts.add(stmt);
                        resolvedCount++;
                    } 
                    else if (!anyCtxPoly && resolvedCalls.containsKey(stmt)) {
                        perCtxMonoStmts.add(stmt);
                        resolvedCount++;
                    } 
                    else if (anyCtxPoly) {
                        trulyPolyStmts.add(stmt);
                    } else {
                        unknownStmts.add(stmt); // has PT info but nothing resolved
                    }
                }
            }
        }
    }


    private SootMethod resolveVirtual(SootClass cls, String subSig) {
        SootClass curr = cls;
        while (curr != null) {
            try {
                SootMethod m = curr.getMethod(subSig);
                if (!m.isAbstract()) return m;
            } catch (RuntimeException e) { /* not in this class */ }
            curr = curr.hasSuperclass() ? curr.getSuperclass() : null;
        }
        for (SootClass iface : cls.getInterfaces()) {
            try {
                SootMethod m = iface.getMethod(subSig);
                if (!m.isAbstract()) return m;
            } catch (RuntimeException e) { /* not in this interface */ }
        }
        return null;
    }

   
    private boolean propagateArgsWithTracking(
            List<Value> args,
            SootMethod  callee,   Context calleeCtx,
            SootMethod  caller,   Context callerCtx) {

        if (!callee.hasActiveBody()) return false;
        boolean newFacts = false;

        for (Unit u : callee.getActiveBody().getUnits()) {
            if (!(u instanceof IdentityStmt)) continue;
            IdentityStmt id = (IdentityStmt) u;
            if (!(id.getRightOp() instanceof ParameterRef)) continue;

            int idx = ((ParameterRef) id.getRightOp()).getIndex();
            if (idx >= args.size()) continue;
            Value arg = args.get(idx);
            if (!(arg instanceof Local)) continue;

            Set<HeapContext> argHCs = getLocalHCs(caller, callerCtx, (Local) arg);
            if (argHCs == null || argHCs.isEmpty()) continue;

            Local param  = (Local) id.getLeftOp();
            int   before = sizeOf(getLocalHCs(callee, calleeCtx, param));
            for (HeapContext hc : argHCs)
                addLocalHC(callee, calleeCtx, param, hc);
            int after = sizeOf(getLocalHCs(callee, calleeCtx, param));
            if (after > before) newFacts = true;
        }
        return newFacts;
    }

   
    private void addLocalHC(SootMethod m, Context ctx, Local l, HeapContext hc) {
        localPT.computeIfAbsent(makeLocalKey(m, ctx, l), k -> new HashSet<>()).add(hc);
    }
    private Set<HeapContext> getLocalHCs(SootMethod m, Context ctx, Local l) {
        return localPT.get(makeLocalKey(m, ctx, l));
    }

   
    private int sizeOf(Set<?> s) { return s == null ? 0 : s.size(); }

 
    private String makeLocalKey(SootMethod m, Context ctx, Local l) {
        return m.getSignature() + "|" + ctx + "|" + l.getName();
    }
    private String makeFieldKey(SootField f, HeapContext hc) {
        return f.getSignature() + "|" + hc.allocSite + "|" + hc.callerAllocSite;
    }
    private String makeReturnKey(SootMethod m, Context ctx) {
        return "RET|" + m.getSignature() + "|" + ctx;
    }
    private String visitKey(SootMethod m, Context ctx) {
        return m.getSignature() + "|" + ctx;
    }

    
    private boolean isNewExpr(Stmt s) {
        return s instanceof AssignStmt
            && ((AssignStmt) s).getRightOp() instanceof NewExpr;
    }
    private boolean isFieldStore(Stmt s) {
        return s instanceof AssignStmt
            && ((AssignStmt) s).getLeftOp() instanceof InstanceFieldRef;
    }
    private boolean isFieldLoad(Stmt s) {
        return s instanceof AssignStmt
            && ((AssignStmt) s).getRightOp() instanceof InstanceFieldRef;
    }
    public boolean isDispatchCall(Stmt s) {
        return s.containsInvokeExpr()
            && (s.getInvokeExpr() instanceof VirtualInvokeExpr
             || s.getInvokeExpr() instanceof InterfaceInvokeExpr);
    }
    private boolean isSpecialCall(Stmt s) {
        return s.containsInvokeExpr()
            && s.getInvokeExpr() instanceof SpecialInvokeExpr;
    }
    private boolean isStaticCall(Stmt s) {
        return s.containsInvokeExpr()
            && s.getInvokeExpr() instanceof StaticInvokeExpr;
    }
    private boolean isCast(Stmt s) {
        return s instanceof AssignStmt
            && ((AssignStmt) s).getRightOp() instanceof CastExpr;
    }
    private boolean isCopy(Stmt s) {
        return s instanceof AssignStmt
            && ((AssignStmt) s).getRightOp() instanceof Local
            && ((AssignStmt) s).getLeftOp()  instanceof Local; 
    }
    private boolean isReturn(Stmt s) { return s instanceof ReturnStmt; }

    private boolean isStaticFieldStore(Stmt s) {
    return s instanceof AssignStmt
        && ((AssignStmt) s).getLeftOp()  instanceof StaticFieldRef
        && ((AssignStmt) s).getRightOp() instanceof Local;
}
private boolean isStaticFieldLoad(Stmt s) {
    return s instanceof AssignStmt
        && ((AssignStmt) s).getRightOp() instanceof StaticFieldRef
        && ((AssignStmt) s).getLeftOp()  instanceof Local;
}

    private Local getReceiver(Stmt s) {
        InvokeExpr ie = s.getInvokeExpr();
        if (ie instanceof VirtualInvokeExpr)
            return (Local) ((VirtualInvokeExpr)  ie).getBase();
        if (ie instanceof InterfaceInvokeExpr)
            return (Local) ((InterfaceInvokeExpr) ie).getBase();
        throw new RuntimeException(
            "getReceiver called on non-dispatch stmt: " + s);
    }
}