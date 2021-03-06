package org.drools.modelcompiler.builder.generator.visitor;

import static java.util.Optional.of;
import static org.drools.core.rule.Pattern.isCompatibleWithFromReturnType;
import static org.drools.modelcompiler.builder.generator.DrlxParseUtil.findViaScopeWithPredicate;
import static org.drools.modelcompiler.builder.generator.DrlxParseUtil.generateLambdaWithoutParameters;
import static org.drools.modelcompiler.builder.generator.DslMethodNames.FROM_CALL;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.drools.compiler.lang.descr.FromDescr;
import org.drools.compiler.lang.descr.PatternSourceDescr;
import org.drools.compiler.rule.builder.dialect.java.parser.JavaParser;
import org.drools.javaparser.ast.drlx.expr.DrlxExpression;
import org.drools.javaparser.ast.expr.FieldAccessExpr;
import org.drools.javaparser.ast.expr.LambdaExpr;
import org.drools.javaparser.ast.expr.LiteralExpr;
import org.drools.javaparser.ast.expr.MethodCallExpr;
import org.drools.javaparser.ast.expr.NameExpr;
import org.drools.javaparser.ast.expr.ObjectCreationExpr;
import org.drools.javaparser.ast.nodeTypes.NodeWithArguments;
import org.drools.javaparser.ast.stmt.ExpressionStmt;
import org.drools.modelcompiler.builder.PackageModel;
import org.drools.modelcompiler.builder.errors.InvalidExpressionErrorResult;
import org.drools.modelcompiler.builder.generator.DeclarationSpec;
import org.drools.modelcompiler.builder.generator.DrlxParseUtil;
import org.drools.modelcompiler.builder.generator.RuleContext;
import org.drools.modelcompiler.builder.generator.TypedExpression;
import org.drools.modelcompiler.builder.generator.drlxparse.ConstraintParser;
import org.drools.modelcompiler.builder.generator.drlxparse.DrlxParseResult;
import org.drools.modelcompiler.builder.generator.drlxparse.SingleDrlxParseSuccess;
import org.drools.modelcompiler.builder.generator.expressiontyper.ExpressionTyper;
import org.w3c.dom.NodeList;

public class FromVisitor {

    private final RuleContext context;
    private final PackageModel packageModel;
    private final Class<?> patternType;

    public FromVisitor(RuleContext context, PackageModel packageModel, Class<?> patternType) {
        this.context = context;
        this.packageModel = packageModel;
        this.patternType = patternType;
    }

    public Optional<Expression> visit(PatternSourceDescr sourceDescr) {
        if (sourceDescr instanceof FromDescr) {
            String expression = ((FromDescr) sourceDescr).getDataSource().toString();

            boolean isEnumeratedList = expression.startsWith( "[" ) && expression.endsWith( "]" );
            return isEnumeratedList ?
                    createEnumeratedFrom( expression.substring( 1, expression.length()-1 ) ) :
                    createSingleFrom( expression );
        } else {
            return Optional.empty();
        }
    }

    private Optional<Expression> createSingleFrom( String expression ) {
        final Expression parsedExpression = DrlxParseUtil.parseExpression(expression).getExpr();

        if (parsedExpression instanceof FieldAccessExpr || parsedExpression instanceof NameExpr ) {
            return fromFieldOrName(expression);
        }

        if (parsedExpression instanceof MethodCallExpr ) {
            return fromMethodExpr(expression, (MethodCallExpr) parsedExpression);
        }

        if (parsedExpression instanceof ObjectCreationExpr ) {
            return fromConstructorExpr(expression, (ObjectCreationExpr) parsedExpression);
        }

        if (parsedExpression instanceof LiteralExpr ) {
            MethodCallExpr fromCall = new MethodCallExpr(null, FROM_CALL);
            fromCall.addArgument( parsedExpression );
            return of(fromCall);
        }

        return Optional.empty();
    }

    private Optional<Expression> createEnumeratedFrom( String expressions ) {
        MethodCallExpr fromCall = new MethodCallExpr(null, FROM_CALL);
        MethodCallExpr asListCall = new MethodCallExpr(null, "java.util.Arrays.asList");
        Collection<String> usedDeclarations = new ArrayList<>();

        for (String expr : expressions.split( "," )) {
            Optional<DeclarationSpec> optContainsBinding = context.getDeclarationById( expr );
            if (optContainsBinding.isPresent()) {
                String bindingId = optContainsBinding.get().getBindingId();
                fromCall.addArgument( context.getVarExpr(bindingId));
                usedDeclarations.add( expr );
            }
            asListCall.addArgument(expr);
        }

        fromCall.addArgument( generateLambdaWithoutParameters( usedDeclarations, asListCall, true ) );
        return of(fromCall);
    }

    private Optional<Expression> fromMethodExpr(String expression, MethodCallExpr parsedExpression) {
        return fromExpressionViaScope(expression, parsedExpression).map(Optional::of)
                .orElseGet(() -> fromExpressionUsingArguments(expression, parsedExpression));
    }

