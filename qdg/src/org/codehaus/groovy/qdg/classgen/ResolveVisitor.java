/*
 * Copyright 2003-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.qdg.classgen;

import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.classgen.Verifier;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;

/**
 * Visitor to resolve Types and convert VariableExpression to
 * ClassExpressions if needed. The ResolveVisitor will try to
 * find the Class for a ClassExpression and prints an error if
 * it fails to do so. Constructions like C[], foo as C, (C) foo
 * will force creation of a ClassExpression for C
 * <p/>
 * Note: the method to start the resolving is  startResolving(ClassNode, SourceUnit).
 * <p/>
 * 
 * ported for Qdg, 23 Dec 2007
 *
 * @author Jochen Theodorou
 * @author Chanwit Kaewkasi
 */
public class ResolveVisitor extends ClassCodeExpressionTransformer {
    private ClassNode currentClass;
    // note: BigInteger and BigDecimal are also imported by default
    public static final String[] DEFAULT_IMPORTS = 
    	{"java.lang.", 
    	 "java.io.", 
    	 "java.net.", 
    	 "java.util.", 
    	 "groovy.lang.", 
    	 "groovy.util."};
    // private CompilationUnit compilationUnit;
    private Map<String, Object> cachedClasses = new HashMap<String, Object>();
    private static final Object NO_CLASS = new Object();
    private static final Object SCRIPT = new Object();
    private SourceUnit source;
    private VariableScope currentScope;

    private boolean isTopLevelProperty = true;
    private boolean inPropertyExpression = false;
    private boolean inClosure = false;
    private boolean isSpecialConstructorCall = false;

    private Map<String, GenericsType> genericParameterNames = new HashMap<String, GenericsType>();

    public ResolveVisitor() {
//        CompilationUnit cu) {
//    }
//        compilationUnit = cu;
    }

    public void startResolving(ClassNode node, SourceUnit source) {
        this.source = source;
        visitClass(node);
    }

    protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
        VariableScope oldScope = currentScope;
        currentScope = node.getVariableScope();
        Map oldPNames = genericParameterNames;
        genericParameterNames = new HashMap(genericParameterNames);

        resolveGenericsHeader(node.getGenericsTypes());
        
        Parameter[] paras = node.getParameters();
        for (int i = 0; i < paras.length; i++) {
            Parameter p = paras[i];
            p.setInitialExpression(transform(p.getInitialExpression()));
            resolveOrFail(p.getType(), p.getType());
            visitAnnotations(p);
        }
        ClassNode[] exceptions = node.getExceptions();
        for (int i = 0; i < exceptions.length; i++) {
            ClassNode t = exceptions[i];
            resolveOrFail(t, node);
        }
        resolveOrFail(node.getReturnType(), node);

        super.visitConstructorOrMethod(node, isConstructor);

        genericParameterNames = oldPNames;
        currentScope = oldScope;
    }

    public void visitField(FieldNode node) {
        ClassNode t = node.getType();
        resolveOrFail(t, node);
        super.visitField(node);
    }

    public void visitProperty(PropertyNode node) {
        ClassNode t = node.getType();
        resolveOrFail(t, node);
        super.visitProperty(node);
    }

    private boolean resolveToInner(ClassNode type) {
        String name = type.getName(), saved = name;
        while (true) {
            int len = name.lastIndexOf('.');
            if (len == -1)
                break;
            name = name.substring(0, len) + "$" + name.substring(len + 1);
            type.setName(name);
            if (resolve(type))
                return true;
        }
        type.setName(saved);
        return false;
    }

    private void resolveOrFail(ClassNode type, String msg, ASTNode node) {
        if (resolve(type)) return;
        if (resolveToInner(type)) return;
        addError("unable to resolve class " + type.getName() + " " + msg, node);
    }

    private void resolveOrFail(ClassNode type, ASTNode node, boolean prefereImports) {
        resolveGenericsTypes(type.getGenericsTypes());
        if (prefereImports && resolveAliasFromModule(type)) return;
        resolveOrFail(type, node);
    }

    private void resolveOrFail(ClassNode type, ASTNode node) {
        resolveOrFail(type, "", node);
    }

    private boolean resolve(ClassNode type) {
        return resolve(type, true, true, true);
    }

    private boolean resolve(ClassNode type, boolean testModuleImports, boolean testDefaultImports, boolean testStaticInnerClasses) {
        if (type.isResolved() || type.isPrimaryClassNode()) return true;
        resolveGenericsTypes(type.getGenericsTypes());
        if (type.isArray()) {
            ClassNode element = type.getComponentType();
            boolean resolved = resolve(element, testModuleImports, testDefaultImports, testStaticInnerClasses);
            if (resolved) {
                ClassNode cn = element.makeArray();
                type.setRedirect(cn);
            }
            return resolved;
        }

        // test if vanilla name is current class name
        if (currentClass == type) return true;

        if (genericParameterNames.get(type.getName()) != null) {
            GenericsType gt = (GenericsType) genericParameterNames.get(type.getName());
            type.setRedirect(gt.getType());
            type.setGenericsTypes(new GenericsType[]{gt});
            type.setGenericsPlaceHolder(true);
            return true;
        }

        if (currentClass.getNameWithoutPackage().equals(type.getName())) {
            type.setRedirect(currentClass);
            return true;
        }

        return resolveFromModule(type, testModuleImports) ||
                resolveFromCompileUnit(type) ||
                resolveFromDefaultImports(type, testDefaultImports) ||
                resolveFromStaticInnerClasses(type, testStaticInnerClasses) ||
                resolveFromClassCache(type) ||
                resolveToClass(type)/* ||
                resolveToScript(type)*/;
    }

    private boolean resolveFromClassCache(ClassNode type) {
        String name = type.getName();
        Object val = cachedClasses.get(name);
        if (val == null || val == NO_CLASS) {
            return false;
        } else {
            try {
                setClass(type, (Class<?>)val);
            } catch (Exception e) {
                return false;
            }
            return true;
        }
    }

    // NOTE: copied from GroovyClassLoader
    private long getTimeStamp(Class<?> cls) {
        return Verifier.getTimestamp(cls);
    }

    // NOTE: copied from GroovyClassLoader
    private boolean isSourceNewer(URL source, Class<?> cls) {
        try {
            long lastMod;

            // Special handling for file:// protocol, as getLastModified() often reports
            // incorrect results (-1)
            if (source.getProtocol().equals("file")) {
                // Coerce the file URL to a File
                String path = source.getPath().replace('/', File.separatorChar).replace('|', ':');
                File file = new File(path);
                lastMod = file.lastModified();
            } else {
                URLConnection conn = source.openConnection();
                lastMod = conn.getLastModified();
                conn.getInputStream().close();
            }
            return lastMod > getTimeStamp(cls);
        } catch (IOException e) {
            // if the stream can't be opened, let's keep the old reference
            return false;
        }
    }