    private Optional<Expression> fromConstructorExpr(String expression, ObjectCreationExpr parsedExpression) {
        MethodCallExpr fromCall = new MethodCallExpr(null, FROM_CALL);
        List<String> bindingIds = new ArrayList<>();

        for (Expression argument : parsedExpression.getArguments()) {
            final String argumentName = argument.toString();
            if ( context.hasDeclaration(argumentName) || packageModel.hasDeclaration(argumentName)) {
                bindingIds.add(argumentName);
                fromCall.addArgument( context.getVarExpr(argumentName));
            }
        }

        fromCall.addArgument( generateLambdaWithoutParameters( bindingIds, parsedExpression, true ) );
        return of( fromCall );
    }

    private Optional<Expression> fromFieldOrName(String expression) {
        Optional<String> optContainsBinding = DrlxParseUtil.findBindingIdFromDotExpression(expression);
        final String bindingId = optContainsBinding.orElse(expression);

        final DrlxExpression drlxExpression = DrlxParseUtil.parseExpression(expression);

        final Expression parsedExpression = drlxExpression.getExpr();
        Optional<TypedExpression> staticField = parsedExpression instanceof FieldAccessExpr ?
                ExpressionTyper.tryParseAsConstantField((FieldAccessExpr) parsedExpression, context.getTypeResolver()) :
                Optional.empty();

        if (staticField.isPresent()) {
            return of( createSupplier(parsedExpression) );
        }
        if ( context.hasDeclaration(bindingId) || packageModel.hasDeclaration(bindingId)) {
            return of( createFromCall(expression, bindingId, optContainsBinding.isPresent()) );
        }
        return of(createUnitDataCall(bindingId));
    }

    private Expression createSupplier(Expression parsedExpression) {
        final LambdaExpr lambdaExpr = new LambdaExpr(NodeList.nodeList(), new ExpressionStmt(parsedExpression), true);

        MethodCallExpr fromCall = new MethodCallExpr(null, FROM_CALL);
        fromCall.addArgument(lambdaExpr);
        return fromCall;
    }

    private Optional<Expression> fromExpressionUsingArguments(String expression, NodeWithArguments<?> methodCallExpr) {
        MethodCallExpr fromCall = new MethodCallExpr(null, FROM_CALL);
        String bindingId = null;

        for (Expression argument : methodCallExpr.getArguments()) {
            final String argumentName = argument.toString();
            if ( context.hasDeclaration(argumentName) || packageModel.hasDeclaration(argumentName)) {
                if (bindingId == null) {
                    bindingId = argumentName;
                }
                fromCall.addArgument( context.getVarExpr(argumentName));
            }
        }

        return bindingId != null ?
                of(addLambdaToFromExpression( expression, bindingId, fromCall )) :
                of(addNoArgLambdaToFromExpression( expression, fromCall ));
    }

    private Optional<Expression> fromExpressionViaScope(String expression, Expression methodCallExpr) {
        return findViaScopeWithPredicate(methodCallExpr, e -> {
            if (e instanceof NameExpr) {
                final String name = ((NameExpr) e).getName().toString();
                return context.hasDeclaration(name) || packageModel.hasDeclaration(name);
            }
            return false;
        })
        .filter( Expression::isNameExpr )
        .map( e -> createFromCall(expression, e.asNameExpr().toString(), true) );
    }

    private Expression createFromCall( String expression, String bindingId, boolean hasBinding ) {
        MethodCallExpr fromCall = new MethodCallExpr(null, FROM_CALL);
        fromCall.addArgument( context.getVarExpr(bindingId));
        return hasBinding ? addLambdaToFromExpression( expression, bindingId, fromCall ) : fromCall;
    }

    private Expression addLambdaToFromExpression( String expression, String bindingId, MethodCallExpr fromCall ) {
        Expression exprArg = createArg( expression, bindingId );
        if (exprArg != null) {
            fromCall.addArgument( exprArg );
        }
        return fromCall;
    }

    private Expression addNoArgLambdaToFromExpression( String expression, MethodCallExpr fromCall ) {
        fromCall.addArgument( generateLambdaWithoutParameters( Collections.emptyList(), DrlxParseUtil.parseExpression( expression ).getExpr(), true ) );
        return fromCall;
    }

    private Expression createArg( String expression, String bindingId ) {
        if (bindingId != null) {
            DeclarationSpec declarationSpec = context.getDeclarationById( bindingId ).orElseThrow( RuntimeException::new );
            Class<?> clazz = declarationSpec.getDeclarationClass();

            DrlxParseResult drlxParseResult = new ConstraintParser( context, packageModel ).drlxParse( clazz, bindingId, expression );

            return drlxParseResult.acceptWithReturnValue( drlxParseSuccess -> {
                SingleDrlxParseSuccess singleResult = ( SingleDrlxParseSuccess ) drlxParseResult;
                TypedExpression left = singleResult.getLeft();
                if ( left != null && !isCompatibleWithFromReturnType( patternType, left.getRawClass() ) ) {
                    context.addCompilationError( new InvalidExpressionErrorResult(
                            "Pattern of type: '" + patternType.getCanonicalName() + "' on rule '" + context.getRuleName() +
                                    "' is not compatible with type " + left.getRawClass().getCanonicalName() + " returned by source" ) );
                }
                Expression parsedExpression = drlxParseSuccess.getExpr();
                return generateLambdaWithoutParameters( singleResult.getUsedDeclarations(), parsedExpression );
            } );
        }
        return null;
    }

    private Expression createUnitDataCall( String bindingId ) {
        return JavaParser.parseExpression(DrlxParseUtil.toVar(bindingId));
    }
}