//    private boolean resolveToScript(ClassNode type) {
//        String name = type.getName();
//        if (cachedClasses.get(name) == NO_CLASS) return false;
//        if (cachedClasses.get(name) == SCRIPT) cachedClasses.put(name, NO_CLASS);
//        if (name.startsWith("java.")) return type.isResolved();
//        //TODO: don't ignore inner static classes completely
//        if (name.indexOf('$') != -1) return type.isResolved();
//        ModuleNode module = currentClass.getModule();
//        if (module.hasPackageName() && name.indexOf('.') == -1) return type.isResolved();
//        // try to find a script from classpath
////        GroovyClassLoader gcl = compilationUnit.getClassLoader();
//        ClassLoader gcl = this.getClass().getClassLoader();
//        URL url = null;
//        try {
//            url = gcl.getResourceLoader().loadGroovySource(name);
//        } catch (MalformedURLException e) {
//            // fall through and let the URL be null
//        }
//        if (url != null) {
//            if (type.isResolved()) {
//                Class cls = type.getTypeClass();
//                // if the file is not newer we don't want to recompile
//                if (!isSourceNewer(url, cls)) return true;
//                cachedClasses.remove(type.getName());
//                type.setRedirect(null);
//            }
//            SourceUnit su = compilationUnit.addSource(url);
//            currentClass.getCompileUnit().addClassNodeToCompile(type, su);
//            return true;
//        }
//        // type may be resolved through the classloader before
//        return type.isResolved();
//    }


    private boolean resolveFromStaticInnerClasses(ClassNode type, boolean testStaticInnerClasses) {
        // try to resolve a public static inner class' name
        testStaticInnerClasses &= type.hasPackageName();
        if (testStaticInnerClasses) {
            String name = type.getName();
            String replacedPointType = name;
            int lastPoint = replacedPointType.lastIndexOf('.');
            replacedPointType = new StringBuffer()
                    .append(replacedPointType.substring(0, lastPoint))
                    .append("$")
                    .append(replacedPointType.substring(lastPoint + 1))
                    .toString();
            type.setName(replacedPointType);
            if (resolve(type, false, true, true)) return true;
            type.setName(name);
        }
        return false;
    }

    private boolean resolveFromDefaultImports(ClassNode type, boolean testDefaultImports) {
        // test default imports
        testDefaultImports &= !type.hasPackageName();
        if (testDefaultImports) {
            for (int i = 0, size = DEFAULT_IMPORTS.length; i < size; i++) {
                String packagePrefix = DEFAULT_IMPORTS[i];
                String name = type.getName();
                String fqn = packagePrefix + name;
                type.setName(fqn);
                if (resolve(type, false, false, false)) return true;
                type.setName(name);
            }
            String name = type.getName();
            if (name.equals("BigInteger")) {
                type.setRedirect(ClassHelper.BigInteger_TYPE);
                return true;
            } else if (name.equals("BigDecimal")) {
                type.setRedirect(ClassHelper.BigDecimal_TYPE);
                return true;
            }
        }
        return false;
    }

    private boolean resolveFromCompileUnit(ClassNode type) {
        // look into the compile unit if there is a class with that name
        CompileUnit compileUnit = currentClass.getCompileUnit();
        if (compileUnit == null) return false;
        ClassNode cuClass = compileUnit.getClass(type.getName());
        if (cuClass != null) {
            if (type != cuClass) type.setRedirect(cuClass);
            return true;
        }
        return false;
    }

    private void setClass(ClassNode n, Class<?> cls) {
        ClassNode cn = ClassHelper.make(cls);
        n.setRedirect(cn);
    }

    private void ambiguousClass(ClassNode type, ClassNode iType, String name, boolean resolved) {
        if (resolved && !type.getName().equals(iType.getName())) {
            addError("reference to " + name + " is ambiguous, both class " + type.getName() + " and " + iType.getName() + " match", type);
        } else {
            type.setRedirect(iType);
        }
    }

    private boolean resolveAliasFromModule(ClassNode type) {
        ModuleNode module = currentClass.getModule();
        if (module == null) return false;
        String name = type.getName();

        // check module node imports aliases
        // the while loop enables a check for inner classes which are not fully imported,
        // but visible as the surrounding class is imported and the inner class is public/protected static
        String pname = name;
        int index = name.length();
        /*
         * we have a name foo.bar and an import foo.foo. This means foo.bar is possibly
         * foo.foo.bar rather than foo.bar. This means to cut at the dot in foo.bar and
         * foo for import
         */
        while (true) {
            pname = name.substring(0, index);
            ClassNode aliasedNode = module.getImport(pname);
            if (aliasedNode != null) {
                if (pname.length() == name.length()) {
                    // full match, no need to create a new class
                    type.setRedirect(aliasedNode);
                    return true;
                } else {
                    //partial match
                    String newName = aliasedNode.getName() + name.substring(pname.length());
                    type.setName(newName);
                    if (resolve(type, true, true, true)) return true;
                    // was not resolved so it was a fake match
                    type.setName(name);
                }
            }
            index = pname.lastIndexOf('.');
            if (index == -1) break;
        }
        return false;
    }

    private boolean resolveFromModule(ClassNode type, boolean testModuleImports) {
        String name = type.getName();
        ModuleNode module = currentClass.getModule();
        if (module == null) return false;

        if (!type.hasPackageName() && module.hasPackageName()) {
            type.setName(module.getPackageName() + name);
        }
        // look into the module node if there is a class with that name
        List<ClassNode> moduleClasses = (List<ClassNode>)module.getClasses();
        for(ClassNode mClass: moduleClasses) {
            if (mClass.getName().equals(type.getName())) {
                if (mClass != type) type.setRedirect(mClass);
                return true;
            }        	
        }
//        for (Iterator iter = moduleClasses.iterator(); iter.hasNext();) {
//            ClassNode mClass = (ClassNode) iter.next();
//        }
        type.setName(name);

        if (testModuleImports) {
            if (resolveAliasFromModule(type)) return true;

            boolean resolved = false;
            if (module.hasPackageName()) {
                // check package this class is defined in
                type.setName(module.getPackageName() + name);
                resolved = resolve(type, false, false, false);
            }
            // check module node imports packages
            List packages = module.getImportPackages();
            ClassNode iType = ClassHelper.makeWithoutCaching(name);
            for (Iterator iter = packages.iterator(); iter.hasNext();) {
                String packagePrefix = (String) iter.next();
                String fqn = packagePrefix + name;
                iType.setName(fqn);
                if (resolve(iType, false, false, true)) {
                    ambiguousClass(type, iType, name, resolved);
                    return true;
                }
                iType.setName(name);
            }
            if (!resolved) type.setName(name);
            return resolved;
        }
        return false;
    }

    private boolean resolveToClass(ClassNode type) {
        String name = type.getName();
        if (cachedClasses.get(name) == NO_CLASS) return false;
        if (currentClass.getModule().hasPackageName() && name.indexOf('.') == -1) return false;
        //GroovyClassLoader loader = compilationUnit.getClassLoader();
        ClassLoader loader = this.getClass().getClassLoader();
        Class<?> cls;
        try {
            // NOTE: it's important to do no lookup against script files
            // here since the GroovyClassLoader would create a new CompilationUnit
            cls = loader.loadClass(name);//, false, true);
        } catch (ClassNotFoundException cnfe) {
            cachedClasses.put(name, SCRIPT);
            return false;
        } catch (CompilationFailedException cfe) {
            //compilationUnit.getErrorCollector().addErrorAndContinue(new ExceptionMessage(cfe, true, source));
            return false;
        }
        //TODO: the case of a NoClassDefFoundError needs a bit more research
        // a simple recompilation is not possible it seems. The current class
        // we are searching for is there, so we should mark that somehow.
        // Basically the missing class needs to be completly compiled before
        // we can again search for the current name.
        /*catch (NoClassDefFoundError ncdfe) {
            cachedClasses.put(name,SCRIPT);
            return false;
        }*/
        if (cls == null) return false;
        cachedClasses.put(name, cls);
        setClass(type, cls);
        //NOTE: we return false here even if we found a class,
        //but we want to give a possible script a chance to recompile.
        //this can only be done if the loader was not the instance
        //defining the class.
        return cls.getClassLoader() == loader;
    }


    public Expression transform(Expression exp) {
        if (exp == null) return null;
        if (exp instanceof VariableExpression) {
            return transformVariableExpression((VariableExpression) exp);
        } else if (exp.getClass() == PropertyExpression.class) {
            return transformPropertyExpression((PropertyExpression) exp);
        } else if (exp instanceof DeclarationExpression) {
            return transformDeclarationExpression((DeclarationExpression) exp);
        } else if (exp instanceof BinaryExpression) {
            return transformBinaryExpression((BinaryExpression) exp);
        } else if (exp instanceof MethodCallExpression) {
            return transformMethodCallExpression((MethodCallExpression) exp);
        } else if (exp instanceof ClosureExpression) {
            return transformClosureExpression((ClosureExpression) exp);
        } else if (exp instanceof ConstructorCallExpression) {
            return transformConstructorCallExpression((ConstructorCallExpression) exp);
        } else if (exp instanceof AnnotationConstantExpression) {
            return transformAnnotationConstantExpression((AnnotationConstantExpression) exp);
        } else {
            resolveOrFail(exp.getType(), exp);
            return exp.transformExpression(this);
        }
    }

    private String lookupClassName(PropertyExpression pe) {
        String name = "";
        for (Expression it = pe; it != null; it = ((PropertyExpression) it).getObjectExpression()) {
            if (it instanceof VariableExpression) {
                VariableExpression ve = (VariableExpression) it;
                // stop at super and this
                if (ve == VariableExpression.SUPER_EXPRESSION || ve == VariableExpression.THIS_EXPRESSION) {
                    return null;
                }
                name = ve.getName() + "." + name;
                break;
            }
            // anything other than PropertyExpressions, ClassExpression or
            // VariableExpressions will stop resolving
            else if (!(it.getClass() == PropertyExpression.class)) {
                return null;
            } else {
                PropertyExpression current = (PropertyExpression) it;
                String propertyPart = current.getPropertyAsString();
                // the class property stops resolving, dynamic property names too
                if (propertyPart == null || propertyPart.equals("class")) {
                    return null;
                }
                name = propertyPart + "." + name;
            }
        }
        if (name.length() > 0) return name.substring(0, name.length() - 1);
        return null;
    }

    // iterate from the inner most to the outer and check for classes
    // this check will ignore a .class property, for Example Integer.class will be
    // a PropertyExpression with the ClassExpression of Integer as objectExpression
    // and class as property
    private Expression correctClassClassChain(PropertyExpression pe) {
        LinkedList stack = new LinkedList();
        ClassExpression found = null;
        for (Expression it = pe; it != null; it = ((PropertyExpression) it).getObjectExpression()) {
            if (it instanceof ClassExpression) {
                found = (ClassExpression) it;
                break;
            } else if (!(it.getClass() == PropertyExpression.class)) {
                return pe;
            }
            stack.addFirst(it);
        }
        if (found == null) return pe;

        if (stack.isEmpty()) return pe;
        Object stackElement = stack.removeFirst();
        if (!(stackElement.getClass() == PropertyExpression.class)) return pe;
        PropertyExpression classPropertyExpression = (PropertyExpression) stackElement;
        String propertyNamePart = classPropertyExpression.getPropertyAsString();
        if (propertyNamePart == null || !propertyNamePart.equals("class")) return pe;

        if (stack.isEmpty()) return found;
        stackElement = stack.removeFirst();
        if (!(stackElement.getClass() == PropertyExpression.class)) return pe;
        PropertyExpression classPropertyExpressionContainer = (PropertyExpression) stackElement;

        classPropertyExpressionContainer.setObjectExpression(found);
        return pe;
    }

    protected Expression transformPropertyExpression(PropertyExpression pe) {
        boolean itlp = isTopLevelProperty;
        boolean ipe = inPropertyExpression;

        Expression objectExpression = pe.getObjectExpression();
        inPropertyExpression = true;
        isTopLevelProperty = !(objectExpression.getClass() == PropertyExpression.class);
        objectExpression = transform(objectExpression);
        // we handle the property part as if it where not part of the property
        inPropertyExpression = false;
        Expression property = transform(pe.getProperty());
        isTopLevelProperty = itlp;
        inPropertyExpression = ipe;

        boolean spreadSafe = pe.isSpreadSafe();
        pe = new PropertyExpression(objectExpression, property, pe.isSafe());
        pe.setSpreadSafe(spreadSafe);

        String className = lookupClassName(pe);
        if (className != null) {
            ClassNode type = ClassHelper.make(className);
            if (resolve(type)) return new ClassExpression(type);
        }
        if (objectExpression instanceof ClassExpression && pe.getPropertyAsString() != null) {
            // possibly an inner class
            ClassExpression ce = (ClassExpression) objectExpression;
            ClassNode type = ClassHelper.make(ce.getType().getName() + "$" + pe.getPropertyAsString());
            if (resolve(type, false, false, false)) return new ClassExpression(type);
        }
        Expression ret = pe;
        if (isTopLevelProperty) ret = correctClassClassChain(pe);
        return ret;
    }

    protected Expression transformVariableExpression(VariableExpression ve) {
        if (ve.getName().equals("this")) return VariableExpression.THIS_EXPRESSION;
        if (ve.getName().equals("super")) return VariableExpression.SUPER_EXPRESSION;
        Variable v = ve.getAccessedVariable();
        if (v instanceof DynamicVariable) {
            ClassNode t = ClassHelper.make(ve.getName());
            if (resolve(t)) {
                // the name is a type so remove it from the scoping
                // as it is only a classvariable, it is only in
                // referencedClassVariables, but must be removed
                // for each parentscope too
                for (VariableScope scope = currentScope; scope != null && !scope.isRoot(); scope = scope.getParent()) {
                    if (scope.isRoot()) break;
                    if (scope.removeReferencedClassVariable(ve.getName()) == null) break;
                }
                ClassExpression ce = new ClassExpression(t);
                ce.setSourcePosition(ve);
                return ce;
            }
        }
        resolveOrFail(ve.getType(), ve);
        return ve;
    }

    protected Expression transformBinaryExpression(BinaryExpression be) {
        Expression left = transform(be.getLeftExpression());
        int type = be.getOperation().getType();
        if ((type == Types.ASSIGNMENT_OPERATOR || type == Types.EQUAL) &&
                left instanceof ClassExpression) {
            ClassExpression ce = (ClassExpression) left;
            String error = "you tried to assign a value to the class '" + ce.getType().getName() + "'";
            if (ce.getType().isScript()) {
                error += ". Do you have a script with this name?";
            }
            addError(error, be.getLeftExpression());
            return be;
        }
        if (left instanceof ClassExpression && be.getRightExpression() instanceof ListExpression) {
            // we have C[] if the list is empty -> should be an array then!
            ListExpression list = (ListExpression) be.getRightExpression();
            if (list.getExpressions().isEmpty()) {
                return new ClassExpression(left.getType().makeArray());
            }
        }
        Expression right = transform(be.getRightExpression());
        be.setLeftExpression(left);
        be.setRightExpression(right);
        return be;
    }

    protected Expression transformClosureExpression(ClosureExpression ce) {
        boolean oldInClosure = inClosure;
        inClosure = true;
        Parameter[] paras = ce.getParameters();
        if (paras != null) {
            for (int i = 0; i < paras.length; i++) {
                ClassNode t = paras[i].getType();
                resolveOrFail(t, ce);
            }
        }
        Statement code = ce.getCode();
        if (code != null) code.visit(this);
        inClosure = oldInClosure;
        return ce;
    }

    protected Expression transformConstructorCallExpression(ConstructorCallExpression cce) {
        ClassNode type = cce.getType();
        resolveOrFail(type, cce);
        isSpecialConstructorCall = cce.isSpecialCall();
        Expression ret = cce.transformExpression(this);
        isSpecialConstructorCall = false;
        return ret;
    }

    protected Expression transformMethodCallExpression(MethodCallExpression mce) {
        Expression args = transform(mce.getArguments());
        Expression method = transform(mce.getMethod());
        Expression object = transform(mce.getObjectExpression());

        MethodCallExpression result = new MethodCallExpression(object, method, args);
        result.setSafe(mce.isSafe());
        result.setImplicitThis(mce.isImplicitThis());
        result.setSpreadSafe(mce.isSpreadSafe());
        result.setSourcePosition(mce);
        return result;
    }

    protected Expression transformDeclarationExpression(DeclarationExpression de) {
        Expression oldLeft = de.getLeftExpression();
        Expression left = transform(oldLeft);
        if (left != oldLeft) {
            ClassExpression ce = (ClassExpression) left;
            addError("you tried to assign a value to " + ce.getType().getName(), oldLeft);
            return de;
        }
        Expression right = transform(de.getRightExpression());
        if (right == de.getRightExpression()) return de;
        return new DeclarationExpression((VariableExpression) left, de.getOperation(), right);
    }

    protected Expression transformAnnotationConstantExpression(AnnotationConstantExpression ace) {
        AnnotationNode an = (AnnotationNode) ace.getValue();
        ClassNode type = an.getClassNode();
        resolveOrFail(type, "unable to find class for annotation", an);
        for (Iterator iter = an.getMembers().entrySet().iterator(); iter.hasNext();) {
            Map.Entry member = (Map.Entry) iter.next();
            Expression memberValue = (Expression) member.getValue();
            member.setValue(transform(memberValue));
        }

        return ace;
    }

    public void visitAnnotations(AnnotatedNode node) {
        Map annotionMap = node.getAnnotations();
        if (annotionMap.isEmpty()) return;
        Iterator it = annotionMap.values().iterator();
        while (it.hasNext()) {
            AnnotationNode an = (AnnotationNode) it.next();
            //skip builtin properties
            if (an.isBuiltIn()) continue;
            ClassNode type = an.getClassNode();
            resolveOrFail(type, "unable to find class for annotation", an);
            for (Iterator iter = an.getMembers().entrySet().iterator(); iter.hasNext();) {
                Map.Entry member = (Map.Entry) iter.next();
                Expression memberValue = (Expression) member.getValue();
                member.setValue(transform(memberValue));
            }
        }
    }

    public void visitClass(ClassNode node) {
        ClassNode oldNode = currentClass;
        currentClass = node;

        resolveGenericsHeader(node.getGenericsTypes());

        ModuleNode module = node.getModule();
        if (!module.hasImportsResolved()) {
            List l = module.getImports();
            for (Iterator iter = l.iterator(); iter.hasNext();) {
                ImportNode element = (ImportNode) iter.next();
                ClassNode type = element.getType();
                if (resolve(type, false, false, true)) continue;
                addError("unable to resolve class " + type.getName(), type);
            }
            Map importPackages = module.getStaticImportClasses();
            for (Iterator iter = importPackages.values().iterator(); iter.hasNext();) {
                ClassNode type = (ClassNode) iter.next();
                if (resolve(type, false, false, true)) continue;
                addError("unable to resolve class " + type.getName(), type);
            }
            for (Iterator iter = module.getStaticImportAliases().values().iterator(); iter.hasNext();) {
                ClassNode type = (ClassNode) iter.next();
                if (resolve(type, true, true, true)) continue;
                addError("unable to resolve class " + type.getName(), type);
            }
            for (Iterator iter = module.getStaticImportClasses().values().iterator(); iter.hasNext();) {
                ClassNode type = (ClassNode) iter.next();
                if (resolve(type, true, true, true)) continue;
                addError("unable to resolve class " + type.getName(), type);
            }
            module.setImportsResolved(true);
        }

        ClassNode sn = node.getUnresolvedSuperClass();
        if (sn != null) resolveOrFail(sn, node, true);

        ClassNode[] interfaces = node.getInterfaces();
        for (int i = 0; i < interfaces.length; i++) {
            resolveOrFail(interfaces[i], node, true);

        }

        super.visitClass(node);

        currentClass = oldNode;
    }

    public void visitCatchStatement(CatchStatement cs) {
        resolveOrFail(cs.getExceptionType(), cs);
        if (cs.getExceptionType() == ClassHelper.DYNAMIC_TYPE) {
            cs.getVariable().setType(ClassHelper.make(Exception.class));
        }
        super.visitCatchStatement(cs);
    }

    public void visitForLoop(ForStatement forLoop) {
        resolveOrFail(forLoop.getVariableType(), forLoop);
        super.visitForLoop(forLoop);
    }

    public void visitBlockStatement(BlockStatement block) {
        VariableScope oldScope = currentScope;
        currentScope = block.getVariableScope();
        super.visitBlockStatement(block);
        currentScope = oldScope;
    }

    protected SourceUnit getSourceUnit() {
        return source;
    }

    private void resolveGenericsTypes(GenericsType[] types) {
        if (types == null) return;
        currentClass.setUsingGenerics(true);
        for (int i = 0; i < types.length; i++) {
            resolveGenericsType(types[i]);
        }
    }

    private void resolveGenericsHeader(GenericsType[] types) {
        if (types == null) return;
        currentClass.setUsingGenerics(true);
        for (int i = 0; i < types.length; i++) {
            ClassNode type = types[i].getType();
            String name = type.getName();
            ClassNode[] bounds = types[i].getUpperBounds();
            if (bounds != null) {
                boolean nameAdded = false;
                for (int j = 0; j < bounds.length; j++) {
                    ClassNode upperBound = bounds[j];
                    if (!nameAdded && upperBound != null || !resolve(type)) {
                        genericParameterNames.put(name, types[i]);
                        types[i].setPlaceholder(true);
                        type.setRedirect(upperBound);
                        nameAdded = true;
                    }
                    resolveOrFail(upperBound, type);
                }
            } else {
                genericParameterNames.put(name, types[i]);
                type.setRedirect(ClassHelper.OBJECT_TYPE);
                types[i].setPlaceholder(true);
            }
        }
    }

    private void resolveGenericsType(GenericsType genericsType) {
        if (genericsType.isResolved()) return;
        currentClass.setUsingGenerics(true);
        ClassNode type = genericsType.getType();
        // save name before redirect
        String name = type.getName();
        ClassNode[] bounds = genericsType.getUpperBounds();
        if (!genericParameterNames.containsKey(name)) {
            if (bounds != null) {
                for (int j = 0; j < bounds.length; j++) {
                    ClassNode upperBound = bounds[j];
                    resolveOrFail(upperBound, genericsType);
                    type.setRedirect(upperBound);
                    resolveGenericsTypes(upperBound.getGenericsTypes());
                }
            } else if (genericsType.isWildcard()) {
                type.setRedirect(ClassHelper.OBJECT_TYPE);
            } else {
                resolveOrFail(type, genericsType);
            }
        } else {
            GenericsType gt = (GenericsType) genericParameterNames.get(name);
            type.setRedirect(gt.getType());
            genericsType.setPlaceholder(true);
        }


        if (genericsType.getLowerBound() != null) {
            resolveOrFail(genericsType.getLowerBound(), genericsType);
        }
        resolveGenericsTypes(type.getGenericsTypes());
        genericsType.setResolved(true);
    }
}
